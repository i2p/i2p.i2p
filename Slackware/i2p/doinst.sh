#!/bin/sh

INST_DIR=directory

( cd install

echo
for i in *.config ; {
	if [ -f $INST_DIR/$i ] ; then
		echo "Please check $i, as there is a new version."
		cp $i $INST_DIR/$i.new
	else
		cp $i $INST_DIR/$i
	fi
}

)
echo
echo "FINISHING I2P INSTALLATION. PLEASE WAIT."

cd $INST_DIR
sh postinstall.sh || (
  echo "ERROR: failed execution of postinstall.sh. Please"
  echo "cd into i2p installation directory and run "
  echo "postinstall.sh manually with ./postinstall.sh"
  echo "It is also reccomended to set router.blocklist.enable=true "
  echo "in the router.config file."
  exit 1
)

sleep 10

sh i2prouter stop || exit 1

echo
echo "Installation finished."
echo

exit
