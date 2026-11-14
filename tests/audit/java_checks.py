"""Compile and exercise the integrated networking code without downloading dependencies."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

ROOTS = ("core/java/src", "router/java/src", "apps/ministreaming/java/src",
         "apps/streaming/java/src", "apps/addressbook/java/src")
TESTS = {
    "UTF8ReaderRegression": "net.i2p.sam.UTF8ReaderRegression",
    "SOCKS5LengthRegression": "net.i2p.socks.SOCKS5LengthRegression",
    "SubscriptionMetadataRegression": "net.i2p.addressbook.SubscriptionMetadataRegression",
    "StreamingQueueRegression": "net.i2p.client.streaming.impl.StreamingQueueRegression",
    "NTCPFrameBoundsRegression": "net.i2p.router.transport.ntcp.NTCPFrameBoundsRegression",
    "PacketOffsetRegression": "net.i2p.client.streaming.impl.PacketOffsetRegression",
    "HTTPRequestLogRegression": "net.i2p.util.HTTPRequestLogRegression",
    "NTCPPayloadLogRegression": "net.i2p.router.transport.ntcp.NTCPPayloadLogRegression",
    "HTTPPostLengthRegression": "net.i2p.util.HTTPPostLengthRegression",
    "HTTPChunkLengthRegression": "net.i2p.util.HTTPChunkLengthRegression",
    "LookaheadShortInputRegression": "net.i2p.util.LookaheadShortInputRegression",
    "I2PSocketRequestLogRegression": "net.i2p.util.I2PSocketRequestLogRegression",
    "HTTPRemainingLengthRegression": "net.i2p.util.HTTPRemainingLengthRegression",
    "NTCPReadBufferRegression": "net.i2p.router.transport.ntcp.NTCPReadBufferRegression",
    "EncryptedLeaseSetPrivacyRegression": "net.i2p.util.EncryptedLeaseSetPrivacyRegression",
    "JobQueueClockShiftRegression": "net.i2p.router.JobQueueClockShiftRegression",
    "TunnelBuildPrivacyRegression": "net.i2p.util.TunnelBuildPrivacyRegression",
    "TunnelFragmentRegression": "net.i2p.router.tunnel.TunnelFragmentRegression",
    "TunnelShareAdmissionRegression": "net.i2p.router.TunnelShareAdmissionRegression",
    "TunnelBuildAccountingRegression": "net.i2p.router.tunnel.pool.TunnelBuildAccountingRegression",
    "TunnelFrameIntegrityRegression": "net.i2p.router.tunnel.TunnelFrameIntegrityRegression",
    "TunnelBuildSchedulerRegression": "net.i2p.router.tunnel.pool.TunnelBuildSchedulerRegression",
    "ZipCommentWindowRegression": "net.i2p.util.ZipCommentWindowRegression",
    "ZipCommentBoundsRegression": "net.i2p.util.ZipCommentBoundsRegression",
}
GROUPS = {
    "regression": tuple(TESTS),
    "compatibility": ("StreamingQueueRegression", "NTCPFrameBoundsRegression", "PacketOffsetRegression", "HTTPPostLengthRegression", "HTTPRemainingLengthRegression", "TunnelBuildPrivacyRegression", "TunnelFragmentRegression", "TunnelFrameIntegrityRegression"),
    "privacy": ("HTTPRequestLogRegression", "NTCPPayloadLogRegression", "LookaheadShortInputRegression", "I2PSocketRequestLogRegression", "EncryptedLeaseSetPrivacyRegression", "TunnelBuildPrivacyRegression", "TunnelFragmentRegression"),
    "performance": ("StreamingQueueRegression", "TunnelBuildSchedulerRegression"),
    "fuzz": ("ParserFuzz", "StreamingParserFuzz"),
    "build": (),
}


def invoke(arguments, working, timeout=120):
    started = time.monotonic()
    result = subprocess.run(arguments, cwd=working, capture_output=True, text=True, timeout=timeout)
    return {"exit_code": result.returncode, "stdout": result.stdout, "stderr": result.stderr,
            "duration_seconds": round(time.monotonic() - started, 3)}


def run(root, group):
    root = Path(root)
    contracts = json.loads((root / "tests/audit/contracts.json").read_text())
    selected = GROUPS[group]
    with tempfile.TemporaryDirectory(prefix="i2p-worker-java-") as temporary:
        working = Path(temporary)
        sources = [path for directory in ROOTS for path in sorted((root / directory).rglob("*.java"))]
        sources.append(root / "apps/sam/java/src/net/i2p/sam/UTF8Reader.java")
        for name in selected:
            sources.append(root / ("tests/audit/" + name + ".java" if name in ("ParserFuzz", "StreamingParserFuzz")
                                   else "tests/regression/" + name + ".java"))
        missing = [str(path.relative_to(root)) for path in sources if not path.is_file()]
        if missing:
            return {"passed": False, "error": "required source or regression is missing", "missing": missing}
        arguments = working / "sources.args"
        arguments.write_text("\n".join('"' + str(path).replace('\\', '\\\\').replace('"', '\\"') + '"'
                                       for path in sources) + "\n")
        classes = working / "classes"
        classes.mkdir()
        compiled = invoke(["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(classes),
                           "@" + str(arguments)], working)
        report = {"passed": compiled["exit_code"] == 0, "compiled_sources": len(sources),
                  "compilation": compiled, "tests": {}, "coverage": list(ROOTS)}
        if not report["passed"]:
            return report
        for name in selected:
            if name == "ParserFuzz":
                main = "net.i2p.router.transport.ntcp.ParserFuzz"
            elif name == "StreamingParserFuzz":
                main = "net.i2p.client.streaming.impl.StreamingParserFuzz"
            else:
                main = TESTS[name]
            command = ["java", "-Xmx256m", "-Djbigi.enable=false", "-Djbigi.dontLog=true", "-cp", str(classes), main]
            if group == "performance":
                command.append("--benchmark")
            outcome = invoke(command, working, timeout=90)
            matches = {}
            for key, expected in contracts.get(name, {}).items():
                found = re.search(r"^" + re.escape(key) + r"=([^\n]+)$", outcome["stdout"], re.MULTILINE)
                matches[key] = bool(found and found.group(1) == expected)
            outcome["compatibility_contracts"] = matches
            report["tests"][name] = outcome
            report["passed"] = report["passed"] and outcome["exit_code"] == 0 and all(matches.values())
        if group in ("regression", "privacy"):
            result = invoke(["python3", str(root / "tests/scripts/check_ntcp_key_logs.py"), str(root)], working)
            report["tests"]["NTCPKeyLogs"] = result
            report["passed"] = report["passed"] and result["exit_code"] == 0
        if group == "performance":
            report["measurement_scope"] = "Local ready-queue drain timings and tunnel scheduler slot/budget checks with synthetic sends; no end-to-end throughput claim or timing speedup gate."
        return report
