OSX instructions
----------------

Make the wrapper "quadfat" using lipo:

	lipo -create wrapper-macosx-universal-32 wrapper-macosx-universal-64 -output i2psvc
	lipo -create libwrapper-macosx-universal-32.jnilib libwrapper-macosx-universal-64.jnilib -output libwrapper.jnilib

Then strip the wrapper:
	strip i2psvc

The jnilib file does not need to be stripped.
