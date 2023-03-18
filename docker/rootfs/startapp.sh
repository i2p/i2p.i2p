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

if [ -f /.dockerenv ] || [ -f /run/.containerenv ]; then
    echo "[startapp] Running in container"
    if [ -z "$IP_ADDR" ]; then
        export IP_ADDR=$(hostname -i)
        echo "[startapp] Running in docker network"
    fi
    echo "[startapp] setting reachable IP to container IP $IP_ADDR"
    find . -name '*.config' -exec sed -i "s/127.0.0.1/$IP_ADDR/g" {} \;
    find . -name '*.config' -exec sed -i "s/localhost/$IP_ADDR/g" {} \;
fi

# Options required for reflective access in dynamic JVM languages like Groovy and Jython
JAVA17OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/sun.nio.fs=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.util.Properties=ALL-UNNAMED --add-opens java.base/java.util.Properties.defaults=ALL-UNNAMED"
# Old java options
JAVAOPTS="-Djava.net.preferIPv4Stack=false -Djava.library.path=${I2P}:${I2P}/lib -Di2p.dir.base=${I2P} -Di2p.dir.config=${HOME}/.i2p -DloggerFilenameOverride=logs/log-router-@.txt -Xmx$JVM_XMX"

java -cp "${CLASSPATH}" ${JAVA_OPTS} net.i2p.router.RouterLaunch
