"""Check baseline isolation and failure propagation in the transport lab runner."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

SPEC = importlib.util.spec_from_file_location("recovery_runner", Path(__file__).with_name("run_recovery.py"))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class RecoveryRunnerTest(unittest.TestCase):
    def record(self, **changes):
        value = {name: 0 for name in runner.MEASUREMENTS}
        value.update(scenario="control", seed=1, passed=True, shutdownConfirmed=True,
                     deadlineMs=45000, forwardedPackets=10, failure="", tcpFallback=False,
                     peerEndpointMigrated=False, connectionIdsRetained=True, exitCode=0)
        value.update(changes)
        return "RECOVERY_RESULT " + json.dumps(value)

    def measurement(self, **changes):
        return json.loads(self.record(**changes).removeprefix("RECOVERY_RESULT "))

    def test_exit_failure_cannot_be_hidden_by_passing_record(self):
        self.assertFalse(runner.parse_result([self.record()], "control", 1, 7)["passed"])

    def test_incomplete_or_wrong_run_cannot_pass(self):
        for changes in ({"scenario": "reconnect"}, {"seed": 2}, {"seed": True},
                        {"shutdownConfirmed": False}, {"passed": "true"}):
            with self.subTest(changes=changes):
                self.assertFalse(runner.parse_result([self.record(**changes)], "control", 1, 0)["passed"])
        for lines in ([], [self.record(), self.record()]):
            self.assertFalse(runner.parse_result(lines, "control", 1, 0)["passed"])
        self.assertTrue(runner.parse_result([self.record()], "control", 1, 0)["passed"])

    def test_baseline_uses_committed_sources_and_worktree_includes_new_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            repo = root / "repo"
            repo.mkdir()
            subprocess.run(["git", "init", "--quiet", str(repo)], check=True)
            for name in ("core", "router"):
                path = repo / name / "java/src/Example.java"
                path.parent.mkdir(parents=True)
                path.write_text("committed")
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "-c", "user.name=Test",
                            "-c", "user.email=test@invalid", "commit", "--quiet", "-m", "fixture"], check=True)
            (repo / "core/java/src/Example.java").write_text("changed")
            (repo / "core/java/src/New.java").write_text("new")
            baseline = root / "baseline"
            candidate = root / "candidate"
            self.assertEqual(runner.snapshot_sources(repo, "HEAD", baseline),
                             runner.snapshot_sources(repo, "WORKTREE", candidate))
            self.assertEqual("committed", (baseline / "core/java/src/Example.java").read_text())
            self.assertFalse((baseline / "core/java/src/New.java").exists())
            self.assertEqual("changed", (candidate / "core/java/src/Example.java").read_text())
            self.assertEqual("new", (candidate / "core/java/src/New.java").read_text())

    def test_process_failure_keeps_exit_status_and_diagnostics(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            status = runner.run_process([sys.executable, "-c", "print('diagnostic'); raise SystemExit(9)"], root)
            self.assertEqual(9, status)
            self.assertIn("diagnostic", (root / "process.log").read_text())

    def test_process_deadline_is_enforced(self):
        with tempfile.TemporaryDirectory() as folder:
            started = time.monotonic()
            with self.assertRaises(subprocess.TimeoutExpired):
                runner.run_process([sys.executable, "-c", "import time; time.sleep(60)"], Path(folder), timeout=0.1)
            self.assertLess(time.monotonic() - started, 10)

    def test_baseline_failure_stays_visible_without_becoming_a_new_regression(self):
        failed = self.measurement(passed=False, failure="recovery-deadline", exitCode=1)
        result = runner.comparison_verdict([failed], [dict(failed)])
        self.assertTrue(result["measurementsComplete"])
        self.assertTrue(result["noRegressions"])
        self.assertFalse(result["candidatePassed"])

    def test_new_protocol_failure_is_a_regression(self):
        result = runner.comparison_verdict([self.measurement()], [self.measurement(
            passed=False, failure="recovery-deadline", exitCode=1)])
        self.assertFalse(result["noRegressions"])
        self.assertEqual(["recovery-deadline"], result["regressions"][0]["newFailures"])

    def test_additional_failure_is_a_regression_even_when_baseline_already_fails(self):
        result = runner.comparison_verdict([self.measurement(
            passed=False, failure="recovery-deadline", exitCode=1)], [self.measurement(
            passed=False, failure="recovery-deadline;residual-state-bound", exitCode=1)])
        self.assertFalse(result["noRegressions"])
        self.assertEqual(["residual-state-bound"], result["regressions"][0]["newFailures"])

    def test_missing_crashed_or_malformed_runs_cannot_pass_regression_gate(self):
        good = self.measurement()
        for old, new in (([], []), ([good], []), ([good], [good, good]),
                         ([self.measurement(exitCode=124)], [self.measurement(exitCode=124)]),
                         ([self.measurement(recoveryMs=True)], [good]),
                         ([self.measurement(deadlineMs=0)], [good])):
            with self.subTest(old=old, new=new):
                result = runner.comparison_verdict(old, new)
                self.assertFalse(result["measurementsComplete"])
                self.assertFalse(result["noRegressions"])

    def test_unobserved_faults_cannot_be_treated_as_known_baseline_failures(self):
        failed = self.measurement(passed=False, failure="packet-faults-not-observed", exitCode=1)
        result = runner.comparison_verdict([failed], [dict(failed)])
        self.assertFalse(result["measurementsComplete"])
        self.assertFalse(result["noRegressions"])

    def test_successful_candidate_improvement_passes_both_gates(self):
        result = runner.comparison_verdict([self.measurement(
            passed=False, failure="recovery-deadline", exitCode=1)], [self.measurement()])
        self.assertTrue(result["measurementsComplete"])
        self.assertTrue(result["candidatePassed"])
        self.assertTrue(result["noRegressions"])


if __name__ == "__main__":
    unittest.main()
