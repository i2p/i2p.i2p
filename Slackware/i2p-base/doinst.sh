#!/bin/sh
#
#
# Now in the future we only need to look for '#I2P' and '#/I2P' 
# for modifications to rc.local and rc.local_shutdown. 
# I was a moron for not doing it this way in the first place :-) -- Sponge
#
#

touch /etc/rc.d/rc.local
touch /etc/rc.d/rc.local_shutdown

echo
echo -n "Check 1: /etc/rc.d/rc.local "
I2PRCA=`grep -c /etc/rc.d/rc.local -e '/etc/rc.d/rc.i2p'`

if [ $I2PRCA -eq 0 ] ; then
	echo '#I2P' >> /etc/rc.d/rc.local
	echo '( cd /tmp ; rm -Rf i2p-*.tmp )' >> /etc/rc.d/rc.local
	echo "if [ -x /etc/rc.d/rc.i2p ] ; then" >> /etc/rc.d/rc.local
	echo "        sh /etc/rc.d/rc.i2p start" >> /etc/rc.d/rc.local
	echo "fi" >> /etc/rc.d/rc.local
	echo '#/I2P' >> /etc/rc.d/rc.local
	echo "modified."
else
	echo -n "looks OK so far,"
	# Fix old installs, or where people have modified.

	echo -n " Check 1A: "
	I2PRCC=`grep -c /etc/rc.d/rc.local -e 'i2p-\*\.tmp'`

	if [ $I2PRCC -eq 0 ] ; then
		DATA=$(cat /etc/rc.d/rc.local | sed -re 's/if \[ -x \/etc\/rc\.d\/rc\.i2p \] ; then/#I2P\n\( cd \/tmp ; rm -Rf i2p-*.tmp \)\nif \[ -x \/etc\/rc.d\/rc.i2p \] ; then/')
		echo "${DATA}" > /etc/rc.d/rc.local
		echo -n "additional modifications applied,"
	else
		echo -n "looks OK so far,"
	fi

	echo -n " Check 1B: "
	I2PRCE=`grep -c /etc/rc.d/rc.local -e 'i2p-\*\.tmp'`
	if [ $I2PRCE -eq 0 ] ; then
		DATATOP=$(cat /etc/rc.d/rc.local | sed -n '0,/i2p-\*\.tmp/p' | sed '$d' )
		DATABOT=$(cat /etc/rc.d/rc.local | sed -n '/i2p-\*\.tmp/,$p' | sed -n '/^fi/,$p' | sed "1d")
		echo "${DATATOP}" > /etc/rc.d/rc.local
		echo '#I2P' >> /etc/rc.d/rc.local
		echo '( cd /tmp ; rm -Rf i2p-*.tmp )' >> /etc/rc.d/rc.local
		echo "if [ -x /etc/rc.d/rc.i2p ] ; then" >> /etc/rc.d/rc.local
		echo "        sh /etc/rc.d/rc.i2p start" >> /etc/rc.d/rc.local
		echo "fi" >> /etc/rc.d/rc.local
		echo '#/I2P' >> /etc/rc.d/rc.local
		echo "${DATABOT}" >> /etc/rc.d/rc.local
		
		echo -n "additional modifications applied,"
	else
		echo -n "looks ok so far,"
	fi
	echo -n " Check 1C: "
	I2PRCF=`grep -c /etc/rc.d/rc.local -e '#/I2P'`
	if [ $I2PRCF -eq 0 ] ; then
		DATATOP=$(cat /etc/rc.d/rc.local | sed -n '0,/^#I2P/p' | sed '$d' )
		DATABOT=$(cat /etc/rc.d/rc.local | sed -n '/^#I2P/,$p' | sed -n '/^fi/,$p' | sed "1d")
		echo "${DATATOP}" > /etc/rc.d/rc.local
		echo '#I2P' >> /etc/rc.d/rc.local
		echo '( cd /tmp ; rm -Rf i2p-*.tmp )' >> /etc/rc.d/rc.local
		echo "if [ -x /etc/rc.d/rc.i2p ] ; then" >> /etc/rc.d/rc.local
		echo "        sh /etc/rc.d/rc.i2p start" >> /etc/rc.d/rc.local
		echo "fi" >> /etc/rc.d/rc.local
		echo '#/I2P' >> /etc/rc.d/rc.local
		echo "${DATABOT}" >> /etc/rc.d/rc.local

		echo -n "additional modifications applied,"
	else
		echo -n "looks ok so far,"
	fi
	echo " Done."
fi

