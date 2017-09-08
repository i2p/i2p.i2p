#!/bin/sh

#
# Benchmark runner
#
# Usage: benchmark.sh [--jbigi] [JMH_ARGS]
#
# To use:
# 1) Set jmh.home in override.properties to a folder containing:
#    - jmh-core.jar
#    - jmh-generator-annprocess.jar
#    - jopt-simple.jar
#    - commons-math3.jar
#    Fetch these from Maven Central. Tested using JMH 1.19 which requires
#    jopt-simple 4.6 and commons-math3 3.2.
# 2) Compile the benchmarks with "ant bench".
# 3) Run the benchmarks:
#    - To see underlying JMH options:
#      - ./benchmark.sh -h
#    - To run the benchmarks in pure-Java mode:
#      - ./benchmark.sh
#    - To run the benchmarks with jbigi.jar in the classpath:
#      - ./benchmark.sh --jbigi
#    - To run the benchmarks with a different JVM:
#      - JAVA=/path/to/java ./benchmarks/benchmark.sh
#

CWD=$(dirname "$0")
if [ "x$JAVA" = 'x' ]
then
    JAVA=java
fi

if [ "x$BENCHMARKS" = 'x' ]
then
    BENCHMARKS="$CWD/i2p-benchmarks.jar"
    stat "$BENCHMARKS" >/dev/null 2>&1
    if [ "x$?" != 'x0' ]
    then
        BENCHMARKS="$CWD/../../core/java/build/i2p-benchmarks.jar"
    fi
fi

if [ "x$JBIGI" = 'x' ]
then
    JBIGI="$CWD/jbigi.jar"
    stat "$JBIGI" >/dev/null 2>&1
    if [ "x$?" != 'x0' ]
    then
        JBIGI="$CWD/../../build/jbigi.jar"
    fi
fi

CLASSPATH="$BENCHMARKS"
if [ "x${1:-}" = 'x--jbigi' ]
then
    CLASSPATH="$CLASSPATH:$JBIGI"
    shift
fi

$JAVA -cp "$CLASSPATH" org.openjdk.jmh.Main "$@"
