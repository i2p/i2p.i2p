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

    /**
     * A public constructor.  It contstructs things.  In this case, 
     * it constructs a nice HTTPListener, for all your listening on
     * HTTP needs.  Yep.  That's right.
     * @param smp A SocketManagerProducer, producing Sockets, no doubt
     * @param port A port, to connect to.
     * @param listenHost A host, to connect to.
     */
    
    public HTTPListener(SocketManagerProducer smp, int port,
			String listenHost) {
	this.smp = smp;
	this.port = port;
	start();
    }
    
    /* (non-Javadoc)
     * @see java.lang.Thread#run()
     */
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
    
    /**
     * Query whether this is the first use of the proxy or not . . .
     * @return Whether this is the first proxy use, no doubt.
     */
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

    /**
     * @return The SocketManagerProducer being used.
     */
    public SocketManagerProducer getSMP() {
	return smp;
    }

    /** 
     * Outputs with HTTP 1.1 flair that a feature isn't implemented.
     * @param out The stream the text goes to.
     * @deprecated
     * @throws IOException
     */
    public void handleNotImplemented(OutputStream out) throws IOException {
	out.write(("HTTP/1.1 200 Document following\n\n"+
		  "<h1>Feature not implemented</h1>").getBytes("ISO-8859-1"));
	out.flush();
    }
}
