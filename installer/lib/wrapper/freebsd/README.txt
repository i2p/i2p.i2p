Basic instructions for BSD
--------------------------

Prerequisites for compiling the wrapper can be installed with:
	pkg_add -r apache-ant gmake openjdk6

One the prereqs are installed, cd into the wrapper source and run

For 32bit:
	ant -Dbits=32 compile-c-unix

For 64bit:
	ant -Dbits=64 compile-c-unix

Omit "compile-c-unix" from the command-lines if you want to compile
wrapper.jar too.

Then strip the binaries:
	strip --strip-unneeded bin/wrapper lib/libwrapper.so

...and turn off the executable bit:
	chmod 644 bin/wrapper lib/libwrapper.so

Don't forget to rename the binary "wrapper" to "i2psvc".
