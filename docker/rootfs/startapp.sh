#!/bin/sh
set -e

# Explicitly define HOME otherwise it might not have been set
export HOME=/i2p

export I2P=${HOME}/i2p

echo "Starting I2P"

cd $HOME
export CLASSPATH=.

for jar in `ls lib/*.jar`; do
    CLASSPATH=${CLASSPATH}:${jar}
done

JAVAOPTS="-Djava.net.preferIPv4Stack=false -Djava.library.path=${I2P}:${I2P}/lib -Di2p.dir.base=${I2P} -Di2p.dir.config=${HOME}/.i2p -DloggerFilenameOverride=logs/log-router-@.txt"

java -cp "${CLASSPATH}" ${JAVA_OPTS} net.i2p.router.RouterLaunch

