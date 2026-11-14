#!/usr/bin/env python3
"""Focused Cyber Sec audit workers; heuristic matches require human review."""

import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
FIELDS = ("security", "bugs", "regression", "compatibility", "privacy", "dependencies",
          "workflows", "build", "performance", "fuzz")


def finding(path, text, position, rule, severity="review"):
    # Do not include source lines, matched secrets, credentials, or payloads.
    return {"path": str(path), "line": text.count("\n", 0, position) + 1,
            "rule": rule, "severity": severity, "status": "needs-review",
            "fingerprint": hashlib.sha256((str(path) + rule + str(position)).encode()).hexdigest()}


def scan_text(path, text, field):
    rules = []
    path = str(path)
    if field == "bugs":
        if path.endswith("EepGet.java"):
            rules.append(("post-character-count", r"append\(_postData\.length\(\)\)"))
        if path.endswith("Packet.java"):
            rules.append(("absolute-payload-size", r"payloadSize\s*=\s*length\s*-\s*payloadBegin"))
        if path.endswith("sam/UTF8Reader.java"):
            rules.append(("single-char-utf8-decode-buffer", r"CharBuffer\.allocate\(1\)"))
    elif field == "privacy":
        if path.endswith(("EepGet.java", "EepHead.java", "PartialEepGet.java")):
            rules.append(("http-request-dump", r"_log\.debug\(\"Request:[\s\S]{0,150}?buf\.toString\(\)"))
        if path.endswith("ntcp/NTCP2Payload.java"):
            rules.append(("decrypted-payload-dump", r"HexDump\.dump\(payload,\s*off,\s*length\)"))
        if "/ntcp/" in path:
            secret = r"\b(?:sip_ab|sip_ba|_sendSipk1|_sendSipk2|_sendSipIV|_sipk1|_sipk2|_sipIV)\b"
            for log in re.finditer(r"^\s*_log\.(?:debug|info|warn|error|log)\([\s\S]*?\);", text, re.MULTILINE):
                if re.search(secret, log.group()):
                    rules.append(("framing-key-log", re.escape(log.group())))
    matches = []
    for rule, pattern in rules:
        for match in re.finditer(pattern, text):
            matches.append(finding(path, text, match.start(), rule))
    return matches


def scan(root, field):
    if field == "security":
        from pattern_scan import scan as scan_patterns
        report = scan_patterns(root)
        report["passed"] = report["status"] != "incomplete"
        return report
    matches = []
    count = 0
    for directory in ("core/java/src/net", "router/java/src/net", "apps"):
        for path in sorted((root / directory).rglob("*.java")):
            if "test" in path.parts or "build" in path.parts:
                continue
            count += 1
            matches.extend(scan_text(path.relative_to(root), path.read_text(errors="replace"), field))
    return {"scanned_files": count, "findings": matches,
            "coverage": "Focused source patterns; matches are triage candidates, not proven vulnerabilities.",
            "passed": count > 0 and not matches,
            "status": "incomplete" if count == 0 else ("triage-required" if matches else "no-rule-matches")}


def workflow_findings(path, text):
    issues = []
    rules = {
        "privileged-trigger": r"\bpull_request_target\s*:|\bworkflow_run\s*:",
        "write-permission": r"^\s+(?:contents|actions|checks|issues|pull-requests|security-events|id-token):\s*write\b|permissions:\s*write-all",
        "secret-reference": r"secrets\.",
        "untrusted-event-expression": r"\$\{\{\s*github\.event\.(?:pull_request|issue|comment)",
        "self-hosted-runner": r"self-hosted",
        "persistent-checkout-credentials": r"persist-credentials:\s*true",
    }
    for rule, pattern in rules.items():
        for match in re.finditer(pattern, text, re.MULTILINE):
            issues.append(finding(path, text, match.start(), rule))
    for match in re.finditer(r"uses:\s*([^\s#]+)", text):
        action = match.group(1)
        if not action.startswith("./") and not re.fullmatch(r"[^@]+@[0-9a-f]{40}", action):
            issues.append(finding(path, text, match.start(), "unpinned-action"))
    for pattern, rule in ((r"^permissions:\n\s+contents:\s*read\s*$", "missing-read-only-permissions"),
                          (r"timeout-minutes:\s*\d+", "missing-job-timeout"),
                          (r"persist-credentials:\s*false", "missing-credential-cleanup")):
        if not re.search(pattern, text, re.MULTILINE):
            issues.append(finding(path, text, 0, rule))
    return issues


def workflows(root):
    from workflow_definitions import render
    findings = []
    legacy = []
    count = 0
    for path in sorted((root / ".github/workflows").glob("*.y*ml")):
        issues = workflow_findings(path.relative_to(root), path.read_text())
        if path.name.startswith("cybersec-"):
            count += 1
            findings.extend(issues)
        else:
            legacy.extend(issues)
    matches = all((root / ".github/workflows" / name).is_file()
                  and (root / ".github/workflows" / name).read_text() == content for name, content in render().items())
    return {"passed": count == 10 and not findings and matches, "worker_count": count, "findings": findings,
            "generated_configuration_matches": matches,
            "existing_workflow_review": legacy,
            "coverage": "Policy gates new audit workers; existing publishing workflows are recorded separately."}


