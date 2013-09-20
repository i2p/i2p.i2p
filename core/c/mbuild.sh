#!/bin/sh -e
# Automatic build of so files, ignores failed builds.
# Place latest gmp tarball in the jbigi dir, and exec this script.

rm -f t/* jcpuid/lib/freenet/support/CPUInformation/* jbigi/lib/net/i2p/util/*

( cd jcpuid ; ./build.sh )
( cd jbigi ; ./mbuild-all.sh )

rm -Rf t
mkdir t

(
	cd t
	cp ../../../installer/lib/jbigi/*.so ../../../installer/lib/jbigi/*.dll ../../../installer/lib/jbigi/*.jnilib .
)

cp jbigi/lib/net/i2p/util/* t/
( 
	cd t
	for i in *.so ; do strip $i ; done
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
	for i in *.so ; do strip $i ; done
)


echo "jbigi.jar created."
echo "raw files are in t."
