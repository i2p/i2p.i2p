package net.i2p.client.streaming;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.SigType;
import net.i2p.util.Log;

/**
 * Simplify the creation of I2PSession and transient I2P Destination objects if 
 * necessary to create a socket manager.  This class is most likely how classes
 * will begin their use of the socket library.
 *
 * For new applications, createDisconnectedManager() is the preferred method.
 * It is non-blocking and throws on all errors.
 * All createManager() methods are blocking and return null on error.
 *
 * Note that for all methods, host and port arguments are ignored if in RouterContext;
 * it will connect internally to the router in the JVM.
 * You cannot connect out from a router JVM to another router.
 *
 */
public class I2PSocketManagerFactory {

    /**
     *  Ignored since 0.9.12, cannot be changed via properties.
     *  @deprecated
     */
    @Deprecated
    public static final String PROP_MANAGER = "i2p.streaming.manager";

    /**
     *  The one and only manager.
     */
    public static final String DEFAULT_MANAGER = "net.i2p.client.streaming.impl.I2PSocketManagerFull";

    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the local machine on the default port (7654).
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     * 
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager() {
        return createManager(getHost(), getPort(), (Properties) System.getProperties().clone(), 
                    IncomingConnectionFilter.ALLOW);
    }

    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the local machine on the default port (7654) with the
     * specified incoming connection filter.
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     * 
     * @since 0.9.40
     * @param filter The filter for incoming connections
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(IncomingConnectionFilter filter) {
        return createManager(getHost(), getPort(), (Properties) System.getProperties().clone(), filter);
    }
    
    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the local machine on the default port (7654).
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     * 
     * @param opts Streaming and I2CP options, may be null
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(Properties opts) {
        return createManager(getHost(), getPort(), opts, IncomingConnectionFilter.ALLOW);
    }

    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the local machine on the default port (7654).
     *
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     *
     * @since 0.9.40
     * @param opts Streaming and I2CP options, may be null
     * @param filter The filter to use for incoming connections
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(Properties opts, IncomingConnectionFilter filter) {
        return createManager(getHost(), getPort(), opts, filter);
    }

    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the specified host and port.
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     * 
     * @param host I2CP host null to use default, ignored if in router context
     * @param port I2CP port &lt;= 0 to use default, ignored if in router context
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(String host, int port) {
        return createManager(host, port, (Properties) System.getProperties().clone(),
                      IncomingConnectionFilter.ALLOW);
    }

    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the specified host and port with the specified connection filter
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     * 
     * @param host I2CP host null to use default, ignored if in router context
     * @param port I2CP port &lt;= 0 to use default, ignored if in router context
     * @param filter The filter to use for incoming connections
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(String host, int port, IncomingConnectionFilter filter) {
        return createManager(host, port, (Properties) System.getProperties().clone(), filter);
    }
    
    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the given machine reachable through the given port.
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     *
     * @param i2cpHost I2CP host null to use default, ignored if in router context
     * @param i2cpPort I2CP port &lt;= 0 to use default, ignored if in router context
     * @param opts Streaming and I2CP options, may be null
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(String i2cpHost, int i2cpPort, Properties opts) {
        return createManager(i2cpHost, i2cpPort, opts, IncomingConnectionFilter.ALLOW);
    }

    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the given machine reachable through the given port with 
     * the specified connection filter
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     *
     * @since 0.9.40
     * @param i2cpHost I2CP host null to use default, ignored if in router context
     * @param i2cpPort I2CP port &lt;= 0 to use default, ignored if in router context
     * @param opts Streaming and I2CP options, may be null
     * @param filter The filter to use for incoming connections
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(String i2cpHost, int i2cpPort, Properties opts,
                    IncomingConnectionFilter filter) {
        I2PClient client = I2PClientFactory.createClient();
        ByteArrayOutputStream keyStream = new ByteArrayOutputStream(1024);
        try {
            client.createDestination(keyStream, getSigType(opts));
            ByteArrayInputStream in = new ByteArrayInputStream(keyStream.toByteArray());
            return createManager(in, i2cpHost, i2cpPort, opts, filter);
        } catch (IOException ioe) {
            getLog().error("Error creating the destination for socket manager", ioe);
            return null;
        } catch (I2PException ie) {
            getLog().error("Error creating the destination for socket manager", ie);
            return null;
        }
    }

