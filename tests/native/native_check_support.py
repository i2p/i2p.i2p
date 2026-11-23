"""Shared support for focused Linux C/JNI boundary regressions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SANITIZERS = ["gcc", "-std=gnu11", "-O1", "-g", "-fsanitize=address,undefined",
              "-fno-omit-frame-pointer", "-fno-pie", "-no-pie"]


def arguments(description):
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--source-tree", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path,
                        default=ROOT / "tests/results" / (Path(sys.argv[0]).stem + ".json"))
    parser.add_argument("--java-classes", type=Path, help="reuse compiled core classes for the jbigi check")
    parser.add_argument("--valid-jni-only", action="store_true",
                        help="avoid direct malformed calls when checking an unpatched jbigi library")
    return parser.parse_args()


def invoke(command, work, timeout=30):
    environment = dict(os.environ, ASAN_OPTIONS="detect_leaks=0:halt_on_error=1",
                       UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
    result = subprocess.run(list(map(str, command)), cwd=work, env=environment,
                            text=True, capture_output=True, timeout=timeout,
                            preexec_fn=lambda: resource.setrlimit(resource.RLIMIT_CORE, (0, 0)))
    return {"exit_code": result.returncode, "stdout": result.stdout, "stderr": result.stderr}


def definition(source, name):
    # Preserve positions while masking C comments and quoted strings/characters.
    tokens = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27')
    masked = tokens.sub(lambda m: "".join("\n" if c == "\n" else " " for c in m[0]), source)
    match = re.search(r"(?m)^[ \t]*(?:static\s+)?(?:int|void|jboolean|jint)\s+" + re.escape(name)
                      + r"\s*\([^;{}]*\)\s*\{", masked)
    if match is None:
        raise ValueError("C definition not found: " + name)
    end = masked.index("{", match.start()) + 1
    depth = 1
    while depth and end < len(masked):
        if masked[end] == "{": depth += 1
        elif masked[end] == "}": depth -= 1
        end += 1
    if depth:
        raise ValueError("Unclosed C definition: " + name)
    return source[match.start():end] + "\n"


def jni_includes(source_tree, library):
    compiler = shutil.which("javac")
    if compiler is None:
        raise RuntimeError("A JDK with JNI headers is required")
    home = Path(compiler).resolve().parents[1]
    relative = "core/c/jbigi/jbigi/include" if library == "jbigi" else "core/c/jcpuid/include"
    return ["-I" + str(home / "include"), "-I" + str(home / "include/linux"),
            "-I" + str(source_tree / relative)]


def finish(report, output):
    report["passed"] = (bool(report["cases"])
                        and all(c["exit_code"] == 0 for c in report["compilation"].values())
                        and all(c["exit_code"] == 0 for c in report["cases"].values()))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"passed": report["passed"], "cases": len(report["cases"]),
                      "failures": sum(c["exit_code"] != 0 for c in report["cases"].values()),
                      "report": str(output)}))
    return 0 if report["passed"] else 1


def check_extracted(description, relative, fixture, build_body, cases):
    args = arguments(description)
    source_tree = args.source_tree.resolve()
    source = source_tree / relative
    report = {"source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
              "compilation": {}, "cases": {},
              "limits": ["Unchanged production function bodies with platform stubs on Linux; no complete Windows launcher is executed.",
                         "Address/undefined-behavior sanitizers are enabled; leak detection is disabled."]}
    with tempfile.TemporaryDirectory(prefix="i2p-native-check-") as temporary:
        work = Path(temporary)
        generated = work / "harness.c"
        generated.write_text((ROOT / "tests/native" / fixture).read_text().replace(
            "/* INSERT_SOURCE */", build_body(source.read_text())))
        executable = work / "check"
        report["compilation"]["harness"] = invoke(SANITIZERS + [generated, "-o", executable], work)
        if report["compilation"]["harness"]["exit_code"] == 0:
            for case in cases:
                report["cases"][case] = invoke([executable, case], work, timeout=10)
    return finish(report, args.output)
