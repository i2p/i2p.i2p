package net.i2p.client.streaming.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.crypto.SigAlgo;
import net.i2p.crypto.SigType;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.ConvertToHash;
import net.i2p.util.ConcurrentHashSet;
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
    private final I2PAppContext _context;
    private final Log _log;
    private final I2PSession _session;
    private final Set<I2PSession> _subsessions;
    private final I2PServerSocketFull _serverSocket;
    private StandardServerSocket _realServerSocket;
    private final ConnectionOptions _defaultOptions;
    private long _acceptTimeout;
    private String _name;
    private static final AtomicInteger __managerId = new AtomicInteger();
    private final ConnectionManager _connectionManager;
    private final AtomicBoolean _isDestroyed = new AtomicBoolean();

    /**
     *  Does not support EC
     *  @since 0.9.21
     */
    private static final Set<Hash> _ecUnsupported = new HashSet<Hash>(16);
    private static final String[] EC_UNSUPPORTED_HASHES = {
        // list from http://zzz.i2p/topics/1682?page=1#p8414
        // bzr.welterde.i2p
        "Cvs1gCZTTkgD2Z2byh2J9atPmh5~I8~L7BNQnQl0hUE=",
        // docs.i2p2.i2p
        "WCXV87RdrF6j-mnn6qt7kVSBifHTlPL0PmVMFWwaolo=",
        // flibusta.i2p
        "yy2hYtqqfl84N9skwdRkeM7baFMXHKyDWU3XRShlEo8=",
        // forum.i2p
        "3t5Ar2NCTIOId70uzX2bZyJljR0aBogxMEzNyHirB7A=",
        // i2jump.i2p
        "9vaoGZbOaeqdRK2qEunlwRM9mUSW-I9R4OON35TDKK4=",
        // irc.welterde.i2p
        "5rjezx4McFk3bNhoJV-NTLlQW1AR~jiUcN6DOWMCCVc=",
        // lists.i2p2.i2p
        "qwtgoFoMSK0TOtbT4ovBX1jHUzCoZCPzrJVxjKD7RCg=",
        // mtn.i2p2.i2p
        "X5VDzYaoX9-P6bAWnrVSR5seGLkOeORP2l3Mh4drXPo=",
        // nntp.welterde.i2p
        "VXwmNIwMy1BcUVmut0oZ72jbWoqFzvxJukmS-G8kAAE=",
        // paste.i2p2.i2p
        "DoyMyUUgOSTddvRpqYfKHFPPjkkX~iQmResyfjjBYWs=",
        // syndie.welterde.i2p
        "xMxC54BFgyp-~zzrQI3F8m2CK--9XMcNmSAep6RH4Kk=",
        // ugha.i2p
        "zsu3WF~QLBxZXH-gHq9MuZE6y8ROZmMF7dA2MbMMKkY=",
        // tracker.welterde.i2p
        "EVkFgKkrDKyGfI7TIuDmlHoAmvHC~FbnY946DfujR0A=",
        // www.i2p2.i2p
        "im9gytzKT15mT1sB5LC9bHXCcwytQ4EPcrGQhoam-4w="
    };

    /**
     *  Does not support Ed
     *  @since 0.9.23
     */
    private static final Set<Hash> _edUnsupported = new HashSet<Hash>(16);
    private static final String[] ED_UNSUPPORTED_HASHES = {
        // list from http://zzz.i2p/topics/1682?page=1#p8414
        // minus those tested to support Ed
        // last tested 2015-11-04
        // bzr.welterde.i2p
        "Cvs1gCZTTkgD2Z2byh2J9atPmh5~I8~L7BNQnQl0hUE=",
        // docs.i2p2.i2p
        "WCXV87RdrF6j-mnn6qt7kVSBifHTlPL0PmVMFWwaolo=",
        // i2jump.i2p
        "9vaoGZbOaeqdRK2qEunlwRM9mUSW-I9R4OON35TDKK4=",
        // irc.welterde.i2p
        "5rjezx4McFk3bNhoJV-NTLlQW1AR~jiUcN6DOWMCCVc=",
        // lists.i2p2.i2p
        "qwtgoFoMSK0TOtbT4ovBX1jHUzCoZCPzrJVxjKD7RCg=",
        // mtn.i2p2.i2p
        "X5VDzYaoX9-P6bAWnrVSR5seGLkOeORP2l3Mh4drXPo=",
        // nntp.welterde.i2p
        "VXwmNIwMy1BcUVmut0oZ72jbWoqFzvxJukmS-G8kAAE=",
        // paste.i2p2.i2p
        "DoyMyUUgOSTddvRpqYfKHFPPjkkX~iQmResyfjjBYWs=",
        // syndie.welterde.i2p
        "xMxC54BFgyp-~zzrQI3F8m2CK--9XMcNmSAep6RH4Kk=",
        // tracker.welterde.i2p
        "EVkFgKkrDKyGfI7TIuDmlHoAmvHC~FbnY946DfujR0A=",
        // www.i2p2.i2p
        "im9gytzKT15mT1sB5LC9bHXCcwytQ4EPcrGQhoam-4w="
    };
    
    static {
        for (int i = 0; i < EC_UNSUPPORTED_HASHES.length; i++) {
            String s = EC_UNSUPPORTED_HASHES[i];
            Hash h = ConvertToHash.getHash(s);
            if (h != null)
                _ecUnsupported.add(h);
            else
                System.out.println("Bad hash " + s);
        }
        for (int i = 0; i < ED_UNSUPPORTED_HASHES.length; i++) {
            String s = ED_UNSUPPORTED_HASHES[i];
            Hash h = ConvertToHash.getHash(s);
            if (h != null)
                _edUnsupported.add(h);
            else
                System.out.println("Bad hash " + s);
        }
    }

    /** cache of the property to detect changes */
    private static volatile String _userDsaList = "";
    private static final Set<Hash> _userDsaOnly = new ConcurrentHashSet<Hash>(4);
    private static final String PROP_DSALIST = "i2p.streaming.dsalist";

    /**
     * How long to wait for the client app to accept() before sending back CLOSE?
     * This includes the time waiting in the queue.  Currently set to 5 seconds.
     */
    private static final long ACCEPT_TIMEOUT_DEFAULT = 5*1000;

    /**
     * @deprecated use 4-arg constructor
     * @throws UnsupportedOperationException always
     */
    @Deprecated
    public I2PSocketManagerFull() {
        throw new UnsupportedOperationException();
    }
    
    /**
     * @deprecated use 4-arg constructor
     * @throws UnsupportedOperationException always
     */
    @Deprecated
    public void init(I2PAppContext context, I2PSession session, Properties opts, String name) {
        throw new UnsupportedOperationException();
    }

    /**
     * This is what I2PSocketManagerFactory.createManager() returns.
     * Direct instantiation by others is deprecated.
     * 
     * @param context non-null
     * @param session non-null
     * @param opts may be null
     * @param name non-null
     */
    public I2PSocketManagerFull(I2PAppContext context, I2PSession session, Properties opts, String name) {
        _context = context;
        _session = session;
        _subsessions = new ConcurrentHashSet<I2PSession>(4);
        _log = _context.logManager().getLog(I2PSocketManagerFull.class);
        
        _name = name + " " + (__managerId.incrementAndGet());
        _acceptTimeout = ACCEPT_TIMEOUT_DEFAULT;
        _defaultOptions = new ConnectionOptions(opts);
        _connectionManager = new ConnectionManager(_context, _session, _defaultOptions);
        _serverSocket = new I2PServerSocketFull(this);
        
        if (_log.shouldLog(Log.INFO)) {
            _log.info("Socket manager created.  \ndefault options: " + _defaultOptions
                      + "\noriginal properties: " + opts);
        }
        debugInit(context);
    }

    /**
     *  Create a copy of the current options, to be used in a setDefaultOptions() call.
     */
    public I2PSocketOptions buildOptions() { return buildOptions(null); }

    /**
     *  Create a modified copy of the current options, to be used in a setDefaultOptions() call.
     *
     *  As of 0.9.19, defaults in opts are honored.
     *
     *  @param opts The new options, may be null
     */
    public I2PSocketOptions buildOptions(Properties opts) {
        ConnectionOptions curOpts = new ConnectionOptions(_defaultOptions);
        curOpts.setProperties(opts);
        return curOpts;
    }
    
    /**
     *  @return the session, non-null
     */
    public I2PSession getSession() {
        return _session;
    }
    
    /**
     *  For a server, you must call connect() on the returned object.
     *  Connecting the primary session does NOT connect any subsessions.
     *  If the primary session is not connected, connecting a subsession will connect the primary session first.
     *
     *  @return a new subsession, non-null
     *  @param privateKeyStream null for transient, if non-null must have same encryption keys as primary session
     *                          and different signing keys
     *  @param opts subsession options if any, may be null
     *  @since 0.9.21
     */
    public I2PSession addSubsession(InputStream privateKeyStream, Properties opts) throws I2PSessionException {
        if (privateKeyStream == null) {
            // We don't actually need the same pubkey in the dest, just in the LS.
            // The dest one is unused. But this is how we find the LS keys
            // to reuse in RequestLeaseSetMessageHandler.
            ByteArrayOutputStream keyStream = new ByteArrayOutputStream(1024);
            try {
                SigType type = getSigType(opts);
                if (type != SigType.DSA_SHA1) {
                    // hassle, have to set up the padding and cert, see I2PClientImpl
                    throw new I2PSessionException("type " + type + " unsupported");
                }
                PublicKey pub = _session.getMyDestination().getPublicKey();
                PrivateKey priv = _session.getDecryptionKey();
                SimpleDataStructure[] keys = _context.keyGenerator().generateSigningKeys(type);
                pub.writeBytes(keyStream);
                keys[0].writeBytes(keyStream); // signing pub
                Certificate.NULL_CERT.writeBytes(keyStream);
                priv.writeBytes(keyStream);
                keys[1].writeBytes(keyStream); // signing priv
            } catch (GeneralSecurityException e) {
                throw new I2PSessionException("Error creating keys", e);
            } catch (I2PException e) {
                throw new I2PSessionException("Error creating keys", e);
            } catch (IOException e) {
                throw new I2PSessionException("Error creating keys", e);
            } catch (RuntimeException e) {
                throw new I2PSessionException("Error creating keys", e);
            }
            privateKeyStream = new ByteArrayInputStream(keyStream.toByteArray());
        }
        I2PSession rv = _session.addSubsession(privateKeyStream, opts);
        boolean added = _subsessions.add(rv);
        if (!added) {
            // shouldn't happen
            _session.removeSubsession(rv);
            throw new I2PSessionException("dup");
        }
        ConnectionOptions defaultOptions = new ConnectionOptions(opts);
        int protocol = defaultOptions.getEnforceProtocol() ? I2PSession.PROTO_STREAMING : I2PSession.PROTO_ANY;
        rv.addMuxedSessionListener(_connectionManager.getMessageHandler(), protocol, defaultOptions.getLocalPort());
        if (_log.shouldLog(Log.WARN))
            _log.warn("Added subsession " + rv);
        return rv;
    }

    /**
     *  @param opts may be null
     *  @since 0.9.21 copied from I2PSocketManagerFactory
     */
    private SigType getSigType(Properties opts) {
        if (opts != null) {
            String st = opts.getProperty(I2PClient.PROP_SIGTYPE);
            if (st != null) {
                SigType rv = SigType.parseSigType(st);
                if (rv != null && rv.isAvailable())
                    return rv;
                if (rv != null)
                    st = rv.toString();
                _log.logAlways(Log.WARN, "Unsupported sig type " + st +
                                         ", reverting to " + I2PClient.DEFAULT_SIGTYPE);
                // TODO throw instead?
            }
        }
        return I2PClient.DEFAULT_SIGTYPE;
    }
    
    /**
     *  Remove the subsession
     *
     *  @since 0.9.21
     */
    public void removeSubsession(I2PSession session) {
        _session.removeSubsession(session);
        boolean removed = _subsessions.remove(session);
        if (removed) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Removeed subsession " + session);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Subsession not found to remove " + session);
        }
    }
    
    /**
     *  @return a list of subsessions, non-null, does not include the primary session
     *  @since 0.9.21
     */
    public List<I2PSession> getSubsessions() {
        return _session.getSubsessions();
    }
    
    public ConnectionManager getConnectionManager() {
        return _connectionManager;
    }

    /**
     * The accept() call.
     * 
     * This only listens on the primary session. There is no way to get
     * incoming connections on a subsession.
     * 
     * @return connected I2PSocket, or null through 0.9.16, non-null as of 0.9.17
     * @throws I2PException if session is closed
     * @throws RouterRestartException (extends I2PException) if the router is apparently restarting, since 0.9.34
     * @throws ConnectException (since 0.9.17; I2PServerSocket interface always declared it)
     * @throws SocketTimeoutException if a timeout was previously set with setSoTimeout and the timeout has been reached.
     */
    public I2PSocket receiveSocket() throws I2PException, ConnectException, SocketTimeoutException {
        verifySession();
        Connection con = _connectionManager.getConnectionHandler().accept(_connectionManager.getSoTimeout());
        I2PSocketFull sock = new I2PSocketFull(con, _context);
        con.setSocket(sock);
        return sock;
    }
    
    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     * Uses the ports from the default options.
     * 
     * TODO There is no way to ping on a subsession.
     * 
     * @param peer
     * @param timeoutMs timeout in ms, greater than zero
     * @return true on success, false on failure
     * @throws IllegalArgumentException
     */
    public boolean ping(Destination peer, long timeoutMs) {
        if (timeoutMs <= 0)
            throw new IllegalArgumentException("bad timeout");
        return _connectionManager.ping(peer, _defaultOptions.getLocalPort(),
                                       _defaultOptions.getPort(), timeoutMs);
    }

    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     * Uses the ports specified.
     * 
     * TODO There is no way to ping on a subsession.
     *
     * @param peer Destination to ping
     * @param localPort 0 - 65535
     * @param remotePort 0 - 65535
     * @param timeoutMs timeout in ms, greater than zero
     * @return success or failure
     * @throws IllegalArgumentException
     * @since 0.9.12
     */
    public boolean ping(Destination peer, int localPort, int remotePort, long timeoutMs) {
        if (localPort < 0 || localPort > 65535 ||
            remotePort < 0 || remotePort > 65535)
            throw new IllegalArgumentException("bad port");
        if (timeoutMs <= 0)
            throw new IllegalArgumentException("bad timeout");
        return _connectionManager.ping(peer, localPort, remotePort, timeoutMs);
    }

    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     * Uses the ports specified.
     * 
     * TODO There is no way to ping on a subsession.
     *
     * @param peer Destination to ping
     * @param localPort 0 - 65535
     * @param remotePort 0 - 65535
     * @param timeoutMs timeout in ms, greater than zero
     * @param payload to include in the ping
     * @return the payload received in the pong, zero-length if none, null on failure or timeout
     * @throws IllegalArgumentException
     * @since 0.9.18
     */
    public byte[] ping(Destination peer, int localPort, int remotePort, long timeoutMs, byte[] payload) {
        if (localPort < 0 || localPort > 65535 ||
            remotePort < 0 || remotePort > 65535)
            throw new IllegalArgumentException("bad port");
        if (timeoutMs <= 0)
            throw new IllegalArgumentException("bad timeout");
        return _connectionManager.ping(peer, localPort, remotePort, timeoutMs, payload);
    }

    /**
     * How long should we wait for the client to .accept() a socket before
     * sending back a NACK/Close?  
     *
     * @param ms milliseconds to wait, maximum
     */
    public void setAcceptTimeout(long ms) { _acceptTimeout = ms; }
    public long getAcceptTimeout() { return _acceptTimeout; }

    /**
     *  Update the options on a running socket manager.
     *  Parameters in the I2PSocketOptions interface may be changed directly
     *  with the setters; no need to use this method for those.
     *  This does NOT update the underlying I2CP or tunnel options; use getSession().updateOptions() for that.
     * 
     *  TODO There is no way to update the options on a subsession.
     *
     *  @param options as created from a call to buildOptions(properties), non-null
     */
    public void setDefaultOptions(I2PSocketOptions options) {
        if (!(options instanceof ConnectionOptions))
            throw new IllegalArgumentException();
        if (_log.shouldLog(Log.WARN))
            _log.warn("Changing options from:\n " + _defaultOptions + "\nto:\n " + options);
        _defaultOptions.updateAll((ConnectionOptions) options);
        _connectionManager.updateOptions();
    }

    /**
     *  Current options, not a copy, setters may be used to make changes.
     * 
     *  TODO There is no facility to specify the session.
     */
    public I2PSocketOptions getDefaultOptions() {
        return _defaultOptions;
    }

    /**
     *  Returns non-null socket.
     *  This method does not throw exceptions, but methods on the returned socket
     *  may throw exceptions if the socket or socket manager is closed.
     * 
     *  This only listens on the primary session. There is no way to get
     *  incoming connections on a subsession.
     *
     *  @return non-null
     */
    public I2PServerSocket getServerSocket() {
        _connectionManager.setAllowIncomingConnections(true);
        return _serverSocket;
    }

    /**
     *  Like getServerSocket but returns a real ServerSocket for easier porting of apps.
     * 
     *  This only listens on the primary session. There is no way to get
     *  incoming connections on a subsession.
     * 
     *  @since 0.8.4
     */
    public synchronized ServerSocket getStandardServerSocket() throws IOException {
        if (_realServerSocket == null)
            _realServerSocket = new StandardServerSocket(_serverSocket);
        _connectionManager.setAllowIncomingConnections(true);
        return _realServerSocket;
    }

    private void verifySession() throws I2PException {
        verifySession(_connectionManager.getSession());
    }

    /** @since 0.9.21 */
    private void verifySession(I2PSession session) throws I2PException {
        if (_isDestroyed.get())
            throw new I2PException("Session was closed");
        if (!session.isClosed())
            return;
        session.connect();
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
        if (peer == null)
            throw new NullPointerException();
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
        // pick the subsession here
        I2PSession session = _session;
        if (!_subsessions.isEmpty()) {
            updateUserDsaList();
            Hash h = peer.calculateHash();
            SigAlgo myAlgo = session.getMyDestination().getSigType().getBaseAlgorithm();
            if ((myAlgo == SigAlgo.EC && _ecUnsupported.contains(h)) ||
                (myAlgo == SigAlgo.EdDSA && _edUnsupported.contains(h)) ||
                (!_userDsaOnly.isEmpty() && _userDsaOnly.contains(h))) {
                // FIXME just taking the first one for now
                for (I2PSession sess : _subsessions) {
                    if (sess.getMyDestination().getSigType() == SigType.DSA_SHA1) {
                        session = sess;
                        break;
                    }
                }
            }
        }
        verifySession(session);
        // the following blocks unless connect delay > 0
        Connection con = _connectionManager.connect(peer, opts, session);
        if (con == null)
            throw new TooManyStreamsException("Too many streams, max " + _defaultOptions.getMaxConns());
        I2PSocketFull socket = new I2PSocketFull(con,_context);
        con.setSocket(socket);
        if (con.getConnectionError() != null) { 
            con.disconnect(false);
            throw new NoRouteToHostException(con.getConnectionError());
        }
        return socket;
    }

    /**
     * Update the global user DSA-only list.
     * This does not affect the hardcoded Ex_UNSUPPORTED_HASHES lists above,
     * the user can only add, not remove.
     *
     * @since 0.9.21
     */
    private void updateUserDsaList() {
        String hashes = _context.getProperty(PROP_DSALIST, "");
        if (!_userDsaList.equals(hashes)) {
            // rebuild _userDsaOnly when property changes
            synchronized(_userDsaOnly) {
                if (hashes.length() > 0) {
                    Set<Hash> newSet = new HashSet<Hash>();
                    StringTokenizer tok = new StringTokenizer(hashes, ",; ");
                    while (tok.hasMoreTokens()) {
                        String hashstr = tok.nextToken();
                        Hash hh = ConvertToHash.getHash(hashstr);
                        if (hh != null)
                            newSet.add(hh);
                        else
                            _log.error("Bad " + PROP_DSALIST + " entry: " + hashstr);
                    }
                    _userDsaOnly.addAll(newSet);
                    _userDsaOnly.retainAll(newSet);
                    _userDsaList = hashes;
                } else {
                    _userDsaOnly.clear();
                    _userDsaList = "";
                }
            }
        }
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
     *  @param timeout ms if &gt; 0, forces blocking (disables connectDelay)
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
            IOException ioe = new IOException("connect fail");
            ioe.initCause(i2pe);
            throw ioe;
        }
    }

    /**
     * Destroy the socket manager, freeing all the associated resources.  This
     * method will block until all the managed sockets are closed.
     *
     * CANNOT be restarted.
     */
    public void destroySocketManager() {
        if (!_isDestroyed.compareAndSet(false,true)) {
            // shouldn't happen, log a stack trace to find out why it happened
            _log.logCloseLoop("I2PSocketManager", getName());
            return;
        }
        _connectionManager.setAllowIncomingConnections(false);
        _connectionManager.shutdown();
        if (!_subsessions.isEmpty()) {
            for (I2PSession sess : _subsessions) {
                 removeSubsession(sess);
            }
        }

        // should we destroy the _session too?
        // yes, since the old lib did (and SAM wants it to, and i dont know why not)
        if ( (_session != null) && (!_session.isClosed()) ) {
            try {
                _session.destroySession();
            } catch (I2PSessionException ise) {
                _log.warn("Unable to destroy the session", ise);
            }
            PcapWriter pcap = null;
            synchronized(_pcapInitLock) {
                pcap = pcapWriter;
            }
            if (pcap != null)
                pcap.flush();
        }
    }

    /**
     * Has the socket manager been destroyed?
     *
     * @since 0.9.9
     */
    public boolean isDestroyed() {
        return _isDestroyed.get();
    }

    /**
     * Retrieve a set of currently connected I2PSockets, either initiated locally or remotely.
     *
     * @return set of currently connected I2PSockets
     */
    public Set<I2PSocket> listSockets() {
        Set<Connection> connections = _connectionManager.listConnections();
        Set<I2PSocket> rv = new HashSet<I2PSocket>(connections.size());
        for (Connection con : connections) {
            if (con.getSocket() != null)
                rv.add(con.getSocket());
        }
        return rv;
    }

    /**
     *  For logging / diagnostics only
     */
    public String getName() { return _name; }

    /**
     *  For logging / diagnostics only
     */
    public void setName(String name) { _name = name; }
    
    
    public void addDisconnectListener(I2PSocketManager.DisconnectListener lsnr) { 
        _connectionManager.getMessageHandler().addDisconnectListener(lsnr);
    }
    public void removeDisconnectListener(I2PSocketManager.DisconnectListener lsnr) {
        _connectionManager.getMessageHandler().removeDisconnectListener(lsnr);
    }

    private static final Object _pcapInitLock = new Object();
    private static boolean _pcapInitialized;
    static PcapWriter pcapWriter;
    static final String PROP_PCAP = "i2p.streaming.pcap";
    private static final String PCAP_FILE = "streaming.pcap";

    private static void debugInit(I2PAppContext ctx) {
        if (!ctx.getBooleanProperty(PROP_PCAP))
            return;
        synchronized(_pcapInitLock) {
            if (!_pcapInitialized) {
                try {
                    pcapWriter = new PcapWriter(ctx, PCAP_FILE);
                } catch (java.io.IOException ioe) {
                     System.err.println("pcap init ioe: " + ioe);
                }
                _pcapInitialized = true;
            }
        }
    }
}
