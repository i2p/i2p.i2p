#!/usr/bin/env python3
"""Check allocation of the Windows launcher's PATH assignment with and without an old PATH."""
from native_check_support import check_extracted, definition

if __name__ == "__main__":
    raise SystemExit(check_extracted(__doc__, "installer/c/i2pExe/java_md.c",
        "jre_environment_harness.c", lambda source: definition(source, "GetJREPath"),
        ["env-absent", "env-present"]))
