#!/usr/bin/env python3
"""Build immutable test artifacts and run a finite, isolated live I2P router."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from verify_live import verify_file


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--duration", type=int, default=720)
    parser.add_argument("--port", type=int, default=24741)
    parser.add_argument("--mode", choices=("normal", "firewalled", "ssu2-only", "ntcp2-only"), default="ssu2-only")
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--skip-build", action="store_true", help="Use jars from an already validated build")
    parser.add_argument("--core-jar", type=Path, help="Use a staged core jar with installation-specific bundled resources")
    parser.add_argument("--verification", choices=("connectivity", "observe"), default="connectivity",
                        help="Require working tunnels by default; observe preserves completion-only operation")
    parser.add_argument("--min-roundtrips", type=int, default=1)
    parser.add_argument("--min-two-hop-samples", type=int, default=1)
    args = parser.parse_args()
    if not 30 <= args.duration <= 3600 or not 1024 <= args.port <= 65535:
        parser.error("duration must be 30..3600 seconds and port 1024..65535")
    if args.min_roundtrips < 1 or args.min_two_hop_samples < 1:
        parser.error("verification thresholds must be at least one")
    repo = Path(__file__).resolve().parents[2]
    runtime = args.runtime.resolve()
    runtime.parent.mkdir(parents=True, exist_ok=True)
    java = str(args.java_home / "bin/java") if args.java_home else shutil.which("java")
    javac = str(args.java_home / "bin/javac") if args.java_home else shutil.which("javac")
    if not java or not javac:
        parser.error("A compatible JDK is required")
    if not args.skip_build:
        env = os.environ.copy()
        if args.java_home:
            env["JAVA_HOME"] = str(args.java_home)
        subprocess.run([str(repo / "gradlew"), "--no-daemon", ":router:jar", ":apps:streaming:jar"],
                       cwd=repo, env=env, check=True)
    artifacts = Path(tempfile.mkdtemp(prefix="i2p-live-artifacts-", dir=runtime.parent))
    lib = artifacts / "lib"
    classes = artifacts / "classes"
    lib.mkdir()
    classes.mkdir()
    jars = [repo / path for path in (
        "core/build/libs/i2p.jar", "router/build/libs/router.jar",
        "apps/ministreaming/build/libs/mstreaming.jar", "apps/streaming/build/libs/streaming.jar",
    )]
    if args.core_jar:
        jars[0] = args.core_jar.resolve()
    # The core/router jars contain the transport and crypto implementations.
    # These two external runtime dependencies are resolved by the normal Gradle build.
    gradle_cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
    for group, name in (("gnu.getopt", "java-getopt"), ("org.gnu.gettext", "libintl")):
        candidates = sorted((gradle_cache / group / name).glob("*/*/*.jar"))
        if candidates:
            jars.append(candidates[-1])
    manifest = {}
    for jar in jars:
        if not jar.is_file():
            raise FileNotFoundError("Build artifact missing: " + str(jar))
        target = lib / jar.name
        shutil.copy2(jar, target)
        manifest[jar.name] = hashlib.sha256(target.read_bytes()).hexdigest()
    classpath = str(lib / "*")
    subprocess.run([javac, "-cp", classpath, "-d", str(classes),
                    str(repo / "tests/live/LiveRouterSmoke.java")], cwd=repo, check=True)
    command = [java, "-Xms64m", "-Xmx384m", "-cp", str(classes) + os.pathsep + classpath,
               "net.i2p.router.transport.udp.LiveRouterSmoke", str(repo), str(runtime),
               str(args.duration), str(args.port), args.mode]
    metadata = {"runtime": str(runtime), "artifacts": str(artifacts), "mode": args.mode,
                "durationSeconds": args.duration, "jarSha256": manifest, "command": command,
                "verification": args.verification, "minRoundtrips": args.min_roundtrips,
                "minTwoHopSamples": args.min_two_hop_samples}
    (artifacts / "launch.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata), flush=True)
    launcher_failure = None
    with (artifacts / "console.log").open("w") as output:
        child = subprocess.Popen(command, cwd=artifacts, stdout=output, stderr=subprocess.STDOUT)
        try:
            status = child.wait(timeout=args.duration + 240)
        except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
            launcher_failure = "interrupted" if isinstance(error, KeyboardInterrupt) else "timeout"
            if runtime.exists():
                (runtime / "STOP").touch()
            try:
                status = child.wait(timeout=45)
            except subprocess.TimeoutExpired:
                child.terminate()
                try:
                    status = child.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    child.kill()
                    status = child.wait()
    print(json.dumps({"event": "process-exit", "returnCode": status,
                      "runtime": str(runtime), "artifacts": str(artifacts)}), flush=True)
    verdict, exit_status = finish_run(runtime, metadata, status, launcher_failure=launcher_failure)
    (artifacts / "verification.json").write_text(json.dumps(verdict, indent=2) + "\n")
    print(json.dumps(verdict), flush=True)
    raise SystemExit(exit_status)


def finish_run(runtime, metadata, status, *, launcher_failure=None):
    """Keep observation completion separate from the connectivity verdict."""
    if metadata["verification"] == "observe":
        # Observation records completion, without asserting that traffic worked.
        verdict = {"event": "verification", "verification": "observe",
                   "passed": None, "connectivityVerified": False,
                   "processCompleted": status == 0 and launcher_failure is None,
                   "processExitCode": status}
        return verdict, 0 if verdict["processCompleted"] else 1
    verdict = verify_file(Path(runtime) / "metrics.jsonl", process_exit_code=status,
                          min_roundtrips=metadata["minRoundtrips"],
                          min_two_hop_samples=metadata["minTwoHopSamples"],
                          expected_duration=metadata["durationSeconds"],
                          expected_hashes=metadata["jarSha256"])
    verdict["verification"] = "connectivity"
    if launcher_failure:
        verdict["passed"] = False
        verdict["failures"].append("launcher-" + launcher_failure)
    return verdict, 0 if verdict["passed"] else 1


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.SubprocessError) as error:
        print(json.dumps({"event": "verification", "passed": False,
                          "failures": [str(error)]}), flush=True)
        raise SystemExit(1)
