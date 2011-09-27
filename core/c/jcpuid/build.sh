#!/bin/sh

cd `dirname $0`

case `uname -sr` in
    MINGW*|CYGWIN*)
        echo "Building windows .dlls";;
    SunOS*)
        echo "Building solaris .sos";;
    Darwin*)
        echo "Building Darwin jnilibs";;
    Linux*|NetBSD*|OpenBSD*|*FreeBSD*)
        echo "Building `uname -s |tr [A-Z] [a-z]` .sos";;
    *)
        echo "Unsupported build environment"
        exit;;
esac

rm -rf lib
mkdir -p lib/freenet/support/CPUInformation

CC="gcc"

case `uname -sr` in
    MINGW*|CYGWIN*)
        JAVA_HOME="/c/software/j2sdk1.4.2_05"
        COMPILEFLAGS="-Wall"
        INCLUDES="-I. -Iinclude -I${JAVA_HOME}/include/ -I${JAVA_HOME}/include/win32/"
        LINKFLAGS="-shared -static -static-libgcc -Wl,--kill-at"
        LIBFILE="lib/freenet/support/CPUInformation/jcpuid-x86-windows.dll";;
    Darwin*)
        JAVA_HOME=$(/usr/libexec/java_home)
        COMPILEFLAGS="-fPIC -Wall -arch x86_64 -arch i386"
        INCLUDES="-I. -Iinclude -I${JAVA_HOME}/include/"
        LINKFLAGS="-dynamiclib -framework JavaVM"
        LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86-darwin.jnilib";;
    Linux*|OpenBSD*|NetBSD*|*FreeBSD*|SunOS*)
        UNIXTYPE="`uname -s | tr [A-Z] [a-z]`"
        if [ ${UNIXTYPE} = "sunos" ]; then
            UNIXTYPE="solaris"
        elif [ ${UNIXTYPE} = "kfreebsd" ]; then
            UNIXTYPE="linux"
        fi

        # If JAVA_HOME is set elsewhere, obey it. Otherwise we'll try to
        # deduce its location ourselves.
        if [ -z "${JAVA_HOME}" ]; then
            if [ ${UNIXTYPE} = "freebsd" ]; then
                if [ -d /usr/local/openjdk6 ]; then
                    JAVA_HOME="/usr/local/openjdk6"
                elif [ -d /usr/local/openjdk7 ]; then
                    JAVA_HOME="/usr/local/openjdk7"
                fi
            elif [ ${UNIXTYPE} = "openbsd" ]; then # The default in 4.9
                if [ -d /usr/local/jdk-1.7.0 ]; then
                    JAVA_HOME="/usr/local/jdk-1.7.0"
                fi
            elif [ ${UNIXTYPE} = "netbsd" ]; then
                if [ -d /usr/pkg/java/openjdk7 ]; then
                    JAVA_HOME="/usr/pkg/java/openjdk7"
                fi
            elif [ ${UNIXTYPE} = "linux" -a -e /etc/debian_version ]; then
                if [ -d /usr/lib/jvm/default-java ]; then
                    JAVA_HOME="/usr/lib/jvm/default-java"
                fi
            fi
        fi
        case `uname -m` in
            x86_64*|amd64)
                ARCH="x86_64";;
            ia64*)
                ARCH="ia64";;
            i?86*)
                ARCH="x86";;
            *)
                echo "Unsupported build environment. jcpuid is only used on x86 systems."
                exit 1;;
        esac

        # JAVA_HOME being set doesn't guarantee that it's usable
        if [ ! -r ${JAVA_HOME}/include/jni.h ]; then
            echo "Please ensure you have a Java SDK installed"
            echo "and/or set JAVA_HOME then re-run this script."
            exit 1
        fi
        LINKFLAGS="-shared -Wl,-soname,libjcpuid-${ARCH}-${UNIXTYPE}.so"
        LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-${ARCH}-${UNIXTYPE}.so"
        COMPILEFLAGS="-fPIC -Wall"
        INCLUDES="-I. -Iinclude -I${JAVA_HOME}/include -I${JAVA_HOME}/include/${UNIXTYPE}";;
esac

echo "Compiling C code..."
rm -f ${LIBFILE}
${CC} ${COMPILEFLAGS} ${LINKFLAGS} ${INCLUDES} src/*.c -o ${LIBFILE} || (echo "Failed to compile ${LIBFILE}"; exit 1)
strip ${LIBFILE} || (echo "Failed to strip ${LIBFILE}" ; exit 1)
echo Built `dirname $0`/${LIBFILE}
