#
# build GMP and libjbigi.so using the Android tools directly
#

# uncomment to skip
# exit 0

THISDIR=$(realpath $(dirname $(which $0)))
cd $THISDIR
export NDK=$(realpath ../../../android-ndk-r5b/)

#
# API level, must match that in ../AndroidManifest.xml
#
LEVEL=3
ARCH=arm
export SYSROOT=$NDK/platforms/android-$LEVEL/arch-$ARCH/
export AABI=arm-linux-androideabi-4.4.3
export SYSTEM=linux-x86
export BINPREFIX=arm-linux-androideabi-
export CC="$NDK/toolchains/$AABI/prebuilt/$SYSTEM/bin/${BINPREFIX}gcc --sysroot=$SYSROOT"

#echo "CC is $CC"

JBIGI=$(realpath ../../core/c/jbigi)
GMPVER=4.3.2
GMP=$JBIGI/gmp-$GMPVER

if [ ! -d $GMP ]
then
	echo "Source dir for GMP version $GMPVER not found in $GMP"
	echo "Install it there or change GMPVER and/or GMP in this script"
	exit 1
fi

LIBFILE=$PWD/libjbigi.so
if [ -f $LIBFILE ]
then
	echo "$LIBFILE exists, nothing to do here"
	echo "If you wish to force a recompile, delete it"
	exit 0
fi

mkdir -p build
cd build

# we must set both build and host, so that the configure
# script will set cross_compile=yes, so that it
# won't attempt to run the a.out files
if [ ! -f config.status ]
then
	echo "Configuring GMP..."
	$GMP/configure --with-pic --build=x86-none-linux --host=armv5-eabi-linux || exit 1
fi

echo "Building GMP..."
make || exit 1

export JAVA_HOME=$(dirname $(dirname $(realpath $(which javac))))
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "Cannot find jni.h! Looked in '$JAVA_HOME/include/jni.h'"
    echo "Please set JAVA_HOME to a java home that has the JNI"
    exit 1
fi

COMPILEFLAGS="-fPIC -Wall"
INCLUDES="-I. -I$JBIGI/jbigi/include -I$JAVA_HOME/include -I$JAVA_HOME/include/linux"
LINKFLAGS="-shared -Wl,-soname,libjbigi.so,--fix-cortex-a8"

echo "Building jbigi lib that is statically linked to GMP"
STATICLIBS=".libs/libgmp.a"

echo "Compiling C code..."
rm -f jbigi.o $LIBFILE
echo "$CC -c $COMPILEFLAGS $INCLUDES $JBIGI/jbigi/src/jbigi.c"
$CC -c $COMPILEFLAGS $INCLUDES $JBIGI/jbigi/src/jbigi.c || exit 1
echo "$CC $LINKFLAGS $INCLUDES $INCLUDELIBS -o $LIBFILE jbigi.o $STATICLIBS"
$CC $LINKFLAGS $INCLUDES $INCLUDELIBS -o $LIBFILE jbigi.o $STATICLIBS || exit 1

ls -l $LIBFILE || exit 1


echo 'Built successfully'
