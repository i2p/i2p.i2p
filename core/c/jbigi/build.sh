#/bin/sh

case `uname -sr` in
MINGW*)
	echo "Building windows .dll's";;
CYGWIN*)
	echo "Building windows .dll's";;
Linux*)
	echo "Building linux .so's";;
FreeBSD*)
	echo "Building freebsd .so's";;
*)
	echo "Unsupported build environment"
	exit;;
esac

echo "Extracting GMP..."
tar -xzf gmp-4.1.3.tar.gz
echo "Building..."
mkdir bin
mkdir lib
mkdir lib/net
mkdir lib/net/i2p
mkdir lib/net/i2p/util
mkdir bin/local
cd bin/local
../../gmp-4.1.3/configure
make
sh ../../build_jbigi.sh static
case `uname -sr` in
MINGW*)
	cp jbigi.dll ../../lib/net/i2p/util/jbigi-windows-x86.dll;;
CYGWIN*)
	cp jbigi.dll ../../lib/net/i2p/util/jbigi-windows-x86.dll;;
Linux*)
	cp libjbigi.so ../../lib/net/i2p/util/libjbigi-linux-x86.so;;
FreeBSD*)
	cp libjbigi.so ../../lib/net/i2p/util/libjbigi-freebsd-x86.so;;
esac
cd ..
cd ..
