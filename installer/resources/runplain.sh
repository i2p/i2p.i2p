#!/bin/sh

# This runs the router by itself, WITHOUT the wrapper.
# This means the router will not restart if it crashes.
# Also, you will be using the default memory size, which is
# probably not enough for i2p, unless you set it below.
# You should really use the i2prouter script instead.
#

# Paths
# Note that (percent)INSTALL_PATH and (percent)SYSTEM_java_io_tmpdir
# should have been replaced by the izpack installer.
# If you did not run the installer, replace them with the appropriate path.
I2P="%INSTALL_PATH"
I2PTEMP="%SYSTEM_java_io_tmpdir"

# Having IPv6 enabled can cause problems with certain configurations. Changing the
# next value to true may help.
PREFERv4="false"
CP=

# Uncomment to set the maximum memory. The default and the option may vary in different JVMs.
# Check your java documentation to be sure.
#MAXMEMOPT="-Xmx256m"

# Try using the Java binary that I2P was installed with.
# If it's not found, try looking in the system PATH.
JAVA=$(which "%JAVA_HOME"/bin/java || which java)

if [ -z $JAVA ] || [ ! -x $JAVA ]; then
    echo "Error: Cannot find java." >&2
    exit 1
fi

for jar in `ls ${I2P}/lib/*.jar`; do
    if [ ! -z $CP ]; then
        CP=${CP}:${jar};
    else
        CP=${jar}
    fi
done

if [ $(uname -s) = "Darwin" ]; then
    export JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"
fi
JAVAOPTS="${MAXMEMOPT} -Djava.net.preferIPv4Stack=${PREFERv4} -Djava.library.path=${I2P}:${I2P}/lib -Di2p.dir.base=${I2P} -DloggerFilenameOverride=logs/log-router-@.txt"
(
    nohup ${JAVA} -cp \"${CP}\" ${JAVAOPTS} net.i2p.router.RouterLaunch > /dev/null 2>&1
) &
PID=$!

if [ ! -z $PID ] && kill -0 $PID > /dev/null 2>&1 ; then
    echo "I2P started [$PID]" >&2
    echo $PID > "${I2PTEMP}/router.pid"
else
    echo "I2P failed to start." >&2
    exit 1
fi
