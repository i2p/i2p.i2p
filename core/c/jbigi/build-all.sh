#!/bin/sh
#
#  NOTE:
#  This script is not supported - see mbuild-all.sh
#

case `uname -sr` in
MINGW*)
	echo "Building windows .dlls for all architectures";;
SunOS*) 
	echo "Building solaris .sos for all architectures";;
Linux*)
	echo "Building linux .sos for all architectures";;
FreeBSD*)
	echo "Building freebsd .sos for all architectures";;
*)
	echo "Unsupported build environment"
	exit;;
esac

# Import gmp version variables and download gmp.
. ./download_gmp.sh

echo "Building..."
mkdir -p lib/net/i2p/util

#
# look in configure file in gmp source for supported host CPUs, at about line 5000
#
#
for x in \
  none pentium pentiummmx pentium2 pentium3 pentium4 k6 k62 k63 athlon geode pentiumm core2 \
  athlon64 k10 bobcat jaguar bulldozer piledriver steamroller excavator corei atom nano
do
	mkdir -p bin/$x
	cd bin/$x
	../../gmp-$GMP_VER/configure --with-pic --build=$x
	make clean
	make
	sh ../../build_jbigi.sh static
	case `uname -sr` in
	MINGW*)
		cp jbigi.dll ../../lib/net/i2p/util/jbigi-windows-$x.dll;;
	SunOS*)
		cp libjbigi.so ../../lib/net/i2p/util/libjbigi-solaris-$x.so;;
	Linux*)
		cp libjbigi.so ../../lib/net/i2p/util/libjbigi-linux-$x.so;;
	FreeBSD*)
		cp libjbigi.so ../../lib/net/i2p/util/libjbigi-freebsd-$x.so;;
	esac
	cd ..
	cd ..
done
