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
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

import net.i2p.data.Destination;
import net.i2p.data.DataFormatException;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;

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
    private Properties i2cpProps;
    /** 
     * app designated destination name to the base64 of the I2P formatted 
     * destination keys (Destination+PrivateKey+SigningPrivateKey)
     */
    private Map nameToPrivKeys = Collections.synchronizedMap(new HashMap(8));

    private boolean acceptConnections = true;

    private final static int SAM_LISTENPORT = 7656;
    
    private SAMBridge() {}
    
    /**
     * Build a new SAM bridge.
     *
     * @param listenHost hostname to listen for SAM connections on ("0.0.0.0" for all)
     * @param listenPort port number to listen for SAM connections on
     * @param i2cpProps set of I2CP properties for finding and communicating with the router
     */
    public SAMBridge(String listenHost, int listenPort, Properties i2cpProps) {
        try {
            if ( (listenHost != null) && !("0.0.0.0".equals(listenHost)) ) {
                serverSocket = new ServerSocket(listenPort, 0, InetAddress.getByName(listenHost));
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("SAM bridge listening on "
                               + listenHost + ":" + listenPort);
            } else {
                serverSocket = new ServerSocket(listenPort);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("SAM bridge listening on 0.0.0.0:" + listenPort);
            }
        } catch (Exception e) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error starting SAM bridge on "
                           + (listenHost == null ? "0.0.0.0" : listenHost)
                           + ":" + listenPort, e);
        }
        
        this.i2cpProps = i2cpProps;
    }

    /**
     * Retrieve the destination associated with the given name
     *
     * @return null if the name does not exist, or if it is improperly formatted
     */
    public Destination getDestination(String name) {
        String val = (String)nameToPrivKeys.get(name);
        if (val == null) return null;
        try {
            Destination d = new Destination();
            d.fromBase64(val);
            return d;
        } catch (DataFormatException dfe) {
            _log.error("Error retrieving the destination from " + name, dfe);
            nameToPrivKeys.remove(name);
            return null;
        }
    }
    
    /**
     * Retrieve the I2P private keystream for the given name, formatted
     * as a base64 string (Destination+PrivateKey+SessionPrivateKey, as I2CP 
     * stores it).
     *
     * @return null if the name does not exist, else the stream
     */
    public String getKeystream(String name) {
        String val = (String)nameToPrivKeys.get(name);
        if (val == null) return null;
        return val;
    }

    /**
     * Specify that the given keystream should be used for the given name
     *
     */
    public void addKeystream(String name, String stream) {
        nameToPrivKeys.put(name, stream);
    }
    
    /**
     * Usage:
     *  <pre>SAMBridge [[listenHost ]listenPort[ name=val]*]</pre>
     * 
     * name=val options are passed to the I2CP code to build a session, 
     * allowing the bridge to specify an alternate I2CP host and port, tunnel
     * depth, etc.
     */ 
    public static void main(String args[]) {
        int port = SAM_LISTENPORT;
        String host = "0.0.0.0";
        Properties opts = null;
        if (args.length > 0) {
            int portIndex = 0;
            try {
                port = Integer.parseInt(args[portIndex]);
            } catch (NumberFormatException nfe) {
                host = args[0];
                portIndex++;
                try {
                    port = Integer.parseInt(args[portIndex]);
                } catch (NumberFormatException nfe1) {
                    usage();
                    return;
                }
            }
            opts = parseOptions(args, portIndex+1);
        }
        SAMBridge bridge = new SAMBridge(host, port, opts);
        I2PThread t = new I2PThread(bridge, "SAMListener");
        t.start();
    }

    private static Properties parseOptions(String args[], int startArgs) {
        Properties props = new Properties();
        // skip over first few options
        for (int i = startArgs; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0) continue;
            if (eq >= args[i].length()-1) continue;
            String key = args[i].substring(0, eq);
            String val = args[i].substring(eq+1);
            key = key.trim();
            val = val.trim();
            if ( (key.length() > 0) && (val.length() > 0) )
                props.setProperty(key, val);
        }
        return props;
    }
    
    private static void usage() {
        System.err.println("Usage: SAMBridge [listenHost listenPortNum[ name=val]*]");
        System.err.println(" listenHost: interface to listen on (0.0.0.0 for all interfaces)");
        System.err.println(" listenPort: port to listen for SAM connections on (default 7656)");
        System.err.println(" name=val: options to pass when connecting via I2CP, such as ");
        System.err.println("           i2cp.host=localhost and i2cp.port=7654");
    }
    
    public void run() {
        try {
            while (acceptConnections) {
                Socket s = serverSocket.accept();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("New connection from "
                               + s.getInetAddress().toString() + ":"
                               + s.getPort());

                try {
                    SAMHandler handler = SAMHandlerFactory.createSAMHandler(s, i2cpProps);
                    if (handler == null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("SAM handler has not been instantiated");
                        try {
                            s.close();
                        } catch (IOException e) {}
                        continue;
                    }
                    handler.setBridge(this);
                    handler.startHandling();
                } catch (SAMException e) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("SAM error: " + e.getMessage(), e);
                    try {
                        String reply = "HELLO REPLY RESULT=I2P_ERROR MESSAGE=\"" + e.getMessage() + "\"\n";
                        s.getOutputStream().write(reply.getBytes("ISO-8859-1"));
                    } catch (IOException ioe) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("SAM Error sending error reply", ioe);
                    }
                    s.close();
                }
            }
        } catch (Exception e) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unexpected error while listening for connections", e);
        } finally {
            try {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Shutting down, closing server socket");
                serverSocket.close();
            } catch (IOException e) {}
        }
    }
}