def coordinates(text):
    found = []
    pattern = r"[\"']([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([^\"'\s:]+)[\"']"
    for match in re.finditer(pattern, text):
        version = match.group(3)
        exact = bool(re.fullmatch(r"[0-9][0-9A-Za-z_.-]*", version))
        found.append({"name": match.group(1) + ":" + match.group(2), "version": version, "exact": exact})
    return found


def osv_query(packages, opener=None):
    opener = opener or urllib.request.urlopen
    queries = [{"package": {"name": package["name"], "ecosystem": "Maven"}, "version": package["version"]}
               for package in packages]
    if not queries:
        return []
    if len(queries) > 200:
        raise ValueError("advisory batch exceeds the audit bound")
    request = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": queries}).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
    with opener(request, timeout=30) as response:
        body = response.read(4 * 1024 * 1024 + 1)
    if len(body) > 4 * 1024 * 1024:
        raise ValueError("advisory response exceeds the audit bound")
    results = json.loads(body).get("results")
    if not isinstance(results, list) or len(results) != len(packages):
        raise ValueError("advisory response is incomplete; vulnerability status is unknown")
    advisories = []
    for package, result in zip(packages, results):
        if not isinstance(result, dict) or not isinstance(result.get("vulns", []), list):
            raise ValueError("invalid advisory response")
        if any(not isinstance(item, dict) for item in result.get("vulns", [])):
            raise ValueError("invalid advisory entry")
        ids = [item.get("id") for item in result.get("vulns", [])]
        if any(not isinstance(value, str) for value in ids):
            raise ValueError("invalid advisory identifier")
        if ids:
            advisories.append({"package": package, "advisories": ids})
    return advisories


def dependencies(root, online=False):
    inventory = {}
    unresolved = []
    excluded = {".git", ".gradle", "build", "target", "tests", "audit-results", "pkg-temp", "classes", "dist"}
    for path in sorted(root.rglob("*.gradle")):
        if any(part in excluded for part in path.relative_to(root).parts):
            continue
        for entry in coordinates(path.read_text(errors="replace")):
            entry["source"] = str(path.relative_to(root))
            if entry["exact"]:
                inventory[(entry["name"], entry["version"])] = entry
            else:
                unresolved.append(entry)
    jars = []
    for path in sorted(root.rglob("*.jar")):
        if any(part in excluded for part in path.relative_to(root).parts):
            continue
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        jars.append({"path": str(path.relative_to(root)), "sha256": digest.hexdigest()})
    packages = list(inventory.values())
    advisories = osv_query(packages) if online else []
    return {"passed": not advisories, "exact_maven_coordinates": packages,
            "unresolved_versions": unresolved, "bundled_jar_hashes": jars, "advisories": advisories,
            "advisory_status": "queried-exact-maven-versions" if online else "not-queried-offline",
            "coverage": "Dynamic versions, vendored source versions, and unmapped JARs require review; no complete SCA claim."}


def syntax(root):
    errors = []
    for path in (root / "tests/audit").glob("*.py"):
        try:
            ast.parse(path.read_text(), filename=str(path.relative_to(root)))
        except SyntaxError as error:
            errors.append({"path": str(path.relative_to(root)), "line": error.lineno, "rule": "python-syntax"})
    for path in [root / "build.xml"] + sorted((root / "core").rglob("build.xml")):
        if path.is_file():
            try:
                ET.parse(path)
            except ET.ParseError as error:
                errors.append({"path": str(path.relative_to(root)), "rule": "xml-syntax"})
    return {"passed": not errors, "findings": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("field", choices=FIELDS)
    parser.add_argument("--output", type=Path, default=ROOT / "tests/results/audit")
    parser.add_argument("--online", action="store_true", help="query OSV for exact Maven versions")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    try:
        if args.field in ("security", "bugs"):
            report = scan(ROOT, args.field)
        elif args.field == "dependencies":
            report = dependencies(ROOT, args.online)
        elif args.field == "workflows":
            report = workflows(ROOT)
        else:
            from java_checks import run
            report = run(ROOT, args.field)
            if args.field == "privacy":
                patterns = scan(ROOT, "privacy")
                report["source_patterns"] = patterns
                report["passed"] = report["passed"] and patterns["passed"]
            if args.field == "build":
                report["syntax"] = syntax(ROOT)
                report["passed"] = report["passed"] and report["syntax"]["passed"]
        report.update({"schema_version": 1, "field": args.field, "organization": "Cyber Sec"})
    except Exception as error:
        # No exception body: network/proxy errors may contain sensitive URLs.
        report = {"schema_version": 1, "field": args.field, "passed": False,
                  "status": "incomplete", "error_type": type(error).__name__}
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"field": args.field, "passed": report["passed"], "status": report.get("status", "completed"),
                      "report": str(args.output / "report.json")}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
