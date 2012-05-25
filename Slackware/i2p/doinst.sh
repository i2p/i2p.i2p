#!/bin/sh

INST_DIR=directory

( cd install

echo
for i in *.config ; {
	if [ -f $INST_DIR/$i ] ; then
		echo "Please check ${INST_DIR}${i}, as there is a new version."
		cp $i $INST_DIR/$i.new
	else
		cp $i $INST_DIR/$i
	fi
}

)

( cd $INST_DIR
	if [ -f blocklist.txt ] ; then
		echo "Please check ${INST_DIR}blocklist.txt, as there is a new version."
	else
		mv blocklist.txt.new blocklist.txt
	fi
)

( cd $INST_DIR/eepsite
	if [ -f jetty.xml ] ; then
		rm jetty.xml.new
	else
		mv jetty.xml.new jetty.xml
	fi
)

( cd $INST_DIR/eepsite/docroot
	if [ -f index.html ] ; then
		rm index.html.new
	else
		mv index.html.new index.html
	fi
	if [ -f favicon.ico ] ; then
		rm favicon.ico.new
	else
		mv favicon.ico.new favicon.ico
	fi
)

echo
echo "FINISHING I2P INSTALLATION. PLEASE WAIT."

cd $INST_DIR



OS_ARCH=`uname -m`
X86_64=`echo "$OS_ARCH" | grep x86_64`
if [ "X$X86_64" = "X" ]; then
        wrapperpath="./lib/wrapper/linux"
else
        wrapperpath="./lib/wrapper/linux64"
fi
cp $wrapperpath/libwrapper.so ./lib/
cp $wrapperpath/wrapper.jar ./lib/
cp $wrapperpath/i2psvc .
rm -rf ./lib/wrapper
chmod 744 ./i2psvc

echo
echo "Installation finished."
echo

exit
