package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.client.I2PClient;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.DisconnectMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.SendMessageMessage;
import net.i2p.data.i2cp.SendMessageExpiresMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.crypto.TransientSessionKeyManager;
import net.i2p.router.crypto.ratchet.RatchetSKM;
import net.i2p.router.crypto.ratchet.MuxedPQSKM;
import net.i2p.router.crypto.ratchet.MuxedSKM;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Bridge the router and the client - managing state for a client.
 *
 * As of release 0.9.21, multiple sessions are supported on a single
 * I2CP connection. These sessions share tunnels and some configuration.
 *
 * @author jrandom
 */
class ClientConnectionRunner {
    protected final Log _log;
    protected final RouterContext _context;
    protected final ClientManager _manager;
    /** socket for this particular peer connection */
    private final Socket _socket;
    /** output stream of the socket that I2CP messages bound to the client should be written to */
    private OutputStream _out;

    private final ConcurrentHashMap<Hash, SessionParams> _sessions;

    private String _clientVersion;
    /**
     *  Mapping of MessageId to Payload, storing messages for retrieval.
     *  Unused for i2cp.fastReceive = "true" (_dontSendMSMOnRecive = true)
     */
    private final Map<MessageId, Payload> _messages; 
    private int _consecutiveLeaseRequestFails;
    /**
     *  Set of messageIds created but not yet ACCEPTED.
     *  Unused for i2cp.messageReliability = "none" (_dontSendMSM = true)
     */
    private final Set<MessageId> _acceptedPending;
    /** thingy that does stuff */
    protected I2CPMessageReader _reader;
    /** Used for all sessions, which must all have the same crypto keys */
    private SessionKeyManager _sessionKeyManager;
    /** Used for leaseSets sent to and received from this client */
    private FloodfillNetworkDatabaseFacade _floodfillNetworkDatabaseFacade;
    /** 
     * This contains the last 10 MessageIds that have had their (non-ack) status 
     * delivered to the client (so that we can be sure only to update when necessary)
     */
    private final List<MessageId> _alreadyProcessed;
    private ClientWriterRunner _writer;
    /** are we, uh, dead */
    private volatile boolean _dead;
    /** For outbound traffic. true if i2cp.messageReliability = "none"; @since 0.8.1 */
    private boolean _dontSendMSM;
    /** For inbound traffic. true if i2cp.fastReceive = "true"; @since 0.9.4 */
    private boolean _dontSendMSMOnReceive;
    private final AtomicInteger _messageId; // messageId counter
    private Hash _encryptedLSHash;
    
    // Was 32767 since the beginning (04-2004).
    // But it's 4 bytes in the I2CP spec and stored as a long in MessageID....
    // If this is too low and wraps around, I2CP VerifyUsage could delete the wrong message,
    // e.g. on local access
    private static final int MAX_MESSAGE_ID = 0x4000000;

    private static final int MAX_LEASE_FAILS = 5;
    private static final int BUF_SIZE = 32*1024;
    private static final int MAX_SESSIONS = 4;

    /** @since 0.9.2 */
    private static final String PROP_TAGS = "crypto.tagsToSend";
    private static final String PROP_THRESH = "crypto.lowTagThreshold";

    /**
     *  For multisession
     *  @since 0.9.21
     */
    private static class SessionParams {
        final Destination dest;
        final boolean isPrimary;
        SessionId sessionId;
        SessionConfig config;
        LeaseRequestState leaseRequest;
        Rerequest rerequestTimer;
        /** possibly decrypted */
        LeaseSet currentLeaseSet;
        /** only if encrypted */
        LeaseSet currentEncryptedLeaseSet;

        SessionParams(Destination d, boolean isPrimary) {
            dest = d;
            this.isPrimary = isPrimary;
        }
    }

    /**
     * Create a new runner against the given socket
     *
     */
    public ClientConnectionRunner(RouterContext context, ClientManager manager, Socket socket) {
        _context = context;
        _log = _context.logManager().getLog(ClientConnectionRunner.class);
        _manager = manager;
        _socket = socket;
        // unused for fastReceive
        _messages = new ConcurrentHashMap<MessageId, Payload>();
        _sessions = new ConcurrentHashMap<Hash, SessionParams>(4);
        _alreadyProcessed = new ArrayList<MessageId>();
        _acceptedPending = new ConcurrentHashSet<MessageId>();
        _messageId = new AtomicInteger(_context.random().nextInt());
    }
    
    private static final AtomicInteger __id = new AtomicInteger();

    /**
     * Actually run the connection - listen for I2CP messages and respond.  This
     * is the main driver for this class, though it gets all its meat from the
     * {@link net.i2p.data.i2cp.I2CPMessageReader I2CPMessageReader}
     *
     */
    public synchronized void startRunning() throws IOException {
            if (_dead || _reader != null)
                throw new IllegalStateException();
            _reader = new I2CPMessageReader(new BufferedInputStream(_socket.getInputStream(), BUF_SIZE),
                                            createListener());
            _writer = new ClientWriterRunner(_context, this);
            I2PThread t = new I2PThread(_writer);
            t.setName("I2CP Writer " + __id.incrementAndGet());
            t.setDaemon(true);
            t.start();
            _out = new BufferedOutputStream(_socket.getOutputStream());
            _reader.startReading();
            // TODO need a cleaner for unclaimed items in _messages, but we have no timestamps...
    }
    