    /**
     * Create a socket manager using the destination loaded from the given private key
     * stream and connected to the default I2CP host and port.
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     *
     * @param myPrivateKeyStream private key stream, format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     *                           or null for a transient destination. Caller must close.
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(InputStream myPrivateKeyStream) {
        return createManager(myPrivateKeyStream, IncomingConnectionFilter.ALLOW);
    }

    /**
     * Create a socket manager using the destination loaded from the given private key
     * stream and connected to the default I2CP host and port with the specified connection filter
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     *
     * @since 0.9.40
     * @param myPrivateKeyStream private key stream, format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     *                           or null for a transient destination. Caller must close.
     * @param filter The filter to use for incoming connections
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(InputStream myPrivateKeyStream, 
                    IncomingConnectionFilter filter) {
        return createManager(myPrivateKeyStream, getHost(), getPort(), 
                             (Properties) System.getProperties().clone(),
                             filter);

    }
    
    /**
     * Create a socket manager using the destination loaded from the given private key
     * stream and connected to the default I2CP host and port.
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     *
     * @param myPrivateKeyStream private key stream, format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     *                           or null for a transient destination. Caller must close.
     * @param opts Streaming and I2CP options, may be null
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(InputStream myPrivateKeyStream, Properties opts) {
        return createManager(myPrivateKeyStream, opts, IncomingConnectionFilter.ALLOW);
    }

    /**
     * Create a socket manager using the destination loaded from the given private key
     * stream and connected to the default I2CP host and port.
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     *
     * @param myPrivateKeyStream private key stream, format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     *                           or null for a transient destination. Caller must close.
     * @param opts Streaming and I2CP options, may be null
     * @param filter The filter to use for incoming connections
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(InputStream myPrivateKeyStream,
		                                 Properties opts,
                                                 IncomingConnectionFilter filter) {
        return createManager(myPrivateKeyStream, getHost(), getPort(), opts, filter);
    }
    
    /**
     * Create a socket manager using the destination loaded from the given private key
     * stream and connected to the I2CP router on the specified machine on the given
     * port.
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     *
     * @param myPrivateKeyStream private key stream, format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     *                           or null for a transient destination. Caller must close.
     * @param i2cpHost I2CP host null to use default, ignored if in router context
     * @param i2cpPort I2CP port &lt;= 0 to use default, ignored if in router context
     * @param opts Streaming and I2CP options, may be null
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(InputStream myPrivateKeyStream, String i2cpHost, int i2cpPort,
                                                 Properties opts) {
         return createManager(myPrivateKeyStream, i2cpHost, i2cpPort, opts, IncomingConnectionFilter.ALLOW);
    }

    /**
     * Create a socket manager using the destination loaded from the given private key
     * stream and connected to the I2CP router on the specified machine on the given
     * port.
     * 
     * Blocks for a long time while the router builds tunnels.
     * The nonblocking createDisconnectedManager() is preferred.
     *
     * @param myPrivateKeyStream private key stream, format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     *                           or null for a transient destination. Caller must close.
     * @param i2cpHost I2CP host null to use default, ignored if in router context
     * @param i2cpPort I2CP port &lt;= 0 to use default, ignored if in router context
     * @param opts Streaming and I2CP options, may be null
     * @param filter The filter to use for incoming connections
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(InputStream myPrivateKeyStream,
		                                 String i2cpHost,
                                                 int i2cpPort,
                                                 Properties opts,
                                                 IncomingConnectionFilter filter) {
        try {
            return createManager(myPrivateKeyStream, i2cpHost, i2cpPort, opts, true, filter);
        } catch (I2PSessionException ise) {
            getLog().error("Error creating session for socket manager", ise);
            return null;
        }
    }	    

    /**
     * Create a disconnected socket manager using the destination loaded from the given private key
     * stream, or null for a transient destination.
     * 
     * Non-blocking. Does not connect to the router or build tunnels.
     * For servers, caller MUST call getSession().connect() to build tunnels and start listening.
     * For clients, caller may do that to build tunnels in advance;
     * otherwise, the first call to connect() will initiate a connection to the router,
     * with significant delay for tunnel building.
     *
     * @param myPrivateKeyStream private key stream, format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     *                           or null for a transient destination. Caller must close.
     * @param i2cpHost I2CP host null to use default, ignored if in router context
     * @param i2cpPort I2CP port &lt;= 0 to use default, ignored if in router context
     * @param opts Streaming and I2CP options, may be null
     * @return the newly created socket manager, non-null (throws on error)
     * @since 0.9.8
     */
    public static I2PSocketManager createDisconnectedManager(InputStream myPrivateKeyStream, String i2cpHost,
                                                             int i2cpPort, Properties opts) throws I2PSessionException {
        return createDisconnectedManager(myPrivateKeyStream,
                                         i2cpHost,
                                         i2cpPort,
                                         opts,
                                         IncomingConnectionFilter.ALLOW);	 
    }
    
