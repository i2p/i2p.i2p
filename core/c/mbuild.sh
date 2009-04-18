#/bin/bash

JBIGI=../../../installer/lib/jbigi/jbigi.jar

if [ -f jbigi.jarx ] ; then
JBIGI=../jbigi.jar
fi

(cd jcpuid ; sh build.sh ; cd ..)
(cd jbigi ; sh mbuild-all.sh ; cd ..)

mkdir t

(
	cd t
	jar xf ../../../installer/lib/jbigi/jbigi.jar
)

cp jbigi/lib/net/i2p/util/* t/
cp jcpuid/lib/freenet/support/CPUInformation/* t/

(
	cd t
	jar cf ../jbigi.jar .
)

rm -R t
echo "jbigi.jar Refreshed."
