"""Deterministic connectivity verdict tests; no public routers or network required."""

import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from run_live import finish_run
from verify_live import evaluate, verify_file


HASHES = {"i2p.jar": "a" * 64, "router.jar": "b" * 64}


def recording():
    return [
        {"event": "start", "durationSeconds": 240,
         "coreArtifact": {"sha256": HASHES["i2p.jar"]},
         "routerArtifact": {"sha256": HASHES["router.jar"]}},
        {"event": "sample", "elapsedSeconds": 0, "routerAlive": True,
         "inboundExploratoryLengths": [], "outboundExploratoryLengths": [],
         "stats": {"tunnel.testSuccessTime": {"events": 0}}},
        {"event": "sample", "elapsedSeconds": 240, "routerAlive": True,
         "inboundExploratoryLengths": [0, 1, 2], "outboundExploratoryLengths": [2],
         "stats": {"tunnel.testSuccessTime": {"events": 3}}},
        {"event": "shutdown", "reason": "duration-complete",
         "completed": True, "routerAlive": False},
    ]


class LiveVerdictTest(unittest.TestCase):
    def verdict(self, events, **requirements):
        return evaluate(events, process_exit_code=0, expected_hashes=HASHES, **requirements)

    def test_completed_connected_run_passes_with_loaded_artifact_hashes(self):
        result = self.verdict(recording(), expected_duration=240)
        self.assertTrue(result["passed"], result)
        self.assertEqual(3, result["evidence"]["successfulRoundtrips"])
        self.assertEqual(HASHES, result["evidence"]["jarSha256"])

    def test_isolated_router_fails_despite_successful_process_shutdown(self):
        events = recording()
        events[2].update(inboundExploratoryLengths=[], outboundExploratoryLengths=[])
        events[2]["stats"]["tunnel.testSuccessTime"]["events"] = 0
        result = self.verdict(events)
        self.assertFalse(result["passed"])
        self.assertEqual({"successfulRoundtrips", "twoHopTunnelsBothDirections"},
                         set(result["failures"]))
        self.assertTrue(result["checks"]["shutdownCompleted"])

    def test_zero_and_one_hop_fallback_tunnels_do_not_pass(self):
        for lengths in ([], [0], [1], [0, 1, 1]):
            with self.subTest(lengths=lengths):
                events = recording()
                events[2]["inboundExploratoryLengths"] = lengths
                self.assertFalse(self.verdict(events)["passed"])

    def test_both_directions_must_exist_at_the_same_time(self):
        events = recording()
        events[1]["inboundExploratoryLengths"] = [2]
        events[2]["inboundExploratoryLengths"] = []
        self.assertFalse(self.verdict(events)["passed"])

    def test_requirement_thresholds_are_enforced(self):
        events = recording()
        self.assertFalse(self.verdict(events, min_roundtrips=4)["passed"])
        self.assertFalse(self.verdict(events, min_two_hop_samples=2)["passed"])
        earlier = copy.deepcopy(events[2])
        earlier["elapsedSeconds"] = 225
        events.insert(2, earlier)
        self.assertTrue(self.verdict(events, min_two_hop_samples=2)["passed"])

    def test_duplicate_final_sample_cannot_inflate_two_hop_sample_requirement(self):
        events = recording()
        events.insert(3, copy.deepcopy(events[2]))
        self.assertFalse(self.verdict(events, min_two_hop_samples=2)["passed"])

    def test_stop_file_and_error_never_count_as_completed_runs(self):
        for reason in ("stop-file", "error", None):
            with self.subTest(reason=reason):
                events = recording()
                events[-1]["reason"] = reason
                self.assertFalse(self.verdict(events)["passed"])

    def test_full_duration_must_be_sampled_and_match_launcher(self):
        events = recording()
        events[2]["elapsedSeconds"] = 239
        self.assertFalse(self.verdict(events)["passed"])
        self.assertFalse(self.verdict(recording(), expected_duration=300)["passed"])

    def test_old_premature_shutdown_marker_does_not_pass(self):
        events = recording()
        del events[-1]["completed"]
        del events[-1]["routerAlive"]
        result = self.verdict(events)
        self.assertFalse(result["passed"])
        self.assertEqual(["shutdownCompleted"], result["failures"])
        self.assertTrue(result["checks"]["successfulRoundtrips"])

    def test_router_still_alive_at_shutdown_fails(self):
        events = recording()
        events[-1]["routerAlive"] = True
        self.assertFalse(self.verdict(events)["passed"])

    def test_child_exit_status_must_be_known_and_zero(self):
        for code in (None, 1, -9, True):
            with self.subTest(code=code):
                self.assertFalse(evaluate(recording(), process_exit_code=code)["passed"])

    def test_invalid_or_changed_artifact_hash_fails(self):
        for digest in (None, "", "x" * 64, "a" * 63, "a" * 65):
            with self.subTest(digest=digest):
                events = recording()
                events[0]["coreArtifact"]["sha256"] = digest
                self.assertFalse(self.verdict(events)["passed"])
        events = recording()
        events[0]["coreArtifact"]["sha256"] = "c" * 64
        self.assertFalse(self.verdict(events)["passed"])

    def test_missing_or_reordered_events_fail(self):
        events = recording()
        variants = [[], events[1:], events[:1], events[:-1], events + events,
                    [events[0], events[-1], events[1]],
                    events + [{"event": "unexpected"}]]
        for value in variants:
            with self.subTest(events=value):
                self.assertFalse(self.verdict(value)["passed"])

    def test_invalid_sample_fields_fail(self):
        for field, value in (("elapsedSeconds", True), ("elapsedSeconds", -1),
                             ("routerAlive", 1), ("inboundExploratoryLengths", ["2"]),
                             ("outboundExploratoryLengths", None), ("stats", {})):
            with self.subTest(field=field, value=value):
                events = recording()
                events[2][field] = value
                self.assertFalse(self.verdict(events)["passed"])

    def test_backwards_time_and_counter_fail(self):
        events = recording()
        events[1]["elapsedSeconds"] = 250
        self.assertFalse(self.verdict(events)["passed"])
        events = recording()
        events[1]["stats"]["tunnel.testSuccessTime"]["events"] = 4
        self.assertFalse(self.verdict(events)["passed"])

    def test_zero_verification_threshold_is_rejected(self):
        for key in ("min_roundtrips", "min_two_hop_samples"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.verdict(recording(), **{key: 0})

    def test_missing_corrupt_or_non_json_metrics_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "metrics.jsonl"
            self.assertFalse(verify_file(path, process_exit_code=0)["passed"])
            for text in ('{"event":', 'not-json\n', '[]\n',
                         '{"event":"start","event":"sample"}\n',
                         '{"event":"sample","elapsedSeconds":NaN}\n'):
                with self.subTest(text=text):
                    path.write_text(text)
                    self.assertFalse(verify_file(path, process_exit_code=0)["passed"])

    def test_cli_verdict_exit_code_tracks_success_and_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "metrics.jsonl"
            for events, passed in ((recording(), True), (recording()[:-1], False)):
                with self.subTest(passed=passed):
                    path.write_text("".join(json.dumps(e) + "\n" for e in events))
                    run = subprocess.run([sys.executable, str(Path(__file__).with_name("verify_live.py")),
                                          str(path), "--process-exit-code", "0"],
                                         capture_output=True, text=True)
                    self.assertEqual(0 if passed else 1, run.returncode, run.stderr)
                    self.assertIs(json.loads(run.stdout)["passed"], passed)

    def test_cli_launch_manifest_must_match_loaded_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "metrics.jsonl"
            manifest = Path(directory) / "launch.json"
            path.write_text("".join(json.dumps(e) + "\n" for e in recording()))
            variants = [(dict(durationSeconds=240, jarSha256=HASHES), True),
                        (dict(durationSeconds=240, jarSha256=None), False),
                        (dict(durationSeconds=240, jarSha256={}), False),
                        (dict(durationSeconds=True, jarSha256=HASHES), False),
                        (dict(durationSeconds=240, jarSha256={**HASHES, "i2p.jar": "c" * 64}), False),
                        ([], False)]
            for value, passed in variants:
                with self.subTest(manifest=value):
                    manifest.write_text(json.dumps(value))
                    run = subprocess.run([sys.executable, str(Path(__file__).with_name("verify_live.py")),
                                          str(path), "--process-exit-code", "0",
                                          "--launch-manifest", str(manifest)], capture_output=True, text=True)
                    self.assertEqual(0 if passed else 1, run.returncode, run.stderr)
                    self.assertIs(json.loads(run.stdout)["passed"], passed)

    def test_cli_cannot_weaken_recorded_launch_thresholds(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "metrics.jsonl"
            manifest = Path(directory) / "launch.json"
            path.write_text("".join(json.dumps(e) + "\n" for e in recording()))
            for key in ("minRoundtrips", "minTwoHopSamples"):
                for override in ([], ["--min-roundtrips", "1", "--min-two-hop-samples", "1"]):
                    with self.subTest(key=key, override=override):
                        manifest.write_text(json.dumps(dict(durationSeconds=240, jarSha256=HASHES,
                                                            **{key: 5})))
                        run = subprocess.run([sys.executable, str(Path(__file__).with_name("verify_live.py")),
                                              str(path), "--process-exit-code", "0",
                                              "--launch-manifest", str(manifest), *override],
                                             capture_output=True, text=True)
                        self.assertEqual(1, run.returncode, run.stderr)
                        result = json.loads(run.stdout)
                        self.assertFalse(result["passed"])
                        name = "minRoundtrips" if key == "minRoundtrips" else "minTwoHopSamples"
                        self.assertEqual(5, result["requirements"][name])

    def test_launcher_connectivity_mode_rejects_an_isolated_run(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            events = recording()
            events[2]["inboundExploratoryLengths"] = []
            events[2]["stats"]["tunnel.testSuccessTime"]["events"] = 0
            (runtime / "metrics.jsonl").write_text("".join(json.dumps(e) + "\n" for e in events))
            result, code = finish_run(runtime, self.metadata(), 0)
            self.assertEqual(1, code)
            self.assertFalse(result["passed"])

    def test_launcher_observation_cannot_claim_verified_connectivity(self):
        with tempfile.TemporaryDirectory() as directory:
            result, code = finish_run(Path(directory), self.metadata("observe"), 0)
            self.assertEqual(0, code)
            self.assertIsNone(result["passed"])
            self.assertFalse(result["connectivityVerified"])
            self.assertEqual(1, finish_run(Path(directory), self.metadata("observe"), -9)[1])

    def test_launcher_timeout_cannot_turn_into_success_on_clean_child_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            (runtime / "metrics.jsonl").write_text("".join(json.dumps(e) + "\n" for e in recording()))
            result, code = finish_run(runtime, self.metadata(), 0, launcher_failure="timeout")
            self.assertEqual(1, code)
            self.assertIn("launcher-timeout", result["failures"])

    @staticmethod
    def metadata(mode="connectivity"):
        return dict(verification=mode, minRoundtrips=1, minTwoHopSamples=1,
                    durationSeconds=240, jarSha256=HASHES)


if __name__ == "__main__":
    unittest.main()
