#/bin/bash
# Automatic build of so files, ignores failed builds.
# Place latest gmp tarball in the jbigi dir, and exec this script.

JBIGI=../../../installer/lib/jbigi/jbigi.jar

if [ -f jbigi.jarx ] ; then
JBIGI=../jbigi.jar
fi

(cd jcpuid ; sh build.sh )
(cd jbigi ; sh mbuild-all.sh )

rm -Rf t
mkdir t

(
	cd t
	cp ../../../installer/lib/jbigi/*.so ../../../installer/lib/jbigi/*.dll ../../../installer/lib/jbigi/*.jnilib .
)

cp jbigi/lib/net/i2p/util/* t/
( 
	cd t
	for i in *.so ; { strip $i ; }
)

cp jcpuid/lib/freenet/support/CPUInformation/* t/

(
	cd t
	jar cf ../jbigi.jar .
)

rm -R t
mkdir t
cp jbigi/lib/net/i2p/util/* t/
( 
	cd t
	for i in *.so ; { strip $i ; }
)


echo "jbigi.jar Refreshed."
echo "raw files ar in t."
