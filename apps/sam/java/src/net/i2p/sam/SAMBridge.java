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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;
import net.i2p.app.*;
import static net.i2p.app.ClientAppState.*;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.PortMapper;
import net.i2p.util.SystemVersion;

/**
 * SAM bridge implementation.
 * This is the main entry point for SAM.
 *
 * @author human
 */
public class SAMBridge implements Runnable, ClientApp {
    private final Log _log;
    private volatile ServerSocketChannel serverSocket;
    private final String _listenHost;
    private final int _listenPort;
    private final Properties i2cpProps;
    private final boolean _useSSL;
    private final File _configFile;
    private volatile Thread _runner;
    private final Object _v3DGServerLock = new Object();
    private SAMv3DatagramServer _v3DGServer;
    /**
     * Pluggable "Secure Session Manager" for interactive, GUI-based session
     * confirmation. This will block the SAM Handler Factory at the HELLO phase.
     * during the createSAMHandler call. If it's null, then no interactive session
     * will be used and SAM will work without it.
     */
    private final SAMSecureSessionInterface _secureSession;

    /**
     * filename in which the name to private key mapping should
     * be stored (and loaded from)
     */
    private final String persistFilename;
    /**
     * app designated destination name to the base64 of the I2P formatted
     * destination keys (Destination+PrivateKey+SigningPrivateKey)
     */
    private final Map<String, String> nameToPrivKeys;
    private final Set<Handler> _handlers;

    private volatile boolean acceptConnections = true;

    private final ClientAppManager _mgr;
    private volatile ClientAppState _state = UNINITIALIZED;

    private static final int SAM_LISTENPORT = 7656;

    public static final String DEFAULT_SAM_KEYFILE = "sam.keys";
    static final String DEFAULT_SAM_CONFIGFILE = "sam.config";
    private static final String PROP_SAM_KEYFILE = "sam.keyfile";
    private static final String PROP_SAM_SSL = "sam.useSSL";
    public static final String PROP_TCP_HOST = "sam.tcp.host";
    public static final String PROP_TCP_PORT = "sam.tcp.port";
    public static final String PROP_AUTH = "sam.auth";
    public static final String PROP_PW_PREFIX = "sam.auth.";
    public static final String PROP_PW_SUFFIX = ".shash";
    protected static final String DEFAULT_TCP_HOST = "127.0.0.1";
    protected static final String DEFAULT_TCP_PORT = "7656";

    public static final String PROP_DATAGRAM_HOST = "sam.udp.host";
    public static final String PROP_DATAGRAM_PORT = "sam.udp.port";
    protected static final String DEFAULT_DATAGRAM_HOST = "127.0.0.1";
    protected static final int DEFAULT_DATAGRAM_PORT_INT = 7655;
    protected static final String DEFAULT_DATAGRAM_PORT = Integer.toString(DEFAULT_DATAGRAM_PORT_INT);

    /**
     * For ClientApp interface.
     * Recommended constructor for external use.
     * Does NOT open the listener socket or start threads; caller must call
     * startup()
     *
     * @param mgr  may be null
     * @param args non-null
     * @throws Exception on bad args
     * @since 0.9.6
     */
    public SAMBridge(I2PAppContext context, ClientAppManager mgr, String[] args) throws Exception {
        _log = context.logManager().getLog(SAMBridge.class);
        _mgr = mgr;
        _secureSession = null;
        Options options = getOptions(args);
        _listenHost = options.host;
        _listenPort = options.port;
        _useSSL = options.isSSL;
        if (_useSSL && !SystemVersion.isJava7())
            throw new IllegalArgumentException("SSL requires Java 7 or higher");
        persistFilename = options.keyFile;
        _configFile = options.configFile;
        nameToPrivKeys = new HashMap<String, String>(8);
        _handlers = new HashSet<Handler>(8);
        this.i2cpProps = options.opts;
        _state = INITIALIZED;
    }

    /**
     * Build a new SAM bridge.
     * NOT recommended for external use.
     *
     * Opens the listener socket but does NOT start the thread, and there's no
     * way to do that externally.
     * Use main(), or use the other constructor and call startup().
     *
     * Deprecated for external use, to be made private.
     *
     * @param listenHost  hostname to listen for SAM connections on ("0.0.0.0" for
     *                    all)
     * @param listenPort  port number to listen for SAM connections on
     * @param i2cpProps   set of I2CP properties for finding and communicating with
     *                    the router
     * @param persistFile location to store/load named keys to/from
     * @throws RuntimeException if a server socket can't be opened
     */
    public SAMBridge(String listenHost, int listenPort, boolean isSSL, Properties i2cpProps,
            String persistFile, File configFile) {
        this(listenHost, listenPort, isSSL, i2cpProps,
                persistFile, configFile, null);

    }

