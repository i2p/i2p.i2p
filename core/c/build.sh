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
#INCLUDES="-Iinclude -Ic:/software/j2sdk1.4.2/include/win32/ -Ic:/software/j2sdk1.4.2/include/ -Ic:/dev/gmp-4.1.2/"
#LINKFLAGS="-shared -Wl,--kill-at"
#LIBFILE="jbigi.dll"
#INCLUDELIBS=""
#STATICLIBS="c:/dev/libgmp.a"

rm -f jbigi.o $LIBFILE
$CC -c $COMPILEFLAGS $INCLUDES src/jbigi.c
$CC $LINKFLAGS $INCLUDES $INCLUDELIBS -o $LIBFILE jbigi.o $STATICLIBS

echo "built, now testing"
(cd ../java/src/ ; $ANT )
LD_LIBRARY_PATH=. $JAVA -cp ../java/src/i2p.jar -DloggerConfigLocation=../java/src/logger.config net.i2p.util.NativeBigInteger


echo ""
echo ""
echo "test complete.  please review the lines 'native run time:', 'java run time:', and 'native = '"
