#!/usr/bin/env python3
"""Link the complete CPUID source and check host registers plus JNI lookup/allocation failures."""
import hashlib
import tempfile
from pathlib import Path
from native_check_support import ROOT, SANITIZERS, arguments, finish, invoke, jni_includes


def main():
    args = arguments(__doc__)
    source_tree = args.source_tree.resolve()
    source = source_tree / "core/c/jcpuid/src/jcpuid.c"
    report = {"source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "compilation": {}, "cases": {},
              "limits": ["Actual x86 CPUID instructions and full production source, with controlled JNI callbacks.",
                         "Linux address/undefined-behavior sanitizers are enabled; leak detection is disabled."]}
    with tempfile.TemporaryDirectory(prefix="i2p-jcpuid-check-") as temporary:
        work = Path(temporary)
        executable = work / "jcpuid"
        report["compilation"]["harness"] = invoke(SANITIZERS + jni_includes(source_tree, "jcpuid")
            + [source, ROOT / "tests/native/jcpuid_harness.c", "-o", executable], work)
        if report["compilation"]["harness"]["exit_code"] == 0:
            for case in ["host-registers", "class-failure", "constructor-failure", "allocation-failure"]:
                report["cases"][case] = invoke([executable, case], work)
    return finish(report, args.output)


if __name__ == "__main__":
    raise SystemExit(main())