echo -n "Check 2: /etc/rc.d/rc.local_shutdown "
I2PRCB=`grep -c /etc/rc.d/rc.local_shutdown -e '/etc/rc.d/rc.i2p'`
if [ $I2PRCB -eq 0 ] ; then
	echo "#I2P" >> /etc/rc.d/rc.local_shutdown
	echo "if [ -x /etc/rc.d/rc.i2p ] ; then" >> /etc/rc.d/rc.local_shutdown
	echo "        sh /etc/rc.d/rc.i2p stop" >> /etc/rc.d/rc.local_shutdown
	echo "fi" >> /etc/rc.d/rc.local_shutdown
	echo "#/I2P" >> /etc/rc.d/rc.local_shutdown
	echo "modified."
else
	echo -n "looks OK so far,"
	# Fix old installs

	echo -n " Check 1A: "
	I2PRCG=`grep -c /etc/rc.d/rc.local_shutdown -e '#I2P'`
	if [ $I2PRCG -eq 0 ] ; then
		DATATOP=$(cat /etc/rc.d/rc.local_shutdown | sed -n '0,/^if \[ -x \/etc\/rc\.d\/rc\.i2p \] ; then/p' | sed '$d' )
		DATABOT=$(cat /etc/rc.d/rc.local_shutdown | sed -n '/^if \[ -x \/etc\/rc\.d\/rc\.i2p \] ; then/,$p' | sed -n '/^fi/,$p' | sed "1d")
		echo "${DATATOP}" > /etc/rc.d/rc.local_shutdown
		echo '#I2P' >> /etc/rc.d/rc.local_shutdown
		echo "if [ -x /etc/rc.d/rc.i2p ] ; then" >> /etc/rc.d/rc.local_shutdown
		echo "        sh /etc/rc.d/rc.i2p stop" >> /etc/rc.d/rc.local_shutdown
		echo "fi" >> /etc/rc.d/rc.local_shutdown
		echo "#/I2P" >> /etc/rc.d/rc.local_shutdown
		echo "${DATABOT}" >> /etc/rc.d/rc.local_shutdown
		echo -n "additional modifications applied,"
	else
		echo -n "looks OK so far,"
	fi
	echo -n " Check 1B: "
	I2PRCH=`grep -c /etc/rc.d/rc.local_shutdown -e '#/I2P'`
	if [ $I2PRCH -eq 0 ] ; then
		DATATOP=$(cat /etc/rc.d/rc.local_shutdown | sed -n '0,/^#I2P/p' | sed '$d' )
		DATABOT=$(cat /etc/rc.d/rc.local_shutdown | sed -n '/^#I2P/,$p' | sed -n '/^fi/,$p' | sed "1d")
		echo "${DATATOP}" > /etc/rc.d/rc.local_shutdown
		echo '#I2P' >> /etc/rc.d/rc.local_shutdown
		echo "if [ -x /etc/rc.d/rc.i2p ] ; then" >> /etc/rc.d/rc.local_shutdown
		echo "        sh /etc/rc.d/rc.i2p stop" >> /etc/rc.d/rc.local_shutdown
		echo "fi" >> /etc/rc.d/rc.local_shutdown
		echo "#/I2P" >> /etc/rc.d/rc.local_shutdown
		echo "${DATABOT}" >> /etc/rc.d/rc.local_shutdown
		echo -n "additional modifications applied,"
	else
		echo -n "looks OK so far,"
	fi
	echo " Done."
fi

if [ -f /etc/rc.d/rc.i2p ] ; then
	if [ -x /etc/rc.d/rc.i2p ] ; then
		chmod +x /etc/rc.d/rc.i2p.new
	fi
	# Hopefully get admin's attention.
	echo -ne "\007" ; sleep 0.3
	echo -ne "\007" ; sleep 0.3
	echo -ne "\007" ; sleep 0.3
	echo -ne "\007" ; sleep 0.3
	echo -ne "\007" ; sleep 0.3
	echo -ne "\007" ; sleep 0.3
	echo -e "\007" ; sleep 0.3
	echo "It apears that you already have /etc/rc.d/rc.i2p"
	echo "You should replace it with /etc/rc.d/rc.i2p.new as soon as possible"
	echo -ne "\007" ; sleep 0.3
	echo -ne "\007" ; sleep 0.3
	echo -ne "\007" ; sleep 0.3
	echo -ne "\007" ; sleep 0.3
	echo -ne "\007" ; sleep 0.3
	echo -ne "\007" ; sleep 0.3
	echo -e "\007" ; sleep 0.3
else
	mv /etc/rc.d/rc.i2p.new /etc/rc.d/rc.i2p
	echo
	echo "Installation finished. The i2p start/stop script has been"
	echo "installed in /etc/rc.d . You should chmod +x"
	echo '/etc/rc.d/rc.i2p to start it on boot.'
	echo
fi

exit
