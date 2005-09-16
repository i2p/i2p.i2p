#!/bin/sh
cd `dirname $0`
if [ -n "$JAVA_HOME" ]; then
  $JAVA_HOME/bin/java -jar ./launch4j.jar $*
else
  java -jar ./launch4j.jar $*
fi
cd $OLDPWD
