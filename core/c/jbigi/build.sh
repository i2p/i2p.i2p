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
tar -xjf gmp-4.1.3.tar.bz2
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
	cp jbigi.dll ../../lib/jbigi;;
CYGWIN*)
	cp jbigi.dll ../../lib/jbigi;;
Linux*)
	cp libjbigi.so ../../lib/jbigi;;
FreeBSD*)
	cp libjbigi.so ../../lib/jbigi;;
esac
cd ..
cd ..
