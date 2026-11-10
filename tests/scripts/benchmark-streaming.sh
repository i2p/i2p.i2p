#!/bin/sh
# Compare the current streaming receive buffer with a Git revision.
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$project_dir"
baseline_ref=${1:-HEAD}
classpath="core/build/classes/java/main:apps/ministreaming/build/classes/java/main:apps/streaming/build/classes/java/main"
source_path="apps/streaming/java/src/net/i2p/client/streaming/impl/MessageInputStream.java"
benchmark_path="tests/benchmarks/StreamingBufferBenchmark.java"
benchmark_class="net.i2p.client.streaming.impl.StreamingBufferBenchmark"

if [ ! -f apps/streaming/build/classes/java/main/net/i2p/client/streaming/impl/MessageInputStream.class ]; then
    printf '%s\n' 'Compile the streaming classes first: ./gradlew :apps:streaming:classes' >&2
    exit 1
fi

java_cmd=java
javac_cmd=javac
if [ -n "${JAVA_HOME:-}" ]; then
    java_cmd="$JAVA_HOME/bin/java"
    javac_cmd="$JAVA_HOME/bin/javac"
fi

scratch_dir=$(mktemp -d "${TMPDIR:-/tmp}/i2p-stream-bench.XXXXXX")
trap 'rm -rf "$scratch_dir"' 0
trap 'exit 1' HUP INT TERM
mkdir -p "$scratch_dir/baseline" "$scratch_dir/current"
git show "$baseline_ref:$source_path" > "$scratch_dir/MessageInputStream.java"
"$javac_cmd" -cp "$classpath" -d "$scratch_dir/baseline" \
    "$scratch_dir/MessageInputStream.java" "$benchmark_path"
"$javac_cmd" -cp "$classpath" -d "$scratch_dir/current" "$benchmark_path"

printf 'Baseline: %s\n' "$baseline_ref"
"$java_cmd" -Xms256m -Xmx256m -cp "$scratch_dir/baseline:$classpath" "$benchmark_class"
printf '%s\n' 'Current compiled implementation:'
"$java_cmd" -Xms256m -Xmx256m -cp "$scratch_dir/current:$classpath" "$benchmark_class"
