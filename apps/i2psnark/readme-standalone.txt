To run i2psnark's standalone mode make sure you have an i2p router running in the background, then run:

launch-i2psnark
or
launch-i2psnark.bat (Windows)

I2PSnark web ui will be at http://127.0.0.1:8002/i2psnark/ 

To change or disable browser launch at startup, edit i2psnark-appctx.config.
To change the port, edit jetty-i2psnark.xml.


I2PSnark is GPL'ed software, based on Snark (http://www.klomp.org/) to run on top of I2P
(https://geti2p.net/) within a webserver (such as the bundled Jetty from
https://www.eclipse.org/jetty/).  For more information about I2PSnark, get in touch
with the folks at http://zzz.i2p/


To add RPC support:

1) Stop i2psnark standalone if running.

2a) If you have the i2psnark-rpc plugin installed in your router already,
    copy the file ~/.i2p/plugins/i2psnark-rpc/console/webapps/transmission.war
    to the webapps/ directory in your standalone install.

2b) If you do not have the i2psnark-rpc plugin installed, get the i2p.plugins.i2psnark-rpc
    branch out of git, build with 'ant war', and copy the file src/build/transmission.war.jar
    to the file webapps/transmission.war in your standalone install.

3) Start i2psnark standalone as usual. The transmission web interface will be at
   http://127.0.0.1:8002/transmission/web/ or if you have transmission-remote installed,
   test with 'transmission-remote 8002 -l'
