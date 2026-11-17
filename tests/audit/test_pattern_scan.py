import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import pattern_scan


class PatternScanTests(unittest.TestCase):
    def rule_ids(self, source, path="Example.java", categories=None):
        return {item["rule"] for item in pattern_scan.scan_text(path, source, categories)}

    def test_every_rule_detects_its_target(self):
        examples = {
            "empty-catch": ("Example.java", "catch (IOException e) { /* ignored */ }"),
            "broad-catch": ("Example.java", "catch (final java.lang.Throwable e) { recover(e); }"),
            "catch-sentinel-return": ("Example.java", "catch (IOException e) { return false; }"),
            "exception-message-without-cause": ("Example.java", "throw new IOException(e.getMessage());"),
            "finally-overrides-exit": ("Example.java", "finally { return result; }"),
            "direct-stack-trace": ("Example.java", "e.printStackTrace();"),
            "dynamic-array-size": ("Example.java", "new byte[length + header];"),
            "byte-buffer-allocation": ("Example.java", "ByteBuffer.allocateDirect(size);"),
            "whole-input-read": ("Example.java", "input.readAllBytes();"),
            "native-allocation": ("example.c", "void *p = malloc(n * size);"),
            "realloc-overwrites-pointer": ("example.c", "p = (char *) realloc(p, n);"),
            "native-unbounded-string": ("example.c", 'sprintf(out, "%s", input);'),
            "native-buffer-copy": ("example.c", "memcpy(out, input, length);"),
            "discarded-read-or-skip": ("Example.java", "  in.read(\n    buf, 0, buf.length\n  );"),
            "line-read": ("Example.java", "String line = reader.readLine();"),
            "numeric-input-parse": ("Example.java", "Integer.parseInt(input);"),
            "slice-end-addition": ("Example.java", "int end = offset + length;"),
            "read-byte-before-eof-check": ("Example.java", "byte b = (byte) in.read();"),
            "default-charset-encoding": ("Example.java", "value.getBytes();"),
            "dynamic-regex": ("Example.java", "Pattern.compile(userPattern, flags);"),
            "native-lossy-number-parse": ("example.c", "int n = atoi(input);"),
            "native-variable-format": ("example.c", "fprintf(stderr, userFormat);"),
            "resource-construction": ("Example.java", "new FileInputStream(path);"),
            "discarded-file-result": ("Example.java", "  file.renameTo(destination);"),
            "discarded-native-io-result": ("example.c", "  fwrite(buf, 1, n, out);"),
            "executor-capacity": ("Example.java", "Executors.newFixedThreadPool(4);"),
            "process-execution": ("Example.java", "new ProcessBuilder(args);"),
            "object-deserialization": ("Example.java", "new ObjectInputStream(input);"),
            "empty-server-trust-check": ("Example.java", "void checkServerTrusted(Cert[] chain, String auth) throws CertificateException { }"),
            "unconditional-hostname-verifier": ("Example.java", "setHostnameVerifier((name, session) -> { return true; });"),
            "sql-expression-concatenation": ("Example.java", 'db.executeQuery("SELECT * FROM t WHERE x=" + input);'),
            "xml-parser-configuration": ("Example.java", "DocumentBuilderFactory.newInstance();"),
        }
        self.assertEqual(set(examples), set(pattern_scan.COMPILED))
        self.assertEqual(len(examples), len(pattern_scan.RULES))
        for rule, (path, source) in examples.items():
            with self.subTest(rule=rule):
                self.assertIn(rule, self.rule_ids(source, path))

    def test_comments_literals_and_text_blocks_do_not_create_findings(self):
        source = '''// catch (Exception e) {} new byte[length]
/* in.read(buf); new ObjectInputStream(in); */
String a = "new FileInputStream(path); // not code";
String b = "escaped \\\" catch (Throwable e) {}";
String block = """
catch (Exception e) {} new byte[length]
""";
char quote = '\\"';
'''
        self.assertEqual(pattern_scan.scan_text("Example.java", source), [])
        self.assertEqual(pattern_scan.scan_text("example.c", 'char *s = "strcpy(a,b);"; /* malloc(n) */'), [])

    def test_mask_preserves_offsets_and_lines(self):
        source = '/* first\r\nsecond */\r\nString u = "https://example.invalid";\r\n  in.read(buf);'
        masked = pattern_scan.mask_non_code(source)
        self.assertEqual(len(source), len(masked))
        self.assertEqual([i for i, char in enumerate(source) if char in "\r\n"],
                         [i for i, char in enumerate(masked) if char in "\r\n"])
        findings = pattern_scan.scan_text("Example.java", source)
        self.assertEqual([(item["rule"], item["line"]) for item in findings],
                         [("discarded-read-or-skip", 4)])
        self.assertEqual(pattern_scan.scan_text("Example.java", "  e.printStackTrace();")[0]["column"], 4)

    def test_common_checked_forms_do_not_match_specific_bad_shapes(self):
        examples = {
            "empty-catch": "catch (IOException e) { report(e); }",
            "catch-sentinel-return": "catch (IOException e) { throw e; }",
            "exception-message-without-cause": "throw new IOException(e.getMessage(), e);",
            "finally-overrides-exit": "finally { in.close(); }",
            "dynamic-array-size": "new byte[4096];",
            "discarded-read-or-skip": "int n = in.read(buf);\nreturn in.skip(length);\nin.readFully(buf);",
            "read-byte-before-eof-check": "int b = in.read(); if (b < 0) throw eof;",
            "default-charset-encoding": "value.getBytes(StandardCharsets.UTF_8);",
            "dynamic-regex": 'Pattern.compile("fixed.*pattern");',
            "discarded-file-result": "boolean deleted = file.delete();",
            "unconditional-hostname-verifier": "setHostnameVerifier((n, s) -> verify(n, s));",
            "sql-expression-concatenation": 'db.executeQuery("SELECT a + b FROM t");',
        }
        for rule, source in examples.items():
            with self.subTest(rule=rule):
                self.assertNotIn(rule, self.rule_ids(source))
        self.assertNotIn("realloc-overwrites-pointer", self.rule_ids("tmp = realloc(p, n);", "x.c"))
        self.assertNotIn("default-charset-encoding", self.rule_ids('value.getBytes("UTF-8");'))
        self.assertNotIn("native-variable-format", self.rule_ids('printf("%s", input);', "x.c"))
        self.assertNotIn("native-variable-format", self.rule_ids('fprintf(stderr, "prefix " NAME " suffix");', "x.c"))
        self.assertNotIn("discarded-native-io-result", self.rule_ids("size_t n = fread(p, 1, len, f);", "x.c"))

    def test_category_and_language_filters(self):
        source = "catch (Exception e) {} new byte[size]; strcpy(out, in);"
        self.assertEqual(self.rule_ids(source, categories=["memory"]), {"dynamic-array-size"})
        self.assertEqual(self.rule_ids(source, "x.c"), {"native-unbounded-string"})
        self.assertEqual(self.rule_ids(source, "x.py"), set())

    def test_scan_scope_report_and_redaction(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = {
                "core/java/src/net/Example.java": 'new ProcessBuilder("SYNTHETIC_PRIVATE_MARKER");',
                "core/java/src/net/test/Skip.java": "catch (Exception e) {}",
                "apps/example/build/Generated.java": "catch (Exception e) {}",
                "apps/example/java/demo/Example.java": "catch (Exception e) {}",
                "apps/example/java/src/Example.java": "new byte[size];",
                "core/c/native.c": "strcpy(out, input);",
            }
            for name, source in sources.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")
            (root / "core/c/alias.c").symlink_to(root / "core/c/native.c")
            (root / "core/c/alias-dir").symlink_to(root / "core/c", target_is_directory=True)
            report = pattern_scan.scan(root)
            self.assertEqual(report["scanned_files"], 3)
            self.assertEqual(report["finding_count"], 3)
            self.assertEqual(report["status"], "triage-required")
            self.assertNotIn("SYNTHETIC_PRIVATE_MARKER", json.dumps(report))
            self.assertTrue(all(item["status"] == "needs-review" for item in report["findings"]))

    def test_empty_and_unreadable_scans_are_incomplete(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertEqual(pattern_scan.scan(root)["status"], "incomplete")
            path = root / "core/c/example.c"
            path.parent.mkdir(parents=True)
            path.write_bytes(b"\xff")
            report = pattern_scan.scan(root)
            self.assertEqual(report["status"], "incomplete")
            self.assertEqual(report["errors"][0]["error_type"], "UnicodeDecodeError")
            with patch.object(Path, "read_text", side_effect=PermissionError("PRIVATE_ERROR_BODY")):
                report = pattern_scan.scan(root)
            self.assertEqual(report["status"], "incomplete")
            self.assertNotIn("PRIVATE_ERROR_BODY", json.dumps(report))

    def test_no_matches_is_not_a_security_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "core/java/src/net/Example.java"
            path.parent.mkdir(parents=True)
            path.write_text("class Example {}", encoding="utf-8")
            report = pattern_scan.scan(root)
            self.assertEqual(report["status"], "no-rule-matches")
            self.assertEqual(report["scanned_files"], 1)
            self.assertNotIn("passed", report)


if __name__ == "__main__":
    unittest.main()
