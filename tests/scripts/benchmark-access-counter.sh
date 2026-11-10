#!/bin/sh
set -eu

benchmark_base=${1:-HEAD^}
benchmark_attempts=${2:-100000}
benchmark_root=$(git rev-parse --show-toplevel)
benchmark_dir=$(mktemp -d "${TMPDIR:-/tmp}/i2p-access-benchmark.XXXXXX")
benchmark_path=apps/i2ptunnel/java/src/net/i2p/i2ptunnel/access
benchmark_java=${JAVA_HOME:+$JAVA_HOME/bin/}
trap 'rm -rf "$benchmark_dir"' EXIT HUP INT TERM

for benchmark_variant in base updated; do
    mkdir -p "$benchmark_dir/$benchmark_variant/classes"
    for benchmark_source in AccessCounter Threshold; do
        if [ "$benchmark_variant" = base ]; then
            git -C "$benchmark_root" show "$benchmark_base:$benchmark_path/$benchmark_source.java" > "$benchmark_dir/$benchmark_variant/$benchmark_source.java"
        else
            cp "$benchmark_root/$benchmark_path/$benchmark_source.java" "$benchmark_dir/$benchmark_variant/$benchmark_source.java"
        fi
    done
    "${benchmark_java}javac" -d "$benchmark_dir/$benchmark_variant/classes" \
        "$benchmark_dir/$benchmark_variant/AccessCounter.java" \
        "$benchmark_dir/$benchmark_variant/Threshold.java" \
        "$benchmark_root/tests/benchmarks/AccessCounterFloodBenchmark.java"
    printf '%s ' "$benchmark_variant"
    "${benchmark_java}java" -Xms256m -Xmx256m -cp "$benchmark_dir/$benchmark_variant/classes" \
        net.i2p.i2ptunnel.access.AccessCounterFloodBenchmark "$benchmark_attempts"
done
