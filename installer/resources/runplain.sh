#!/bin/sh

# This runs the router by itself, WITHOUT the wrapper.
# This means the router will not restart if it crashes.
# Also, you will be using the default memory size, which is
# probably not enough for i2p.
# You should really use the i2prouter script instead.
#

# Paths
# Note that (percent)INSTALL_PATH and (percent)SYSTEM_java_io_tmpdir
# should have been replaced by the izpack installer.
# If you did not run the installer, replace them with the appropriate path.
I2P="%INSTALL_PATH"
I2PTEMP="%SYSTEM_java_io_tmpdir"

export CP="${I2P}" ; for j in "${I2P}/lib/*"  ; do export CP="${CP}:${j}" ; done;
JAVA=java

JAVAOPTS="-Djava.library.path=$ {I2P}:${I2P}/lib -Di2p.dir.base=${I2P} -DloggerFilenameOverride=logs/log-router-@.txt"
nohup ${JAVA} -cp "${CP}" ${JAVAOPTS} net.i2p.router.RouterLaunch > /dev/null 2>&1 &
echo $! > "${I2PTEMP}/router.pid"