    /**
     * Create a disconnected socket manager using the destination loaded from the given private key
     * stream, or null for a transient destination.
     * 
     * Non-blocking. Does not connect to the router or build tunnels.
     * For servers, caller MUST call getSession().connect() to build tunnels and start listening.
     * For clients, caller may do that to build tunnels in advance;
     * otherwise, the first call to connect() will initiate a connection to the router,
     * with significant delay for tunnel building.
     *
     * @param myPrivateKeyStream private key stream, format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     *                           or null for a transient destination. Caller must close.
     * @param i2cpHost I2CP host null to use default, ignored if in router context
     * @param i2cpPort I2CP port &lt;= 0 to use default, ignored if in router context
     * @param opts Streaming and I2CP options, may be null
     * @param filter The filter to use for incoming connections
     * @return the newly created socket manager, non-null (throws on error)
     * @since 0.9.40
     */
    public static I2PSocketManager createDisconnectedManager(InputStream myPrivateKeyStream,
                                                             String i2cpHost,
                                                             int i2cpPort,
                                                             Properties opts,
                                                             IncomingConnectionFilter filter) 
                                    throws I2PSessionException {
        if (myPrivateKeyStream == null) {
            I2PClient client = I2PClientFactory.createClient();
            ByteArrayOutputStream keyStream = new ByteArrayOutputStream(1024);
            try {
                client.createDestination(keyStream, getSigType(opts));
            } catch (I2PException e) {
                throw new I2PSessionException("Error creating keys", e);
            } catch (IOException e) {
                throw new I2PSessionException("Error creating keys", e);
            }
            myPrivateKeyStream = new ByteArrayInputStream(keyStream.toByteArray());
        }
        return createManager(myPrivateKeyStream, i2cpHost, i2cpPort, opts, false, filter);
    }

    /**
     * Create a socket manager using the destination loaded from the given private key
     * stream and connected to the I2CP router on the specified machine on the given
     * port.
     * 
     * Blocks for a long time while the router builds tunnels if connect is true.
     *
     * @param myPrivateKeyStream private key stream, format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     *                           non-null. Caller must close.
     * @param i2cpHost I2CP host null to use default, ignored if in router context
     * @param i2cpPort I2CP port &lt;= 0 to use default, ignored if in router context
     * @param opts Streaming and I2CP options, may be null
     * @param connect true to connect (blocking)
     * @param filter The filter to use for incoming connections
     * @return the newly created socket manager, non-null (throws on error)
     * @since 0.9.40
     */
    private static I2PSocketManager createManager(InputStream myPrivateKeyStream, String i2cpHost, int i2cpPort,
                                                 Properties opts, boolean connect,
                                                 IncomingConnectionFilter filter) throws I2PSessionException {
        I2PClient client = I2PClientFactory.createClient();
        if (opts == null)
            opts = new Properties();
        Properties syscopy = (Properties) System.getProperties().clone();
        for (Map.Entry<Object, Object> e : syscopy.entrySet()) {
            String name = (String) e.getKey();
            if (opts.getProperty(name) == null)
                opts.setProperty(name, (String) e.getValue());
        }
        // as of 0.8.1 (I2CP default is BestEffort)
        if (opts.getProperty(I2PClient.PROP_RELIABILITY) == null)
            opts.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_NONE);

        if (i2cpHost != null)
            opts.setProperty(I2PClient.PROP_TCP_HOST, i2cpHost);
        if (i2cpPort > 0)
            opts.setProperty(I2PClient.PROP_TCP_PORT, Integer.toString(i2cpPort));
        
        I2PSession session = client.createSession(myPrivateKeyStream, opts);
        if (connect)
            session.connect();
        I2PSocketManager sockMgr = createManager(session, opts, "manager", filter);
        return sockMgr;
    }

    private static I2PSocketManager createManager(I2PSession session, Properties opts, String name,
                                                  IncomingConnectionFilter filter) {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        // As of 0.9.12, ignore this setting, as jwebcache and i2phex set it to the old value.
        // There is no other valid manager.
        //String classname = opts.getProperty(PROP_MANAGER, DEFAULT_MANAGER);
        String classname = DEFAULT_MANAGER;
        try {
            Class<?> cls = Class.forName(classname);
            if (!I2PSocketManager.class.isAssignableFrom(cls))
                throw new IllegalArgumentException(classname + " is not an I2PSocketManager");
            Constructor<?> con =
                  cls.getConstructor(I2PAppContext.class, 
                                     I2PSession.class,
                                     Properties.class,
                                     String.class,
                                     IncomingConnectionFilter.class);
            I2PSocketManager mgr = (I2PSocketManager) con.newInstance(
                                   new Object[] {context, session, opts, name, filter});
            return mgr;
        } catch (Throwable t) {
            getLog().log(Log.CRIT, "Error loading " + classname, t);
            throw new IllegalStateException(t);
        }

    }

    private static String getHost() {
        return System.getProperty(I2PClient.PROP_TCP_HOST, "127.0.0.1");
    }

    private static int getPort() {
        int i2cpPort = I2PClient.DEFAULT_LISTEN_PORT;
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

    /**
     *  @param opts may be null
     *  @since 0.9.12
     */
    private static SigType getSigType(Properties opts) {
        if (opts != null) {
            String st = opts.getProperty(I2PClient.PROP_SIGTYPE);
            if (st != null) {
                SigType rv = SigType.parseSigType(st);
                if (rv != null && rv.isAvailable())
                    return rv;
                if (rv != null)
                    st = rv.toString();
                getLog().logAlways(Log.WARN, "Unsupported sig type " + st +
                                             ", reverting to " + I2PClient.DEFAULT_SIGTYPE);
            }
        }
        return I2PClient.DEFAULT_SIGTYPE;
    }

    /** @since 0.9.7 */
    private static Log getLog() {
        return I2PAppContext.getGlobalContext().logManager().getLog(I2PSocketManagerFactory.class);
    }
}
