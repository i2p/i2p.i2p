An rc file called rc.i2p has been placed into the /etc/rc.d directory.
If you want to change installation dir, change the variable INSTALL_DIR
on base-i2p.SlackBuild and rebuild the package. You also will need to do the
same for the i2p package.

The install script will insert everything needed into /etc/rc.d/rc.local and
into /etc/rc.d/rc.local_shutdown automatically.

If you want to start I2P at boot you have to chmod +x /etc/rc.d/rc.i2p

