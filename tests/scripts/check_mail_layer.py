#!/usr/bin/env python3
"""Compile and check a focused SusiMail layer after building normal dependency jars."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def invoke(command, cwd):
    try:
        result = subprocess.run(command, cwd=cwd, text=True, capture_output=True, timeout=120)
        return dict(exit_code=result.returncode, stdout=result.stdout, stderr=result.stderr)
    except subprocess.TimeoutExpired:
        return dict(exit_code=124, stdout="", stderr="Regression exceeded its 120-second deadline")


def run(source, dependencies, suites, output):
    jars = sorted((dependencies / "build").glob("*.jar"))
    homes = [p for p in (dependencies / "apps/jetty").glob("jetty-home-*")
             if p.is_dir() and re.fullmatch(r"jetty-home-\d+\.\d+\.\d+", p.name)]
    if homes:
        latest = max(homes, key=lambda p: tuple(map(int, p.name.split("-")[-1].split("."))))
        jars += sorted((latest / "lib").glob("*.jar"))
    if not (dependencies / "build/i2p.jar").exists():
        raise RuntimeError("Build the normal dependency jars first")
    classpath = os.pathsep.join(map(str, jars))
    sources = sorted((source / "apps/susimail/src/src").rglob("*.java"))
    fixtures = sorted((source / "tests/regression").glob("SusiMail*Regression.java"))
    report = dict(passed=False, suites=suites, source_sha256={str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
                  dependency_sha256={str(p.relative_to(dependencies)): hashlib.sha256(p.read_bytes()).hexdigest() for p in jars}, results={})
    with tempfile.TemporaryDirectory(prefix="susimail-layer-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        report["compile"] = invoke(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes), *map(str, sources + fixtures)], work)
        if report["compile"]["exit_code"]:
            return report
        runtime = os.pathsep.join([str(classes), str(source / "apps/susimail/src"), classpath])
        for suite in suites:
            args = ["java", "-Xmx384m", "-Djbigi.enable=false", "-Djbigi.dontLog=true", "-DI2P_DISABLE_OUTPUT_OVERRIDE=true", "-cp", runtime]
            if suite in ("all", "protocol", "downloads", "http", "privacy"):
                args += ["i2p.susi.webmail.SusiMailSecurityRegression", str(output), suite]
            elif suite == "frame":
                args = ["node", str(source / "tests/regression/SusiMailFrameRegression.js"), str(source)]
            else:
                suffix = {"identity": "Identity", "vault": "Vault", "mfa": "MFA", "protection": "Protection", "content": "Content", "flood": "Flood", "request": "Request"}[suite]
                args += ["i2p.susi.webmail.SusiMail" + suffix + "Regression"]
            result = invoke(args, work)
            summary = re.search(r"SUSIMAIL_\w+_CHECKS=(\d+); FAILURES=(\d+)", result["stdout"])
            result["checks"] = int(summary[1]) if summary else None
            result["failures"] = int(summary[2]) if summary else None
            report["results"][suite] = result
        report["passed"] = all(r["exit_code"] == 0 and r["checks"] and r["failures"] == 0 for r in report["results"].values())
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--dependency-root", type=Path, default=ROOT)
    parser.add_argument("--suite", nargs="+", choices=("all", "protocol", "privacy", "downloads", "http", "identity", "vault", "mfa", "protection", "content", "flood", "request", "frame"), default=['all', 'identity', 'frame', 'content', 'flood'])
    parser.add_argument("--output", type=Path, default=ROOT / "tests/results/mail-security/layer")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    report = run(args.source_root.resolve(), args.dependency_root.resolve(), args.suite, output)
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    if report["compile"]["exit_code"]:
        print(report["compile"]["stderr"], file=sys.stderr)
    for name, result in report["results"].items():
        print(name + ": " + result["stdout"], end="")
        if result["exit_code"]:
            print(result["stderr"], file=sys.stderr)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
