#!/usr/bin/env python3
"""Fail a finite live-router run unless its recorded connectivity requirements pass."""

import argparse
import json
from pathlib import Path
import re
import sys


class InvalidMetrics(ValueError):
    """The recording does not contain one complete, valid live-router run."""


def _integer(value, name, minimum=0):
    if type(value) is not int or value < minimum:
        raise InvalidMetrics(name + " must be an integer >= " + str(minimum))
    return value


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise InvalidMetrics("Duplicate JSON field: " + key)
        value[key] = item
    return value


def read_events(path):
    """Read every nonempty line, rejecting truncated or non-JSON output."""
    def invalid_constant(value):
        raise InvalidMetrics("Invalid JSON number: " + value)

    events = []
    with Path(path).open(encoding="utf-8") as source:
        for number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                event = json.loads(line, object_pairs_hook=_unique_object,
                                   parse_constant=invalid_constant)
            except (ValueError, TypeError) as error:
                raise InvalidMetrics("Invalid metrics line " + str(number)) from error
            if not isinstance(event, dict):
                raise InvalidMetrics("Metrics line " + str(number) + " is not an object")
            events.append(event)
    return events


def evaluate(events, *, process_exit_code=None, min_roundtrips=1,
             min_two_hop_samples=1, expected_duration=None, expected_hashes=None):
    """Return an identity-free verdict; invalid or incomplete recordings fail closed."""
    _integer(min_roundtrips, "min_roundtrips", 1)
    _integer(min_two_hop_samples, "min_two_hop_samples", 1)
    checks = {}
    evidence = {}
    try:
        if not isinstance(events, list) or not events or not all(isinstance(e, dict) for e in events):
            raise InvalidMetrics("No valid metrics events recorded")
        if any(e.get("event") not in ("start", "sample", "shutdown") for e in events):
            raise InvalidMetrics("Unknown metrics event")
        starts = [e for e in events if e.get("event") == "start"]
        samples = [e for e in events if e.get("event") == "sample"]
        endings = [e for e in events if e.get("event") == "shutdown"]
        if len(starts) != 1 or events[0] is not starts[0] or not samples:
            raise InvalidMetrics("Expected one start event followed by samples")
        if len(endings) > 1 or (endings and events[-1] is not endings[0]):
            raise InvalidMetrics("Shutdown must occur once, after all samples")
        first = starts[0]
        duration = _integer(first.get("durationSeconds"), "durationSeconds", 1)
        if expected_duration is not None:
            _integer(expected_duration, "expected_duration", 1)
            if duration != expected_duration:
                raise InvalidMetrics("Recorded duration differs from requested duration")
        hashes = {}
        for field, name in (("coreArtifact", "i2p.jar"), ("routerArtifact", "router.jar")):
            artifact = first.get(field)
            digest = artifact.get("sha256") if isinstance(artifact, dict) else None
            if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
                raise InvalidMetrics("Missing valid SHA-256 for " + name)
            hashes[name] = digest
        checks["artifactHashesRecorded"] = True
        if expected_hashes is not None:
            checks["artifactHashesMatch"] = all(
                expected_hashes.get(name) == digest for name, digest in hashes.items())
        evidence["jarSha256"] = hashes
        previous_elapsed = -1
        previous_roundtrips = -1
        two_hop_times = set()
        for sample in samples:
            elapsed = _integer(sample.get("elapsedSeconds"), "elapsedSeconds")
            if elapsed < previous_elapsed:
                raise InvalidMetrics("Sample times moved backwards")
            previous_elapsed = elapsed
            if sample.get("routerAlive") is not True:
                raise InvalidMetrics("Router was not alive during sampling")
            lengths = []
            for direction in ("inboundExploratoryLengths", "outboundExploratoryLengths"):
                value = sample.get(direction)
                if not isinstance(value, list):
                    raise InvalidMetrics("Missing tunnel lengths: " + direction)
                for length in value:
                    _integer(length, direction)
                lengths.append(value)
            if all(any(length >= 2 for length in direction) for direction in lengths):
                two_hop_times.add(elapsed)
            stats = sample.get("stats")
            success = stats.get("tunnel.testSuccessTime") if isinstance(stats, dict) else None
            count = _integer(success.get("events") if isinstance(success, dict) else None,
                             "tunnel round-trip count")
            if count < previous_roundtrips:
                raise InvalidMetrics("Round-trip counter moved backwards")
            previous_roundtrips = count
        ending = endings[0] if endings else {}
        checks["processExitedSuccessfully"] = type(process_exit_code) is int and process_exit_code == 0
        checks["durationCompleted"] = (ending.get("reason") == "duration-complete"
                                       and previous_elapsed >= duration)
        checks["shutdownCompleted"] = (ending.get("completed") is True
                                       and ending.get("routerAlive") is False)
        checks["successfulRoundtrips"] = previous_roundtrips >= min_roundtrips
        checks["twoHopTunnelsBothDirections"] = len(two_hop_times) >= min_two_hop_samples
        evidence.update(durationSeconds=duration, elapsedSeconds=previous_elapsed,
                        successfulRoundtrips=previous_roundtrips,
                        twoHopSamplesBothDirections=len(two_hop_times))
    except (InvalidMetrics, TypeError, AttributeError) as error:
        return {"event": "verification", "passed": False, "checks": checks,
                "failures": [str(error)], "evidence": evidence}
    return {"event": "verification", "passed": all(checks.values()), "checks": checks,
            "failures": [name for name, passed in checks.items() if not passed],
            "requirements": {"minRoundtrips": min_roundtrips,
                             "minTwoHopSamples": min_two_hop_samples},
            "evidence": evidence}


