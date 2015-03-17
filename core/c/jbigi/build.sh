#!/bin/sh
#
#  Build the jbigi library for i2p
#
#  To build a static library:
#     Set $I2P to point to your I2P installation
#     Set $JAVA_HOME to point to your Java SDK
#     build.sh
#       This script downloads gmp-4.3.2.tar.bz2 to this directory
#       (if a different version, change the VER= line below)
#
#  To build a dynamic library (you must have a libgmp.so somewhere in your system)
#     Set $I2P to point to your I2P installation
#     Set $JAVA_HOME to point to your Java SDK
#     build.sh dynamic
#
#  The resulting library is lib/libjbigi.so
#

rm -rf bin/local
mkdir -p lib bin/local

# Use 4.3.2 32bit CPUs.
# Use 5.0.2 64bit CPUs.
VER=4.3.2

# If JAVA_HOME isn't set, try to figure it out on our own
[ -z $JAVA_HOME ] && . ../find-java-home
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "ERROR: Cannot find jni.h! Looked in \"$JAVA_HOME/include/jni.h\"" >&2
    echo "Please set JAVA_HOME to a java home that has the JNI" >&2
    exit 1
fi

# Abort script on uncaught errors
set -e

download_gmp ()
{
if [ $(which wget) ]; then
    echo "Downloading ftp://ftp.gmplib.org/pub/gmp-${VER}/${TAR}"
    wget -N --progress=dot ftp://ftp.gmplib.org/pub/gmp-${VER}/${TAR}
else
    echo "ERROR: Cannot find wget." >&2
    echo >&2
    echo "Please download ftp://ftp.gmplib.org/pub/gmp-${VER}/${TAR}" >&2
    echo "manually and rerun this script." >&2
    exit 1
fi
}

extract_gmp ()
{
tar -xjf ${TAR} > /dev/null 2>&1|| (rm -f ${TAR} && download_gmp && extract_gmp || exit 1)
}

TAR=gmp-${VER}.tar.bz2

if [ "$1" != "dynamic" -a ! -d gmp-${VER} ]; then
    if [ ! -f $TAR ]; then
        download_gmp
    fi

    echo "Building the jbigi library with GMP Version ${VER}"
    echo "Extracting GMP..."
    extract_gmp
fi

cd bin/local

echo "Building..."
if [ "$1" != "dynamic" ]; then
    case `uname -sr` in
        Darwin*)
            # --with-pic is required for static linking
            ../../gmp-${VER}/configure --with-pic;;
        *)
            # and it's required for ASLR
            ../../gmp-${VER}/configure --with-pic;;
    esac
    make
    sh ../../build_jbigi.sh static
else
    shift
    sh ../../build_jbigi.sh dynamic
fi

cp *jbigi???* ../../lib/
echo 'Library copied to lib/'
cd ../..

if [ "$1" != "notest" ]; then
    if [ -z "$I2P" ]; then
        if [ -r $HOME/i2p/lib/i2p.jar ]; then
            I2P="$HOME/i2p"
        elif [ -r /usr/share/i2p/lib/i2p.jar ]; then
            I2P="/usr/share/i2p"
        else
            echo "Please set the environment variable \$I2P to run tests." >&2
        fi
    fi

    if [ ! -f $I2P/lib/i2p.jar ]; then
        echo "I2P installation not found" >&2
        echo "We looked in $I2P" >&2
        echo "Not running tests against I2P installation without knowing where it is." >&2
        echo >&2
        echo "Please set the environment variable I2P to the location of your"
        echo "I2P installation (so that \$I2P/lib/i2p.jar works)." >&2
        echo "If you do so, this script will run two tests to compare your" >&2
        echo "installed jbigi with the one here you just compiled to see if" >&2
        echo "there is a marked improvement." >&2
        exit 1
    fi
    echo 'Running test with standard I2P installation...'
    java -cp $I2P/lib/i2p.jar:$I2P/lib/jbigi.jar net.i2p.util.NativeBigInteger
    echo
    echo 'Running test with new libjbigi...'
    java -Djava.library.path=lib/ -cp $I2P/lib/i2p.jar:$I2P/lib/jbigi.jar net.i2p.util.NativeBigInteger
    echo 'If the second run shows better performance, please use the jbigi that you have compiled so that I2P will work better!'
    echo "(You can do that just by copying lib/libjbigi.so over the existing libjbigi.so file in \$I2P)"
fi
