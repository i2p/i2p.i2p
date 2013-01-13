Wrapper build instructions (Ubuntu):

export ANT_HOME=/usr/share/ant
export JAVA_HOME=/usr/lib/jvm/java-6-openjdk
cp src/c/Makefile-linux-x86-32.make src/c/Makefile-linux-arm-32.make
build32.sh
strip lib/libwrapper.so bin/wrapper
