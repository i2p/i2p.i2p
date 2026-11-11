#!/usr/bin/env python3
"""Run repository regressions and retain portable results and source fingerprints."""

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
INIT = Path(__file__).with_name("jdk17-tests.gradle")
EXCLUDED = {".git", ".gradle", "build", "node_modules", "__pycache__"}


def execute(command, cwd, log, env=None):
    """Keep complete logs while making a failed command visible to callers."""
    started = time.monotonic()
    print("Running: " + " ".join(map(str, command)), flush=True)
    with log.open("w") as output:
        result = subprocess.run(list(map(str, command)), cwd=cwd, env=env,
                                stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print(log.read_text(errors="replace")[-12000:], file=sys.stderr)
    return {"command": list(map(str, command)), "exitCode": result.returncode,
            "elapsedSeconds": round(time.monotonic() - started, 3),
            "log": log.name, "logSha256": digest(log)}


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def source_hashes(root):
    result = {}
    for current, directories, filenames in os.walk(root):
        directories[:] = sorted(name for name in directories if name not in EXCLUDED)
        for name in sorted(filenames):
            path = Path(current) / name
            if path.suffix in (".java", ".scala", ".gradle", ".properties", ".py", ".yml", ".yaml") or name == "gradlew":
                result[path.relative_to(root).as_posix()] = digest(path)
    return result


def collect_junit(project, destination):
    """Collect only the current Gradle invocation's clean JUnit results."""
    destination.mkdir(parents=True, exist_ok=True)
    result = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "reports": []}
    for path in sorted(project.glob("**/build/test-results/test/TEST-*.xml")):
        relative = path.relative_to(project)
        # Ignore copied reports inside the root build/validation output.
        if relative.parts.count("build") != 1:
            continue
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        suite = ET.parse(path).getroot()
        for key in ("tests", "failures", "errors", "skipped"):
            result[key] += int(suite.attrib.get(key, 0))
        result["reports"].append({"path": relative.as_posix(), "sha256": digest(path)})
    return result


def python_suites(project):
    # Validation's own tests are required even when examining an older ref.
    suites = [(Path(__file__).resolve().parent, "test_*.py")]
    for name in ("tests/live", "tests/recovery"):
        directory = project / name
        if directory.is_dir() and any(directory.rglob("test_*.py")):
            suites.append((directory, "test_*.py"))
    return suites


def run_project(project, output, args, revision):
    output.mkdir(parents=True, exist_ok=True)
    before = source_hashes(project)
    env = os.environ.copy()
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home.resolve())
    java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
    version = execute([java, "-version"], project, output / "java-version.log", env)
    result = {"revision": revision, "sourceFileSha256": before,
              "toolSha256": {"runner": digest(Path(__file__)), "gradleInit": digest(INIT)},
              "java": version, "commands": [], "pythonSuites": []}
    # A clean build prevents old XML reports or classes from turning a failed
    # compilation into a successful validation result.
    command = [str(project / "gradlew"), "--no-daemon", "--no-build-cache", "--console=plain", "--max-workers=2",
               "--init-script", str(INIT), "clean", *args.task]
    if args.offline:
        command.insert(1, "--offline")
    gradle = execute(command, project, output / "gradle.log", env)
    result["commands"].append(gradle)
    scala = re.search(r"Tests: succeeded (\d+), failed (\d+), canceled (\d+), ignored (\d+), pending (\d+)",
                      (output / "gradle.log").read_text(errors="replace"))
    if scala:
        result["scala"] = dict(zip(("passed", "failed", "canceled", "ignored", "pending"), map(int, scala.groups())))
    result["junit"] = collect_junit(project, output / "junit")
    for index, (directory, pattern) in enumerate(python_suites(project)):
        check = execute([sys.executable, "-m", "unittest", "discover", "-s", str(directory),
                         "-p", pattern, "-v"], project, output / ("python-%d.log" % index), env)
        check["suite"] = directory.name
        count = re.search(r"^Ran (\d+) tests? in ",
                          (output / ("python-%d.log" % index)).read_text(errors="replace"), re.MULTILINE)
        check["tests"] = int(count.group(1)) if count else 0
        check["discoveryPassed"] = check["tests"] > 0
        if not check["discoveryPassed"]:
            check["exitCode"] = check["exitCode"] or 1
        result["pythonSuites"].append(check)
    # Copy the HTML test reports for humans; JUnit XML remains machine-readable.
    for path in sorted(project.glob("**/build/reports/tests/test/index.html")):
        if path.relative_to(project).parts.count("build") != 1:
            continue
        target = output / "html" / path.relative_to(project).parent
        shutil.copytree(path.parent, target, dirs_exist_ok=True)
    failures = [entry for entry in [version, gradle, *result["pythonSuites"]] if entry["exitCode"]]
    result["passed"] = not failures and result["junit"]["tests"] > 0 and not (
        result["junit"]["failures"] or result["junit"]["errors"])
    result["artifactSha256"] = {path.relative_to(project).as_posix(): digest(path)
                                for path in sorted(project.glob("**/build/libs/*.jar"))}
    result["sourceUnchanged"] = source_hashes(project) == before
    if not result["sourceUnchanged"]:
        result["passed"] = False
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"revision": revision, "passed": result["passed"],
                      "junit": {k: v for k, v in result["junit"].items() if k != "reports"},
                      "results": str(output)}, indent=2), flush=True)
    return result


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/validation")
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--offline", action="store_true", help="Use an already populated Gradle dependency cache")
    parser.add_argument("--task", action="append", help="Gradle task; repeat to select modules (default: test)")
    parser.add_argument("--ref", action="append", default=[], help="Validate a clean local Git revision instead of the working tree; repeat to compare results")
    parser.add_argument("--combine-ref", action="append", default=[], help="Merge this local revision into a disposable copy of HEAD; repeat in desired merge order")
    args = parser.parse_args(argv)
    args.task = args.task or ["test"]
    args.output = args.output.resolve()
    if args.output == ROOT or ROOT.is_relative_to(args.output):
        parser.error("output must not be the repository or its parent")
    if args.ref and args.combine_ref:
        parser.error("--ref and --combine-ref are separate validation modes")
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    if args.ref or args.combine_ref:
        with tempfile.TemporaryDirectory(prefix="i2p-regressions-") as temporary:
            project = Path(temporary) / "checkout"
            subprocess.run(["git", "clone", "--quiet", "--shared", "--no-checkout", str(ROOT), str(project)], check=True)
            targets = args.ref or ["HEAD"]
            for index, ref in enumerate(targets):
                sha = git(ROOT, "rev-parse", "--verify", ref + "^{commit}")
                subprocess.run(["git", "-C", str(project), "checkout", "--quiet", "--detach", sha], check=True)
                if args.combine_ref:
                    # Conflicts are errors. Never silently replace a merge conflict
                    # with the combined workspace's files.
                    for branch in args.combine_ref:
                        other = git(ROOT, "rev-parse", "--verify", branch + "^{commit}")
                        subprocess.run(["git", "-C", str(project), "-c", "user.name=Regression validation",
                                        "-c", "user.email=validation@invalid", "merge", "--no-edit", "--no-ff", other], check=True)
                    sha = git(project, "rev-parse", "HEAD")
                results.append(run_project(project, args.output / ("ref-%d" % index), args, sha))
    else:
        results.append(run_project(ROOT, args.output / "working-tree", args, git(ROOT, "rev-parse", "HEAD")))
    summary = {"passed": all(result["passed"] for result in results),
               "results": [{"revision": r["revision"], "passed": r["passed"], "junit": r["junit"]} for r in results]}
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
