#!/bin/bash
# 2012, The I2P Project
# http://www.i2p2.de/
# This code is public domain.
# Author: Meeh
echo "Please enter your password for sudo privileges to uninstall I2P from launchd"
sudo launchctl unload -w /Library/LaunchDaemons/net.i2p.router.plist
if [ $? == 0 ]; then
	sudo rm -f /Library/LaunchDaemons/net.i2p.router.plist
	echo "I2P Router wrapper was successfully uninstalled from launchd."
else
	echo "I2P Router wrapper was not uninstalled from launchd."
fi
