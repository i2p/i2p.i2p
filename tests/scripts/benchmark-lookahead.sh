#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$project_dir"
baseline_ref=${1:-HEAD}
classpath=core/build/classes/java/main
source_path=core/java/src/net/i2p/util/LookaheadInputStream.java
benchmark_path=tests/benchmarks/LookaheadGzipBenchmark.java
java_cmd=java
javac_cmd=javac
if [ -n "${JAVA_HOME:-}" ]; then
    java_cmd="$JAVA_HOME/bin/java"
    javac_cmd="$JAVA_HOME/bin/javac"
fi
if [ ! -f "$classpath/net/i2p/util/LookaheadInputStream.class" ]; then
    printf '%s\n' 'Compile core first: ./gradlew :core:classes' >&2
    exit 1
fi
scratch_dir=$(mktemp -d "${TMPDIR:-/tmp}/i2p-lookahead-bench.XXXXXX")
trap 'rm -rf "$scratch_dir"' 0
trap 'exit 1' HUP INT TERM
mkdir -p "$scratch_dir/baseline" "$scratch_dir/current"
git show "$baseline_ref:$source_path" > "$scratch_dir/LookaheadInputStream.java"
"$javac_cmd" -cp "$classpath" -d "$scratch_dir/baseline" "$scratch_dir/LookaheadInputStream.java" "$benchmark_path"
"$javac_cmd" -cp "$classpath" -d "$scratch_dir/current" "$benchmark_path"
printf 'Baseline: %s\n' "$baseline_ref"
"$java_cmd" -Xms256m -Xmx256m -cp "$scratch_dir/baseline:$classpath" LookaheadGzipBenchmark
printf '%s\n' 'Current compiled implementation:'
"$java_cmd" -Xms256m -Xmx256m -cp "$scratch_dir/current:$classpath" LookaheadGzipBenchmark
