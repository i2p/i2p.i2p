#!/usr/bin/env python3
"""Regex triage for Java/C source; matches need control-flow and caller review."""

import argparse
from bisect import bisect_right
from collections import Counter
from dataclasses import asdict, dataclass
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOTS = (
    "core/java/src", "router/java/src", "apps", "installer/java/src",
    "installer/tools/java/src", "core/c", "installer/c",
)
EXCLUDED = {
    ".git", ".gradle", "build", "target", "pkg-temp", "classes", "dist",
    "node_modules", "test", "tests", "demo", "demos", "bench", "benchmark",
}
JAVA = (".java",)
NATIVE = (".c", ".h")


@dataclass(frozen=True)
class Rule:
    id: str
    category: str
    extensions: tuple
    pattern: str
    review: str


RULES = (
    Rule("empty-catch", "exceptions", JAVA,
         r"\bcatch\s*\([^(){}]*\)\s*\{\s*\}",
         "Check whether ignoring failure is intentional; close-on-cleanup catches often are."),
    Rule("broad-catch", "exceptions", JAVA,
         r"\bcatch\s*\(\s*(?:final\s+)?(?:java\.lang\.)?(?:Throwable|Error|Exception)\b",
         "Check recovery and propagation; top-level isolation boundaries may need broad catches."),
    Rule("catch-sentinel-return", "exceptions", JAVA,
         r"\bcatch\s*\([^(){}]*\)\s*\{\s*return\s+(?:null|false|-1|0)\s*;\s*\}",
         "Check whether the caller distinguishes failure from an ordinary empty result."),
    Rule("exception-message-without-cause", "exceptions", JAVA,
         r"\bthrow\s+new\s+[\w.$]*Exception\s*\(\s*\w+\s*\.\s*getMessage\s*\(\s*\)\s*\)\s*;",
         "Consider preserving the original cause, subject to diagnostic privacy requirements."),
    Rule("finally-overrides-exit", "exceptions", JAVA,
         r"\bfinally\s*\{\s*(?:return|throw)\b",
         "An immediate return/throw can replace the original return value or exception."),
    Rule("direct-stack-trace", "exceptions", JAVA,
         r"\.\s*printStackTrace\s*\(",
         "Check diagnostic destination, sensitive exception contents, and log volume."),
    Rule("dynamic-array-size", "memory", JAVA,
         r"\bnew\s+(?:byte|char|short|int|long|float|double|boolean)\s*\[[^\]\n]*[A-Za-z_$][^\]\n]*\]",
         "Trace size to its source; check negative lengths, upper bounds and arithmetic overflow. Named constants also match."),
    Rule("byte-buffer-allocation", "memory", JAVA,
         r"\bByteBuffer\s*\.\s*allocate(?:Direct)?\s*\(",
         "Check allocation bounds and lifetime, especially repeated direct-buffer allocation."),
    Rule("whole-input-read", "memory", JAVA,
         r"\.\s*(?:readAllBytes|readAllLines)\s*\(",
         "Check whether the input size is bounded before loading it entirely into memory."),
    Rule("native-allocation", "memory", NATIVE,
         r"\b(?:malloc|calloc|realloc|MemAlloc)\s*\(",
         "Check size overflow, allocation failure, zero-size behavior, ownership and release paths."),
    Rule("realloc-overwrites-pointer", "memory", NATIVE,
         r"\b(?P<ptr>[A-Za-z_]\w*)\s*=\s*(?:\(\s*[A-Za-z_][\w \t*]*\)\s*)?realloc\s*\(\s*(?P=ptr)\s*,",
         "A failed realloc can lose the original allocation; check for a temporary result and size-zero handling."),
    Rule("native-unbounded-string", "memory", NATIVE,
         r"\b(?:gets|strcpy|strcat|sprintf|vsprintf)\s*\(",
         "Verify destination capacity against every source and formatted expansion."),
    Rule("native-buffer-copy", "memory", NATIVE,
         r"\b(?:memcpy|memmove|strncpy|strncat|snprintf|vsnprintf)\s*\(",
         "Verify both buffer bounds, overlap rules, truncation and NUL termination as applicable."),
    Rule("discarded-read-or-skip", "input", JAVA,
         r"(?m)^[ \t]*[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*\s*\.\s*(?:read|skip)\s*\([^;{}]*\)\s*;",
         "Check short reads, EOF and partial skips; use a counted loop or a suitable exact-read helper."),
    Rule("line-read", "input", JAVA,
         r"\.\s*readLine\s*\(",
         "Check line-length limits and deadlines for network/untrusted input; local bounded files may be safe."),
    Rule("numeric-input-parse", "input", JAVA,
         r"\b(?:Integer|Long|Short|Byte)\s*\.\s*(?:parseInt|parseLong|parseShort|parseByte|parseUnsignedInt|parseUnsignedLong)\s*\(",
         "Check exception handling and protocol/application range limits after parsing."),
    Rule("slice-end-addition", "input", JAVA,
         r"\b(?:off(?:set)?|start|pos(?:ition)?)\s*\+\s*(?:len(?:gth)?|size)\b",
         "Check negatives and overflow before addition; a prior length <= buffer.length - offset check may make it safe."),
    Rule("read-byte-before-eof-check", "input", JAVA,
         r"\(\s*(?:byte|char)\s*\)\s*[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*\s*\.\s*read\s*\(\s*\)",
         "Check EOF as an int before narrowing; the cast can hide -1 or confuse it with a valid byte."),
    Rule("default-charset-encoding", "input", JAVA,
         r"\.\s*getBytes\s*\(\s*\)",
         "Check that the platform default charset agrees with the protocol or file format."),
    Rule("dynamic-regex", "input", JAVA,
         r"\bPattern\s*\.\s*compile\s*\(\s*[A-Za-z_$][\w$]*(?:\s*[,)]|\s*\+)",
         "Trace the pattern and input; check regex complexity and whether literal input needs Pattern.quote. Constants also match."),
    Rule("native-lossy-number-parse", "input", NATIVE,
         r"\b(?:atoi|atol|atoll)\s*\(",
         "Check conversion errors, overflow and trailing text; consider strtol/strtoll with complete validation."),
    Rule("native-variable-format", "input", NATIVE,
         r"\bprintf\s*\(\s*[A-Za-z_]\w*\s*[,)]|\bfprintf\s*\(\s*[A-Za-z_]\w*\s*,\s*[A-Za-z_]\w*\s*[,)]",
         "Trace the format argument; externally controlled format strings require a fixed format."),
    Rule("resource-construction", "resources", JAVA,
         r"\bnew\s+(?:FileInputStream|FileOutputStream|RandomAccessFile|Socket|ServerSocket|ZipFile)\s*\(",
         "Inspect ownership, try-with-resources/finally and exceptional paths; creation alone does not prove a leak."),
    Rule("discarded-file-result", "resources", JAVA,
         r"(?m)^[ \t]*[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*\s*\.\s*(?:delete|renameTo|mkdir|mkdirs|createNewFile|setReadOnly)\s*\([^;{}]*\)\s*;",
         "Check whether a failed filesystem operation must change the caller's behavior."),
    Rule("discarded-native-io-result", "resources", NATIVE,
         r"(?m)^[ \t]*(?:fread|fwrite|fclose|fflush|fseek|read|write|close|send|recv|connect)\s*\(",
         "Check errors and short I/O; cleanup-only failures may be intentionally ignored."),
    Rule("executor-capacity", "concurrency", JAVA,
         r"\bExecutors\s*\.\s*(?:newCachedThreadPool|newFixedThreadPool|newSingleThreadExecutor)\s*\(",
         "Check admission limits: cached pools can grow threads; fixed/single pools can grow queued work."),
    Rule("process-execution", "security", JAVA,
         r"\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(|\bnew\s+ProcessBuilder\s*\(",
         "Trace command, arguments, environment and working directory; verify intended caller control."),
    Rule("object-deserialization", "security", JAVA,
         r"\bnew\s+ObjectInputStream\s*\(",
         "Check input trust, serialization filters, accepted classes and size/depth limits."),
    Rule("empty-server-trust-check", "security", JAVA,
         r"\bvoid\s+checkServerTrusted\s*\([^(){}]*\)\s*(?:throws[^{}]+)?\{\s*\}",
         "Check whether the trust manager accepts certificates without validation."),
    Rule("unconditional-hostname-verifier", "security", JAVA,
         r"\bsetHostnameVerifier\s*\(\s*\([^(){}]*\)\s*->\s*(?:true\b|\{\s*return\s+true\s*;\s*\})",
         "Check whether hostname validation is disabled. This rule covers simple inline lambdas only."),
    Rule("sql-expression-concatenation", "security", JAVA,
         r"\.\s*(?:execute|executeQuery|executeUpdate|prepareStatement)\s*\([^;{}\n]{0,512}\+",
         "Trace concatenated SQL values; use parameters for externally supplied values. Constant concatenation also matches."),
    Rule("xml-parser-configuration", "security", JAVA,
         r"\b(?:DocumentBuilderFactory|SAXParserFactory|XMLInputFactory|TransformerFactory)\s*\.\s*new(?:Instance|Factory)\s*\(",
         "Review external entity/DTD/schema/stylesheet access settings before parsing untrusted XML."),
)
CATEGORIES = tuple(sorted({rule.category for rule in RULES}))
COMPILED = {rule.id: re.compile(rule.pattern) for rule in RULES}

