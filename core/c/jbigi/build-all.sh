#/bin/sh

case `uname -sr` in
MINGW*)
	echo "Building windows .dlls for all architectures";;
Linux*)
	echo "Building linux .sos for all architectures";;
FreeBSD*)
	echo "Building freebsd .sos for all architectures";;
*)
	echo "Unsupported build environment"
	exit;;
esac

VER=4.2.4
echo "Extracting GMP Version $VER ..."
tar -xjf gmp-$VER.tar.bz2
echo "Building..."
mkdir bin
mkdir lib
mkdir lib/net
mkdir lib/net/i2p
mkdir lib/net/i2p/util

for x in none pentium pentiummmx pentium2 pentium3 pentium4 k6 k62 k63 athlon geode pentiumm core2
do
	mkdir bin/$x
	cd bin/$x
	../../gmp-$VER/configure --build=$x
	make
	sh ../../build_jbigi.sh static
	case `uname -sr` in
	MINGW*)
		cp jbigi.dll ../../lib/net/i2p/util/jbigi-windows-$x.dll;;
	Linux*)
		cp libjbigi.so ../../lib/net/i2p/util/libjbigi-linux-$x.so;;
	FreeBSD*)
		cp libjbigi.so ../../lib/net/i2p/util/libjbigi-freebsd-$x.so;;
	esac
	cd ..
	cd ..
done
