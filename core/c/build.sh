#!/bin/sh
# linux settings:
CC="gcc"
ANT="ant"
JAVA="java"

COMPILEFLAGS="-fPIC -Wall"
LINKFLAGS="-shared -Wl,-soname,libjbigi.so"

INCLUDES="-Iinclude -I$JAVA_HOME/include -I$JAVA_HOME/include/linux"
INCLUDELIBS="-lgmp"
STATICLIBS=""

LIBFILE="libjbigi.so"

# jrandom's mingw setup:
#COMPILEFLAGS="-Wall"
#INCLUDES="-Iinclude -Ic:/software/j2sdk1.4.2/include/win32/ -Ic:/software/j2sdk1.4.2/include/ -Ic:/dev/gmp-4.1.2/"
#LINKFLAGS="-shared -Wl,--kill-at"
#LIBFILE="jbigi.dll"
#INCLUDELIBS=""
#STATICLIBS="libgmp.a"

echo "Compiling C code..."
rm -f jbigi.o $LIBFILE
$CC -c $COMPILEFLAGS $INCLUDES src/jbigi.c
$CC $LINKFLAGS $INCLUDES $INCLUDELIBS -o $LIBFILE jbigi.o $STATICLIBS

echo ""
echo "Doing an ant build..."
(cd ../java/ ; $ANT build)

echo ""
echo "Built, now testing... This will take a while."
LD_LIBRARY_PATH=. $JAVA -cp ../java/build/i2p.jar -DloggerConfigLocation=../../installer/java/src/logger.config.template net.i2p.util.NativeBigInteger


echo ""
echo ""
echo "Test complete. Please review the lines 'native run time:', 'java run time:', and 'native = '"
