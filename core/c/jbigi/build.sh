#/bin/sh
#
#  Build the jbigi library for i2p
#
#  To build a static library:
#     download gmp-4.2.2.tar.bz2 to this directory
#       (if a different version, change the VER= line below)
#     build.sh
#
#  To build a dynamic library (you must have a libgmp.so somewhere in your system)
#     build.sh dynamic
#
#  The resulting library is lib/libjbigi.so
#

mkdir -p lib/
mkdir -p bin/local
VER=4.3.1

if [ "$1" != "dynamic" -a ! -d gmp-$VER ]
then
	TAR=gmp-$VER.tar.lzma
        if [ ! -f $TAR ]
        then
	    echo "Downloading ftp://ftp.gmplib.org/pub/gmp-4.3.1/gmp-4.3.1.tar.lzma"
	    wget ftp://ftp.gmplib.org/pub/gmp-4.3.1/gmp-4.3.1.tar.lzma
        fi

	echo "Building the jbigi library with GMP Version $VER"

	echo "Extracting GMP..."
	tar -xf gmp-$VER.tar.lzma --lzma
fi

cd bin/local

echo "Building..."
if [ "$1" != "dynamic" ]
then
	case `uname -sr` in
		Darwin*)
			# --with-pic is required for static linking
			../../gmp-$VER/configure --with-pic;;
		*)
			../../gmp-$VER/configure --with-pic;;
	esac
	make
	sh ../../build_jbigi.sh static
else
	sh ../../build_jbigi.sh dynamic
fi

cp *jbigi???* ../../lib/
echo 'Library copied to lib/'
cd ../..

I2P=~/i2p/i2p
if [ ! -f $I2P/lib/i2p.jar ]
then
	echo "I2P installation not found in $I2P - correct \$I2P definition in script to run speed test"
	exit 1
fi
echo 'Running test with standard I2P installation...'
java -cp $I2P/lib/i2p.jar:$I2P/lib/jbigi.jar net.i2p.util.NativeBigInteger
echo
echo 'Running test with new libjbigi...'
java -Djava.library.path=lib/ -cp $I2P/lib/i2p.jar:$I2P/lib/jbigi.jar net.i2p.util.NativeBigInteger
