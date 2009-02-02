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
sh postinstall.sh || (
  echo "ERROR: failed execution of postinstall.sh. Please"
  echo "cd into i2p installation directory and run "
  echo "postinstall.sh manually with ./postinstall.sh"
  exit 1
)

sleep 10

sh i2prouter stop || exit 1

echo
echo "Installation finished."
echo

exit
