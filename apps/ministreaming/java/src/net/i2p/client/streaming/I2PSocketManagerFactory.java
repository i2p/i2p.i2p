package net.i2p.client.streaming;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Simplify the creation of I2PSession and transient I2P Destination objects if 
 * necessary to create a socket manager.  This class is most likely how classes
 * will begin their use of the socket library
 *
 */
public class I2PSocketManagerFactory {
    private final static Log _log = new Log(I2PSocketManagerFactory.class);

    public static final String PROP_MANAGER = "i2p.streaming.manager";
    //public static final String DEFAULT_MANAGER = "net.i2p.client.streaming.I2PSocketManagerImpl";
    public static final String DEFAULT_MANAGER = "net.i2p.client.streaming.I2PSocketManagerFull";
    
    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the local machine on the default port (7654).
     * 
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager() {
        return createManager(getHost(), getPort(), System.getProperties());
    }
    
    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the local machine on the default port (7654).
     * 
     * @param opts I2CP options
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(Properties opts) {
        return createManager(getHost(), getPort(), opts);
    }

    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the specified host and port
     * 
     * @param host I2CP host
     * @param port I2CP port
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(String host, int port) {
        return createManager(host, port, System.getProperties());
    }

    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the given machine reachable through the given port.
     *
     * @param i2cpHost I2CP host
     * @param i2cpPort I2CP port
     * @param opts I2CP options
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(String i2cpHost, int i2cpPort, Properties opts) {
        I2PClient client = I2PClientFactory.createClient();
        ByteArrayOutputStream keyStream = new ByteArrayOutputStream(512);
        try {
            client.createDestination(keyStream);
            ByteArrayInputStream in = new ByteArrayInputStream(keyStream.toByteArray());
            return createManager(in, i2cpHost, i2cpPort, opts);
        } catch (IOException ioe) {
            _log.error("Error creating the destination for socket manager", ioe);
            return null;
        } catch (I2PException ie) {
            _log.error("Error creating the destination for socket manager", ie);
            return null;
        }
    }

    /**
     * Create a socket manager using the destination loaded from the given private key
     * stream and connected to the default I2CP host and port.
     *
     * @param myPrivateKeyStream private key stream
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(InputStream myPrivateKeyStream) {
        return createManager(myPrivateKeyStream, getHost(), getPort(), System.getProperties());
    }
    
    /**
     * Create a socket manager using the destination loaded from the given private key
     * stream and connected to the default I2CP host and port.
     *
     * @param myPrivateKeyStream private key stream
     * @param opts I2CP options
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(InputStream myPrivateKeyStream, Properties opts) {
        return createManager(myPrivateKeyStream, getHost(), getPort(), opts);
    }
    
    /**
     * Create a socket manager using the destination loaded from the given private key
     * stream and connected to the I2CP router on the specified machine on the given
     * port
     *
     * @param myPrivateKeyStream private key stream
     * @param i2cpHost I2CP host
     * @param i2cpPort I2CP port
     * @param opts I2CP options
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(InputStream myPrivateKeyStream, String i2cpHost, int i2cpPort,
                                                 Properties opts) {
        I2PClient client = I2PClientFactory.createClient();
        if (opts == null)
            opts = new Properties();
        for (Iterator iter = System.getProperties().keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            if (!opts.containsKey(name))
                opts.setProperty(name, System.getProperty(name));
        }
        boolean oldLib = DEFAULT_MANAGER.equals(opts.getProperty(PROP_MANAGER, DEFAULT_MANAGER));
        if (oldLib && false) {
            // for the old streaming lib
            opts.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_GUARANTEED);
            //opts.setProperty("tunnels.depthInbound", "0");
        } else {
            // for new streaming lib:
            opts.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_BEST_EFFORT);
            //p.setProperty("tunnels.depthInbound", "0");
        }

        if (i2cpHost != null)
            opts.setProperty(I2PClient.PROP_TCP_HOST, i2cpHost);
        if (i2cpPort > 0)
            opts.setProperty(I2PClient.PROP_TCP_PORT, "" + i2cpPort);
        
        try {
            I2PSession session = client.createSession(myPrivateKeyStream, opts);
            session.connect();
            I2PSocketManager sockMgr = createManager(session, opts, "manager");
            return sockMgr;
        } catch (I2PSessionException ise) {
            _log.error("Error creating session for socket manager", ise);
            return null;
        }
    }

    private static I2PSocketManager createManager(I2PSession session, Properties opts, String name) {
        if (false) {
            I2PSocketManagerImpl mgr = new I2PSocketManagerImpl();
            mgr.setSession(session);
            //mgr.setDefaultOptions(new I2PSocketOptions());
            return mgr;
        } else {
            String classname = opts.getProperty(PROP_MANAGER, DEFAULT_MANAGER);
            if (classname != null) {
                try {
                    Class cls = Class.forName(classname);
                    Object obj = cls.newInstance();
                    if (obj instanceof I2PSocketManager) {
                        I2PSocketManager mgr = (I2PSocketManager)obj;
                        I2PAppContext context = I2PAppContext.getGlobalContext();
                        mgr.init(context, session, opts, name);
                        return mgr;
                    } else {
                        throw new IllegalStateException("Invalid manager class [" + classname + "]");
                    }
                } catch (ClassNotFoundException cnfe) {
                    _log.error("Error loading " + classname, cnfe);
                    throw new IllegalStateException("Invalid manager class [" + classname + "] - not found");
                } catch (InstantiationException ie) {
                    _log.error("Error loading " + classname, ie);
                    throw new IllegalStateException("Invalid manager class [" + classname + "] - unable to instantiate");
                } catch (IllegalAccessException iae) {
                    _log.error("Error loading " + classname, iae);
                    throw new IllegalStateException("Invalid manager class [" + classname + "] - illegal access");
                }
            } else {
                throw new IllegalStateException("No manager class specified");
            }
        }
        
    }

    private static String getHost() {
        return System.getProperty(I2PClient.PROP_TCP_HOST, "127.0.0.1");
    }
    private static int getPort() {
        int i2cpPort = 7654;
        String i2cpPortStr = System.getProperty(I2PClient.PROP_TCP_PORT);
        if (i2cpPortStr != null) {
            try {
                i2cpPort = Integer.parseInt(i2cpPortStr);
            } catch (NumberFormatException nfe) {
                // gobble gobble
            }
        }
        return i2cpPort;
    }
}
