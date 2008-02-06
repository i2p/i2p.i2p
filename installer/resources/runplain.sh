#
# This runs the router by itself, WITHOUT the wrapper.
# This means the router will not restart if it crashes.
# Also, you will be using the default memory size, which is
# probably not enough for i2p.
# You should really use the i2prouter script instead.
#
export CP=. ; for j in lib/*  ; do export CP=$CP:$j ; done;
JAVA=java

JAVAOPTS="-Djava.library.path=.:lib -DloggerFilenameOverride=logs/log-router-@.txt"
nohup $JAVA -cp $CP $JAVAOPTS net.i2p.router.RouterLaunch > /dev/null 2>&1 &
echo $! > router.pid