    /**
     *  Allow override for testing
     *  @since 0.9.8
     */
    protected I2CPMessageReader.I2CPMessageEventListener createListener() {
        return new ClientMessageEventListener(_context, this, true);
    }

    /**
     *  Die a horrible death. Cannot be restarted.
     */
    public synchronized void stopRunning() {
        if (_dead) return;
        // router may be null in unit tests
        if ((_context.router() == null || _context.router().isAlive()) &&
            _log.shouldWarn()) 
            _log.warn("Stop the I2CP connection!", new Exception("Stop client connection"));
        _dead = true;
        // we need these keys to unpublish the leaseSet
        if (_reader != null) _reader.stopReading();
        if (_writer != null) _writer.stopWriting();
        if (_socket != null) try { _socket.close(); } catch (IOException ioe) { }
        _messages.clear();
        _acceptedPending.clear();
        if (_sessionKeyManager != null)
            _sessionKeyManager.shutdown();
        if (_encryptedLSHash != null)
            _manager.unregisterEncryptedDestination(this, _encryptedLSHash);
        _manager.unregisterConnection(this);
        // netdb may be null in unit tests
        if (_context.netDb() != null) {
            // Note that if the client sent us a destroy message,
            // removeSession() was called just before this, and
            // _sessions will be empty.
            for (SessionParams sp : _sessions.values()) {
                // we don't need to unpublish(),
                // as we shut down our subdb below.
                if (!sp.isPrimary)
                    _context.tunnelManager().removeAlias(sp.dest);
            }
            for (SessionParams sp : _sessions.values()) {
                if (sp.isPrimary)
                    _context.tunnelManager().removeTunnels(sp.dest);
                if (sp.rerequestTimer != null)
                    sp.rerequestTimer.cancel();
            }
        }
        if (_floodfillNetworkDatabaseFacade != null)
            _floodfillNetworkDatabaseFacade.shutdown();
        synchronized (_alreadyProcessed) {
            _alreadyProcessed.clear();
        }
        _sessions.clear();
    }
    
    /**
     *  @since 0.9.66
     */
    public InetAddress getAddress() {
        return _socket.getInetAddress();
    }

    /**
     *  Current client's config,
     *  will be null if session not found
     *  IS subsession aware.
     *  @since 0.9.21 added hash param
     */
    public SessionConfig getConfig(Hash h) {
        SessionParams sp  = _sessions.get(h);
        if (sp == null)
            return null;
        return sp.config;
    }
    
    /**
     *  Current client's config,
     *  will be null if session not found
     *  IS subsession aware.
     *  Returns null if id is null.
     *  @since 0.9.21 added id param
     */
    public SessionConfig getConfig(SessionId id) {
        if (id == null)
            return null;
        for (SessionParams sp : _sessions.values()) {
            if (id.equals(sp.sessionId))
                return sp.config;
        }
        return null;
    }
    
    /**
     *  Primary client's config,
     *  will be null if session not set up
     *  @since 0.9.21
     */
    public SessionConfig getPrimaryConfig() {
        for (SessionParams sp : _sessions.values()) {
            if (sp.isPrimary)
                return sp.config;
        }
        return null;
    }

    /**
     *  The client version.
     *  @since 0.9.7
     */
    public void setClientVersion(String version) {
        _clientVersion = version;
    }

    /**
     *  The client version.
     *  @return null if unknown or less than 0.8.7
     *  @since 0.9.7
     */
    public String getClientVersion() {
        return _clientVersion;
    }

    /**
     *  The current client's SessionKeyManager.
     *  As of 0.9.44, returned implementation varies based on supported encryption types.
     */
    public SessionKeyManager getSessionKeyManager() { return _sessionKeyManager; }

    /**
     *  Currently allocated leaseSet.
     *  IS subsession aware. Returns primary leaseset only.
     *  @return leaseSet or null if not yet set or unknown hash
     *  @since 0.9.21 added hash parameter
     */
    public LeaseSet getLeaseSet(Hash h) {
        SessionParams sp = _sessions.get(h);
        if (sp == null)
            return null;
        return sp.currentLeaseSet;
    }

    /**
     *  Currently allocated leaseSet.
     *  IS subsession aware.
     */
/****
    void setLeaseSet(LeaseSet ls) {
        Hash h = ls.getDestination().calculateHash();
        SessionParams sp = _sessions.get(h);
        if (sp == null)
            return;
        sp.currentLeaseSet = ls;
    }
****/

    /**
     *  Equivalent to getConfig().getDestination().calculateHash();
     *  will be null before session is established
     *  Not subsession aware. Returns primary session hash.
     *  Don't use if you can help it.
     *
     *  @return primary hash or null if not yet set
     */
    public Hash getDestHash() {
        SessionConfig cfg = getPrimaryConfig();
        if (cfg != null)
            return cfg.getDestination().calculateHash();
        return null;
    }

    /**
     *  Return the hash for the given ID
     *  @return hash or null if unknown
     *  @since 0.9.21
     */
    public Hash getDestHash(SessionId id) {
        if (id == null)
            return null;
        for (Map.Entry<Hash, SessionParams> e : _sessions.entrySet()) {
            if (id.equals(e.getValue().sessionId))
                return e.getKey();
        }
        return null;
    }

    /**
     *  Return the dest for the given ID
     *  @return dest or null if unknown
     *  @since 0.9.21
     */
    public Destination getDestination(SessionId id) {
        if (id == null)
            return null;
        for (SessionParams sp : _sessions.values()) {
            if (id.equals(sp.sessionId))
                return sp.dest;
        }
        return null;
    }
    
