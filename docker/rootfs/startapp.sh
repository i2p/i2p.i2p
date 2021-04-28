#!/bin/sh
set -e

if [ -z $JVM_XMX ]; then
    echo "*** Defaulting to 512MB JVM heap limit"
    echo "*** You can override that value with the JVM_XMX variable"
    echo "*** (for example JVM_XMX=256m)"
    JVM_XMX=512m
fi

# Explicitly define HOME otherwise it might not have been set
export HOME=/i2p

export I2P=${HOME}/i2p

echo "Starting I2P"

cd $HOME
export CLASSPATH=.

for jar in `ls lib/*.jar`; do
    CLASSPATH=${CLASSPATH}:${jar}
done

JAVAOPTS="-Djava.net.preferIPv4Stack=false -Djava.library.path=${I2P}:${I2P}/lib -Di2p.dir.base=${I2P} -Di2p.dir.config=${HOME}/.i2p -DloggerFilenameOverride=logs/log-router-@.txt -Xmx$JVM_XMX"

java -cp "${CLASSPATH}" ${JAVA_OPTS} net.i2p.router.RouterLaunch

