package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * SAM bridge implementation.
 *
 * @author human
 */
public class SAMBridge implements Runnable {
    private final static Log _log = new Log(SAMBridge.class);
    private ServerSocketChannel serverSocket;
    private Properties i2cpProps;
    /** 
     * filename in which the name to private key mapping should 
     * be stored (and loaded from) 
     */
    private String persistFilename;
    /** 
     * app designated destination name to the base64 of the I2P formatted 
     * destination keys (Destination+PrivateKey+SigningPrivateKey)
     */
    private Map<String,String> nameToPrivKeys;

    private boolean acceptConnections = true;

    private static final int SAM_LISTENPORT = 7656;
    
    public static final String DEFAULT_SAM_KEYFILE = "sam.keys";
    public static final String PROP_TCP_HOST = "sam.tcp.host";
    public static final String PROP_TCP_PORT = "sam.tcp.port";
    protected static final String DEFAULT_TCP_HOST = "0.0.0.0";
    protected static final String DEFAULT_TCP_PORT = "7656";
    
    public static final String PROP_DATAGRAM_HOST = "sam.udp.host";
    public static final String PROP_DATAGRAM_PORT = "sam.udp.port";
    protected static final String DEFAULT_DATAGRAM_HOST = "0.0.0.0";
    protected static final String DEFAULT_DATAGRAM_PORT = "7655";

    
    private SAMBridge() {}
    
