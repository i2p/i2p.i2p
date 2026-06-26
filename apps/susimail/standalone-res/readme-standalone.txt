To run susimail's standalone mode make sure you have an i2p router running in the background, then run:

launch-susimail
or
launch-susimail.bat (Windows)

SusiMail web ui will be at http://127.0.0.1:8004/susimail/ 

This implementation uses I2CP to connect to the router.
You do NOT need to enable and start dedicated tunnels for pop3 and smtp.

Common configuration changes:

- To change or disable browser launch at startup, edit susimail-appctx.config.
- To change the browser that is launched at startup, edit susimail-appctx.config.
- To change the language, edit susimail-appctx.config.
- To change the theme to dark mode, edit susimail-appctx.config.
- To change the hostname of the router, edit susimail-appctx.config.
- To change the web UI port, edit jetty-susimail.xml.
