package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.crypto.DHSessionKeyBuilder;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
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
    private final RouterContext _context;
    private final Log _log;
    // SessionRequest message
    private byte _sentX[];
    private byte _bobIP[];
    private int _bobPort;
    private DHSessionKeyBuilder _keyBuilder;
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
    private int _currentState;
    private long _introductionNonce;
    // intro
    private final UDPAddress _remoteAddress;
    private boolean _complete;
    
    /** nothin sent yet */
    public static final int STATE_UNKNOWN = 0;
    /** we have sent an initial request */
    public static final int STATE_REQUEST_SENT = 1;
    /** we have received a signed creation packet */
    public static final int STATE_CREATED_RECEIVED = 2;
    /** we have sent one or more confirmation packets */
    public static final int STATE_CONFIRMED_PARTIALLY = 3;
    /** we have received a data packet */
    public static final int STATE_CONFIRMED_COMPLETELY = 4;
    /** we need to have someone introduce us to the peer, but haven't received a RelayResponse yet */
    public static final int STATE_PENDING_INTRO = 5;
    
    public OutboundEstablishState(RouterContext ctx, InetAddress remoteHost, int remotePort, 
                                  RouterIdentity remotePeer, SessionKey introKey, UDPAddress addr) {
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
        _currentState = STATE_UNKNOWN;
        _establishBegin = ctx.clock().now();
        _remoteAddress = addr;
        _introductionNonce = -1;
        prepareSessionRequest();
        if ( (addr != null) && (addr.getIntroducerCount() > 0) ) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("new outbound establish to " + remotePeer.calculateHash().toBase64() + ", with address: " + addr);
            _currentState = STATE_PENDING_INTRO;
        }
    }
    
    public synchronized int getState() { return _currentState; }
    public synchronized boolean complete() { 
        boolean already = _complete; 
        _complete = true; 
        return already; 
    }

    public UDPAddress getRemoteAddress() { return _remoteAddress; }
    public void setIntroNonce(long nonce) { _introductionNonce = nonce; }
    public long getIntroNonce() { return _introductionNonce; }
    
    public void addMessage(OutNetMessage msg) {
        // chance of a duplicate here in a race, that's ok
        if (!_queuedMessages.contains(msg))
            _queuedMessages.offer(msg);
        else if (_log.shouldLog(Log.WARN))
             _log.warn("attempt to add duplicate msg to queue: " + msg);
    }

    public OutNetMessage getNextQueuedMessage() { 
        return _queuedMessages.poll();
    }
    
    public RouterIdentity getRemoteIdentity() { return _remotePeer; }
    public SessionKey getIntroKey() { return _introKey; }
    
    /** called from constructor, no need to synch */
    private void prepareSessionRequest() {
        _keyBuilder = new DHSessionKeyBuilder();
        byte X[] = _keyBuilder.getMyPublicValue().toByteArray();
        if (_sentX == null)
            _sentX = new byte[UDPPacketReader.SessionRequestReader.X_LENGTH];
        if (X.length == 257)
            System.arraycopy(X, 1, _sentX, 0, _sentX.length);
        else if (X.length == 256)
            System.arraycopy(X, 0, _sentX, 0, _sentX.length);
        else
            System.arraycopy(X, 0, _sentX, _sentX.length - X.length, X.length);
    }

    public byte[] getSentX() { return _sentX; }
    public synchronized byte[] getSentIP() { return _bobIP; }
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
            _log.debug("Receive session created:\neSig: " + Base64.encode(_receivedEncryptedSignature)
                       + "\nreceivedIV: " + Base64.encode(_receivedIV)
                       + "\nAliceIP: " + Addresses.toString(_aliceIP)
                       + " RelayTag: " + _receivedRelayTag
                       + " SignedOn: " + _receivedSignedOnTime
                       + "\nthis: " + this.toString());
        
        if ( (_currentState == STATE_UNKNOWN) || (_currentState == STATE_REQUEST_SENT) )
            _currentState = STATE_CREATED_RECEIVED;
        packetReceived();
    }
    
    /**
     * Blocking call (run in the establisher thread) to determine if the 
     * session was created properly.  If it wasn't, all the SessionCreated
     * remnants are dropped (perhaps they were spoofed, etc) so that we can
     * receive another one
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

        if ( (_currentState == STATE_UNKNOWN) || 
             (_currentState == STATE_REQUEST_SENT) || 
             (_currentState == STATE_CREATED_RECEIVED) )
            _currentState = STATE_REQUEST_SENT;

        _nextSend = _context.clock().now();
    }
    
    private void generateSessionKey() throws DHSessionKeyBuilder.InvalidPublicParameterException {
        if (_sessionKey != null) return;
        _keyBuilder.setPeerPublicValue(_receivedY);
        _sessionKey = _keyBuilder.getSessionKey();
        ByteArray extra = _keyBuilder.getExtraBytes();
        _macKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(extra.getData(), 0, _macKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Established outbound keys.  cipher: " + Base64.encode(_sessionKey.getData())
                       + " mac: " + Base64.encode(_macKey.getData()));
    }
    
    /** 
     * decrypt the signature (and subsequent pad bytes) with the 
     * additional layer of encryption using the negotiated key along side
     * the packet's IV
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
            _log.debug("Decrypted received signature: \n" + Base64.encode(signatureBytes));
    }

    /**
     * Verify: Alice's IP + Alice's port + Bob's IP + Bob's port + Alice's
     *         new relay tag + Bob's signed on time
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
     * Lets sign everything so we can fragment properly
     *
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
        _nextSend = _lastSend + 1000;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send confirm packets, nextSend = 1s");
        if ( (_currentState == STATE_UNKNOWN) || 
             (_currentState == STATE_REQUEST_SENT) ||
             (_currentState == STATE_CREATED_RECEIVED) )
            _currentState = STATE_CONFIRMED_PARTIALLY;
    }
    /** note that we just sent the SessionRequest packet */
    public synchronized void requestSent() {
        _lastSend = _context.clock().now();
        _nextSend = _lastSend + 1000;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send a request packet, nextSend = 1s");
        if (_currentState == STATE_UNKNOWN)
            _currentState = STATE_REQUEST_SENT;
    }
    public synchronized void introSent() {
        _lastSend = _context.clock().now();
        _nextSend = _lastSend + 1000;
        if (_currentState == STATE_UNKNOWN)
            _currentState = STATE_PENDING_INTRO;
    }
    public synchronized void introductionFailed() {
        _nextSend = _context.clock().now();
        // keep the state as STATE_PENDING_INTRO, so next time the EstablishmentManager asks us
        // whats up, it'll try a new random intro peer
    }
    
    public synchronized void introduced(InetAddress bob, byte bobIP[], int bobPort) {
        if (_currentState != STATE_PENDING_INTRO)
            return; // we've already successfully been introduced, so don't overwrite old settings
        _nextSend = _context.clock().now() + 500; // wait briefly for the hole punching
        if (_currentState == STATE_PENDING_INTRO) {
            // STATE_UNKNOWN will probe the EstablishmentManager to send a new
            // session request to this newly known address
            _currentState = STATE_UNKNOWN; 
        }
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
    public synchronized void setNextSendTime(long when) { 
        _nextSend = when; 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Explicit nextSend=" + (_nextSend-_context.clock().now()), new Exception("Set by"));
    }

    /** uniquely identifies an attempt */
    RemoteHostId getRemoteHostId() { return _remoteHostId; }

    /** we have received a real data packet, so we're done establishing */
    public synchronized void dataReceived() {
        packetReceived();
        _currentState = STATE_CONFIRMED_COMPLETELY;
    }
    
    private void packetReceived() {
        _nextSend = _context.clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Got a packet, nextSend == now");
    }

    /** @since 0.8.9 */
    @Override
    public String toString() {
        return "OES " + _remoteHostId;
    }
}
