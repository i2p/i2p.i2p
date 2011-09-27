#!/usr/bin/env bash
# Automatic build of so files, ignores failed builds.
# Place latest gmp tarball in the jbigi dir, and exec this script.

if [ -z "$BASH_VERSION" ]; then
	echo "This script needs to be run with Bash."
	echo
	echo "Please install bash and then run this script with"
	echo "bash $0"
	exit 1
fi

#JBIGI=../../../installer/lib/jbigi/jbigi.jar

#if [ -f jbigi.jarx ] ; then
#JBIGI=../jbigi.jar
#fi

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


echo "jbigi.jar created."
echo "raw files are in t."
