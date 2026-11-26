import io
import json
from pathlib import Path
import tempfile
import unittest

import audit
import pattern_scan
import workflow_definitions


class AuditToolTests(unittest.TestCase):
    def test_security_triage_is_not_a_proven_vulnerability(self):
        findings = pattern_scan.scan_text("Example.java", "new ObjectInputStream(input)")
        self.assertEqual(findings[0]["status"], "needs-review")

    def test_reports_omit_matched_contents(self):
        findings = pattern_scan.scan_text("Example.java", 'new ProcessBuilder("SECRET_VALUE")')
        self.assertTrue(findings)
        self.assertNotIn("SECRET_VALUE", json.dumps(findings))

    def test_empty_trust_check_detected(self):
        findings = pattern_scan.scan_text("Example.java", "void checkServerTrusted(Cert[] c, String a) { }")
        self.assertEqual(findings[0]["rule"], "empty-server-trust-check")

    def test_bug_pattern_and_fix(self):
        self.assertTrue(audit.scan_text("core/EepGet.java", "append(_postData.length())", "bugs"))
        self.assertFalse(audit.scan_text("core/EepGet.java", "append(DataHelper.getUTF8(_postData).length)", "bugs"))

    def test_http_log_pattern_and_fix(self):
        self.assertTrue(audit.scan_text("core/EepGet.java", '_log.debug("Request:\\n" + buf.toString());', "privacy"))
        self.assertFalse(audit.scan_text("core/EepGet.java", '_log.debug("Request built: " + buf.length());', "privacy"))

    def test_exact_and_unresolved_versions(self):
        values = audit.coordinates("test 'org.example:thing:1.2.3'\ntest 'org.example:dynamic:4.+'")
        self.assertEqual([item["exact"] for item in values], [True, False])

    def test_advisory_response_requires_complete_results(self):
        with self.assertRaises(ValueError):
            audit.osv_query([{"name": "org.example:thing", "version": "1.2.3"}],
                            lambda request, timeout: io.BytesIO(b'{"results": []}'))

    def test_advisory_ids_preserved(self):
        def opener(request, timeout):
            self.assertEqual(request.full_url, "https://api.osv.dev/v1/querybatch")
            self.assertEqual(timeout, 30)
            return io.BytesIO(b'{"results": [{"vulns": [{"id": "TEST-ADVISORY"}]}]}')
        values = audit.osv_query([{"name": "org.example:thing", "version": "1.2.3"}], opener)
        self.assertEqual(values[0]["advisories"], ["TEST-ADVISORY"])

    def test_malformed_advisory_entry_is_not_reported_clean(self):
        with self.assertRaises(ValueError):
            audit.osv_query([{"name": "org.example:thing", "version": "1.2.3"}],
                            lambda request, timeout: io.BytesIO(b'{"results": [{"vulns": [42]}]}'))

    def test_empty_complete_advisory_result(self):
        self.assertEqual(audit.osv_query([{"name": "org.example:thing", "version": "1.2.3"}],
                         lambda request, timeout: io.BytesIO(b'{"results": [{}]}')), [])

    def test_offline_inventory_does_not_claim_advisory_check(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "build.gradle").write_text("test 'org.example:thing:1.2.3'")
            result = audit.dependencies(root)
            self.assertEqual(result["advisory_status"], "not-queried-offline")

    def test_dependency_inventory_excludes_test_output_and_nested_checkouts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "build.gradle").write_text("test 'org.example:thing:1.2.3'")
            for directory in ("target/checkout", "tests/results", "build"):
                path = root / directory
                path.mkdir(parents=True)
                (path / "build.gradle").write_text("test 'org.example:fixture:9.9.9'")
                (path / "fixture.jar").write_bytes(b"fixture")
            result = audit.dependencies(root)
            self.assertEqual([item["name"] for item in result["exact_maven_coordinates"]], ["org.example:thing"])
            self.assertEqual(result["bundled_jar_hashes"], [])

    def test_all_ten_generated_workers_obey_policy(self):
        files = workflow_definitions.render()
        self.assertEqual(len(files), 10)
        for name, source in files.items():
            self.assertEqual(audit.workflow_findings(name, source), [], name)
            self.assertNotIn("pull_request_target", source)
            self.assertNotIn("secrets.", source)
            self.assertIn("AUDIT_JAVA_VERSION:", source)
            self.assertIn('case "$AUDIT_JAVA_VERSION"', source)

    def test_workflow_risks_detected(self):
        source = "on:\n  pull_request_target:\npermissions:\n  contents: write\nsteps:\n  - uses: actions/checkout@main\n"
        rules = {item["rule"] for item in audit.workflow_findings("worker.yml", source)}
        self.assertTrue({"privileged-trigger", "write-permission", "unpinned-action"}.issubset(rules))

    def test_source_check_has_bounded_output(self):
        source = "new ObjectInputStream(input);\n" * 4
        results = pattern_scan.scan_text("Example.java", source)
        self.assertEqual(len(results), 4)
        self.assertTrue(all("rule" in result and "line" in result for result in results))

    def test_security_worker_runs_java_and_native_rule_pack(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            java = root / "core/java/src/Example.java"
            native = root / "core/c/example.c"
            java.parent.mkdir(parents=True)
            native.parent.mkdir(parents=True)
            java.write_text('class Example { void run() { new ProcessBuilder("SECRET_VALUE"); } }')
            native.write_text('void copy(char *dst, char *src) { strcpy(dst, src); }')
            result = audit.scan(root, "security")
            self.assertTrue(result["passed"])
            self.assertEqual(result["status"], "triage-required")
            self.assertEqual(result["rule_count"], 32)
            self.assertEqual(result["scanned_files"], 2)
            rules = {item["rule"] for item in result["findings"]}
            self.assertTrue({"process-execution", "native-unbounded-string"}.issubset(rules))
            self.assertNotIn("SECRET_VALUE", json.dumps(result))

    def test_security_worker_fails_when_no_source_is_scanned(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = audit.scan(Path(temporary), "security")
            self.assertFalse(result["passed"])
            self.assertEqual(result["status"], "incomplete")


if __name__ == "__main__":
    unittest.main()