# Preserve positions while excluding ordinary comments, strings, chars and Java
# text blocks. This is a lexical convenience, not a Java/C parser/preprocessor.
NON_CODE = re.compile(
    r'//[^\r\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\[\s\S]|[^"\\])*"|\'(?:\\[\s\S]|[^\'\\])*\''
)


def mask_non_code(text):
    def mask(match):
        value = match.group()
        masked = re.sub(r"[^\r\n]", " ", value)
        # Keep literal boundaries: otherwise a literal followed by a C macro
        # can look like a variable format argument after the literal vanishes.
        if value[0] in "\"'":
            return value[0] + masked[1:-1] + value[-1]
        return masked

    return NON_CODE.sub(mask, text)


def scan_text(path, text, categories=None):
    suffix = Path(path).suffix
    code = mask_non_code(text)
    line_starts = [0] + [match.end() for match in re.finditer("\n", text)]
    findings = []
    for rule in RULES:
        if suffix not in rule.extensions or (categories and rule.category not in categories):
            continue
        for match in COMPILED[rule.id].finditer(code):
            line = bisect_right(line_starts, match.start())
            findings.append({
                "path": str(path), "line": line,
                "column": match.start() - line_starts[line - 1] + 1,
                "rule": rule.id, "category": rule.category, "status": "needs-review",
            })
    return sorted(findings, key=lambda item: (item["line"], item["column"], item["rule"]))


