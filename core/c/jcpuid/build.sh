#!/bin/sh

cd `dirname $0`
rm -rf lib
mkdir -p lib/freenet/support/CPUInformation

[ -z $CC_PREFIX ] && CC_PREFIX=""
[ -z $TARGET ] && TARGET="$(uname -s)"
[ -z $HOST ] && HOST="$(uname -s | tr '[:upper:]' '[:lower:]')"

case $TARGET in
    MINGW*|CYGWIN*|windows*)
        echo "Building windows .dlls";;
    SunOS*)
        echo "Building solaris .sos";;
    Darwin*)
        echo "Building Darwin jnilibs";;
    Linux*|NetBSD*|OpenBSD*|*FreeBSD*)
        echo "Building `uname -s |tr [A-Z] [a-z]` .sos";;
    *)
        echo "Unsupported build environment"
        exit 1;;
esac


if [ -z $BITS ]; then
  UNAME="$(uname -m)"
  if test "${UNAME#*x86_64}" != "$UNAME"; then
    BITS=64
  elif test "${UNAME#*i386}" != "$UNAME"; then
    BITS=32
  elif test "${UNAME#*i686}" != "$UNAME"; then
    BITS=32
  elif test "${UNAME#*armv6}" != "$UNAME"; then
    BITS=32
  elif test "${UNAME#*armv7}" != "$UNAME"; then
    BITS=32
  elif test "${UNAME#*aarch32}" != "$UNAME"; then
    BITS=32
  elif test "${UNAME#*aarch64}" != "$UNAME"; then
    BITS=64
  else
 
    echo "Unable to detect default setting for BITS variable"
    exit 1
  fi

  printf "BITS variable not set, $BITS bit system detected\n" >&2
fi


if [ -z $CC ]; then
  export CC="gcc"
  printf "CC variable not set, defaulting to $CC\n" >&2
fi


# Debian builds are presumed to be native, we don't need the -mxx flag unless cross-compile,
# and this breaks the x32 build
if [ -z "$DEBIANVERSION" ] ; then
    if [ $BITS -eq 32 ]; then
      export ABI=32
      export CFLAGS="-m32 -mtune=i686 -march=i686"
      export LDFLAGS="-m32"
    elif [ $BITS -eq 64 ]; then
      export ABI=64
      export CFLAGS="-m64 -mtune=generic"
      export LDFLAGS="-m64"
    else
      printf "BITS value \"$BITS\" not valid, please select 32 or 64\n" >&2
      exit 1
    fi
fi

[ -z $ARCH ] && case `uname -m` in
    x86_64*|amd64)
        if [ $BITS -eq 64 ]; then
          ARCH="x86_64"
        else
          ARCH="x86"
        fi
        ;;
    ia64*)
        ARCH="ia64";;
    i?86*)
        ARCH="x86";;
    # Solaris x86
    i86pc)
        if [ $BITS -eq 64 ]; then
          ARCH="x86_64"
        else
          ARCH="x86"
        fi
        ;;
    *)
        echo "Unsupported build environment. jcpuid is only used on x86 systems."
        exit 0;;
esac


case $TARGET in
    MINGW*|CYGWIN*|windows*)
        [ -z $JAVA_HOME ] && JAVA_HOME="/c/software/j2sdk1.4.2_05"
        CFLAGS="${CFLAGS} -Wall"
        INCLUDES="-I. -Iinclude -I${JAVA_HOME}/include/ -I${JAVA_HOME}/include/$HOST/"
        LDFLAGS="${LDFLAGS} -shared -static -static-libgcc -Wl,--kill-at"
        LIBFILE="lib/freenet/support/CPUInformation/jcpuid-${ARCH}-windows.dll";;
    Darwin*)
        JAVA_HOME=$(/usr/libexec/java_home)
        CFLAGS="${CFLAGS} -fPIC -Wall -arch x86_64 -arch i386"
        INCLUDES="-I. -Iinclude -I${JAVA_HOME}/include/ -I${JAVA_HOME}/include/darwin/"
        LDFLAGS="${LDFLAGS} -dynamiclib -framework JavaVM"
        LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86_64-osx.jnilib";;
    Linux*|OpenBSD*|NetBSD*|*FreeBSD*|SunOS*)
        KFREEBSD=0
        UNIXTYPE="`uname -s | tr [A-Z] [a-z]`"
        if [ ${UNIXTYPE} = "sunos" ]; then
            UNIXTYPE="solaris"
        elif [ ${UNIXTYPE} = "gnu/kfreebsd" ]; then
            UNIXTYPE="linux"
            KFREEBSD=1
        fi
        # If JAVA_HOME isn't set, try to figure it out on our own
        [ -z $JAVA_HOME ] && . ../find-java-home
        # JAVA_HOME being set doesn't guarantee that it's usable
        if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
            echo "Please ensure you have a Java SDK installed" >&2
            echo "and/or set JAVA_HOME then re-run this script." >&2
            exit 1
        fi

        # rename jcpuid, formerly 0003-rename-jcpuid.patch
        if [ "$DEBIANVERSION" ] ; then
            LDFLAGS="${LDFLAGS} -shared -Wl,-soname,libjcpuid.so"
            LIBFILE="../jbigi/libjcpuid.so"
        else
            LDFLAGS="${LDFLAGS} -shared -Wl,-soname,libjcpuid-${ARCH}-${UNIXTYPE}.so"
            if [ $KFREEBSD -eq 1 ]; then
                LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-${ARCH}-kfreebsd.so"
            else
                LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-${ARCH}-${UNIXTYPE}.so"
            fi
        fi
        CFLAGS="${CFLAGS} -fPIC -Wall"
        INCLUDES="-I. -Iinclude -I${JAVA_HOME}/include -I${JAVA_HOME}/include/${UNIXTYPE}";;
esac

echo "CC_PREFIX:$CC_PREFIX"
echo "TARGET:$TARGET"
echo "HOST:$HOST"
echo "ARCH:$ARCH"
echo "CFLAGS:$CFLAGS"
echo "LDFLAGS:$LDFLAGS"
echo ""

echo "Compiling C code..."
rm -f ${LIBFILE}
${CC_PREFIX}${CC} ${CFLAGS} ${LDFLAGS} ${INCLUDES} src/*.c -o ${LIBFILE} || (echo "Failed to compile ${LIBFILE}"; exit 1)
${CC_PREFIX}strip ${LIBFILE} || (echo "Failed to strip ${LIBFILE}" ; exit 1)
echo Built `dirname $0`/${LIBFILE}
