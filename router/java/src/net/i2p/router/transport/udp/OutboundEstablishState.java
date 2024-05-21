package net.i2p.router.transport.udp;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

/**
 * Data for a new connection being established, where we initiated the 
 * connection with a remote peer.  In other words, we are Alice and
 * they are Bob.
 *
 */
class OutboundEstablishState {
    protected final RouterContext _context;
    protected final Log _log;
    // SessionRequest message
    private byte _sentX[];
    protected byte _bobIP[];
    protected int _bobPort;
    // SessionCreated message
    private byte _receivedY[];
    protected byte _aliceIP[];
    protected int _alicePort;
    protected long _receivedRelayTag;
    private long _receivedSignedOnTime;
    private Signature _receivedSignature;
    // includes trailing padding to mod 16
    private byte[] _receivedEncryptedSignature;
    private byte[] _receivedIV;
    // SessionConfirmed messages
    private long _sentSignedOnTime;
    // general status 
    protected final long _establishBegin;
    //private long _lastReceive;
    protected long _lastSend;
    protected long _nextSend;
    protected RemoteHostId _remoteHostId;
    private final RemoteHostId _claimedAddress;
    protected final RouterIdentity _remotePeer;
    private final boolean _allowExtendedOptions;
    private final boolean _needIntroduction;
    private final SessionKey _introKey;
    private final Queue<OutNetMessage> _queuedMessages;
    protected OutboundState _currentState;
    private long _introductionNonce;
    private boolean _isFirstMessageOurDSM;
    // intro
    private final UDPAddress _remoteAddress;
    private boolean _complete;
    // counts for backoff
    private int _confirmedSentCount;
    protected int _requestSentCount;
    private int _introSentCount;
    // Times for timeout
    private long _confirmedSentTime;
    protected long _requestSentTime;
    private long _introSentTime;
    protected int _rtt;
    
    public enum OutboundState {
        /** nothin sent yet */
        OB_STATE_UNKNOWN,
        /** we have sent an initial request */
        OB_STATE_REQUEST_SENT,
        /** we have received a signed creation packet */
        OB_STATE_CREATED_RECEIVED,
        /** we have sent one or more confirmation packets */
        OB_STATE_CONFIRMED_PARTIALLY,
        /** we have received a data packet */
        OB_STATE_CONFIRMED_COMPLETELY,
        /** we need to have someone introduce us to the peer, but haven't received a RelayResponse yet */
        OB_STATE_PENDING_INTRO,
        /** RelayResponse received */
        OB_STATE_INTRODUCED,
        /** SessionConfirmed failed validation */
        OB_STATE_VALIDATION_FAILED,

        /**
         * SSU2: We don't have a token
         * @since 0.9.54
         */
        OB_STATE_NEEDS_TOKEN,
        /**
         * SSU2: We have sent a token request
         * @since 0.9.54
         */
        OB_STATE_TOKEN_REQUEST_SENT,
        /**
         * SSU2: We have received a retry
         * @since 0.9.54
         */
        OB_STATE_RETRY_RECEIVED,
        /**
         * SSU2: We have sent a session request after receiving a retry
         * @since 0.9.54
         */
        OB_STATE_REQUEST_SENT_NEW_TOKEN
    }
    
    /** basic delay before backoff
     *  Transmissions at 0, 1.25, 3.75, 8.75 sec
     *  This should be a little longer than for inbound.
     */
    protected static final long RETRANSMIT_DELAY = 1250;

    /**
     *  max delay including backoff
     *  This should be a little longer than for inbound.
     */
    private static final long MAX_DELAY = 15*1000;

    private static final long WAIT_FOR_HOLE_PUNCH_DELAY = 500;