def scan(root, categories=None):
    root = Path(root).resolve()
    findings, errors = [], []
    count = 0

    def record_error(error):
        # Do not copy exception bodies, source text, credentials or payloads.
        path = Path(error.filename) if error.filename else root
        errors.append({"path": str(path.relative_to(root)), "error_type": type(error).__name__})

    for directory in SOURCE_ROOTS:
        base = root / directory
        if not base.is_dir() or base.is_symlink():
            continue
        for current, dirs, files in os.walk(base, onerror=record_error, followlinks=False):
            dirs[:] = sorted(name for name in dirs if name not in EXCLUDED
                             and not (Path(current) / name).is_symlink())
            for name in sorted(files):
                path = Path(current) / name
                if path.suffix not in JAVA + NATIVE or path.is_symlink():
                    continue
                # The apps tree also contains Java examples outside source roots.
                if path.suffix == ".java" and "src" not in path.relative_to(root).parts:
                    continue
                relative = path.relative_to(root)
                try:
                    source = path.read_text(encoding="utf-8")
                except (OSError, UnicodeError) as error:
                    errors.append({"path": str(relative), "error_type": type(error).__name__})
                    continue
                count += 1
                findings.extend(scan_text(relative, source, categories))
    rules = [rule for rule in RULES if not categories or rule.category in categories]
    incomplete = bool(errors) or count == 0
    return {
        "schema_version": 1, "status": "incomplete" if incomplete else (
            "triage-required" if findings else "no-rule-matches"),
        "scanned_files": count, "rule_count": len(rules),
        "finding_count": len(findings),
        "by_category": dict(sorted(Counter(item["category"] for item in findings).items())),
        "by_rule": dict(sorted(Counter(item["rule"] for item in findings).items())),
        "coverage": "Java/C regex triage; comments/literals, tests, demos, generated trees and symlinks excluded. No data-flow or memory-safety proof.",
        "rules": [asdict(rule) for rule in rules], "findings": findings, "errors": errors,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--category", choices=CATEGORIES, action="append", help="repeat to select categories")
    parser.add_argument("--list", action="store_true", help="print regexes and review guidance without scanning")
    parser.add_argument("--output", type=Path, default=ROOT / "tests/results/patterns/report.json")
    args = parser.parse_args()
    if args.list:
        print(json.dumps([asdict(rule) for rule in RULES
                          if not args.category or rule.category in args.category], indent=2))
        return 0
    if not args.root.is_dir():
        parser.error("--root must be an existing directory")
    report = scan(args.root, args.category)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "status", "scanned_files", "rule_count", "finding_count", "by_category"
    )} | {"report": str(args.output)}))
    return 1 if report["status"] == "incomplete" else 0


if __name__ == "__main__":
    raise SystemExit(main())
