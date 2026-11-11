#!/bin/sh
# Isolate baseline/current source in separate JVMs; compile dependencies first.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$project_dir"
baseline_ref=${1:-HEAD}
classpath="core/build/classes/java/main"
source_path="core/java/src/net/i2p/crypto/ChaCha20.java"
benchmark_path="tests/benchmarks/ChaCha20Benchmark.java"
if [ ! -f "$classpath/com/southernstorm/noise/crypto/chacha20/ChaChaCore.class" ]; then
    printf '%s\n' 'Compile core classes first: ./gradlew :core:classes' >&2
    exit 1
fi
java_cmd=java
javac_cmd=javac
if [ -n "${JAVA_HOME:-}" ]; then
    java_cmd="$JAVA_HOME/bin/java"
    javac_cmd="$JAVA_HOME/bin/javac"
fi
scratch_dir=$(mktemp -d "${TMPDIR:-/tmp}/i2p-chacha-bench.XXXXXX")
trap 'rm -rf "$scratch_dir"' 0
trap 'exit 1' HUP INT TERM
mkdir -p "$scratch_dir/baseline" "$scratch_dir/current"
git show "$baseline_ref:$source_path" > "$scratch_dir/ChaCha20.java"
"$javac_cmd" -cp "$classpath" -d "$scratch_dir/baseline" "$scratch_dir/ChaCha20.java" "$benchmark_path"
"$javac_cmd" -cp "$classpath" -d "$scratch_dir/current" "$source_path" "$benchmark_path"
for length in 8 32 64 256 1024 4096; do
    printf 'Baseline: %s size=%s\n' "$baseline_ref" "$length"
    "$java_cmd" -Xms128m -Xmx128m -cp "$scratch_dir/baseline:$classpath" net.i2p.crypto.ChaCha20Benchmark "$length"
    printf 'Current source size=%s\n' "$length"
    "$java_cmd" -Xms128m -Xmx128m -cp "$scratch_dir/current:$classpath" net.i2p.crypto.ChaCha20Benchmark "$length"
done