    /**
     *  For SSU2
     *
     *  @since 0.9.54
     */
    protected OutboundEstablishState(RouterContext ctx, RemoteHostId claimedAddress,
                                  RemoteHostId remoteHostId,
                                  RouterIdentity remotePeer,
                                  boolean needIntroduction,
                                  SessionKey introKey, UDPAddress addr) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        if (claimedAddress != null) {
            _bobIP = claimedAddress.getIP();
            _bobPort = claimedAddress.getPort();
        } else {
            //_bobIP = null;
            _bobPort = -1;
        }
        _claimedAddress = claimedAddress;
        _remoteHostId = remoteHostId;
        _allowExtendedOptions = false;
        _needIntroduction = needIntroduction;
        _remotePeer = remotePeer;
        _introKey = introKey;
        _queuedMessages = new LinkedBlockingQueue<OutNetMessage>();
        _establishBegin = ctx.clock().now();
        _remoteAddress = addr;
        _introductionNonce = -1;
        if (addr.getIntroducerCount() > 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("new outbound establish to " + remotePeer.calculateHash() + ", with address: " + addr);
            _currentState = OutboundState.OB_STATE_PENDING_INTRO;
        } else {
            _currentState = OutboundState.OB_STATE_UNKNOWN;
        }
    }
    
    /**
     * @since 0.9.54
     */
    public int getVersion() { return 1; }
    
    public synchronized OutboundState getState() { return _currentState; }

    /** @return if previously complete */
    public synchronized boolean complete() { 
        boolean already = _complete; 
        _complete = true; 
        return already; 
    }

    /** @return non-null */
    public UDPAddress getRemoteAddress() { return _remoteAddress; }

    public void setIntroNonce(long nonce) { _introductionNonce = nonce; }

    /** @return -1 if unset */
    public long getIntroNonce() { return _introductionNonce; }

    /**
     *  Are we allowed to send extended options to this peer?
     *  @since 0.9.24
     */
    public boolean isExtendedOptionsAllowed() { return _allowExtendedOptions; }

    /**
     *  Should we ask this peer to be an introducer for us?
     *  Ignored unless allowExtendedOptions is true
     *  @since 0.9.24
     */
    public boolean needIntroduction() { return _needIntroduction; }

    synchronized int getRTT() { return _rtt; }
    
    /**
     *  Queue a message to be sent after the session is established.
     */
    public void addMessage(OutNetMessage msg) {
        if (_queuedMessages.isEmpty()) {
            I2NPMessage m = msg.getMessage();
            if (m.getType() == DatabaseStoreMessage.MESSAGE_TYPE) {
               DatabaseStoreMessage dsm = (DatabaseStoreMessage) m;
               if (dsm.getKey().equals(_context.routerHash())) {
                   // version 2 sends our RI in handshake
                   if (getVersion() > 1)
                       return;
                   _isFirstMessageOurDSM = true;
               }
           }
        }
        // chance of a duplicate here in a race, that's ok
        if (!_queuedMessages.contains(msg))
            _queuedMessages.offer(msg);
        else if (_log.shouldLog(Log.WARN))
             _log.warn("attempt to add duplicate msg to queue: " + msg);
    }
    
    /**
     *  Is the first message queued our own DatabaseStoreMessage?
     *  @since 0.9.12
     */
    public boolean isFirstMessageOurDSM() {
        return _isFirstMessageOurDSM;
    }

    /** @return null if none */
    public OutNetMessage getNextQueuedMessage() { 
        return _queuedMessages.poll();
    }
    
    public RouterIdentity getRemoteIdentity() { return _remotePeer; }

    /**
     *  Bob's introduction key, as published in the netdb
     */
    public SessionKey getIntroKey() { return _introKey; }
    
    /**
     * The remote side (Bob) - note that in some places he's called Charlie.
     * Warning - may change after introduction. May be null before introduction.
     */
    public synchronized byte[] getSentIP() { return _bobIP; }

    /**
     * The remote side (Bob) - note that in some places he's called Charlie.
     * Warning - may change after introduction. May be -1 before introduction.
     */
    public synchronized int getSentPort() { return _bobPort; }

    /**
     * Blocking call (run in the establisher thread) to determine if the 
     * session was created properly.  If it wasn't, all the SessionCreated
     * remnants are dropped (perhaps they were spoofed, etc) so that we can
     * receive another one
     *
     *  Generates session key and mac key.
     *
     * @return true if valid
     */
    public synchronized boolean validateSessionCreated() {
        throw new UnsupportedOperationException("see override");
    }
    
    /**
     *  The SessionCreated validation failed
     */
    public synchronized void fail() {
        _receivedY = null;
        _aliceIP = null;
        _receivedRelayTag = 0;
        _receivedSignedOnTime = -1;
        _receivedEncryptedSignature = null;
        _receivedIV = null;
        _receivedSignature = null;
        // sure, there's a chance the packet was corrupted, but in practice
        // this means that Bob doesn't know his external port, so give up.
        _currentState = OutboundState.OB_STATE_VALIDATION_FAILED;

        _nextSend = _context.clock().now();
    }
    
    public synchronized long getReceivedRelayTag() { return _receivedRelayTag; }
    public synchronized long getSentSignedOnTime() { return _sentSignedOnTime; }
    public synchronized long getReceivedSignedOnTime() { return _receivedSignedOnTime; }
    public synchronized byte[] getReceivedIP() { return _aliceIP; }
    public synchronized int getReceivedPort() { return _alicePort; }
    
    /** note that we just sent the SessionConfirmed packet */
    public synchronized void confirmedPacketsSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_confirmedSentCount == 0) {
            delay = RETRANSMIT_DELAY;
            _confirmedSentTime = _lastSend;
        } else {
            delay = Math.min(RETRANSMIT_DELAY << _confirmedSentCount,
                             _confirmedSentTime + EstablishmentManager.OB_MESSAGE_TIMEOUT - _lastSend);
        }
        _confirmedSentCount++;
        _nextSend = _lastSend + delay;
        if (_log.shouldDebug())
            _log.debug("Send confirm packets, nextSend in " + delay + " on " + this);
        if (_currentState == OutboundState.OB_STATE_UNKNOWN || 
            _currentState == OutboundState.OB_STATE_PENDING_INTRO ||
            _currentState == OutboundState.OB_STATE_INTRODUCED ||
            _currentState == OutboundState.OB_STATE_REQUEST_SENT ||
            _currentState == OutboundState.OB_STATE_CREATED_RECEIVED)
            _currentState = OutboundState.OB_STATE_CONFIRMED_PARTIALLY;
    }

    /**
     *  @return when we sent the first SessionConfirmed packet, or 0
     *  @since 0.9.2
     */
    public long getConfirmedSentTime() { return _confirmedSentTime; }

    /** note that we just sent the SessionRequest packet */
    public synchronized void requestSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_requestSentCount == 0) {
            delay = RETRANSMIT_DELAY;
            _requestSentTime = _lastSend;
        } else {
            delay = Math.min(RETRANSMIT_DELAY << _requestSentCount,
                             _requestSentTime + EstablishmentManager.OB_MESSAGE_TIMEOUT - _lastSend);
        }
        _requestSentCount++;
        _nextSend = _lastSend + delay;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send a request packet, nextSend in " + delay + " on " + this);
        if (_currentState == OutboundState.OB_STATE_UNKNOWN ||
            _currentState == OutboundState.OB_STATE_INTRODUCED)
            _currentState = OutboundState.OB_STATE_REQUEST_SENT;
    }


    /**
     *  @return when we sent the first SessionRequest packet, or 0
     *  @since 0.9.2
     */
    public long getRequestSentTime() { return _requestSentTime; }

    /** note that we just sent the RelayRequest packet */
    public synchronized void introSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_introSentCount == 0) {
            delay = RETRANSMIT_DELAY;
            _introSentTime = _lastSend;
        } else {
            delay = Math.min(RETRANSMIT_DELAY << _introSentCount,
                             _introSentTime + EstablishmentManager.OB_MESSAGE_TIMEOUT - _lastSend);
        }
        _introSentCount++;
        _nextSend = _lastSend + delay;
        if (_currentState == OutboundState.OB_STATE_UNKNOWN)
            _currentState = OutboundState.OB_STATE_PENDING_INTRO;
    }

    /**
     *  @return when we sent the first RelayRequest packet, or 0
     *  @since 0.9.2
     */
    public long getIntroSentTime() { return _introSentTime; }

    public synchronized void introductionFailed() {
        _nextSend = _context.clock().now();
        // keep the state as OB_STATE_PENDING_INTRO, so next time the EstablishmentManager asks us
        // whats up, it'll try a new random intro peer
    }
    
    /**
     *  This changes the remoteHostId from a hash-based one or possibly
     *  incorrect IP/port to what the introducer told us.
     *  All params are for the remote end (NOT the introducer) and must have been validated already.
     */
    public synchronized void introduced(byte bobIP[], int bobPort) {
        if (_currentState != OutboundState.OB_STATE_PENDING_INTRO)
            return; // we've already successfully been introduced, so don't overwrite old settings
        _nextSend = _context.clock().now() + WAIT_FOR_HOLE_PUNCH_DELAY; // wait briefly for the hole punching
        _currentState = OutboundState.OB_STATE_INTRODUCED;
        if (_claimedAddress != null && bobPort == _bobPort && DataHelper.eq(bobIP, _bobIP)) {
            // he's who he said he was
            _remoteHostId = _claimedAddress;
        } else {
            // no IP/port or wrong IP/port in RI
            _bobIP = bobIP;
            _bobPort = bobPort;
            _remoteHostId = new RemoteHostId(bobIP, bobPort);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Introduced to " + _remoteHostId + ", now lets get on with establishing");
    }

    /**
     *  Accelerate response to RelayResponse if we haven't sent it yet.
     *
     *  @return true if we should send the SessionRequest now
     *  @since 0.9.15
     */
    synchronized boolean receiveHolePunch() {
        if (_currentState != OutboundState.OB_STATE_INTRODUCED)
            return false;
        if (_requestSentCount > 0)
            return false;
        long now = _context.clock().now();
        if (_log.shouldLog(Log.INFO))
            _log.info(toString() + " accelerating SessionRequest by " + (_nextSend - now) + " ms");
        _nextSend = now;
        return true;
    }

    /**
     * how long have we been trying to establish this session?
     */
    public long getLifetime() { return getLifetime(_context.clock().now()); }

    /**
     * how long have we been trying to establish this session?
     * @since 0.9.57
     */
    public long getLifetime(long now) { return now - _establishBegin; }

    public long getEstablishBeginTime() { return _establishBegin; }

    /**
     *  @return 0 at initialization (to force sending session request),
     *          rcv time after receiving a packet,
     *          send time + delay after sending a packet (including session request)
     */
    public synchronized long getNextSendTime() { return _nextSend; }

    /**
     *  This should be what the state is currently indexed by in the _outboundStates table.
     *  Beware -
     *  During introduction, this is a router hash.
     *  After introduced() is called, this is set to the IP/port the introducer told us.
     *  @return non-null
     */
    RemoteHostId getRemoteHostId() { return _remoteHostId; }

    /**
     *  This will never be a hash-based address.
     *  This is the 'claimed' (unverified) address from the netdb, or null.
     *  It is not changed after introduction. Use getRemoteHostId() for the verified address.
     *  @return may be null
     */
    RemoteHostId getClaimedAddress() { return _claimedAddress; }

    /** we have received a real data packet, so we're done establishing */
    public synchronized void dataReceived() {
        packetReceived();
        _currentState = OutboundState.OB_STATE_CONFIRMED_COMPLETELY;
    }
    
    /**
     *  Call from synchronized method only
     */
    protected void packetReceived() {
        _nextSend = _context.clock().now();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Got a packet, nextSend == now");
    }

    /** @since 0.8.9 */
    @Override
    public String toString() {
        return "OES " + _remotePeer.getHash().toBase64().substring(0, 6) + ' ' + _remoteHostId +
               " lifetime: " + DataHelper.formatDuration(getLifetime()) +
               ' ' + _currentState;
    }
}
