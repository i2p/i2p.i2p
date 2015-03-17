OSX instructions
================

With access to an OSX box
-------------------------

Make the wrapper "quadfat" using lipo:

	lipo -create wrapper-macosx-universal-32 wrapper-macosx-universal-64 -output i2psvc
	lipo -create libwrapper-macosx-universal-32.jnilib libwrapper-macosx-universal-64.jnilib -output libwrapper.jnilib

Then strip the wrapper:
	strip i2psvc

The jnilib file does not need to be stripped.


Without access to an OSX box
----------------------------

If access to an OSX box isn't available, you can copy the osx binaries into this
folder, then rename "^wrapper*" to "i2psvc-*"

For example:
wrapper-macosx-universal-32 will be renamed to i2psvc-macosx-universal-32
wrapper-macosx-universal-64 will be renamed to i2psvc-macosx-universal-64

