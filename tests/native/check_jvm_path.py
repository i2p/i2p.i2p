#!/usr/bin/env python3
"""Check JVM path destination bounds in the production Windows launcher helper."""
from native_check_support import check_extracted, definition

if __name__ == "__main__":
    raise SystemExit(check_extracted(__doc__, "installer/c/i2pExe/java_md.c",
        "jvm_path_bounds_harness.c", lambda source: definition(source, "GetJVMPath"),
        ["normal", "custom", "exact", "small", "zero", "negative", "large-jre", "large-custom"]))