    /**
     * Build a new SAM bridge.
     *
     * @param listenHost hostname to listen for SAM connections on ("0.0.0.0" for all)
     * @param listenPort port number to listen for SAM connections on
     * @param i2cpProps set of I2CP properties for finding and communicating with the router
     * @param persistFile location to store/load named keys to/from
     */
    public SAMBridge(String listenHost, int listenPort, Properties i2cpProps, String persistFile) {
        persistFilename = persistFile;
        nameToPrivKeys = new HashMap<String,String>(8);
        loadKeys();
        try {
            if ( (listenHost != null) && !("0.0.0.0".equals(listenHost)) ) {
                serverSocket = ServerSocketChannel.open();
                serverSocket.socket().bind(new InetSocketAddress(listenHost, listenPort));
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("SAM bridge listening on "
                               + listenHost + ":" + listenPort);
            } else {
                serverSocket = ServerSocketChannel.open();
                serverSocket.socket().bind(new InetSocketAddress(listenPort));
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
     * @param name name of the destination
     * @return null if the name does not exist, or if it is improperly formatted
     */
    public Destination getDestination(String name) {
        synchronized (nameToPrivKeys) {
            String val = nameToPrivKeys.get(name);
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
    }
    
    /**
     * Retrieve the I2P private keystream for the given name, formatted
     * as a base64 string (Destination+PrivateKey+SessionPrivateKey, as I2CP 
     * stores it).
     *
     * @param name Name of the destination
     * @return null if the name does not exist, else the stream
     */
    public String getKeystream(String name) {
        synchronized (nameToPrivKeys) {
            String val = nameToPrivKeys.get(name);
            if (val == null) return null;
            return val;
        }
    }

    /**
     * Specify that the given keystream should be used for the given name
     *
     * @param name Name of the destination
     * @param stream  Name of the stream
     */
    public void addKeystream(String name, String stream) {
        synchronized (nameToPrivKeys) {
            nameToPrivKeys.put(name, stream);
        }
        storeKeys();
    }
    
    /**
     * Load up the keys from the persistFilename
     *
     */
    private void loadKeys() {
        synchronized (nameToPrivKeys) {
            nameToPrivKeys.clear();
            FileInputStream in = null;
            try {
                in = new FileInputStream(persistFilename);
                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                String line = null;
                while ( (line = reader.readLine()) != null) {
                    int eq = line.indexOf('=');
                    String name = line.substring(0, eq);
                    String privKeys = line.substring(eq+1);
                    nameToPrivKeys.put(name, privKeys);
                }
            } catch (FileNotFoundException fnfe) {
                _log.warn("Key file does not exist at " + persistFilename);
            } catch (IOException ioe) {
                _log.error("Unable to read the keys from " + persistFilename, ioe);
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    /**
     * Store the current keys to disk in the location specified on creation
     *
     */
    private void storeKeys() {
        synchronized (nameToPrivKeys) {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(persistFilename);
                for (Iterator iter = nameToPrivKeys.keySet().iterator(); iter.hasNext(); ) {
                    String name = (String)iter.next();
                    String privKeys = nameToPrivKeys.get(name);
                    out.write(name.getBytes());
                    out.write('=');
                    out.write(privKeys.getBytes());
                    out.write('\n');
                }
            } catch (IOException ioe) {
                _log.error("Error writing out the SAM keys to " + persistFilename, ioe);
            } finally {
                if (out != null) try { out.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    static class HelpRequested extends Exception {static final long serialVersionUID=0x1;}
    
    /**
     * Usage:
     *  <pre>SAMBridge [ keyfile [listenHost ] listenPort [ name=val ]* ]</pre>
     * or:
     *  <pre>SAMBridge [ name=val ]* </pre>
     *  
     * name=val options are passed to the I2CP code to build a session, 
     * allowing the bridge to specify an alternate I2CP host and port, tunnel
     * depth, etc.
     * @param args [ keyfile [ listenHost ] listenPort [ name=val ]* ]
     */
    public static void main(String args[]) {
        String keyfile = DEFAULT_SAM_KEYFILE;
        int port = SAM_LISTENPORT;
        String host = DEFAULT_TCP_HOST;
        Properties opts = null;
        if (args.length > 0) {
        	try {
        		opts = parseOptions(args, 0);
        		keyfile = args[0];
        		int portIndex = 1;
        		try {
        			if (args.length>portIndex) port = Integer.parseInt(args[portIndex]);
        		} catch (NumberFormatException nfe) {
        			host = args[portIndex];
        			portIndex++;
        			try {
        				if (args.length>portIndex) port = Integer.parseInt(args[portIndex]);
        			} catch (NumberFormatException nfe1) {
        				try {
        					port = Integer.parseInt(opts.getProperty(SAMBridge.PROP_TCP_PORT, SAMBridge.DEFAULT_TCP_PORT));
        					host = opts.getProperty(SAMBridge.PROP_TCP_HOST, SAMBridge.DEFAULT_TCP_HOST);
        				} catch (NumberFormatException e) {
        					usage();
        					return;
        				}
        			}
        		}
        	} catch (HelpRequested e) {
        		usage();
        		return;
        	}
        }
        SAMBridge bridge = new SAMBridge(host, port, opts, keyfile);
        I2PAppThread t = new I2PAppThread(bridge, "SAMListener");
        if (Boolean.valueOf(System.getProperty("sam.shutdownOnOOM", "false")).booleanValue()) {
            t.addOOMEventThreadListener(new I2PAppThread.OOMEventListener() {
                public void outOfMemory(OutOfMemoryError err) {
                    err.printStackTrace();
                    System.err.println("OOMed, die die die");
                    System.exit(-1);
                }
            });
        }
        t.start();
    }

    private static Properties parseOptions(String args[], int startArgs) throws HelpRequested {
        Properties props = new Properties();
        // skip over first few options
        for (int i = startArgs; i < args.length; i++) {
        	if (args[i].equals("-h")) throw new HelpRequested();
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
        System.err.println("Usage: SAMBridge [keyfile [listenHost] listenPortNum[ name=val]*]");
        System.err.println("or:");
        System.err.println("       SAMBridge [ name=val ]*");
        System.err.println(" keyfile: location to persist private keys (default sam.keys)");
        System.err.println(" listenHost: interface to listen on (0.0.0.0 for all interfaces)");
        System.err.println(" listenPort: port to listen for SAM connections on (default 7656)");
        System.err.println(" name=val: options to pass when connecting via I2CP, such as ");
        System.err.println("           i2cp.host=localhost and i2cp.port=7654");
        System.err.println("");
        System.err.println("Host and ports of the SAM bridge can be specified with the alternate");
        System.err.println("form by specifying options "+SAMBridge.PROP_TCP_HOST+" and/or "+
        		SAMBridge.PROP_TCP_PORT);
        System.err.println("");
        System.err.println("Options "+SAMBridge.PROP_DATAGRAM_HOST+" and "+SAMBridge.PROP_DATAGRAM_PORT+
        		" specify the listening ip");
        System.err.println("range and the port of SAM datagram server. This server is");
        System.err.println("only launched after a client creates the first SAM datagram");
        System.err.println("or raw session, after a handshake with SAM version >= 3.0.");
        System.err.println("");
        System.err.println("The option loglevel=[DEBUG|WARN|ERROR|CRIT] can be used");
        System.err.println("for tuning the log verbosity.\n");
    }
    
    public void run() {
        if (serverSocket == null) return;
        try {
            while (acceptConnections) {
                SocketChannel s = serverSocket.accept();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("New connection from "
                               + s.socket().getInetAddress().toString() + ":"
                               + s.socket().getPort());

                class HelloHandler implements Runnable {
                	SocketChannel s ;
                	SAMBridge parent ;
                	HelloHandler(SocketChannel s, SAMBridge parent) { 
                		this.s = s ;
                		this.parent = parent ;
                	}
                	public void run() {
                        try {
                            SAMHandler handler = SAMHandlerFactory.createSAMHandler(s, i2cpProps);
                            if (handler == null) {
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("SAM handler has not been instantiated");
                                try {
                                    s.close();
                                } catch (IOException e) {}
                                return;
                            }
                            handler.setBridge(parent);
                            handler.startHandling();
                        } catch (SAMException e) {
                            if (_log.shouldLog(Log.ERROR))
                                _log.error("SAM error: " + e.getMessage(), e);
                            try {
                                String reply = "HELLO REPLY RESULT=I2P_ERROR MESSAGE=\"" + e.getMessage() + "\"\n";
                                s.write(ByteBuffer.wrap(reply.getBytes("ISO-8859-1")));
                            } catch (IOException ioe) {
                                if (_log.shouldLog(Log.ERROR))
                                    _log.error("SAM Error sending error reply", ioe);
                            }
                            try { s.close(); } catch (IOException ioe) {}
                        } catch (Exception ee) {
                            try { s.close(); } catch (IOException ioe) {}
                            _log.log(Log.CRIT, "Unexpected error handling SAM connection", ee);
                        }                		
                	}
                }
                new I2PAppThread(new HelloHandler(s,this), "HelloHandler").start();
            }
        } catch (Exception e) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unexpected error while listening for connections", e);
        } finally {
            try {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Shutting down, closing server socket");
                if (serverSocket != null)
                    serverSocket.close();
            } catch (IOException e) {}
        }
    }
}
