package net.i2p.client.streaming;

import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.util.Log;


/**
 * Centralize the coordination and multiplexing of the local client's streaming.
 * There should be one I2PSocketManager for each I2PSession, and if an application
 * is sending and receiving data through the streaming library using an
 * I2PSocketManager, it should not attempt to call I2PSession's setSessionListener
 * or receive any messages with its .receiveMessage
 *
 * This is what I2PSocketManagerFactory.createManager() returns.
 * Direct instantiation by others is deprecated.
 */
public class I2PSocketManagerFull implements I2PSocketManager {
    private I2PAppContext _context;
    private Log _log;
    private I2PSession _session;
    private I2PServerSocketFull _serverSocket;
    private StandardServerSocket _realServerSocket;
    private ConnectionOptions _defaultOptions;
    private long _acceptTimeout;
    private String _name;
    private int _maxStreams;
    private static int __managerId = 0;
    private ConnectionManager _connectionManager;
    
    /**
     * How long to wait for the client app to accept() before sending back CLOSE?
     * This includes the time waiting in the queue.  Currently set to 5 seconds.
     */
    private static final long ACCEPT_TIMEOUT_DEFAULT = 5*1000;
    
    public I2PSocketManagerFull() {
    }

    /**
     * 
     * @param context
     * @param session
     * @param opts
     * @param name
     */
    public I2PSocketManagerFull(I2PAppContext context, I2PSession session, Properties opts, String name) {
        this();
        init(context, session, opts, name);
    }
    
    /** how many streams will we allow at once?  */
    public static final String PROP_MAX_STREAMS = "i2p.streaming.maxConcurrentStreams";
    
    /**
     *
     * 
     * @param context
     * @param session
     * @param opts
     * @param name 
     */
    public void init(I2PAppContext context, I2PSession session, Properties opts, String name) {
        _context = context;
        _session = session;
        _log = _context.logManager().getLog(I2PSocketManagerFull.class);
        
        _maxStreams = -1;
        try {
            String num = (opts != null ? opts.getProperty(PROP_MAX_STREAMS, "-1") : "-1");
            _maxStreams = Integer.parseInt(num);
        } catch (NumberFormatException nfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid max # of concurrent streams, defaulting to unlimited", nfe);
            _maxStreams = -1;
        }
        _name = name + " " + (++__managerId);
        _acceptTimeout = ACCEPT_TIMEOUT_DEFAULT;
        _defaultOptions = new ConnectionOptions(opts);
        _connectionManager = new ConnectionManager(_context, _session, _maxStreams, _defaultOptions);
        _serverSocket = new I2PServerSocketFull(this);
        
        if (_log.shouldLog(Log.INFO)) {
            _log.info("Socket manager created.  \ndefault options: " + _defaultOptions
                      + "\noriginal properties: " + opts);
        }
    }

    public I2PSocketOptions buildOptions() { return buildOptions(null); }
    public I2PSocketOptions buildOptions(Properties opts) {
        ConnectionOptions curOpts = new ConnectionOptions(_defaultOptions);
        curOpts.setProperties(opts);
        return curOpts;
    }
    
    public I2PSession getSession() {
        return _session;
    }
    
    public ConnectionManager getConnectionManager() {
        return _connectionManager;
    }

    /**
     * 
     * @return connected I2PSocket OR NULL
     * @throws net.i2p.I2PException
     * @throws java.net.SocketTimeoutException
     */
    public I2PSocket receiveSocket() throws I2PException, SocketTimeoutException {
        verifySession();
        Connection con = _connectionManager.getConnectionHandler().accept(_connectionManager.getSoTimeout());
        if(_log.shouldLog(Log.DEBUG)) {
            _log.debug("receiveSocket() called: " + con);
        }
        if (con != null) {
            I2PSocketFull sock = new I2PSocketFull(con);
            con.setSocket(sock);
            return sock;
        } else { 
            if(_connectionManager.getSoTimeout() == -1) {
                return null;
            }
            throw new SocketTimeoutException("I2PSocket timed out");
        }
    }
    
    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     * 
     * @param peer
     * @param timeoutMs
     * @return true on success, false on failure
     */
    public boolean ping(Destination peer, long timeoutMs) {
        return _connectionManager.ping(peer, timeoutMs);
    }

    /**
     * How long should we wait for the client to .accept() a socket before
     * sending back a NACK/Close?  
     *
     * @param ms milliseconds to wait, maximum
     */
    public void setAcceptTimeout(long ms) { _acceptTimeout = ms; }
    public long getAcceptTimeout() { return _acceptTimeout; }

    public void setDefaultOptions(I2PSocketOptions options) {
        _defaultOptions = new ConnectionOptions((ConnectionOptions) options);
    }

    public I2PSocketOptions getDefaultOptions() {
        return _defaultOptions;
    }

    public I2PServerSocket getServerSocket() {
        _connectionManager.setAllowIncomingConnections(true);
        return _serverSocket;
    }

