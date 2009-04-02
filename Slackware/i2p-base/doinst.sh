#!/bin/sh
touch /etc/rc.d/rc.local
touch /etc/rc.d/rc.local_shutdown

I2PRCA=`grep -c /etc/rc.d/rc.local -e i2p`
I2PRCB=`grep -c /etc/rc.d/rc.local_shutdown -e i2p`

echo

if [ $I2PRCA -eq 0 ] ; then
	echo "if [ -x /etc/rc.d/rc.i2p ] ; then" >> /etc/rc.d/rc.local
	echo "        sh /etc/rc.d/rc.i2p start" >> /etc/rc.d/rc.local
	echo "fi" >> /etc/rc.d/rc.local
	echo "/etc/rc.d/rc.local modified."
else
	echo "/etc/rc.d/rc.local looks OK"
fi

if [ $I2PRCB -eq 0 ] ; then
	echo "if [ -x /etc/rc.d/rc.i2p ] ; then" >> /etc/rc.d/rc.local_shutdown
	echo "        sh /etc/rc.d/rc.i2p stop" >> /etc/rc.d/rc.local_shutdown
	echo "fi" >> /etc/rc.d/rc.local_shutdown
	echo "/etc/rc.d/rc.local_shutdown modified."
else
	echo "/etc/rc.d/rc.local_shutdown looks OK"
fi

if [ -f /etc/rc.d/rc.i2p ] ; then
	if [ -x /etc/rc.d/rc.i2p ] ; then
		chmod +x /etc/rc.d/rc.i2p.new
	fi
	echo
	echo "It apears that you already have /etc/rc.d/rc.i2p"
	echo "You may wish to replace it with /etc/rc.d/rc.i2p.new"
	echo 
else
	mv /etc/rc.d/rc.i2p.new /etc/rc.d/rc.i2p
	echo
	echo "Installation finished. The i2p start/stop script has been"
	echo "installed on /etc/rc.d directory. You should chmod +x"
	echo '/etc/rc.d/rc.i2p to start it on boot.'
	echo
fi

exit
