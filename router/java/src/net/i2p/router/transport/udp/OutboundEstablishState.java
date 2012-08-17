package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

/**
 * Data for a new connection being established, where we initiated the 
 * connection with a remote peer.  In other words, we are Alice and
 * they are Bob.
 *
 */
class OutboundEstablishState {
    private final RouterContext _context;
    private final Log _log;
    // SessionRequest message
    private final byte _sentX[];
    private byte _bobIP[];
    private int _bobPort;
    private final DHSessionKeyBuilder _keyBuilder;
    // SessionCreated message
    private byte _receivedY[];
    private byte _aliceIP[];
    private int _alicePort;
    private long _receivedRelayTag;
    private long _receivedSignedOnTime;
    private SessionKey _sessionKey;
    private SessionKey _macKey;
    private Signature _receivedSignature;
    private byte[] _receivedEncryptedSignature;
    private byte[] _receivedIV;
    // SessionConfirmed messages
    private long _sentSignedOnTime;
    private Signature _sentSignature;
    // general status 
    private final long _establishBegin;
    //private long _lastReceive;
    private long _lastSend;
    private long _nextSend;
    private RemoteHostId _remoteHostId;
    private final RouterIdentity _remotePeer;
    private final SessionKey _introKey;
    private final Queue<OutNetMessage> _queuedMessages;
    private OutboundState _currentState;
    private long _introductionNonce;
    // intro
    private final UDPAddress _remoteAddress;
    private boolean _complete;
    // counts for backoff
    private int _confirmedSentCount;
    private int _requestSentCount;
    private int _introSentCount;
    // Times for timeout
    private long _confirmedSentTime;
    private long _requestSentTime;
    private long _introSentTime;
    
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
        OB_STATE_INTRODUCED
    }
    
    /** basic delay before backoff */
    private static final long RETRANSMIT_DELAY = 1500;

    /** max delay including backoff */
    private static final long MAX_DELAY = 15*1000;

    /**
     *  @param addr non-null
     */
    public OutboundEstablishState(RouterContext ctx, InetAddress remoteHost, int remotePort, 
                                  RouterIdentity remotePeer, SessionKey introKey, UDPAddress addr,
                                  DHSessionKeyBuilder dh) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundEstablishState.class);
        if ( (remoteHost != null) && (remotePort > 0) ) {
            _bobIP = remoteHost.getAddress();
            _bobPort = remotePort;
            _remoteHostId = new RemoteHostId(_bobIP, _bobPort);
        } else {
            _bobIP = null;
            _bobPort = -1;
            _remoteHostId = new RemoteHostId(remotePeer.calculateHash().getData());
        }
        _remotePeer = remotePeer;
        _introKey = introKey;
        _queuedMessages = new LinkedBlockingQueue();
        _establishBegin = ctx.clock().now();
        _remoteAddress = addr;
        _introductionNonce = -1;
        _keyBuilder = dh;
        _sentX = new byte[UDPPacketReader.SessionRequestReader.X_LENGTH];
        prepareSessionRequest();
        if (addr.getIntroducerCount() > 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("new outbound establish to " + remotePeer.calculateHash() + ", with address: " + addr);
            _currentState = OutboundState.OB_STATE_PENDING_INTRO;
        } else {
            _currentState = OutboundState.OB_STATE_UNKNOWN;
        }
    }
    
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
     *  Queue a message to be sent after the session is established.
     */
    public void addMessage(OutNetMessage msg) {
        // chance of a duplicate here in a race, that's ok
        if (!_queuedMessages.contains(msg))
            _queuedMessages.offer(msg);
        else if (_log.shouldLog(Log.WARN))
             _log.warn("attempt to add duplicate msg to queue: " + msg);
    }

    /** @return null if none */
    public OutNetMessage getNextQueuedMessage() { 
        return _queuedMessages.poll();
    }
    
    public RouterIdentity getRemoteIdentity() { return _remotePeer; }
    public SessionKey getIntroKey() { return _introKey; }
    
    /** called from constructor, no need to synch */
    private void prepareSessionRequest() {
        byte X[] = _keyBuilder.getMyPublicValue().toByteArray();
        if (X.length == 257)
            System.arraycopy(X, 1, _sentX, 0, _sentX.length);
        else if (X.length == 256)
            System.arraycopy(X, 0, _sentX, 0, _sentX.length);
        else
            System.arraycopy(X, 0, _sentX, _sentX.length - X.length, X.length);
    }

    public byte[] getSentX() { return _sentX; }
    /** the remote side (Bob) */
    public synchronized byte[] getSentIP() { return _bobIP; }
    /** the remote side (Bob) */
    public synchronized int getSentPort() { return _bobPort; }

    public synchronized void receiveSessionCreated(UDPPacketReader.SessionCreatedReader reader) {
        if (_receivedY != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Session created already received, ignoring");
            return; // already received
        }
        _receivedY = new byte[UDPPacketReader.SessionCreatedReader.Y_LENGTH];
        reader.readY(_receivedY, 0);
        if (_aliceIP == null)
            _aliceIP = new byte[reader.readIPSize()];
        reader.readIP(_aliceIP, 0);
        _alicePort = reader.readPort();
        _receivedRelayTag = reader.readRelayTag();
        _receivedSignedOnTime = reader.readSignedOnTime();
        _receivedEncryptedSignature = new byte[Signature.SIGNATURE_BYTES + 8];
        reader.readEncryptedSignature(_receivedEncryptedSignature, 0);
        _receivedIV = new byte[UDPPacket.IV_SIZE];
        reader.readIV(_receivedIV, 0);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive session created:Sig: " + Base64.encode(_receivedEncryptedSignature)
                       + "receivedIV: " + Base64.encode(_receivedIV)
                       + "AliceIP: " + Addresses.toString(_aliceIP)
                       + " RelayTag: " + _receivedRelayTag
                       + " SignedOn: " + _receivedSignedOnTime
                       + ' ' + this.toString());
        
        if (_currentState == OutboundState.OB_STATE_UNKNOWN ||
            _currentState == OutboundState.OB_STATE_REQUEST_SENT ||
            _currentState == OutboundState.OB_STATE_INTRODUCED ||
            _currentState == OutboundState.OB_STATE_PENDING_INTRO)
            _currentState = OutboundState.OB_STATE_CREATED_RECEIVED;
        packetReceived();
    }
    
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
        if (_receivedSignature != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Session created already validated");
            return true;
        }
        
        boolean valid = true;
        try {
            generateSessionKey();
        } catch (DHSessionKeyBuilder.InvalidPublicParameterException ippe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Peer " + getRemoteHostId() + " sent us an invalid DH parameter (or were spoofed)", ippe);
            valid = false;
        }
        if (valid)
            decryptSignature();
        
        if (valid && verifySessionCreated()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Session created passed validation");
            return true;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Session created failed validation, clearing state for " + _remoteHostId.toString());
            fail();
            return false;
        }
    }
    
    public synchronized void fail() {
        _receivedY = null;
        _aliceIP = null;
        _receivedRelayTag = 0;
        _receivedSignedOnTime = -1;
        _receivedEncryptedSignature = null;
        _receivedIV = null;
        _receivedSignature = null;

        if ( (_currentState == OutboundState.OB_STATE_UNKNOWN) || 
             (_currentState == OutboundState.OB_STATE_CREATED_RECEIVED) )
            _currentState = OutboundState.OB_STATE_REQUEST_SENT;

        _nextSend = _context.clock().now();
    }
    
    /**
     *  Generates session key and mac key.
     *  Caller must synch on this.
     */
    private void generateSessionKey() throws DHSessionKeyBuilder.InvalidPublicParameterException {
        if (_sessionKey != null) return;
        _keyBuilder.setPeerPublicValue(_receivedY);
        _sessionKey = _keyBuilder.getSessionKey();
        ByteArray extra = _keyBuilder.getExtraBytes();
        _macKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(extra.getData(), 0, _macKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Established outbound keys.  cipher: " + _sessionKey
                       + " mac: " + _macKey);
    }
    
    /** 
     * decrypt the signature (and subsequent pad bytes) with the 
     * additional layer of encryption using the negotiated key along side
     * the packet's IV
     *  Caller must synch on this.
     */
    private void decryptSignature() {
        if (_receivedEncryptedSignature == null) throw new NullPointerException("encrypted signature is null! this=" + this.toString());
        else if (_sessionKey == null) throw new NullPointerException("SessionKey is null!");
        else if (_receivedIV == null) throw new NullPointerException("IV is null!");
        _context.aes().decrypt(_receivedEncryptedSignature, 0, _receivedEncryptedSignature, 0, 
                               _sessionKey, _receivedIV, _receivedEncryptedSignature.length);
        byte signatureBytes[] = new byte[Signature.SIGNATURE_BYTES];
        System.arraycopy(_receivedEncryptedSignature, 0, signatureBytes, 0, Signature.SIGNATURE_BYTES);
        _receivedSignature = new Signature(signatureBytes);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Decrypted received signature: " + Base64.encode(signatureBytes));
    }

    /**
     * Verify: Alice's IP + Alice's port + Bob's IP + Bob's port + Alice's
     *         new relay tag + Bob's signed on time
     *  Caller must synch on this.
     */
    private boolean verifySessionCreated() {
        byte signed[] = new byte[256+256 // X + Y
                                 + _aliceIP.length + 2
                                 + _bobIP.length + 2
                                 + 4 // sent relay tag
                                 + 4 // signed on time
                                 ];
        
        int off = 0;
        System.arraycopy(_sentX, 0, signed, off, _sentX.length);
        off += _sentX.length;
        System.arraycopy(_receivedY, 0, signed, off, _receivedY.length);
        off += _receivedY.length;
        System.arraycopy(_aliceIP, 0, signed, off, _aliceIP.length);
        off += _aliceIP.length;
        DataHelper.toLong(signed, off, 2, _alicePort);
        off += 2;
        System.arraycopy(_bobIP, 0, signed, off, _bobIP.length);
        off += _bobIP.length;
        DataHelper.toLong(signed, off, 2, _bobPort);
        off += 2;
        DataHelper.toLong(signed, off, 4, _receivedRelayTag);
        off += 4;
        DataHelper.toLong(signed, off, 4, _receivedSignedOnTime);
        boolean valid = _context.dsa().verifySignature(_receivedSignature, signed, _remotePeer.getSigningPublicKey());
        if (!valid || _log.shouldLog(Log.DEBUG)) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("Signed sessionCreated:");
            buf.append(" Alice: ").append(Addresses.toString(_aliceIP, _alicePort));
            buf.append(" Bob: ").append(Addresses.toString(_bobIP, _bobPort));
            buf.append(" RelayTag: ").append(_receivedRelayTag);
            buf.append(" SignedOn: ").append(_receivedSignedOnTime);
            buf.append(" signature: ").append(Base64.encode(_receivedSignature.getData()));
            if (valid)
                _log.debug(buf.toString());
            else if (_log.shouldLog(Log.WARN))
                _log.warn("INVALID: " + buf.toString());
        }
        return valid;
    }
    
    public synchronized SessionKey getCipherKey() { return _sessionKey; }
    public synchronized SessionKey getMACKey() { return _macKey; }

    public synchronized long getReceivedRelayTag() { return _receivedRelayTag; }
    public synchronized long getSentSignedOnTime() { return _sentSignedOnTime; }
    public synchronized long getReceivedSignedOnTime() { return _receivedSignedOnTime; }
    public synchronized byte[] getReceivedIP() { return _aliceIP; }
    public synchronized int getReceivedPort() { return _alicePort; }
    
    /**
     *  Let's sign everything so we can fragment properly.
     *
     *  Note that while a SessionConfirmed could in theory be fragmented,
     *  in practice a RouterIdentity is 387 bytes and a single fragment is 512 bytes max,
     *  so it will never be fragmented.
     */
    public synchronized void prepareSessionConfirmed() {
        if (_sentSignedOnTime > 0)
            return;
        byte signed[] = new byte[256+256 // X + Y
                             + _aliceIP.length + 2
                             + _bobIP.length + 2
                             + 4 // Alice's relay key
                             + 4 // signed on time
                             ];

        _sentSignedOnTime = _context.clock().now() / 1000;
        
        int off = 0;
        System.arraycopy(_sentX, 0, signed, off, _sentX.length);
        off += _sentX.length;
        System.arraycopy(_receivedY, 0, signed, off, _receivedY.length);
        off += _receivedY.length;
        System.arraycopy(_aliceIP, 0, signed, off, _aliceIP.length);
        off += _aliceIP.length;
        DataHelper.toLong(signed, off, 2, _alicePort);
        off += 2;
        System.arraycopy(_bobIP, 0, signed, off, _bobIP.length);
        off += _bobIP.length;
        DataHelper.toLong(signed, off, 2, _bobPort);
        off += 2;
        DataHelper.toLong(signed, off, 4, _receivedRelayTag);
        off += 4;
        DataHelper.toLong(signed, off, 4, _sentSignedOnTime);
        // BUG - if SigningPrivateKey is null, _sentSignature will be null, leading to NPE later
        // should we throw something from here?
        _sentSignature = _context.dsa().sign(signed, _context.keyManager().getSigningPrivateKey());
    }
    
    public synchronized Signature getSentSignature() { return _sentSignature; }
    
    /** note that we just sent the SessionConfirmed packet */
    public synchronized void confirmedPacketsSent() {
        _lastSend = _context.clock().now();
        long delay;
        if (_confirmedSentCount == 0) {
            delay = RETRANSMIT_DELAY;
            _confirmedSentTime = _lastSend;
        } else {
            delay = Math.min(RETRANSMIT_DELAY << _confirmedSentCount, MAX_DELAY);
        }
        _confirmedSentCount++;
        _nextSend = _lastSend + delay;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send confirm packets, nextSend in " + delay);
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
            delay = Math.min(RETRANSMIT_DELAY << _requestSentCount, MAX_DELAY);
        }
        _requestSentCount++;
        _nextSend = _lastSend + delay;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send a request packet, nextSend in " + delay);
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
            delay = Math.min(RETRANSMIT_DELAY << _introSentCount, MAX_DELAY);
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
     *  This changes the remoteHostId from a hash-based one to a IP/Port one,
     *  OR the IP or port could change.
     */
    public synchronized void introduced(InetAddress bob, byte bobIP[], int bobPort) {
        if (_currentState != OutboundState.OB_STATE_PENDING_INTRO)
            return; // we've already successfully been introduced, so don't overwrite old settings
        _nextSend = _context.clock().now() + 500; // wait briefly for the hole punching
        _currentState = OutboundState.OB_STATE_INTRODUCED;
        _bobIP = bobIP;
        _bobPort = bobPort;
        _remoteHostId = new RemoteHostId(bobIP, bobPort);
        if (_log.shouldLog(Log.INFO))
            _log.info("Introduced to " + _remoteHostId + ", now lets get on with establishing");
    }
    
    /** how long have we been trying to establish this session? */
    public long getLifetime() { return _context.clock().now() - _establishBegin; }
    public long getEstablishBeginTime() { return _establishBegin; }
    public synchronized long getNextSendTime() { return _nextSend; }

    /** uniquely identifies an attempt */
    RemoteHostId getRemoteHostId() { return _remoteHostId; }

    /** we have received a real data packet, so we're done establishing */
    public synchronized void dataReceived() {
        packetReceived();
        _currentState = OutboundState.OB_STATE_CONFIRMED_COMPLETELY;
    }
    
    private void packetReceived() {
        _nextSend = _context.clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Got a packet, nextSend == now");
    }

    /** @since 0.8.9 */
    @Override
    public String toString() {
        return "OES " + _remoteHostId + ' ' + _currentState;
    }
}
