package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * SAM bridge implementation.
 *
 * @author human
 */
public class SAMBridge implements Runnable {

    private final static Log _log = new Log(SAMBridge.class);
    private ServerSocket serverSocket;

    private boolean acceptConnections = true;

    private final static int SAM_LISTENPORT = 7656;

    /**
     * Build a new SAM bridge listening on 127.0.0.1.
     *
     * @param listenPort The port to listen on
     */
    public SAMBridge(int listenPort) {
	this((String)null, listenPort);
    }

    /**
     * Build a new SAM bridge.
     *
     * @param listenHost The network interface to listen on
     * @param listenPort The port to listen on
     */
    public SAMBridge(String listenHost, int listenPort) {
	try {
	    if (listenHost != null) {
		serverSocket = new ServerSocket(listenPort, 0,
					   InetAddress.getByName(listenHost));
		_log.debug("SAM bridge listening on "
			   + listenHost + ":" + listenPort);
	    } else {
		serverSocket = new ServerSocket(listenPort);
		_log.debug("SAM bridge listening on 0.0.0.0:" + listenPort);
	    }
	} catch (Exception e) {
	    _log.error("Error starting SAM bridge on "
		       + (listenHost == null ? "0.0.0.0" : listenHost)
		       + ":" + listenPort, e);
	}

    }

    public static void main(String args[]) {
	SAMBridge bridge = new SAMBridge(SAM_LISTENPORT);
	I2PThread t = new I2PThread(bridge, "SAMListener");
	t.start();
    }

    public void run() {
	try {
	    while (acceptConnections) {
		Socket s = serverSocket.accept();
		_log.debug("New connection from "
			   + s.getInetAddress().toString() + ":"
			   + s.getPort());
		
		try {
		    SAMHandler handler = SAMHandlerFactory.createSAMHandler(s);
		    if (handler == null) {
			_log.debug("SAM handler has not been instantiated");
			try {
			    s.close();
			} catch (IOException e) {}
			continue;
		    }
		    handler.startHandling();
		} catch (SAMException e) {
		    _log.error("SAM error: " + e.getMessage());
		    s.close();
		}
	    }
	} catch (Exception e) {
	    _log.error("Unexpected error while listening for connections", e);
	} finally {
	    try {
		_log.debug("Shutting down, closing server socket");
		serverSocket.close();
	    } catch (IOException e) {}
	}
    }
}
