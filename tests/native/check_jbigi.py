#!/usr/bin/env python3
"""Check complete jbigi/GMP failure paths and compare a real JNI library with BigInteger."""
import hashlib
import os
from pathlib import Path
import re
import tempfile
from native_check_support import ROOT, SANITIZERS, arguments, finish, invoke, jni_includes


def main():
    args = arguments(__doc__)
    source_tree = args.source_tree.resolve()
    source = source_tree / "core/c/jbigi/jbigi/src/jbigi.c"
    includes = jni_includes(source_tree, "jbigi")
    report = {"source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "compilation": {}, "cases": {},
              "limits": ["The full production C source links real GMP with controlled JNI callbacks for failure injection.",
                         "C harnesses use address/undefined-behavior sanitizers; the real-JVM shared library is uninstrumented. Leak detection is disabled."]}
    with tempfile.TemporaryDirectory(prefix="i2p-jbigi-check-") as temporary:
        work = Path(temporary)
        executable = work / "jbigi"
        report["compilation"]["harness"] = invoke(SANITIZERS + includes
            + [source, ROOT / "tests/native/jbigi_failure_harness.c", "-lgmp", "-o", executable], work)
        if report["compilation"]["harness"]["exit_code"] == 0:
            for operation in ["pow", "ct", "inverse"]:
                for case in ["ok", "null-mod", "null-base", "empty-mod", "empty-base", "pin-mod", "pin-exp", "pin-base", "alloc-output", "pin-output"]:
                    if operation == "inverse" and case == "pin-exp": continue
                    report["cases"][operation + "/" + case] = invoke([executable, operation, case], work)
        library = work / "libjbigi-review.so"
        report["compilation"]["jni_library"] = invoke(["gcc", "-std=gnu11", "-O2", "-fPIC", "-shared"]
            + includes + [source, "-lgmp", "-o", library], work)
        java_classes = args.java_classes.resolve() if args.java_classes else work / "core-classes"
        if not args.java_classes:
            java_classes.mkdir()
            sources = work / "core.args"
            sources.write_text("\n".join('"' + str(p) + '"' for p in sorted((source_tree / "core/java/src").rglob("*.java"))) + "\n")
            report["compilation"]["core"] = invoke(["javac", "--release", "17", "-encoding", "UTF-8",
                "-d", java_classes, "@" + str(sources)], work, timeout=120)
        fixture = work / "fixture-classes"
        report["compilation"]["fixture"] = invoke(["javac", "--release", "17", "-d", fixture,
            ROOT / "tests/regression/NativeBigIntegerJniRegression.java"], work)
        if all(c["exit_code"] == 0 for c in report["compilation"].values()):
            command = ["java", "-Xmx256m", "-Djbigi.enable=false", "-Djbigi.dontLog=true", "-cp",
                       os.pathsep.join([str(fixture), str(java_classes)]), "net.i2p.util.NativeBigIntegerJniRegression", library]
            if args.valid_jni_only: command.append("--valid-only")
            report["cases"]["real-jni"] = invoke(command, work, timeout=60)
            digest = re.search(r"^NATIVE_VALID_SHA256=([0-9a-f]{64})$", report["cases"]["real-jni"]["stdout"], re.MULTILINE)
            report["native_valid_sha256"] = digest[1] if digest else None
    return finish(report, args.output)


if __name__ == "__main__":
    raise SystemExit(main())