    /**
     * Build a new SAM bridge.
     * NOT recommended for external use.
     *
     * Opens the listener socket but does NOT start the thread, and there's no
     * way to do that externally.
     * Use main(), or use the other constructor and call startup().
     *
     * Deprecated for external use, to be made private.
     *
     * @param listenHost    hostname to listen for SAM connections on ("0.0.0.0" for
     *                      all)
     * @param listenPort    port number to listen for SAM connections on
     * @param i2cpProps     set of I2CP properties for finding and communicating
     *                      with the router
     * @param persistFile   location to store/load named keys to/from
     * @param secureSession an instance of a Secure Session to use
     * @throws RuntimeException if a server socket can't be opened
     *
     * @since 1.8.0
     */
    public SAMBridge(String listenHost, int listenPort, boolean isSSL, Properties i2cpProps,
            String persistFile, File configFile, SAMSecureSessionInterface secureSession) {
        _log = I2PAppContext.getGlobalContext().logManager().getLog(SAMBridge.class);
        _mgr = null;
        _listenHost = listenHost;
        _listenPort = listenPort;
        _useSSL = isSSL;
        _secureSession = secureSession;

        if (_useSSL && !SystemVersion.isJava7())
            throw new IllegalArgumentException("SSL requires Java 7 or higher");
        this.i2cpProps = i2cpProps;
        persistFilename = persistFile;
        _configFile = configFile;
        nameToPrivKeys = new HashMap<String, String>(8);
        _handlers = new HashSet<Handler>(8);
        loadKeys();
        try {
            openSocket();
        } catch (IOException e) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error starting SAM bridge on "
                        + (listenHost == null ? "0.0.0.0" : listenHost)
                        + ":" + listenPort, e);
            throw new RuntimeException(e);
        }
        _state = INITIALIZED;
    }

    /**
     * @since 0.9.6
     */
    private void openSocket() throws IOException {
        if (!DEFAULT_TCP_HOST.equals(_listenHost) &&
            !"localhost".equals(_listenHost) &&
            !"::1".equals(_listenHost) &&
            !"0:0:0:0:0:0:0:1".equals(_listenHost) &&
            !(_useSSL && Boolean.parseBoolean(i2cpProps.getProperty(SAMBridge.PROP_AUTH)))) {
            String m = "SECURITY WARNING: SAM interface not restricted to localhost, and SSL and Authentication are not enabled.\n" +
                       "Remote access to local services may be possible.\n" +
                       "Please check command line and configuration.";
            _log.logAlways(Log.WARN, m);
            System.out.println(m);
        }
        if (_useSSL) {
            SSLServerSocketFactory fact = SSLUtil.initializeFactory(i2cpProps);
            InetAddress addr;
            if (_listenHost != null && !_listenHost.equals("0.0.0.0"))
                addr = InetAddress.getByName(_listenHost);
            else
                addr = null;
            SSLServerSocket sock = (SSLServerSocket) fact.createServerSocket(_listenPort, 0, addr);
            I2PSSLSocketFactory.setProtocolsAndCiphers(sock);
            serverSocket = new SSLServerSocketChannel(sock);
        } else {
            serverSocket = ServerSocketChannel.open();
            if (_listenHost != null && !_listenHost.equals("0.0.0.0")) {
                serverSocket.socket().bind(new InetSocketAddress(_listenHost, _listenPort));
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("SAM bridge listening on "
                            + _listenHost + ":" + _listenPort);
            } else {
                serverSocket.socket().bind(new InetSocketAddress(_listenPort));
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("SAM bridge listening on 0.0.0.0:" + _listenPort);
            }
        }
    }

    /**
     * Retrieve the destination associated with the given name
     *
     * @param name name of the destination
     * @return null if the name does not exist, or if it is improperly formatted
     */
    /****
     * public Destination getDestination(String name) {
     * synchronized (nameToPrivKeys) {
     * String val = nameToPrivKeys.get(name);
     * if (val == null) return null;
     * try {
     * Destination d = new Destination();
     * d.fromBase64(val);
     * return d;
     * } catch (DataFormatException dfe) {
     * _log.error("Error retrieving the destination from " + name, dfe);
     * nameToPrivKeys.remove(name);
     * return null;
     * }
     * }
     * }
     ****/

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
            if (val == null)
                return null;
            return val;
        }
    }

    /**
     * Specify that the given keystream should be used for the given name
     *
     * @param name   Name of the destination
     * @param stream Name of the stream
     */
    public void addKeystream(String name, String stream) {
        synchronized (nameToPrivKeys) {
            nameToPrivKeys.put(name, stream);
        }
        storeKeys();
    }

    /**
     * Load up the keys from the persistFilename.
     */
    @SuppressWarnings("unchecked")
    private void loadKeys() {
        synchronized (nameToPrivKeys) {
            nameToPrivKeys.clear();
            File file = new File(persistFilename);
            // now in config dir but check base dir too...
            if (!file.exists()) {
                if (file.isAbsolute())
                    return;
                file = new File(I2PAppContext.getGlobalContext().getConfigDir(), persistFilename);
                if (!file.exists())
                    return;
            }
            try {
                Properties props = new Properties();
                DataHelper.loadProps(props, file);
                // unchecked
                Map foo = props;
                nameToPrivKeys.putAll(foo);
                if (_log.shouldInfo())
                    _log.info("Loaded " + nameToPrivKeys.size() + " private keys from " + file);
            } catch (IOException ioe) {
                _log.error("Unable to read the keys from " + file, ioe);
            }
        }
    }

    /**
     * Store the current keys to disk in the location specified on creation.
     */
    private void storeKeys() {
        synchronized (nameToPrivKeys) {
            File file = new File(persistFilename);
            // now in config dir but check base dir too...
            if (!file.exists() && !file.isAbsolute())
                file = new File(I2PAppContext.getGlobalContext().getConfigDir(), persistFilename);
            try {
                Properties props = new OrderedProperties();
                props.putAll(nameToPrivKeys);
                DataHelper.storeProps(props, file);
                if (_log.shouldInfo())
                    _log.info("Saved " + nameToPrivKeys.size() + " private keys to " + file);
            } catch (IOException ioe) {
                _log.error("Error writing out the SAM keys to " + file, ioe);
            }
        }
    }

    /**
     * Handlers must call on startup
     *
     * @since 0.9.20
     */
    public void register(Handler handler) {
        if (_log.shouldInfo())
            _log.info("Register " + handler);
        synchronized (_handlers) {
            _handlers.add(handler);
        }
    }

    /**
     * Handlers must call on stop
     *
     * @since 0.9.20
     */
    public void unregister(Handler handler) {
        if (_log.shouldInfo())
            _log.info("Unregister " + handler);
        synchronized (_handlers) {
            _handlers.remove(handler);
        }
    }

    /**
     * Stop all the handlers.
     *
     * @since 0.9.20
     */
    private void stopHandlers() {
        List<Handler> handlers = null;
        synchronized (_handlers) {
            if (!_handlers.isEmpty()) {
                handlers = new ArrayList<Handler>(_handlers);
                _handlers.clear();
            }
        }
        if (handlers != null) {
            for (Handler handler : handlers) {
                if (_log.shouldInfo())
                    _log.info("Stopping " + handler);
                handler.stopHandling();
            }
        }
    }

    /**
     * Was a static singleton, now a singleton for this bridge.
     * Instantiate and start server if it doesn't exist.
     * We only listen on one host and port, as specified in the
     * sam.udp.host and sam.udp.port properties.
     * TODO we could have multiple servers on different hosts/ports in the future.
     *
     * @param props non-null instantiate and start server if it doesn't exist
     * @return non-null
     * @throws IOException if can't bind to host/port, or if different than existing
     * @since 0.9.24
     */
    SAMv3DatagramServer getV3DatagramServer(Properties props) throws IOException {
        String host = props.getProperty(PROP_DATAGRAM_HOST, DEFAULT_DATAGRAM_HOST);
        int port;
        String portStr = props.getProperty(PROP_DATAGRAM_PORT, DEFAULT_DATAGRAM_PORT);
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            port = DEFAULT_DATAGRAM_PORT_INT;
        }
        synchronized (_v3DGServerLock) {
            if (_v3DGServer == null) {
                _v3DGServer = new SAMv3DatagramServer(this, host, port, props);
                _v3DGServer.start();
            } else {
                if (_v3DGServer.getPort() != port || !_v3DGServer.getHost().equals(host))
                    throw new IOException("Already have V3 DatagramServer with host=" + host + " port=" + port);
            }
            return _v3DGServer;
        }
    }

    ////// begin ClientApp interface, use only if using correct construtor

    /**
     * @since 0.9.6
     */
    public synchronized void startup() throws IOException {
        if (_state != INITIALIZED)
            return;
        changeState(STARTING);
        synchronized (_handlers) {
            _handlers.clear();
        }
        loadKeys();
        try {
            openSocket();
        } catch (IOException e) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error starting SAM bridge on "
                        + (_listenHost == null ? "0.0.0.0" : _listenHost)
                        + ":" + _listenPort, e);
            changeState(START_FAILED, e);
            throw e;
        }
        startThread();
    }

    /**
     * As of 0.9.20, stops running handlers and sessions.
     *
     * @since 0.9.6
     */
    public synchronized void shutdown(String[] args) {
        if (_state != RUNNING)
            return;
        changeState(STOPPING);
        acceptConnections = false;
        stopHandlers();
        if (_runner != null)
            _runner.interrupt();
        else
            changeState(STOPPED);
    }

    /**
     * @since 0.9.6
     */
    public ClientAppState getState() {
        return _state;
    }

    /**
     * @since 0.9.6
     */
    public String getName() {
        return "SAM";
    }

    /**
     * @since 0.9.6
     */
    public String getDisplayName() {
        return "SAM " + _listenHost + ':' + _listenPort;
    }

    ////// end ClientApp interface
    ////// begin ClientApp helpers

    /**
     * @since 0.9.6
     */
    private void changeState(ClientAppState state) {
        changeState(state, null);
    }

    /**
     * @since 0.9.6
     */
    private synchronized void changeState(ClientAppState state, Exception e) {
        _state = state;
        if (_mgr != null)
            _mgr.notify(this, state, null, e);
    }

    ////// end ClientApp helpers

    private static class HelpRequestedException extends Exception {
        static final long serialVersionUID = 0x1;
    }

    /**
     * Usage:
     *
     * <pre>
     * SAMBridge [ keyfile [listenHost ] listenPort [ name=val ]* ]
     * </pre>
     *
     * or:
     *
     * <pre>
     * SAMBridge [ name=val ]*
     * </pre>
     *
     * name=val options are passed to the I2CP code to build a session,
     * allowing the bridge to specify an alternate I2CP host and port, tunnel
     * depth, etc.
     *
     * @param args [ keyfile [ listenHost ] listenPort [ name=val ]* ]
     */
    public static void main(String args[]) {
        try {
            Options options = getOptions(args);
            SAMBridge bridge = new SAMBridge(options.host, options.port, options.isSSL, options.opts,
                    options.keyFile, options.configFile);
            bridge.startThread();
        } catch (RuntimeException e) {
            e.printStackTrace();
            usage();
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            usage();
            throw new RuntimeException(e);
        }
    }

    /**
     * @since 0.9.6
     */
    private void startThread() {
        I2PAppThread t = new I2PAppThread(this, "SAMListener " + _listenPort);
        if (Boolean.parseBoolean(System.getProperty("sam.shutdownOnOOM"))) {
            t.addOOMEventThreadListener(new I2PAppThread.OOMEventListener() {
                public void outOfMemory(OutOfMemoryError err) {
                    err.printStackTrace();
                    System.err.println("OOMed, die die die");
                    System.exit(-1);
                }
            });
        }
        t.start();
        _runner = t;
    }

    /**
     * @since 0.9.6
     */
    private static class Options {
        private final String host, keyFile;
        private final int port;
        private final Properties opts;
        private final boolean isSSL;
        private final File configFile;

        public Options(String host, int port, boolean isSSL, Properties opts, String keyFile, File configFile) {
            this.host = host;
            this.port = port;
            this.opts = opts;
            this.keyFile = keyFile;
            this.isSSL = isSSL;
            this.configFile = configFile;
        }
    }

    /**
     * Usage:
     *
     * <pre>
     * SAMBridge [ keyfile [listenHost ] listenPort [ name=val ]* ]
     * </pre>
     *
     * or:
     *
     * <pre>
     * SAMBridge [ name=val ]*
     * </pre>
     *
     * name=val options are passed to the I2CP code to build a session,
     * allowing the bridge to specify an alternate I2CP host and port, tunnel
     * depth, etc.
     *
     * @param args [ keyfile [ listenHost ] listenPort [ name=val ]* ]
     * @return non-null Options or throws Exception
     * @throws HelpRequestedException   on command line problems
     * @throws IllegalArgumentException if specified config file does not exist
     * @throws IOException              if specified config file cannot be read, or
     *                                  on SSL keystore problems
     * @since 0.9.6
     */
    private static Options getOptions(String args[]) throws Exception {
        String keyfile = null;
        int port = -1;
        String host = null;
        boolean isSSL = false;
        String cfile = null;
        Getopt g = new Getopt("SAM", args, "hsc:");
        int c;
        while ((c = g.getopt()) != -1) {
            switch (c) {
                case 's':
                    isSSL = true;
                    break;

                case 'c':
                    cfile = g.getOptarg();
                    break;

                case 'h':
                case '?':
                case ':':
                default:
                    throw new HelpRequestedException();
            } // switch
        } // while

        int startArgs = g.getOptind();
        // possible args before ones containing '=';
        // (none)
        // key port
        // key host port
        int startOpts;
        for (startOpts = startArgs; startOpts < args.length; startOpts++) {
            if (args[startOpts].contains("="))
                break;
        }
        int numArgs = startOpts - startArgs;
        switch (numArgs) {
            case 0:
                break;

            case 2:
                keyfile = args[startArgs];
                try {
                    port = Integer.parseInt(args[startArgs + 1]);
                } catch (NumberFormatException nfe) {
                    throw new HelpRequestedException();
                }
                break;

            case 3:
                keyfile = args[startArgs];
                host = args[startArgs + 1];
                try {
                    port = Integer.parseInt(args[startArgs + 2]);
                } catch (NumberFormatException nfe) {
                    throw new HelpRequestedException();
                }
                break;

            default:
                throw new HelpRequestedException();
        }

        String scfile = cfile != null ? cfile : DEFAULT_SAM_CONFIGFILE;
        File file = new File(scfile);
        if (!file.isAbsolute())
            file = new File(I2PAppContext.getGlobalContext().getConfigDir(), scfile);

        Properties opts = new Properties();
        if (file.exists()) {
            DataHelper.loadProps(opts, file);
        } else if (cfile != null) {
            // only throw if specified on command line
            throw new IllegalArgumentException("Config file not found: " + file);
        }
        // command line trumps config file trumps defaults
        if (host == null)
            host = opts.getProperty(PROP_TCP_HOST, DEFAULT_TCP_HOST);
        if (port < 0) {
            try {
                port = Integer.parseInt(opts.getProperty(PROP_TCP_PORT, DEFAULT_TCP_PORT));
            } catch (NumberFormatException nfe) {
                throw new HelpRequestedException();
            }
        }
        if (keyfile == null)
            keyfile = opts.getProperty(PROP_SAM_KEYFILE, DEFAULT_SAM_KEYFILE);
        if (!isSSL)
            isSSL = Boolean.parseBoolean(opts.getProperty(PROP_SAM_SSL));
        if (isSSL) {
            // must do this before we add command line opts since we may be writing them
            // back out
            boolean shouldSave = SSLUtil.verifyKeyStore(opts);
            if (shouldSave)
                DataHelper.storeProps(opts, file);
        }

        int remaining = args.length - startOpts;
        if (remaining > 0) {
            parseOptions(args, startOpts, opts);
        }
        return new Options(host, port, isSSL, opts, keyfile, file);
    }

    /**
     * Parse key=value options starting at startArgs.
     *
     * @param props out parameter, any options found are added
     * @throws HelpRequestedException on any item not of the form key=value.
     */
    private static void parseOptions(String args[], int startArgs, Properties props) throws HelpRequestedException {
        for (int i = startArgs; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0)
                throw new HelpRequestedException();
            if (eq >= args[i].length() - 1)
                throw new HelpRequestedException();
            String key = args[i].substring(0, eq);
            String val = args[i].substring(eq + 1);
            key = key.trim();
            val = val.trim();
            if ((key.length() > 0) && (val.length() > 0))
                props.setProperty(key, val);
            else
                throw new HelpRequestedException();
        }
    }

    private static void usage() {
        System.err.println("Usage: SAMBridge [-s] [-c sam.config] [keyfile [listenHost] listenPortNum[ name=val]*]\n" +
                "or:\n" +
                "       SAMBridge [ name=val ]*\n" +
                " -s: Use SSL\n" +
                " -c sam.config: Specify config file\n" +
                " keyfile: location to persist private keys (default sam.keys)\n" +
                " listenHost: interface to listen on (0.0.0.0 for all interfaces)\n" +
                " listenPort: port to listen for SAM connections on (default 7656)\n" +
                " name=val: options to pass when connecting via I2CP, such as \n" +
                "           i2cp.host=localhost and i2cp.port=7654\n" +
                "\n" +
                "Host and ports of the SAM bridge can be specified with the alternate\n" +
                "form by specifying options " + SAMBridge.PROP_TCP_HOST + " and/or " +
                SAMBridge.PROP_TCP_PORT +
                "\n" +
                "Options " + SAMBridge.PROP_DATAGRAM_HOST + " and " + SAMBridge.PROP_DATAGRAM_PORT +
                " specify the listening ip\n" +
                "range and the port of SAM datagram server. This server is\n" +
                "only launched after a client creates the first SAM datagram\n" +
                "or raw session, after a handshake with SAM version >= 3.0.\n" +
                "\n" +
                "The option loglevel=[DEBUG|WARN|ERROR|CRIT] can be used\n" +
                "for tuning the log verbosity.");
    }

    public void run() {
        if (serverSocket == null)
            return;
        changeState(RUNNING);
        if (_mgr != null)
            _mgr.register(this);
        I2PAppContext.getGlobalContext().portMapper().register(_useSSL ? PortMapper.SVC_SAM_SSL : PortMapper.SVC_SAM,
                _listenHost != null ? _listenHost : "127.0.0.1",
                _listenPort);
        try {
            while (acceptConnections) {
                SocketChannel s = serverSocket.accept();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("New connection from "
                            + s.socket().getInetAddress().toString() + ":"
                            + s.socket().getPort());

                class HelloHandler implements Runnable, Handler {
                    private final SocketChannel s;
                    private final SAMBridge parent;

                    HelloHandler(SocketChannel s, SAMBridge parent) {
                        this.s = s;
                        this.parent = parent;
                    }

                    public void run() {
                        parent.register(this);
                        try {
                            SAMHandler handler = SAMHandlerFactory.createSAMHandler(s, i2cpProps, parent);
                            if (handler == null) {
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("SAM handler has not been instantiated");
                                try {
                                    s.close();
                                } catch (IOException e) {
                                }
                                return;
                            }
                            handler.startHandling();
                        } catch (SAMException e) {
                            if (_log.shouldLog(Log.ERROR))
                                _log.error("SAM error: " + e.getMessage(), e);
                            String reply = "HELLO REPLY RESULT=I2P_ERROR MESSAGE=\"" + e.getMessage() + "\"\n";
                            SAMHandler.writeString(reply, s);
                            try {
                                s.close();
                            } catch (IOException ioe) {
                            }
                        } catch (Exception ee) {
                            try {
                                s.close();
                            } catch (IOException ioe) {
                            }
                            _log.log(Log.CRIT, "Unexpected error handling SAM connection", ee);
                        } finally {
                            parent.unregister(this);
                        }
                    }

                    /** @since 0.9.20 */
                    public void stopHandling() {
                        try {
                            s.close();
                        } catch (IOException ioe) {
                        }
                    }
                }
                new I2PAppThread(new HelloHandler(s, this), "SAM HelloHandler").start();
            }
            changeState(STOPPING);
        } catch (Exception e) {
            if (acceptConnections)
                _log.error("Unexpected error while listening for connections", e);
            else
                e = null;
            changeState(STOPPING, e);
        } finally {
            try {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Shutting down, closing server socket");
                if (serverSocket != null)
                    serverSocket.close();
            } catch (IOException e) {
            }
            I2PAppContext.getGlobalContext().portMapper()
                    .unregister(_useSSL ? PortMapper.SVC_SAM_SSL : PortMapper.SVC_SAM);
            stopHandlers();
            changeState(STOPPED);
        }
    }

    /** @since 0.9.24 */
    public void saveConfig() throws IOException {
        DataHelper.storeProps(i2cpProps, _configFile);
    }

    /*
     * Returns the interactive Secure Session manager which requires SAM
     * applications to seek "approval" for their initial connections from the user
     * before they can start the session.
     *
     * @since 1.8.0
     */
    public SAMSecureSessionInterface secureSession() {
        if (_secureSession == null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("SAMBridge.secureSession() called when secureSession is null, creating default I2CP auth");
            boolean attemptauth = Boolean.parseBoolean(i2cpProps.getProperty(SAMBridge.PROP_AUTH));
            if (attemptauth) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("SAMBridge.secureSession() called when authentication is enabled");
                SAMSecureSessionInterface secureSession = new SAMSecureSession();
                return secureSession;
            }
        }
        return _secureSession;
    }
}
