#!/usr/bin/env python3
"""Reject known NTCP framing secrets in logger statements; no runtime traffic."""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
directory = root / "router/java/src/net/i2p/router/transport/ntcp"
secret = r"\b(?:sip_ab|sip_ba|_sendSipk1|_sendSipk2|_sendSipIV|_sipk1|_sipk2|_sipIV)\b"
hits = []
for name in ("InboundEstablishState.java", "OutboundNTCP2State.java", "NTCPConnection.java"):
    source = (directory / name).read_text()
    for log in re.finditer(r"^\s*_log\.(?:debug|info|warn|error|log)\([\s\S]*?\);", source, re.MULTILINE):
        if re.search(secret, log.group()):
            hits.append(name + ":" + str(source.count("\n", 0, log.start()) + 1))
for hit in hits:
    print("FAIL: framing secret in log at " + hit)
print("SECRET_LOG_SITES=" + str(len(hits)))
raise SystemExit(1 if hits else 0)
