#!/usr/bin/env python3
"""Compile and exercise I2PSnark's bencode decoder with bounded JVMs."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CASES = ("valid", "lengths", "numbers", "truncation", "dictionaries", "nesting", "hashes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--case", choices=CASES, action="append", dest="cases")
    args = parser.parse_args()
    core = ROOT / "core/java/build/i2p.jar"
    if not core.is_file():
        parser.error("build core/java/build/i2p.jar before running this check")
    sources = sorted((args.source_root / "apps/i2psnark/java/src/org/klomp/snark/bencode").glob("*.java"))
    if not sources:
        parser.error("no bencode sources found under --source-root")
    sources.append(ROOT / "tests/regression/BDecoderRegression.java")
    failures = 0
    with tempfile.TemporaryDirectory(prefix="i2psnark-bdecode-") as directory:
        classes = Path(directory) / "classes"
        classes.mkdir()
        subprocess.run(["javac", "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", str(core),
                        "-d", str(classes), *map(str, sources)], check=True, timeout=60)
        for case in args.cases or CASES:
            try:
                result = subprocess.run(["java", "-Xmx64m", "-Xss1m", "-cp",
                                         os.pathsep.join((str(classes), str(core))),
                                         "org.klomp.snark.bencode.BDecoderRegression", case],
                                        cwd=directory, capture_output=True, text=True, timeout=20)
                print(result.stdout, end="", flush=True)
                if result.returncode:
                    failures += 1
                    print(case + ": FAILED\n" + result.stderr[:4000], flush=True)
            except subprocess.TimeoutExpired:
                failures += 1
                print(case + ": FAILED (decoder did not terminate within 20 seconds)", flush=True)
    print("BENCODE_CASES=" + str(len(args.cases or CASES)) + "; FAILURES=" + str(failures))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
