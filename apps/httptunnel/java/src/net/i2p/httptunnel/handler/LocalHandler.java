package net.i2p.httptunnel.handler;
import java.io.IOException;
import java.io.OutputStream;

import net.i2p.httptunnel.HTTPListener;
import net.i2p.httptunnel.Request;
import net.i2p.util.Log;

/**
 * Handler for requests that do not require any connection to anyone
 * (except errors).
 */
public class LocalHandler {

    private static final Log _log = new Log(LocalHandler.class);

    /* package private */ LocalHandler() {

    }
    
    public void handle(Request req, HTTPListener httpl, OutputStream out,
		       boolean fromProxy) throws IOException {
	//FIXME: separate multiple pages, not only a start page
	//FIXME: provide some info on this page
	out.write(("HTTP/1.1 200 Document following\r\n"+
		   "Content-Type: text/html; charset=iso-8859-1\r\n\r\n"+
		   "<html><head><title>Welcome to I2P HTTPTunnel</title>"+
		   "</head><body><h1>Welcome to I2P HTTPTunnel</h1>You can "+
		   "browse Eepsites by adding an eepsite name to the request."+
		   "</body></html>").getBytes("ISO-8859-1"));
	out.flush();
    }

    public void handleProxyConfWarning(Request req, HTTPListener httpl,
				       OutputStream out) throws IOException {
	//FIXME
        throw new IOException("jrandom ate the deprecated method. mooo");
	//httpl.handleNotImplemented(out);

    }

    public void handleHTTPWarning(Request req, HTTPListener httpl,
				  OutputStream out, boolean fromProxy)
    throws IOException {
	// FIXME
        throw new IOException("jrandom ate the deprecated method. mooo");
	//httpl.handleNotImplemented(out);
    }
}
