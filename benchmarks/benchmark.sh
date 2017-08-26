#!/bin/sh

CWD=$(dirname "$0")
if [ "x$JBIGI" = 'x' ]
then
    JBIGI="$CWD/../build/jbigi.jar"
fi
if [ "x$JAVA" = 'x' ]
then
    JAVA=java
fi

CLASSPATH="$CWD/java/build/benchmarks.jar"
if [ "x${1:-}" = 'x--jbigi' ]
then
    CLASSPATH="$CLASSPATH:$JBIGI"
    shift
fi

$JAVA -cp "$CLASSPATH" org.openjdk.jmh.Main "$@"
