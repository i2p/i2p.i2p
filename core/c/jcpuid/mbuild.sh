#/bin/sh

case `uname -sr` in
MINGW*)
	echo "Building windows .dll's";;
CYGWIN*)
	echo "Building windows .dll's";;
Linux*)
	echo "Building linux .so's";;
FreeBSD*)
	echo "Building freebsd .so's";;
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
FreeBSD*)
	case `uname -m` in
		amd64)
			LINKFLAGS="-shared -Wl,-soname,libjcpuid-x86_64-freebsd.so"
			LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86_64-freebsd.so";;
		i?86*)
			LINKFLAGS="-shared -Wl,-soname,libjcpuid-x86-freebsd.so"
			LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86-freebsd.so";;
		*)
			echo "Unknown build environment"
			exit;;
	esac
	COMPILEFLAGS="-fPIC -Wall"
	INCLUDES="-I. -Iinclude -I$JAVA_HOME/include/ -I$JAVA_HOME/include/freebsd/";;
Linux*)
	case `uname -m` in
		x86_64*)
			LINKFLAGS="-shared -Wl,-soname,libjcpuid-x86_64-linux.so"
			LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86_64-linux.so";;
		ia64*)
			LINKFLAGS="-shared -Wl,-soname,libjcpuid-x86-linux.so"
			LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-ia64-linux.so";;
		i?86*)
			LINKFLAGS="-shared -Wl,-soname,libjcpuid-x86-linux.so"
			LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86-linux.so";;
		*)
			echo "Unsupported build environment"
			exit;;
	esac
	COMPILEFLAGS="-fPIC -Wall"
	INCLUDES="-I. -Iinclude -I$JAVA_HOME/include -I$JAVA_HOME/include/linux";;

esac

echo "Compiling C code..."
rm -f $LIBFILE
$CC $COMPILEFLAGS $LINKFLAGS $INCLUDES src/*.c -o $LIBFILE
strip $LIBFILE
echo Built $LIBFILE

#g++ -shared -static -static-libgcc -Iinclude -I$JAVA_HOME/include \
#    -I$JAVA_HOME/include/linux src/*.cpp \
#    -o lib/freenet/support/CPUInformation/libjcpuid-x86-linux.so
