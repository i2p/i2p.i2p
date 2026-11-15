#!/usr/bin/env python3
"""Check actual Windows launcher argument assembly with sanitizers and platform stubs."""
import re
from native_check_support import check_extracted, definition


def body(source):
    constants = "\n".join(re.findall(r"(?m)^#define MAX_OPTION(?:_SIZE|S)\s+\d+.*$", source))
    return (constants + "\n#define main launcher_main\n" + definition(source, "main")
            + "\n#undef main\n" + definition(source, "readOptions"))


if __name__ == "__main__":
    raise SystemExit(check_extracted(__doc__, "installer/c/i2pExe/i2p.c",
        "launcher_bounds_harness.c", body, ["arguments", "default", "0", "1", "9", "100"]))