    /**
     *  Subsession aware.
     *
     *  @param h the local target
     *  @return current client's sessionId or null if not yet set or not a valid hash
     *  @since 0.9.21
     */
    SessionId getSessionId(Hash h) {
        SessionParams sp = _sessions.get(h);
        if (sp == null)
            return null;
        return sp.sessionId;
    }
    
    /**
     *  Subsession aware.
     *
     *  @return all current client's sessionIds, non-null
     *  @since 0.9.21
     */
    List<SessionId> getSessionIds() {
        List<SessionId> rv = new ArrayList<SessionId>(_sessions.size());
        for (SessionParams sp : _sessions.values()) {
            SessionId id = sp.sessionId;
            if (id != null)
                rv.add(id);
        }
        return rv;
    }
    
    /**
     *  Subsession aware.
     *
     *  @return all current client's destinations, non-null
     *  @since 0.9.21
     */
    List<Destination> getDestinations() {
        List<Destination> rv = new ArrayList<Destination>(_sessions.size());
        for (SessionParams sp : _sessions.values()) {
            rv.add(sp.dest);
        }
        return rv;
    }

    /**
     *  To be called only by ClientManager.
     *
     *  @param hash for the session
     *  @throws IllegalStateException if already set
     *  @since 0.9.21 added hash param
     */
    void setSessionId(Hash hash, SessionId id) {
        if (hash == null)
            throw new IllegalStateException();
        if (id == null)
            throw new NullPointerException();
        SessionParams sp = _sessions.get(hash);
        if (sp == null || sp.sessionId != null)
            throw new IllegalStateException();
        sp.sessionId = id;
     }
    
