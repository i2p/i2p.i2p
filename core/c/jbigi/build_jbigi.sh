#!/bin/sh
# When executed in Mingw: Produces a jbigi.dll
# When executed in Linux/FreeBSD: Produces a libjbigi.so
# When executed in OSX: Produces a libjbigi.jnilib
CC="gcc"

# If JAVA_HOME isn't set we'll try to figure it out
[ -z $JAVA_HOME ] && . ./find-java-home
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "Cannot find jni.h! Looked in '$JAVA_HOME/include/jni.h'"
    echo "Please set JAVA_HOME to a java home that has the JNI"
    exit 1
fi

case `uname -s` in
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
        JAVA_HOME=$(/usr/libexec/java_home)
        COMPILEFLAGS="-fPIC -Wall"
        INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include"
        LINKFLAGS="-dynamiclib -framework JavaVM"
        LIBFILE="libjbigi.jnilib";;
SunOS*|OpenBSD*|NetBSD*|FreeBSD*|Linux*)
        UNIXTYPE=$(uname -s | tr "[A-Z]" "[a-z]")
        if [ $UNIXTYPE = "sunos" ]; then
            UNIXTYPE="solaris"
        fi
        COMPILEFLAGS="-fPIC -Wall"
        INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include -I$JAVA_HOME/include/${UNIXTYPE}"
        LINKFLAGS="-shared -Wl,-soname,libjbigi.so"
        LIBFILE="libjbigi.so";;
*)
        echo "Unsupported system type."
        exit 1;;
esac

if [ "$1" = "dynamic" ] ; then
        echo "Building a jbigi lib that is dynamically linked to GMP"
        LIBPATH="-L.libs"
        INCLUDELIBS="-lgmp"
else
        echo "Building a jbigi lib that is statically linked to GMP"
        STATICLIBS=".libs/libgmp.a"
fi

echo "Compiling C code..."
rm -f jbigi.o $LIBFILE
$CC -c $COMPILEFLAGS $INCLUDES ../../jbigi/src/jbigi.c || exit 1
$CC $LINKFLAGS $INCLUDES -o $LIBFILE jbigi.o $INCLUDELIBS $STATICLIBS || exit 1

exit 0