def verify_file(path, **requirements):
    try:
        return evaluate(read_events(path), **requirements)
    except (OSError, UnicodeError, ValueError) as error:
        return {"event": "verification", "passed": False, "checks": {},
                "failures": [str(error)], "evidence": {}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metrics", type=Path)
    parser.add_argument("--process-exit-code", type=int,
                        help="Recorded child exit status; omission cannot produce a passing verdict")
    parser.add_argument("--launch-manifest", type=Path,
                        help="Compare loaded core/router hashes with launch.json")
    parser.add_argument("--min-roundtrips", type=int,
                        help="At least this many round trips; cannot weaken recorded requirements")
    parser.add_argument("--min-two-hop-samples", type=int,
                        help="At least this many distinct samples; cannot weaken recorded requirements")
    args = parser.parse_args()
    if any(value is not None and value < 1
           for value in (args.min_roundtrips, args.min_two_hop_samples)):
        parser.error("verification thresholds must be at least one")
    requirements = dict(process_exit_code=args.process_exit_code)
    recorded_roundtrips = recorded_samples = 1
    if args.launch_manifest:
        try:
            manifest = json.loads(args.launch_manifest.read_text(), object_pairs_hook=_unique_object)
            if not isinstance(manifest, dict) or not isinstance(manifest.get("jarSha256"), dict):
                raise InvalidMetrics("Expected a launch object containing jarSha256")
            _integer(manifest.get("durationSeconds"), "launch durationSeconds", 1)
            recorded_roundtrips = _integer(manifest.get("minRoundtrips", 1), "launch minRoundtrips", 1)
            recorded_samples = _integer(manifest.get("minTwoHopSamples", 1), "launch minTwoHopSamples", 1)
            requirements.update(expected_duration=manifest["durationSeconds"],
                                expected_hashes=manifest["jarSha256"])
        except (OSError, UnicodeError, ValueError, KeyError, TypeError) as error:
            print(json.dumps({"event": "verification", "passed": False,
                              "failures": ["Invalid launch manifest: " + str(error)]}))
            return 1
    requirements.update(min_roundtrips=max(recorded_roundtrips, args.min_roundtrips or 1),
                        min_two_hop_samples=max(recorded_samples, args.min_two_hop_samples or 1))
    verdict = verify_file(args.metrics, **requirements)
    print(json.dumps(verdict, indent=2))
    return 0 if verdict["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
