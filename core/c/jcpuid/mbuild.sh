#/bin/sh

case `uname -sr` in
MINGW*)
	echo "Building windows .dlls";;
CYGWIN*)
	echo "Building windows .dlls";;
Linux*)
	echo "Building linux .sos";;
NetBSD*|OpenBSD*|FreeBSD*)
	echo "Building `uname -s |tr [A-Z] [a-z]` .sos";;
Darwin*)
	echo "Building OSX jnilibs";;
*)
	echo "Unsupported build environment"
	exit;;
esac

rm -rf lib
#mkdir lib
#mkdir lib/freenet
#mkdir lib/freenet/support
mkdir -p lib/freenet/support/CPUInformation

CC="gcc"

case `uname -sr` in
MINGW*)
	JAVA_HOME="/c/software/j2sdk1.4.2_05"
	COMPILEFLAGS="-Wall"
	INCLUDES="-I. -Iinclude -I$JAVA_HOME/include/ -I$JAVA_HOME/include/win32/"
	LINKFLAGS="-shared -static -static-libgcc -Wl,--kill-at"
	LIBFILE="lib/freenet/support/CPUInformation/jcpuid-x86-windows.dll";;
Darwin*)
        JAVA_HOME=$(/usr/libexec/java_home)
        COMPILEFLAGS="-fPIC -Wall -arch x86_64 -arch i386"
        INCLUDES="-I. -Iinclude -I$JAVA_HOME/include/"
        LINKFLAGS="-dynamiclib -framework JavaVM"
        LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86-darwin.jnilib";;
Linux*|OpenBSD*|NetBSD*|FreeBSD*|SunOS*)
        UNIXTYPE="`uname -s | tr [A-Z] [a-z]`"
        if [ $UNIXTYPE = "sunos" ]; then
                UNIXTYPE="solaris"
        elif [ $UNIXTYPE = "freebsd" ]; then
                if [ -d /usr/local/openjdk6 ]; then
                        JAVA_HOME="/usr/local/openjdk6"
                elif [ -d /usr/local/openjdk7 ]; then
                        JAVA_HOME="/usr/local/openjdk7"
                fi
	elif [ $UNIXTYPE = "openbsd" ]; then
		if [ -d /usr/local/jdk-1.7.0 ]; then
			JAVA_HOME="/usr/local/jdk-1.7.0"
		fi
	elif [ $UNIXTYPE = "netbsd" ]; then
		if [ -d /usr/pkg/java/openjdk7 ]; then
			JAVA_HOME="/usr/pkg/java/openjdk7"
		fi
	elif [ $UNIXTYPE = "linux" -a -e /etc/debian_version ]; then
		if [ -d /usr/lib/jvm/default-java ]; then
			JAVA_HOME="/usr/lib/jvm/default-java"
		fi
        fi
	case `uname -m` in
		x86_64*)
			LINKFLAGS="-shared -Wl,-soname,libjcpuid-x86_64-${UNIXTYPE}.so"
			LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86_64-${UNIXTYPE}.so";;
		ia64*)
			LINKFLAGS="-shared -Wl,-soname,libjcpuid-x86-${UNIXTYPE}.so"
			LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-ia64-${UNIXTYPE}.so";;
		i?86*)
			LINKFLAGS="-shared -Wl,-soname,libjcpuid-x86-${UNIXTYPE}.so"
			LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86-${UNIXTYPE}.so";;
		*)
			echo "Unsupported build environment"
			exit;;
	esac
	COMPILEFLAGS="-fPIC -Wall"
	INCLUDES="-I. -Iinclude -I$JAVA_HOME/include -I$JAVA_HOME/include/${UNIXTYPE}";;

esac

echo "Compiling C code..."
rm -f $LIBFILE
$CC $COMPILEFLAGS $LINKFLAGS $INCLUDES src/*.c -o $LIBFILE
strip $LIBFILE
echo Built $LIBFILE

#g++ -shared -static -static-libgcc -Iinclude -I$JAVA_HOME/include \
#    -I$JAVA_HOME/include/linux src/*.cpp \
#    -o lib/freenet/support/CPUInformation/libjcpuid-x86-linux.so
