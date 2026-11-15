#!/usr/bin/env python3
"""Check Snark form navigation through the real console origin guard, without sockets."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--strict-origin", action="store_true", help="also exercise the hardened shared Origin validator")
    args = parser.parse_args()
    jars = ("core/java/build/i2p.jar", "apps/ministreaming/java/build/mstreaming.jar",
            "apps/systray/java/build/systray.jar", "apps/desktopgui/dist/desktopgui.jar",
            "apps/routerconsole/java/build/routerconsole.jar", "apps/jetty/jettylib/*")
    classpath = os.pathsep.join(str(ROOT / jar) for jar in jars)
    sources = sorted((args.source_root / "apps/i2psnark/java/src").rglob("*.java"))
    sources.append(ROOT / "tests/regression/SnarkFormOriginRegression.java")
    with tempfile.TemporaryDirectory(prefix="snark-form-origin-") as temporary:
        work = Path(temporary)
        arguments = work / "sources.args"
        arguments.write_text("\n".join('"' + str(path) + '"' for path in sources) + "\n")
        subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(work / "classes"), "@" + str(arguments)], check=True, timeout=120)
        subprocess.run(["java", "-Xmx128m", "-Djbigi.enable=false", "-Djcpuid.enable=false", "-Djbigi.dontLog=true",
                        "-Dsnark.test.strictOrigin=" + str(args.strict_origin).lower(),
                        "-DI2P_DISABLE_OUTPUT_OVERRIDE=true", "-cp", str(work / "classes") + os.pathsep + classpath,
                        "org.klomp.snark.SnarkFormOriginRegression"], cwd=work, check=True, timeout=30)


if __name__ == "__main__":
    main()
