#!/usr/bin/env python3
"""Compile explicitly selected web-stack sources and run one isolated regression.

Build the repository jars first. The test runs without network sockets in a
temporary working directory; TLS tests receive a temporary, one-day certificate.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]


def invoke(command, cwd, timeout=90):
    start = time.monotonic()
    try:
        result = subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=timeout)
        return dict(exit_code=result.returncode, stdout=result.stdout, stderr=result.stderr,
                    duration_seconds=round(time.monotonic() - start, 3))
    except subprocess.TimeoutExpired:
        return dict(exit_code=None, error="timeout", duration_seconds=timeout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("sources", nargs="*", type=Path)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--jetty-home", type=Path, default=ROOT / "apps/jetty/jetty-home-12.0.41")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    source = args.source_root.resolve()
    fixture = (source / args.fixture).resolve()
    sources = [source / p for p in args.sources] + [fixture]
    jars = sorted(args.jetty_home.rglob("*.jar")) + sorted((ROOT / "build").glob("*.jar"))
    jars.append(ROOT / "installer/lib/wrapper/all/wrapper.jar")
    if not (ROOT / "build/i2p.jar").is_file() or not jars:
        raise SystemExit("Build the repository jars before running this test")
    classpath = os.pathsep.join(map(str, jars))
    text = fixture.read_text()
    package = re.search(r"^package ([\w.]+);", text, re.M)[1]
    classname = re.search(r"public class (\w+)", text)[1]
    report = dict(passed=False, sources={str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest()
                                        for p in sources},
                  jetty_home=str(args.jetty_home),
                  limits=["In-memory HTTP and controlled slow I/O; no TCP, TLS handshake or native QUIC handshake."])
    with tempfile.TemporaryDirectory(prefix="i2p-web-change-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        report["compilation"] = invoke(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", classpath,
                                        "-d", str(classes), *map(str, sources)], ROOT)
        if report["compilation"]["exit_code"] == 0:
            (work / "eepsite/etc").mkdir(parents=True)
            report["certificate"] = invoke(["keytool", "-genkeypair", "-keystore", "eepsite/etc/keystore.ks",
                                             "-storetype", "JKS", "-storepass", "changeit", "-keypass", "myKeyPassword",
                                             "-alias", "regression", "-dname", "CN=localhost", "-keyalg", "EC",
                                             "-groupname", "secp256r1", "-validity", "1", "-noprompt"], work)
            if report["certificate"]["exit_code"] == 0:
                report["runtime"] = invoke(["java", "-Xmx256m", "-XX:-UsePerfData", "-Djbigi.enable=false",
                                            "-Djbigi.dontLog=true", "-cp", os.pathsep.join([str(classes), classpath]),
                                            package + "." + classname, str(source), str(ROOT)], work)
                match = re.search(r"^CHANGE_CHECKS=(\d+); FAILURES=(\d+)$", report["runtime"].get("stdout", ""), re.M)
                report["passed"] = report["runtime"]["exit_code"] == 0 and match is not None and int(match[2]) == 0
                if match:
                    report.update(checks=int(match[1]), failures=int(match[2]))
    output = args.output or ROOT / "tests/results/webserver-stack" / (classname + ".json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(classname + ": " + str(report["passed"]) + "; checks=" + str(report.get("checks", 0)))
    if not report["passed"]:
        print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
