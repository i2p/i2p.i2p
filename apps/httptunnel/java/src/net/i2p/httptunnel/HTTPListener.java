package net.i2p.httptunnel;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import net.i2p.util.Log;

/**
 * Listens on a port for HTTP connections.
 */
public class HTTPListener extends Thread {

    private static final Log _log = new Log(HTTPListener.class);

    private int port;
    private String listenHost;
    private SocketManagerProducer smp;

    public HTTPListener(SocketManagerProducer smp, int port,
			String listenHost) {
	this.smp = smp;
	this.port = port;
	start();
    }
    
    public void run() {
	try {
	    InetAddress lh = listenHost == null
		? null
		: InetAddress.getByName(listenHost);
	    ServerSocket ss = new ServerSocket(port, 0, lh);
	    while(true) {
		Socket s = ss.accept();
		new HTTPSocketHandler(this, s);
	    }
	} catch (IOException ex) {
	    _log.error("Error while accepting connections", ex);
	}
    }

    private boolean proxyUsed=false;
    
    public boolean firstProxyUse() {
	// FIXME: check a config option here
	if (true) return false;
	if (proxyUsed) {
	    return false;
	} else {
	    proxyUsed=true;
	    return true;
	}
    }

    public SocketManagerProducer getSMP() {
	return smp;
    }

    /** @deprecated */
    public void handleNotImplemented(OutputStream out) throws IOException {
	out.write(("HTTP/1.1 200 Document following\n\n"+
		  "<h1>Feature not implemented</h1>").getBytes("ISO-8859-1"));
	out.flush();
    }
}
