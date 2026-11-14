"""Regression tests for result collection and failure propagation."""

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("regression_runner", Path(__file__).with_name("run.py"))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class RunnerTest(unittest.TestCase):
    def test_failed_command_keeps_nonzero_status_and_log(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            result = runner.execute([sys.executable, "-c", "print('failure detail'); raise SystemExit(7)"],
                                    root, root / "command.log")
            self.assertEqual(7, result["exitCode"])
            self.assertIn("failure detail", (root / "command.log").read_text())
            self.assertEqual(runner.digest(root / "command.log"), result["logSha256"])

    def test_collects_reports_from_all_modules_and_preserves_failures(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for module, values in (("core", ("2", "1", "0", "0")), ("router", ("3", "0", "1", "1"))):
                report = root / module / "build/test-results/test/TEST-example.xml"
                report.parent.mkdir(parents=True)
                report.write_text('<testsuite tests="%s" failures="%s" errors="%s" skipped="%s"/>' % values)
            result = runner.collect_junit(root, root / "copied")
            self.assertEqual((5, 1, 1, 1), tuple(result[k] for k in ("tests", "failures", "errors", "skipped")))
            self.assertEqual(2, len(result["reports"]))
            self.assertTrue((root / "copied/core/build/test-results/test/TEST-example.xml").is_file())

    def test_copied_reports_are_not_counted_on_later_runs(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            report = root / "core/build/test-results/test/TEST-example.xml"
            report.parent.mkdir(parents=True)
            report.write_text('<testsuite tests="2" failures="0" errors="0" skipped="0"/>')
            target = root / "build/validation/junit"
            self.assertEqual(2, runner.collect_junit(root, target)["tests"])
            self.assertEqual(2, runner.collect_junit(root, target)["tests"])

    def test_source_fingerprint_ignores_build_outputs(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "source.java").write_text("before")
            before = runner.source_hashes(root)
            (root / "build").mkdir()
            (root / "build/generated.java").write_text("generated")
            self.assertEqual(before, runner.source_hashes(root))
            (root / "source.java").write_text("after")
            self.assertNotEqual(before, runner.source_hashes(root))

    def test_companion_suites_are_discovered_when_present(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for module in ("live", "recovery"):
                directory = root / "tests" / module
                directory.mkdir(parents=True)
                (directory / "test_regression.py").write_text("# suite")
            suites = runner.python_suites(root)
            self.assertEqual({"validation", "live", "recovery"}, {p.name for p, pattern in suites})

    def test_invalid_output_rejected_before_running_gradle(self):
        with self.assertRaises(SystemExit) as raised:
            runner.main(["--output", str(runner.ROOT)])
        self.assertEqual(2, raised.exception.code)


if __name__ == "__main__":
    unittest.main()
