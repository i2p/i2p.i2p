#!/usr/bin/env bash
# 2012, The I2P Project
# http://www.i2p2.de/
# This code is public domain.
# Author: Meeh

I2PDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
shfile="tmp$$.sh"

echo -n 'cat `pwd`/net.i2p.router.plist.template |' > `pwd`/$shfile
echo -n "sed 's#USERNAME#`whoami`#' " >> `pwd`/$shfile
echo -n '| sed "s#COMMAND#`pwd`/i2prouter#" | sed "s#I2PPATH#`pwd`/#" > /Library/LaunchDaemons/net.i2p.router.plist' >> `pwd`/$shfile
chmod +x `pwd`/$shfile
echo "Please enter your password for sudo privileges to install I2P as a launchd service"

sudo ./$shfile
sudo chown root:wheel /Library/LaunchDaemons/net.i2p.router.plist
sudo launchctl load -wF /Library/LaunchDaemons/net.i2p.router.plist
if [ $? == 0 ]; then
	echo "I2P Router wrapper was successfully installed as a launchd service."
else
	echo "I2P Router wrapper was not installed as a launchd service."
fi
rm -f `pwd`/$shfile