    /**
     *  Like getServerSocket but returns a real ServerSocket for easier porting of apps.
     *  @since 0.8.4
     */
    public synchronized ServerSocket getStandardServerSocket() throws IOException {
        if (_realServerSocket == null)
            _realServerSocket = new StandardServerSocket(_serverSocket);
        _connectionManager.setAllowIncomingConnections(true);
        return _realServerSocket;
    }

    private void verifySession() throws I2PException {
        if (!_connectionManager.getSession().isClosed())
            return;
        _connectionManager.getSession().connect();
    }
    
    /**
     * Create a new connected socket. Blocks until the socket is created,
     * unless the connectDelay option (i2p.streaming.connectDelay) is
     * set and greater than zero. If so this will return immediately,
     * and the client may quickly write initial data to the socket and
     * this data will be bundled in the SYN packet.
     *
     * @param peer Destination to connect to
     * @param options I2P socket options to be used for connecting, may be null
     *
     * @return I2PSocket if successful
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket connect(Destination peer, I2PSocketOptions options) 
                             throws I2PException, NoRouteToHostException {
        verifySession();
        if (options == null)
            options = _defaultOptions;
        ConnectionOptions opts = null;
        if (options instanceof ConnectionOptions)
            opts = new ConnectionOptions((ConnectionOptions)options);
        else
            opts = new ConnectionOptions(options);
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Connecting to " + peer.calculateHash().toBase64().substring(0,6) 
                      + " with options: " + opts);
        // the following blocks unless connect delay > 0
        Connection con = _connectionManager.connect(peer, opts);
        if (con == null)
            throw new TooManyStreamsException("Too many streams (max " + _maxStreams + ")");
        I2PSocketFull socket = new I2PSocketFull(con);
        con.setSocket(socket);
        if (con.getConnectionError() != null) { 
            con.disconnect(false);
            throw new NoRouteToHostException(con.getConnectionError());
        }
        return socket;
    }

    /**
     * Create a new connected socket. Blocks until the socket is created,
     * unless the connectDelay option (i2p.streaming.connectDelay) is
     * set and greater than zero in the default options. If so this will return immediately,
     * and the client may quickly write initial data to the socket and
     * this data will be bundled in the SYN packet.
     *
     * @param peer Destination to connect to
     *
     * @return I2PSocket if successful
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket connect(Destination peer) throws I2PException, NoRouteToHostException {
        return connect(peer, _defaultOptions);
    }

    /**
     *  Like connect() but returns a real Socket, and throws only IOE,
     *  for easier porting of apps.
     *  @since 0.8.4
     */
    public Socket connectToSocket(Destination peer) throws IOException {
        return connectToSocket(peer, _defaultOptions);
    }

    /**
     *  Like connect() but returns a real Socket, and throws only IOE,
     *  for easier porting of apps.
     *  @param timeout ms if > 0, forces blocking (disables connectDelay)
     *  @since 0.8.4
     */
    public Socket connectToSocket(Destination peer, int timeout) throws IOException {
        ConnectionOptions opts = new ConnectionOptions(_defaultOptions);
        opts.setConnectTimeout(timeout);
        if (timeout > 0)
            opts.setConnectDelay(-1);
        return connectToSocket(peer, opts);
    }

    /**
     *  Like connect() but returns a real Socket, and throws only IOE,
     *  for easier porting of apps.
     *  @param options may be null
     *  @since 0.8.4
     */
    private Socket connectToSocket(Destination peer, I2PSocketOptions options) throws IOException {
        try {
            I2PSocket sock = connect(peer, options);
            return new StandardSocket(sock);
        } catch (I2PException i2pe) {
            // fixme in 1.6 change to cause
            throw new IOException(i2pe.toString());
        }
    }

    /**
     * Destroy the socket manager, freeing all the associated resources.  This
     * method will block untill all the managed sockets are closed.
     *
     */
    public void destroySocketManager() {
        _connectionManager.setAllowIncomingConnections(false);
        _connectionManager.disconnectAllHard();
        // should we destroy the _session too?
        // yes, since the old lib did (and SAM wants it to, and i dont know why not)
        if ( (_session != null) && (!_session.isClosed()) ) {
            try {
                _session.destroySession();
            } catch (I2PSessionException ise) {
                _log.warn("Unable to destroy the session", ise);
            }
        }
    }

    /**
     * Retrieve a set of currently connected I2PSockets, either initiated locally or remotely.
     *
     * @return set of currently connected I2PSockets
     */
    public Set<I2PSocket> listSockets() {
        Set<Connection> connections = _connectionManager.listConnections();
        Set<I2PSocket> rv = new HashSet(connections.size());
        for (Connection con : connections) {
            if (con.getSocket() != null)
                rv.add(con.getSocket());
        }
        return rv;
    }

    public String getName() { return _name; }
    public void setName(String name) { _name = name; }
    
    
    public void addDisconnectListener(I2PSocketManager.DisconnectListener lsnr) { 
        _connectionManager.getMessageHandler().addDisconnectListener(lsnr);
    }
    public void removeDisconnectListener(I2PSocketManager.DisconnectListener lsnr) {
        _connectionManager.getMessageHandler().removeDisconnectListener(lsnr);
    }
}
