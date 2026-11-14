#!/usr/bin/env python3
"""Summarize LiveRouterSmoke JSONL without exposing router/peer identities."""

import json
from pathlib import Path
import sys


def summarize(path):
    events = [json.loads(line) for line in Path(path).read_text().splitlines() if line.startswith("{")]
    starts = [item for item in events if item.get("event") == "start"]
    samples = [item for item in events if item.get("event") == "sample"]
    if not samples:
        raise ValueError("No samples recorded")
    first = starts[0] if starts else {}
    last = samples[-1]
    stats = last.get("stats", {})
    successes = stats.get("tunnel.buildExploratorySuccess", {}).get("events", 0)
    rejections = stats.get("tunnel.buildExploratoryReject", {}).get("events", 0)
    timeouts = stats.get("tunnel.buildExploratoryExpire", {}).get("events", 0)
    attempts = successes + rejections + timeouts
    established_probes = set()
    sent_probes = set()
    attempted_probes = set()
    for sample in samples:
        for probe in sample.get("introducedPeerProbes", []):
            attempted_probes.add(probe["index"])
            if probe.get("udpEstablished") or probe.get("state") == "OB_STATE_CONFIRMED_COMPLETELY":
                established_probes.add(probe["index"])
            if probe.get("messageSent"):
                sent_probes.add(probe["index"])
    both_tunnels = [sample for sample in samples
                    if any(length >= 2 for length in sample.get("inboundExploratoryLengths", []))
                    and any(length >= 2 for length in sample.get("outboundExploratoryLengths", []))]
    return {
        "mode": first.get("mode"),
        "started": first.get("at"),
        "lastSample": last.get("at"),
        "elapsedSeconds": last.get("elapsedSeconds"),
        "samples": len(samples),
        "shutdownRecorded": any(item.get("event") == "shutdown" for item in events),
        "routerArtifact": first.get("routerArtifact"),
        "coreArtifact": first.get("coreArtifact"),
        "observedStatuses": sorted({item.get("status", "UNKNOWN") for item in samples}),
        "maxActivePeers": max(item.get("activePeers", 0) for item in samples),
        "maxKnownRouters": max(item.get("knownRouters", 0) for item in samples),
        "maxSSU2Peers": max(sum(item.get("udpPeerCounts", [])[4:]) for item in samples),
        "maxPublishedIntroducers": max(item.get("publishedIntroducers", 0) for item in samples),
        "maxAvailableIntroducerTags": max(item.get("availableIntroducerTags", 0) for item in samples),
        "firstBothTwoHopTunnelSeconds": both_tunnels[0]["elapsedSeconds"] if both_tunnels else None,
        "samplesWithBothTwoHopTunnels": len(both_tunnels),
        "exploratoryBuildSuccesses": successes,
        "exploratoryBuildRejections": rejections,
        "exploratoryBuildTimeouts": timeouts,
        "exploratorySuccessEventSharePercent": round(100 * successes / attempts, 2) if attempts else None,
        "tunnelRoundtripSuccesses": stats.get("tunnel.testSuccessTime", {}).get("events", 0),
        "averageTunnelRoundtripMs": stats.get("tunnel.testSuccessTime", {}).get("average"),
        "averageTestedRemoteHops": (stats["tunnel.testSuccessLength"]["average"] - 1)
                                  if stats.get("tunnel.testSuccessLength", {}).get("events", 0) else None,
        "outboundUDPEstablishments": stats.get("udp.outboundEstablishTime", {}).get("events", 0),
        "inboundUDPEstablishments": stats.get("udp.inboundEstablishTime", {}).get("events", 0),
        "introductionTimeouts": stats.get("udp.sendIntroRelayTimeout", {}).get("events", 0),
        "introducedPeerProbesAttempted": len(attempted_probes),
        "introducedPeerProbesEverEstablishedUDP": len(established_probes),
        "introducedPeerProbeMessagesSent": len(sent_probes),
        "inboundBytes": last.get("inboundBytes"),
        "outboundBytes": last.get("outboundBytes"),
    }


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: summarize_live.py /path/to/metrics.jsonl")
    print(json.dumps(summarize(sys.argv[1]), indent=2))
