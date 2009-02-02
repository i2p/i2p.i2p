Building:
The i2p package will be installed in /opt/i2p 

If you want to change installation dir, change the variable INSTALL_DIR
on i2p.SlackBuild and rebuild the package. You will also need to do the same
in the base-i2p package.

Installation and Upgrade:
Probably you will never have to update i2p packages. However if you do,
be sure to installpkg first, then removepkg or custom config files can
be lost with upgradepkg. I2P has an auto-update function. However using
installpkg then removepkg lowers the demand on the I2P network as a 
whole, and is by far faster.

After installpkg command, doinst.sh will execute a postinstallation script
needed by I2P.  Be sure to also install the base-i2p package.

Optional:

chmod +x /etc/rc.d/rc.i2p only if you want it to start on boot and stop on
shutdown.

How to start I2P:

Start I2P service with-
sh /etc/rc.d/rc.i2p start

Now tell your browser to user this proxy: localhost on port 4444 and open
this page: http://localhost:7657/index.jsp
Here you can configure I2P, watch network status and navigate anonimously.
It's suggested to subscribe to various addressbook hosts so that you can
get to the many available eepsites and other service on I2P. These are not
set up by default for security reasons. 

Please see the faqs on http://www.i2p2.i2p/ or http://www.i2p2.de/ on how
to subscribe to the various addressbook services.

To stop I2P:
 /etc/rc.d/rc.i2p stop


For any additional information:

Within I2P- http://www.i2p2.i2p/, http://forum.i2p/, http://zzz.i2p

Internet (not reccomended!) - http://www.i2p2.de/, http://forum.i2p2.de/

