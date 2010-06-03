#!/bin/sh
# When executed in Mingw: Produces an jbigi.dll
# When executed in Linux/FreeBSD: Produces an libjbigi.so
# Darwin produces libjbigi.jnilib, right?

CC="gcc"

case `uname -sr` in
MINGW*)
	JAVA_HOME="c:/software/j2sdk1.4.2_05"
	COMPILEFLAGS="-Wall"
	INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include/win32/ -I$JAVA_HOME/include/"
	LINKFLAGS="-shared -Wl,--kill-at"
	LIBFILE="jbigi.dll";;
CYGWIN*)
	JAVA_HOME="c:/software/j2sdk1.4.2_05"
	COMPILEFLAGS="-Wall -mno-cygwin"
	INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include/win32/ -I$JAVA_HOME/include/"
	LINKFLAGS="-shared -Wl,--kill-at"
	LIBFILE="jbigi.dll";;
Darwin*)
        JAVA_HOME="/Library/Java/Home"
        COMPILEFLAGS="-Wall"
        INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include"
        LINKFLAGS="-dynamiclib -framework JavaVM"
        LIBFILE="libjbigi.jnilib";;
*)
	COMPILEFLAGS="-fPIC -Wall"
	INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include -I$JAVA_HOME/include/linux"
	LINKFLAGS="-shared -Wl,-soname,libjbigi.so"
	LIBFILE="libjbigi.so";;
esac

if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "Cannot find jni.h! Looked in '$JAVA_HOME/include/jni.h'"
    echo "Please set JAVA_HOME to a java home that has the JNI"
    exit 1
fi

#To link dynamically to GMP (use libgmp.so or gmp.lib), uncomment the first line below
#To link statically to GMP, uncomment the second line below
# Bug!!! Quote *BOTH* or neither! --Sponge
if test "$1" = "dynamic"
then
	echo "Building jbigi lib that is dynamically linked to GMP" 
	LIBPATH="-L.libs"
	INCLUDELIBS="-lgmp"
else
	echo "Building jbigi lib that is statically linked to GMP"
	STATICLIBS=".libs/libgmp.a"
fi

echo "Compiling C code..."
rm -f jbigi.o $LIBFILE
$CC -c $COMPILEFLAGS $INCLUDES ../../jbigi/src/jbigi.c
$CC $LINKFLAGS $INCLUDES $INCLUDELIBS -o $LIBFILE jbigi.o $STATICLIBS
