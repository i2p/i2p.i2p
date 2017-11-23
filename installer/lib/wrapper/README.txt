Wrapper update instructions:

Get the community "delta pack" from http://wrapper.tanukisoftware.com/doc/english/download.jsp

Get the source from http://wrapper.tanukisoftware.com/downloads/

From the delta pack, copy lib/wrapper.jar to all/

From the delta pack, strip the binaries in lib/
(if you have the tools to do so for that architecture) and copy
to xxx/librapper.so, libwrapper.jnilib, or wrapper.dll for the following directories.
Don't forget to disable the execute bit.
	freebsd (x86-32)
	freebsd64 (x86-64)
	linux (x86-32)
	linux64 (x86-64)
	linux-ppc (linux-ppc-32)
	linux-armv5 (armv5/armv7)
	solaris (sparc-32)

From the delta pack, strip the binaries in bin/ (if needed) and copy
to xxx/i2psvc for the same directories as above.
Don't forget to disable the execute bit.

For armv6, build from source following the instructions
in linux-armv6/README.txt.
Don't forget to strip the binaries and disable the execute bit.

For macosx, combine (if possible) the universal-32 and universal-64 files
from the delta pack (each is a 2-architecture fat file)
into a "quad-fat" binary. Instructions can be found in
macosx/README.txt

IF you have the ability to build for Windows,
build from source following the instructions
in win64/README-x64-win.txt.
Don't forget to strip the binaries and disable the execute bit.
Delete the old win-all/wrapper.jar, and change the top level
build.xml to use the all/wrapper.jar for the Windows installer.

If you DON'T have the ability to build for Windows,
leave the files in win32, win64, and win-all unchanged.
The Windows installer build in the top-level build.xml
will use the win-all/wrapper.jar instead of all/wrapper.jar.
