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
*)
	echo "Unsupported build environment"
	exit;;
esac

rm -rf lib
mkdir lib
mkdir lib/freenet
mkdir lib/freenet/support
mkdir lib/freenet/support/CPUInformation

CPP="g++"

case `uname -sr` in
MINGW*)
	JAVA_HOME="/c/software/j2sdk1.4.2_05"
	COMPILEFLAGS="-Wall"
	INCLUDES="-I. -Iinclude -I$JAVA_HOME/include/ -I$JAVA_HOME/include/win32/"
	LINKFLAGS="-shared -static -static-libgcc -Wl,--kill-at"
	LIBFILE="lib/freenet/support/CPUInformation/jcpuid-x86-windows.dll";;
FreeBSD*)
	COMPILEFLAGS="-Wall"
	INCLUDES="-I. -Iinclude -I$JAVA_HOME/include/ -I$JAVA_HOME/include/freebsd/"
	LINKFLAGS="-shared -static -Wl,-soname,libjcpuid-x86-freebsd.so"
	LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86-freebsd.so";;
Linux*)
	COMPILEFLAGS="-fPIC -Wall"
	INCLUDES="-I. -Iinclude -I$JAVA_HOME/include -I$JAVA_HOME/include/linux"
	LINKFLAGS="-shared -static -static-libgcc -Wl,-soname,libjcpuid-x86-linux.so"
	LIBFILE="lib/freenet/support/CPUInformation/libjcpuid-x86-linux.so";;
esac

echo "Compiling C code..."
rm -f $LIBFILE
$CPP $LINKFLAGS $INCLUDES src/*.cpp -o $LIBFILE
strip $LIBFILE
echo Built $LIBFILE

#g++ -shared -static -static-libgcc -Iinclude -I$JAVA_HOME/include \
#    -I$JAVA_HOME/include/linux src/*.cpp \
#    -o lib/freenet/support/CPUInformation/libjcpuid-x86-linux.so
