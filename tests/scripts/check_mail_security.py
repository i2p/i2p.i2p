#!/usr/bin/env python3
"""Compile SusiMail and exercise security regressions using existing local jars."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def invoke(command, cwd):
    try:
        result = subprocess.run(command, cwd=cwd, text=True, capture_output=True, timeout=120)
        return {"exit_code": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    except subprocess.TimeoutExpired:
        return {"exit_code": 124, "stdout": "", "stderr": "Regression exceeded its 120-second deadline"}


def run(source, output):
    jars = sorted((ROOT / "build").glob("*.jar"))
    jetty_homes = sorted((path for path in (ROOT / "apps/jetty").glob("jetty-home-*") if path.is_dir() and re.fullmatch(r"jetty-home-\d+\.\d+\.\d+", path.name)), key=lambda path: tuple(map(int, path.name.split("-")[-1].split("."))))
    jars += sorted((jetty_homes[-1] / "lib").glob("*.jar"))
    if not (ROOT / "build/i2p.jar").is_file():
        raise RuntimeError("Build the repository jars before running this offline regression")
    dependencies = os.pathsep.join(map(str, jars))
    sources = sorted((source / "apps/susimail/src/src").rglob("*.java"))
    production = sources + [source / "apps/susimail/src/js/iframer.js"]
    production += [p for p in (source / "apps/susimail/src/WEB-INF/web.xml", source / "apps/susimail/src/susimail.properties",
        source / "apps/susimail/src/themes/security.css", source / "apps/susimail/src/themes/mail-content.css") if p.exists()]
    sources = sources + [ROOT / "tests/regression/SusiMailSecurityRegression.java",
                         ROOT / "tests/regression/SusiMailIdentityRegression.java"]
    expanded = (source / "apps/susimail/src/src/i2p/susi/webmail/MailVault.java").exists()
    if expanded: sources += [ROOT / "tests/regression/SusiMailProtectionRegression.java", ROOT / "tests/regression/SusiMailInstallRegression.java", ROOT / "tests/regression/SusiMailRequestRegression.java"]
    report = {"passed": False, "source_root": str(source), "compiled_sources": len(sources),
              "source_sha256": {str(path.relative_to(source)): hashlib.sha256(path.read_bytes()).hexdigest()
                                for path in production},
              "dependency_sha256": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in jars},
              "java": invoke(["java", "-version"], ROOT),
              "limits": ["Uses locally built dependency jars and Java 17 compilation; does not contact mail servers.",
                         "JavaScript behavior uses a small DOM fixture, not a browser. CSP headers are checked through Jetty LocalConnector."]}
    with tempfile.TemporaryDirectory(prefix="i2p-mail-security-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        report["compile"] = invoke(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", dependencies,
                                    "-d", str(classes), *map(str, sources)], work)
        if report["compile"]["exit_code"] != 0:
            return report
        classpath = os.pathsep.join([str(classes), str(ROOT / "apps/susimail/src"), dependencies])
        report["regression"] = invoke(["java", "-Xmx256m", "-Djbigi.enable=false", "-Djbigi.dontLog=true",
            "-DI2P_DISABLE_OUTPUT_OVERRIDE=true", "-cp", classpath,
            "i2p.susi.webmail.SusiMailSecurityRegression", str(output)], work)
        report["identity_regression"] = invoke(["java", "-Xmx256m", "-Djbigi.enable=false", "-Djbigi.dontLog=true",
            "-DI2P_DISABLE_OUTPUT_OVERRIDE=true", "-cp", classpath,
            "i2p.susi.webmail.SusiMailIdentityRegression"], work)
        steps = ["regression", "identity_regression", "frame_regression"]
        if expanded:
            report["protection_regression"] = invoke(["java", "-Xmx384m", "-Djbigi.enable=false", "-Djbigi.dontLog=true",
                "-DI2P_DISABLE_OUTPUT_OVERRIDE=true", "-cp", classpath, "i2p.susi.webmail.SusiMailProtectionRegression"], work)
            steps.append("protection_regression")
            report["request_regression"] = invoke(["java", "-Xmx128m", "-cp", classpath, "i2p.susi.webmail.SusiMailRequestRegression"], work)
            steps.append("request_regression")
            ant = ROOT / "target/local-i2p/tools/ant/lib"
            ant_command = [shutil.which("ant")] if shutil.which("ant") else ["java", "-cp", str(ant / "*"), "org.apache.tools.ant.Main"]
            report["normal_war_build"] = invoke(ant_command + ["-f", str(source / "apps/susimail/build.xml"), "war"], source)
            war = source / "apps/susimail/susimail.war"
            if war.exists(): report["war_sha256"] = hashlib.sha256(war.read_bytes()).hexdigest()
            if report["normal_war_build"]["exit_code"] == 0:
                fixture = work / "install-fixture/i2p/susi/webmail"
                fixture.mkdir(parents=True)
                for compiled in (classes / "i2p/susi/webmail").glob("SusiMailInstallRegression*.class"):
                    shutil.copy2(compiled, fixture)
                install_classpath = os.pathsep.join([str(work / "install-fixture"), dependencies])
                report["install_regression"] = invoke(["java", "-Xmx384m", "-Djbigi.enable=false", "-Djbigi.dontLog=true", "-DI2P_DISABLE_OUTPUT_OVERRIDE=true", "-cp", install_classpath,
                    "i2p.susi.webmail.SusiMailInstallRegression", str(source / "apps/susimail/susimail.war"), str(output)], work)
            else: report["install_regression"] = {"exit_code": 1, "stdout": "", "stderr": "Normal Ant WAR build failed"}
            steps.append("install_regression")
        report["javascript_syntax"] = invoke(["node", "--check", str(source / "apps/susimail/src/js/iframer.js")], work)
        report["frame_regression"] = invoke(["node", str(ROOT / "tests/regression/SusiMailFrameRegression.js"), str(source)], work)
        for step in steps:
            summary = re.search(r"^SUSIMAIL_\w+_CHECKS=(\d+); FAILURES=(\d+)$", report[step]["stdout"], re.MULTILINE)
            report[step]["checks"] = int(summary[1]) if summary else None
            report[step]["failures"] = int(summary[2]) if summary else None
        report["passed"] = report["javascript_syntax"]["exit_code"] == 0 and all(
            report[step]["exit_code"] == 0 and report[step]["checks"] and report[step]["failures"] == 0
            for step in steps)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path, default=ROOT / "tests/results/mail-security/current")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    report = run(args.source_root.resolve(), output)
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    for step in ("compile", "regression", "identity_regression", "protection_regression", "request_regression", "normal_war_build", "install_regression", "javascript_syntax", "frame_regression"):
        if step in report:
            print(step + ": exit " + str(report[step]["exit_code"]))
            print(report[step]["stdout"], end="")
            if report[step]["exit_code"]:
                print(report[step]["stderr"], end="", file=sys.stderr)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
