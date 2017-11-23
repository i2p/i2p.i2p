#
# copy the files out of the unzipped delta pack
#
V=3.5.34
D=wrapper-delta-pack-$V
B=$D/bin
L=$D/lib

cp $L/wrapper.jar all

cp $L/libwrapper-freebsd-x86-32.so freebsd/libwrapper.so
cp $L/libwrapper-freebsd-x86-64.so freebsd64/libwrapper.so
cp $L/libwrapper-linux-x86-32.so linux/libwrapper.so
cp $L/libwrapper-linux-x86-64.so linux64/libwrapper.so
cp $L/libwrapper-linux-ppcbe-32.so linux-ppc/libwrapper.so
cp $L/libwrapper-linux-armel-32.so linux-armv5/libwrapper.so
cp $L/libwrapper-solaris-sparc-32.so solaris/libwrapper.so
cp $L/libwrapper-macosx-universal-32.jnilib macosx/libwrapper-macosx-universal-32.jnilib
cp $L/libwrapper-macosx-universal-64.jnilib macosx/libwrapper-macosx-universal-64.jnilib

cp $B/wrapper-freebsd-x86-32 freebsd/i2psvc
cp $B/wrapper-freebsd-x86-64 freebsd64/i2psvc
cp $B/wrapper-linux-x86-32 linux/i2psvc
cp $B/wrapper-linux-x86-64 linux64/i2psvc
cp $B/wrapper-linux-ppcbe-32 linux-ppc/i2psvc
cp $B/wrapper-linux-armel-32 linux-armv5/i2psvc
cp $B/wrapper-solaris-sparc-32 solaris/i2psvc
cp $B/wrapper-macosx-universal-32 macosx/i2psvc-macosx-universal-32
cp $B/wrapper-macosx-universal-64 macosx/i2psvc-macosx-universal-64

for i in freebsd freebsd64 linux linux64
do
	strip $i/i2psvc $i/libwrapper.so
	chmod -x $i/i2psvc $i/libwrapper.so
done

for i in linux-ppc linux-armv5 solaris
do
	chmod -x $i/i2psvc $i/libwrapper.so
done

echo 'Windows binaries not copied, see README.txt'
echo 'Now compile the armv6 binaries on a Raspberry Pi, see linux-armv6/README.txt'
