export CP=. ; for j in lib/*  ; do export CP=$CP:$j ; done;
JAVA=java

JAVAOPTS="-Djava.library.path=.:lib -DloggerFilenameOverride=logs/log-router-@.txt"
nohup $JAVA -cp $CP $JAVAOPTS net.i2p.router.RouterLaunch > /dev/null 2>&1 &
echo $! > router.pid
