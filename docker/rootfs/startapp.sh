#!/bin/sh
set -euo

if [ -z "$JVM_XMX" ]; then
    echo "*** Defaulting to 512MB JVM heap limit"
    echo "*** You can override that value with the JVM_XMX variable"
    echo "*** (for example JVM_XMX=256m)"
    JVM_XMX=512m
fi

if [ -z "$EXT_PORT" ]; then
    echo "*** EXT_PORT is unset."
    echo "*** I2P router will resolve to a \"Firewalled\" state"
    echo "*** please configure EXT_PORT in your docker-compose.yaml or docker run command"
else
    find . -name 'router.config' -exec sed -i "s|12345|$EXT_PORT|g" {} \;
fi

# Explicitly define HOME otherwise it might not have been set
export HOME=/i2p

export I2P=${HOME}

echo "Starting I2P"

cd $HOME
export CLASSPATH=.

for jar in lib/*.jar; do
    CLASSPATH=${CLASSPATH}:${jar}
done

if [ -f /.dockerenv ] || [ -f /run/.containerenv ]; then
    echo "[startapp] Running in container"
    # Determine the container's IP address and use it to replace 127.0.0.1 in the config files.
    # Replace spaces with commas to match the format of the IP address in the config files.
    DOCKER_IP="$(hostname -i | tr ' ' ',')"
    # Bypass container IP auto-configuration and force an IP address.
    # for instance: `172.17.0.2` if that's your IP address on the default Docker bridge.
    if [ -z "$IP_ADDR" ]; then
        # Determine if we are running on host network or in a docker network
        if ip link show docker0 > /dev/null 2>&1; then
        # So far so good, now we check if we're on the host network.
        # If we're sharing the host's interface, it is correct to use 127.0.0.1
        # This check is actually reliable, we would not be able to see docker0
        # inside of the container itself, only from the host.
        # Anything else is franky not a Docker problem at all, this is *by definition* the
        # same as running it directly on the host network interface in every way.
            echo "[startapp] Running on host network, restricting access to localhost"
            IP_ADDR="127.0.0.1"
        else
        # If we are not on the host network, then the network is set up like this on purpose
        # so we can expose the ports and APIs to the other containers on the network.
        # This is by design, and not a security problem that can be solved using the
        # default docker network. If you need additional network isolation, you need to
        # set up your own custom network. This IP auto-configuration will also work in the
        # custom network.
        # Note that in a custom network, there will be a 127.0.0.1 loopback interface in the output of hostname -i.
        # No biggie, in a custom network, both are valid. We continue completely replacing the extant entry
        # in order to avoid causing duplicate IP addresses in the config files.
            export IP_ADDR="$DOCKER_IP"
        fi
        echo "[startapp] Running in docker network"
    fi
    echo "[startapp] setting reachable IP to container IP $IP_ADDR"
    find . -name 'clients.config' -exec sed -i "s/127.0.0.1/$IP_ADDR/g" {} \;
    # Rewriting the string localhost is no longer necessary
fi

# Options required for reflective access in dynamic JVM languages like Groovy and Jython
JAVA17OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/sun.nio.fs=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.util.Properties=ALL-UNNAMED --add-opens java.base/java.util.Properties.defaults=ALL-UNNAMED"
# Old java options
JAVAOPTS="-Djava.net.preferIPv4Stack=false -Djava.library.path=${I2P}:${I2P}/lib -Di2p.dir.base=${I2P} -Di2p.dir.config=${HOME}/.i2p -DloggerFilenameOverride=logs/log-router-@.txt -Xmx$JVM_XMX"

# Execute the Java application with the constructed classpath and options
# The `exec` command replaces the shell with the Java process, ensuring that it receives signals directly and can shut down gracefully.
exec `java -cp "${CLASSPATH}" ${JAVAOPTS} ${JAVA17OPTS} net.i2p.router.RouterLaunch`
