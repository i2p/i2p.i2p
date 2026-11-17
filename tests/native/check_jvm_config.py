#!/usr/bin/env python3
"""Check production jvm.cfg parsing with malformed and ordinary text-mode inputs."""
from native_check_support import check_extracted, definition

if __name__ == "__main__":
    raise SystemExit(check_extracted(__doc__, "installer/c/i2pExe/java.c",
        "jvm_config_harness.c", lambda source: definition(source, "ReadKnownVMs") + definition(source, "GrowKnownVMs"),
        ["normal", "crlf", "no-newline", "name-only", "blank", "nul", "fallback", "missing", "missing-speculative", "max-jre"]))
