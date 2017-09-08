#!/bin/sh

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