    /**
     *  Kill the session. Caller must kill runner if none left.
     *  If the session is primary and there are subsessions, this removes all subsessions also.
     *
     *  @since 0.9.21
     */
    void removeSession(SessionId id) {
        if (id == null)
            return;
        boolean isPrimary = false;
        for (Iterator<SessionParams> iter = _sessions.values().iterator(); iter.hasNext(); ) {
            SessionParams sp = iter.next();
            if (id.equals(sp.sessionId)) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Destroying client session " + id);
                iter.remove();
                // Tell client manger
                _manager.unregisterSession(id, sp.dest);
                LeaseSet ls = sp.currentLeaseSet;
                if (ls != null && _floodfillNetworkDatabaseFacade != null)
                    _floodfillNetworkDatabaseFacade.unpublish(ls);
                // unpublish encrypted LS also
                ls = sp.currentEncryptedLeaseSet;
                if (ls != null && _floodfillNetworkDatabaseFacade != null)
                    _floodfillNetworkDatabaseFacade.unpublish(ls);
                isPrimary = sp.isPrimary;
                if (isPrimary)
                    _context.tunnelManager().removeTunnels(sp.dest);
                else
                    _context.tunnelManager().removeAlias(sp.dest);
                synchronized(this) {
                    if (sp.rerequestTimer != null)
                        sp.rerequestTimer.cancel();
                }
                break;
            }
        }
        if (isPrimary && !_sessions.isEmpty()) {
            // kill all the others also
            for (SessionParams sp : _sessions.values()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Destroying remaining client subsession " + sp.sessionId);
                _manager.unregisterSession(sp.sessionId, sp.dest);
                LeaseSet ls = sp.currentLeaseSet;
                if (ls != null && _floodfillNetworkDatabaseFacade != null)
                    _floodfillNetworkDatabaseFacade.unpublish(ls);
                // unpublish encrypted LS also
                ls = sp.currentEncryptedLeaseSet;
                if (ls != null && _floodfillNetworkDatabaseFacade != null)
                    _floodfillNetworkDatabaseFacade.unpublish(ls);
                _context.tunnelManager().removeAlias(sp.dest);
                synchronized(this) {
                    if (sp.rerequestTimer != null)
                        sp.rerequestTimer.cancel();
                }
            }
            _sessions.clear();
        }
    }

    /**
     *  Data for the current leaseRequest, or null if there is no active leaseSet request.
     *  Not subsession aware. Returns primary ID only.
     *  @since 0.9.21 added hash param
     */
    LeaseRequestState getLeaseRequest(Hash h) {
        SessionParams sp = _sessions.get(h);
        if (sp == null)
            return null;
        return sp.leaseRequest;
    }

    /** @param req non-null */
    public void failLeaseRequest(LeaseRequestState req) { 
        boolean disconnect = false;
        Hash h = req.getRequested().getDestination().calculateHash();
        SessionParams sp = _sessions.get(h);
        if (sp == null)
            return;
        synchronized (this) {
            if (sp.leaseRequest == req) {
                sp.leaseRequest = null;
                disconnect = ++_consecutiveLeaseRequestFails > MAX_LEASE_FAILS;
            }
        }
        if (disconnect)
            disconnectClient("Too many leaseset request fails");
    }

    /** already closed? */
    boolean isDead() { return _dead; }

    /**
     *  Only call if _dontSendMSMOnReceive is false, otherwise will always be null
     */
    Payload getPayload(MessageId id) { 
        return _messages.get(id); 
    }

    /**
     *  Only call if _dontSendMSMOnReceive is false
     */
    void setPayload(MessageId id, Payload payload) { 
        if (!_dontSendMSMOnReceive)
            _messages.put(id, payload); 
    }

    /**
     *  Only call if _dontSendMSMOnReceive is false
     */
    void removePayload(MessageId id) { 
        _messages.remove(id); 
    }
    
    /**
     *  Caller must send a SessionStatusMessage to the client with the returned code.
     *  Caller must call disconnectClient() on failure.
     *  Side effect: Sets the session ID.
     *
     *  @return SessionStatusMessage return code, 1 for success, != 1 for failure
     */
    public int sessionEstablished(SessionConfig config) {
        Destination dest = config.getDestination();
        Hash destHash = dest.calculateHash();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("SessionEstablished called for destination " + destHash);
        if (_sessions.size() > MAX_SESSIONS)
            return SessionStatusMessage.STATUS_REFUSED;
        boolean isPrimary = _sessions.isEmpty();
        if (!isPrimary) {
            // all encryption keys must be the same
            for (SessionParams sp : _sessions.values()) {
                if (!dest.getPublicKey().equals(sp.dest.getPublicKey())) {
                    _log.error("LS pubkey mismatch");
                    return SessionStatusMessage.STATUS_INVALID;
                }
            }
        }
        SessionParams sp = new SessionParams(dest, isPrimary);
        sp.config = config;
        SessionParams old = _sessions.putIfAbsent(destHash, sp);
        if (old != null)
            return SessionStatusMessage.STATUS_DUP_DEST;
        // We process a few options here, but most are handled by the tunnel manager.
        // The ones here can't be changed later.
        Properties opts = config.getOptions();
        if (isPrimary && opts != null) {
            _dontSendMSM = "none".equals(opts.getProperty(I2PClient.PROP_RELIABILITY, "").toLowerCase(Locale.US));
            _dontSendMSMOnReceive = Boolean.parseBoolean(opts.getProperty(I2PClient.PROP_FAST_RECEIVE));
        }

        // Set up the
        // per-destination session key manager to prevent rather easy correlation
        // based on the specified encryption types in the config
        if (isPrimary && _sessionKeyManager == null) {
            int tags = TransientSessionKeyManager.DEFAULT_TAGS;
            int thresh = TransientSessionKeyManager.LOW_THRESHOLD;
            boolean hasElg = false;
            boolean hasEC = false;
            boolean hasPQ = false;
            int pqType = 0;
            // router may be null in unit tests, avoid NPEs in ratchet
            // we won't actually be using any SKM anyway
            if (opts != null && _context.router() != null) {
                String ptags = opts.getProperty(PROP_TAGS);
                if (ptags != null) {
                    try { tags = Integer.parseInt(ptags); } catch (NumberFormatException nfe) {}
                }
                String pthresh = opts.getProperty(PROP_THRESH);
                if (pthresh != null) {
                    try { thresh = Integer.parseInt(pthresh); } catch (NumberFormatException nfe) {}
                }
                String senc = opts.getProperty("i2cp.leaseSetEncType");
                if (senc != null) {
                    String[] senca = DataHelper.split(senc, ",");
                    for (String sencaa : senca) {
                        if (sencaa.equals("0")) {
                            hasElg = true;
                        } else if (sencaa.equals("4")) {
                            hasEC = true;
                        } else if (sencaa.equals("5") || sencaa.equals("6") || sencaa.equals("7")) {
                            if (hasPQ) {
                                _log.error("Bad encryption type combination in i2cp.leaseSetEncType for " + dest.toBase32());
                                return SessionStatusMessage.STATUS_INVALID;
                            }
                            pqType = Integer.parseInt(sencaa);
                            hasPQ = true;
                        }
                    }
                } else {
                    hasElg = true;
                }
            } else {
                hasElg = true;
            }
            if (hasElg) {
                if (hasPQ) {
                    _log.error("Bad encryption type combination in i2cp.leaseSetEncType for " + dest.toBase32());
                    return SessionStatusMessage.STATUS_INVALID;
                }
                TransientSessionKeyManager tskm = new TransientSessionKeyManager(_context, tags, thresh);
                if (hasEC) {
                    RatchetSKM rskm = new RatchetSKM(_context, dest);
                    _sessionKeyManager = new MuxedSKM(tskm, rskm);
                } else {
                    _sessionKeyManager = tskm;
                }
            } else if (hasPQ) {
                if (hasEC) {
                    // ECIES
                    RatchetSKM rskm1 = new RatchetSKM(_context, dest);
                    // PQ
                    RatchetSKM rskm2 = new RatchetSKM(_context, dest, EncType.getByCode(pqType));
                    _sessionKeyManager = new MuxedPQSKM(rskm1, rskm2);
                } else {
                    _sessionKeyManager = new RatchetSKM(_context, dest, EncType.getByCode(pqType));
                }
            } else {
                if (hasEC) {
                    _sessionKeyManager = new RatchetSKM(_context, dest);
                } else {
                    _log.error("No supported encryption types in i2cp.leaseSetEncType for " + dest.toBase32());
                    return SessionStatusMessage.STATUS_INVALID;
                }
            }
        }
        if (isPrimary && _floodfillNetworkDatabaseFacade == null) {
            if (_log.shouldDebug())
                _log.debug("Initializing subDb for client" + destHash);
            _floodfillNetworkDatabaseFacade = new FloodfillNetworkDatabaseFacade(_context, destHash);
            _floodfillNetworkDatabaseFacade.startup();
        }
        return _manager.destinationEstablished(this, dest);
    }
    
    /** 
     * Send a notification to the client that their message (id specified) was
     * delivered (or failed delivery)
     * Note that this sends the Guaranteed status codes, even though we only support best effort.
     * Doesn't do anything if i2cp.messageReliability = "none"
     *
     *  Do not use for status = STATUS_SEND_ACCEPTED; use ackSendMessage() for that.
     *
     *  @param dest the client
     *  @param id the router's ID for this message
     *  @param messageNonce the client's ID for this message, greater than zero
     *  @param status see I2CP MessageStatusMessage for success/failure codes
     */
    void updateMessageDeliveryStatus(Destination dest, MessageId id, long messageNonce, int status) {
        if (_dead || messageNonce <= 0)
            return;
        SessionParams sp = _sessions.get(dest.calculateHash());
        if (sp == null)
            return;
        SessionId sid = sp.sessionId;
        if (sid == null)
            return;  // sid = new SessionId(foo) ???
        _context.jobQueue().addJob(new MessageDeliveryStatusUpdate(sid, id, messageNonce, status));
    }

    /** 
     * called after a new leaseSet is granted by the client, the NetworkDb has been
     * updated.  This takes care of all the LeaseRequestState stuff (including firing any jobs)
     *
     * @param ls if encrypted, the encrypted LS, not the decrypted one
     */
    void leaseSetCreated(LeaseSet ls) {
        Hash h = ls.getDestination().calculateHash();
        SessionParams sp = _sessions.get(h);
        if (sp == null)
            return;
        LeaseRequestState state;
        synchronized (this) {
            if (ls.getType() == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                EncryptedLeaseSet encls = (EncryptedLeaseSet) ls;
                sp.currentEncryptedLeaseSet = encls;
                ls = encls.getDecryptedLeaseSet();
            }
            sp.currentLeaseSet = ls;
            state = sp.leaseRequest;
            if (state == null) {
                // We got the LS after the timeout?
                // ClientMessageEventListener told the router to publish.
                if (_log.shouldLog(Log.WARN))
                    _log.warn("LeaseRequest is null and we've received a new lease? " + ls);
                return;
            } else {
                state.setIsSuccessful(true);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("LeaseSet created fully: " + state + '\n' + ls);
                sp.leaseRequest = null;
                _consecutiveLeaseRequestFails = 0;
                if (sp.rerequestTimer != null) {
                    sp.rerequestTimer.cancel();
                    sp.rerequestTimer = null;
                }
            }
        }
        if ( (state != null) && (state.getOnGranted() != null) )
            _context.jobQueue().addJob(state.getOnGranted());
    }

    /**
     *  Call after destinationEstablished(),
     *  when an encrypted leaseset is created, so we know it's local.
     *  Add to the clients list. Check for a dup hash.
     *  Caller must call runner.disconnectClient() on failure.
     *
     *  @param hash the location of the encrypted LS, will change every day
     *  @return success, false on dup
     *  @since 0.9.39
     */
    public boolean registerEncryptedLS(Hash hash) {
        boolean rv = true;
        synchronized(this) {
            if (!hash.equals(_encryptedLSHash)) {
                if (_encryptedLSHash != null)
                    _manager.unregisterEncryptedDestination(this, _encryptedLSHash);
                rv = _manager.registerEncryptedDestination(this, hash);
                if (rv)
                    _encryptedLSHash = hash;
            }
        }
        return rv;
    }
    
    /**
     *  Send a DisconnectMessage and log with level Log.ERROR.
     *  This is always bad.
     *  See ClientMessageEventListener.handleCreateSession()
     *  for why we don't send a SessionStatusMessage when we do this.
     *  @param reason will be truncated to 255 bytes
     */
    void disconnectClient(String reason) {
        disconnectClient(reason, Log.ERROR);
    }

    /**
     * @param reason will be truncated to 255 bytes
     * @param logLevel e.g. Log.WARN
     * @since 0.8.2
     */
    void disconnectClient(String reason, int logLevel) {
        if (_log.shouldLog(logLevel))
            _log.log(logLevel, "Disconnecting the client - " 
                     + reason);
        DisconnectMessage msg = new DisconnectMessage();
        if (reason.length() > 255)
            reason = reason.substring(0, 255);
        msg.setReason(reason);
        try {
            doSend(msg);
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the disconnect message", ime);
        }
        // give it a little time to get sent out...
        // even better would be to have stopRunning() flush it?
        try {
            Thread.sleep(50);
        } catch (InterruptedException ie) {}
        stopRunning();
    }
    
    /**
     * Distribute the message.  If the dest is local, it blocks until its passed 
     * to the target ClientConnectionRunner (which then fires it into a MessageReceivedJob).
     * If the dest is remote, it blocks until it is added into the ClientMessagePool
     *
     */
    MessageId distributeMessage(SendMessageMessage message) {
        Payload payload = message.getPayload();
        Destination dest = message.getDestination();
        MessageId id = new MessageId();
        id.setMessageId(getNextMessageId()); 
        long expiration = 0;
        int flags = 0;
        if (message.getType() == SendMessageExpiresMessage.MESSAGE_TYPE) {
            SendMessageExpiresMessage msg = (SendMessageExpiresMessage) message;
            expiration = msg.getExpirationTime();
            flags = msg.getFlags();
        }
        if ((!_dontSendMSM) && message.getNonce() != 0)
            _acceptedPending.add(id);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("** Receiving message " + id.getMessageId() + " with payload of size " 
                       + payload.getSize() + " for session " + message.getSessionId());
        //long beforeDistribute = _context.clock().now();
        // the following blocks as described above
        Destination fromDest = getDestination(message.getSessionId());
        if (fromDest != null)
            _manager.distributeMessage(this, fromDest, dest, payload,
                                       id, message.getNonce(), expiration, flags);
        // else log error?
        //long timeToDistribute = _context.clock().now() - beforeDistribute;
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.warn("Time to distribute in the manager to " 
        //              + dest.calculateHash().toBase64() + ": " 
        //              + timeToDistribute);
        return id;
    }
    
    /** 
     * Send a notification to the client that their message (id specified) was accepted 
     * for delivery (but not necessarily delivered)
     * Doesn't do anything if i2cp.messageReliability = "none"
     * or if the nonce is 0.
     *
     * @param id OUR id for the message
     * @param nonce HIS id for the message
     */
    void ackSendMessage(SessionId sid, MessageId id, long nonce) {
        if (_dontSendMSM || nonce == 0)
            return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Acking message send [accepted]" + id + " / " + nonce + " for sessionId " 
                       + sid);
        MessageStatusMessage status = new MessageStatusMessage();
        status.setMessageId(id.getMessageId()); 
        status.setSessionId(sid.getSessionId());
        status.setSize(0L);
        status.setNonce(nonce);
        status.setStatus(MessageStatusMessage.STATUS_SEND_ACCEPTED);
        try {
            doSend(status);
            _acceptedPending.remove(id);
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the message status message", ime);
        }
    }
    
    /**
     * Synchronously deliver the message to the current runner
     *
     * Failure indication is available as of 0.9.29.
     * Fails on e.g. queue overflow to client, client dead, etc.
     *
     * @param toDest non-null
     * @param fromDest generally null when from remote, non-null if from local
     * @return success
     */ 
    boolean receiveMessage(Destination toDest, Destination fromDest, Payload payload) {
        if (_dead)
            return false;
        MessageReceivedJob j = new MessageReceivedJob(_context, this, toDest, fromDest, payload, _dontSendMSMOnReceive);
        // This is fast and non-blocking, run in-line
        //_context.jobQueue().addJob(j);
        //j.runJob();
        return j.receiveMessage();
    }
    
    /**
     * Synchronously deliver the message to the current runner
     *
     * Failure indication is available as of 0.9.29.
     * Fails on e.g. queue overflow to client, client dead, etc.
     *
     * @param toHash non-null
     * @param fromDest generally null when from remote, non-null if from local
     * @return success
     * @since 0.9.21
     */ 
    boolean receiveMessage(Hash toHash, Destination fromDest, Payload payload) {
        SessionParams sp = _sessions.get(toHash);
        if (sp == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No session found for receiveMessage()");
            return false;
        }
        return receiveMessage(sp.dest, fromDest, payload);
    }
    
    /**
     * Send async abuse message to the client
     *
     */
    public void reportAbuse(Destination dest, String reason, int severity) {
        if (_dead) return;
        _context.jobQueue().addJob(new ReportAbuseJob(_context, this, dest, reason, severity));
    }
        
    /**
     * Request that a particular client authorize the Leases contained in the 
     * LeaseSet, after which the onCreateJob is queued up.  If that doesn't occur
     * within the timeout specified, queue up the onFailedJob.  This call does not
     * block.
     *
     * Job args are always null, may need some fixups if we start using them.
     *
     * @param h the Destination's hash
     * @param set LeaseSet with requested leases - this object must be updated to contain the 
     *            signed version (as well as any changed/added/removed Leases)
     *            The LeaseSet contains Leases only, it is unsigned.
     *            Must be unique for this hash, do not reuse for subsessions.
     * @param expirationTime ms to wait before failing
     * @param onCreateJob Job to run after the LeaseSet is authorized, null OK
     * @param onFailedJob Job to run after the timeout passes without receiving authorization, null OK
     */
    void requestLeaseSet(Hash h, LeaseSet set, long expirationTime, Job onCreateJob, Job onFailedJob) {
        if (_dead) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Requesting leaseSet from a dead client: " + set);
            if (onFailedJob != null)
                _context.jobQueue().addJob(onFailedJob);
            return;
        }
        SessionParams sp = _sessions.get(h);
        if (sp == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Requesting leaseSet for an unknown sesssion");
            return;
        }
        // We can't use LeaseSet.equals() here because the dest, keys, and sig on
        // the new LeaseSet are null. So we compare leases one by one.
        // In addition, the client rewrites the expiration time of all the leases to
        // the earliest one, so we can't use Lease.equals() or Lease.getEndDate().
        // So compare by tunnel ID, and then by gateway.
        // (on the remote possibility that two gateways are using the same ID).
        // TunnelPool.locked_buildNewLeaseSet() ensures that leases are sorted,
        //  so the comparison will always work.
        int leases = set.getLeaseCount();
        // synch so _currentLeaseSet isn't changed out from under us
        LeaseSet current;
        Destination dest = sp.dest;
        LeaseRequestState state;
        synchronized (this) {
            current = sp.currentLeaseSet;
            // Skip this check for meta, for now, TODO
            if (current != null && current.getLeaseCount() == leases &&
                current.getType() != DatabaseEntry.KEY_TYPE_META_LS2) {
                for (int i = 0; i < leases; i++) {
                    if (! current.getLease(i).getTunnelId().equals(set.getLease(i).getTunnelId()))
                        break;
                    if (! current.getLease(i).getGateway().equals(set.getLease(i).getGateway()))
                        break;
                    if (i == leases - 1) {
                        if (_log.shouldDebug())
                            _log.debug("Requested leaseSet hasn't changed");
                        if (onCreateJob != null)
                            _context.jobQueue().addJob(onCreateJob);
                        return; // no change
                    }
                }
            }

            if (_log.shouldLog(Log.INFO))
                _log.info("Current leaseSet " + current + "\nNew leaseSet " + set);

            state = sp.leaseRequest;
            if (state != null) {
                LeaseSet requested = state.getRequested();
                LeaseSet granted = state.getGranted();
                long ours = set.getEarliestLeaseDate();
                if ( ( (requested != null) && (requested.getEarliestLeaseDate() > ours) ) || 
                     ( (granted != null) && (granted.getEarliestLeaseDate() > ours) ) ) {
                    // theirs is newer
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Already requesting, theirs newer, do nothing: " + state);
                } else {
                    // ours is newer, so wait a few secs and retry
                    set.setDestination(dest);
                    Rerequest timer = new Rerequest(set, expirationTime, onCreateJob, onFailedJob);
                    sp.rerequestTimer = timer;
                    timer.schedule(3*1000);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Already requesting, ours newer, wait 3 sec: " + state);
                }
                // fire onCreated?
                return; // already requesting
            } else {
                set.setDestination(dest);
                if (current == null && _context.tunnelManager().getOutboundClientTunnelCount(h) <= 0) {
                    // at startup of a client, where we don't have a leaseset, wait for
                    // an outbound tunnel also, so the client doesn't start sending data
                    // before we are ready
                    Rerequest timer = new Rerequest(set, expirationTime, onCreateJob, onFailedJob);
                    sp.rerequestTimer = timer;
                    timer.schedule(1000);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("No current LS but no OB tunnels, wait 1 sec for " + h);
                    return;
                } else {
                    // so the timer won't fire off with an older LS request
                    if (sp.rerequestTimer != null) {
                        sp.rerequestTimer.cancel();
                        sp.rerequestTimer = null;
                    }
                    long earliest = (current != null) ? current.getEarliestLeaseDate() : 0;
                    sp.leaseRequest = state = new LeaseRequestState(onCreateJob, onFailedJob, earliest,
                                                                _context.clock().now() + expirationTime, set);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("New request: " + state);
                }
            }
        }
        _context.jobQueue().addJob(new RequestLeaseSetJob(_context, this, state));
    }

    private class Rerequest extends SimpleTimer2.TimedEvent {
        private final LeaseSet _ls;
        private final long _expirationTime;
        private final Job _onCreate;
        private final Job _onFailed;

        /**
         * Caller must schedule()
         * @param ls dest must be set
         */
        public Rerequest(LeaseSet ls, long expirationTime, Job onCreate, Job onFailed) {
            super(_context.simpleTimer2());
            _ls = ls;
            _expirationTime = expirationTime;
            _onCreate = onCreate;
            _onFailed = onFailed;
        }

        public void timeReached() {
            Hash h = _ls.getDestination().calculateHash();
            SessionParams sp = _sessions.get(h);
            if (sp == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("cancelling rerequest, session went away: " + h);
                return;
            }
            synchronized(ClientConnectionRunner.this) {
                if (sp.rerequestTimer != Rerequest.this) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("cancelling rerequest, newer request came in: " + h);
                    return;
                }
            }
            requestLeaseSet(h, _ls, _expirationTime, _onCreate, _onFailed);
        }
    }
    
    void disconnected() {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Disconnected", new Exception("Disconnected?"));
        stopRunning();
    }
    
    ////
    ////
    boolean getIsDead() { return _dead; }

    /**
     *  Not thread-safe. Blocking. Only used for external sockets.
     *  ClientWriterRunner thread is the only caller.
     *  Others must use doSend().
     */
    void writeMessage(I2CPMessage msg) {
        //long before = _context.clock().now();
        try {
            // We don't need synchronization here, ClientWriterRunner is the only writer.
            //synchronized (_out) {
                msg.writeMessage(_out);
                _out.flush();
            //}
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("after writeMessage("+ msg.getClass().getName() + "): " 
            //               + (_context.clock().now()-before) + "ms");
        } catch (I2CPMessageException ime) {
            _log.error("Error sending I2CP message to client", ime);
            stopRunning();
        } catch (EOFException eofe) {
            // only warn if client went away
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error sending I2CP message - client went away", eofe);
            stopRunning();
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR)) 
                _log.error("IO Error sending I2CP message to client", ioe);
            stopRunning();
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Unhandled exception sending I2CP message to client", t);
            stopRunning();
        //} finally {
        //    long after = _context.clock().now();
        //    long lag = after - before;
        //    if (lag > 300) {
        //        if (_log.shouldLog(Log.WARN))
        //            _log.warn("synchronization on the i2cp message send took too long (" + lag 
        //                      + "ms): " + msg);
        //    }
        }
    }
    
    /**
     * Actually send the I2CPMessage to the peer through the socket
     *
     */
    void doSend(I2CPMessage msg) throws I2CPMessageException {
        if (_out == null) throw new I2CPMessageException("Output stream is not initialized");
        if (msg == null) throw new I2CPMessageException("Null message?!");
        //if (_log.shouldLog(Log.DEBUG)) {
        //    if ( (_config == null) || (_config.getDestination() == null) ) 
        //        _log.debug("before doSend of a "+ msg.getClass().getName() 
        //                   + " message on for establishing i2cp con");
        //    else
        //        _log.debug("before doSend of a "+ msg.getClass().getName() 
        //                   + " message on for " 
        //                   + _config.getDestination().calculateHash().toBase64());
        //}
        _writer.addMessage(msg);
        //if (_log.shouldLog(Log.DEBUG)) {
        //    if ( (_config == null) || (_config.getDestination() == null) ) 
        //        _log.debug("after doSend of a "+ msg.getClass().getName() 
        //                   + " message on for establishing i2cp con");
        //    else
        //        _log.debug("after doSend of a "+ msg.getClass().getName() 
        //                   + " message on for " 
        //                   + _config.getDestination().calculateHash().toBase64());
        //}
    }
    
    public int getNextMessageId() { 
        // Don't % so we don't get negative IDs
        return _messageId.incrementAndGet() & (MAX_MESSAGE_ID - 1);
    }
    
    /**
     * True if the client has already been sent the ACCEPTED state for the given
     * message id, false otherwise.
     *
     */
    private boolean alreadyAccepted(MessageId id) {
        if (_dead) return false;
        return !_acceptedPending.contains(id);
    }
    
    /**
     * If the message hasn't been state=ACCEPTED yet, we shouldn't send an update
     * since the client doesn't know the message id (and we don't know the nonce).
     * So, we just wait REQUEUE_DELAY ms before trying again.
     *
     */
    private final static long REQUEUE_DELAY = 500;
    private static final int MAX_REQUEUE = 60;  // 30 sec.

    /**
     * Get the FloodfillNetworkDatabaseFacade for this runner. This is the client
     * netDb.
     * 
     * If a session has not been created yet, it will return null.
     * 
     * @return the client netdb or null if no session was created yet
     * @since 0.9.61
     */
    public FloodfillNetworkDatabaseFacade getFloodfillNetworkDatabaseFacade() {
        return _floodfillNetworkDatabaseFacade;
    }
    
    private class MessageDeliveryStatusUpdate extends JobImpl {
        private final SessionId _sessId;
        private final MessageId _messageId;
        private final long _messageNonce;
        private final int _status;
        private long _lastTried;
        private int _requeueCount;

        /**
         *  Do not use for status = STATUS_SEND_ACCEPTED; use ackSendMessage() for that.
         *
         *  @param id the router's ID for this message
         *  @param messageNonce the client's ID for this message
         *  @param status see I2CP MessageStatusMessage for success/failure codes
         */
        public MessageDeliveryStatusUpdate(SessionId sid, MessageId id, long messageNonce, int status) {
            super(ClientConnectionRunner.this._context);
            _sessId = sid;
            _messageId = id;
            _messageNonce = messageNonce;
            _status = status;
        }

        public String getName() { return "Update Delivery Status"; }

        /**
         * Note that this sends the Guaranteed status codes, even though we only support best effort.
         */
        public void runJob() {
            if (_dead) return;

            MessageStatusMessage msg = new MessageStatusMessage();
            msg.setMessageId(_messageId.getMessageId());
            msg.setSessionId(_sessId.getSessionId());
            // has to be >= 0, it is initialized to -1
            msg.setNonce(_messageNonce);
            msg.setSize(0);
            msg.setStatus(_status);

            if (!alreadyAccepted(_messageId)) {
                if (_requeueCount++ > MAX_REQUEUE) {
                    // bug requeueing forever? failsafe
                    _log.error("Abandon update for message " + _messageId + " to " 
                          + MessageStatusMessage.getStatusString(msg.getStatus()) 
                          + " for " + _sessId);
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Almost send an update for message " + _messageId + " to "
                          + MessageStatusMessage.getStatusString(msg.getStatus())
                          + " for " + _sessId
                          + " before they knew the messageId!  delaying .5s");
                    _lastTried = _context.clock().now();
                    requeue(REQUEUE_DELAY);
                }
                return;
            }

            boolean alreadyProcessed = false;
            long beforeLock = _context.clock().now();
            long inLock = 0;
            synchronized (_alreadyProcessed) {
                inLock = _context.clock().now();
                if (_alreadyProcessed.contains(_messageId)) {
                    _log.info("Status already updated");
                    alreadyProcessed = true;
                } else {
                    _alreadyProcessed.add(_messageId);
                    while (_alreadyProcessed.size() > 10)
                        _alreadyProcessed.remove(0);
                }
            }
            long afterLock = _context.clock().now();

            if (afterLock - beforeLock > 50) {
                _log.warn("MessageDeliveryStatusUpdate.locking took too long: " + (afterLock-beforeLock)
                          + " overall, synchronized took " + (inLock - beforeLock));
            }
            
            if (alreadyProcessed) return;

            if (_lastTried > 0) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.info("Updating message status for message " + _messageId + " to " 
                              + MessageStatusMessage.getStatusString(msg.getStatus()) 
                              + " for " + _sessId
                              + " (with nonce=2), retrying after " 
                              + (_context.clock().now() - _lastTried));
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating message status for message " + _messageId + " to " 
                               + MessageStatusMessage.getStatusString(msg.getStatus()) 
                               + " for " + _sessId + " (with nonce=2)");
            }

            try {
                doSend(msg);
            } catch (I2CPMessageException ime) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error updating the status for message ID " + _messageId, ime);
            }
        }
    }
}
