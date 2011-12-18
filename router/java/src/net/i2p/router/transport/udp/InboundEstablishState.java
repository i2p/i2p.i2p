package net.i2p.router.transport.udp;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import net.i2p.crypto.DHSessionKeyBuilder;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.router.RouterContext;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

/**
 * Data for a new connection being established, where the remote peer has
 * initiated the connection with us.  In other words, they are Alice and
 * we are Bob.
 *
 * TODO do all these methods need to be synchronized?
 */
class InboundEstablishState {
    private final RouterContext _context;
    private final Log _log;
    // SessionRequest message
    private byte _receivedX[];
    private byte _bobIP[];
    private final int _bobPort;
    // try to fix NPE in getSentY() ?????
    private volatile DHSessionKeyBuilder _keyBuilder;
    // SessionCreated message
    private byte _sentY[];
    private final byte _aliceIP[];
    private final int _alicePort;
    private long _sentRelayTag;
    private long _sentSignedOnTime;
    private SessionKey _sessionKey;
    private SessionKey _macKey;
    private Signature _sentSignature;
    // SessionConfirmed messages
    private byte _receivedIdentity[][];
    private long _receivedSignedOnTime;
    private byte _receivedSignature[];
    private boolean _verificationAttempted;
    private RouterIdentity _receivedConfirmedIdentity;
    // general status 
    private final long _establishBegin;
    //private long _lastReceive;
    // private long _lastSend;
    private long _nextSend;
    private final RemoteHostId _remoteHostId;
    private int _currentState;
    private boolean _complete;
    
    /** nothin known yet */
    public static final int STATE_UNKNOWN = 0;
    /** we have received an initial request */
    public static final int STATE_REQUEST_RECEIVED = 1;
    /** we have sent a signed creation packet */
    public static final int STATE_CREATED_SENT = 2;
    /** we have received one or more confirmation packets */
    public static final int STATE_CONFIRMED_PARTIALLY = 3;
    /** we have completely received all of the confirmation packets */
    public static final int STATE_CONFIRMED_COMPLETELY = 4;
    /** we are explicitly failing it */
    public static final int STATE_FAILED = 5;
    
    public InboundEstablishState(RouterContext ctx, byte remoteIP[], int remotePort, int localPort) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundEstablishState.class);
        _aliceIP = remoteIP;
        _alicePort = remotePort;
        _remoteHostId = new RemoteHostId(_aliceIP, _alicePort);
        _bobPort = localPort;
        _currentState = STATE_UNKNOWN;
        _establishBegin = ctx.clock().now();
    }
    
    public synchronized int getState() { return _currentState; }
    public synchronized boolean complete() { 
        boolean already = _complete; 
        _complete = true; 
        return already; 
    }
    
    public synchronized void receiveSessionRequest(UDPPacketReader.SessionRequestReader req) {
        if (_receivedX == null)
            _receivedX = new byte[UDPPacketReader.SessionRequestReader.X_LENGTH];
        req.readX(_receivedX, 0);
        if (_bobIP == null)
            _bobIP = new byte[req.readIPSize()];
        req.readIP(_bobIP, 0);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive sessionRequest, BobIP = " + Addresses.toString(_bobIP));
        if (_currentState == STATE_UNKNOWN)
            _currentState = STATE_REQUEST_RECEIVED;
        packetReceived();
    }
    
    public synchronized boolean sessionRequestReceived() { return _receivedX != null; }
    public synchronized byte[] getReceivedX() { return _receivedX; }
    public synchronized byte[] getReceivedOurIP() { return _bobIP; }
    
    public synchronized void generateSessionKey() throws DHSessionKeyBuilder.InvalidPublicParameterException {
        if (_sessionKey != null) return;
        _keyBuilder = new DHSessionKeyBuilder();
        _keyBuilder.setPeerPublicValue(_receivedX);
        _sessionKey = _keyBuilder.getSessionKey();
        ByteArray extra = _keyBuilder.getExtraBytes();
        _macKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(extra.getData(), 0, _macKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Established inbound keys.  cipher: " + Base64.encode(_sessionKey.getData())
                       + " mac: " + Base64.encode(_macKey.getData()));
    }
    
    public synchronized SessionKey getCipherKey() { return _sessionKey; }
    public synchronized SessionKey getMACKey() { return _macKey; }

    /** what IP do they appear to be on? */
    public byte[] getSentIP() { return _aliceIP; }

    /** what port number do they appear to be coming from? */
    public int getSentPort() { return _alicePort; }
    
    public synchronized byte[] getBobIP() { return _bobIP; }
    
    public synchronized byte[] getSentY() {
        if (_sentY == null)
            // Rare NPE seen here...
            _sentY = _keyBuilder.getMyPublicValueBytes();
        return _sentY;
    }
    
    public synchronized void fail() {
        _currentState = STATE_FAILED;
    }
    
    public synchronized long getSentRelayTag() { return _sentRelayTag; }
    public synchronized void setSentRelayTag(long tag) { _sentRelayTag = tag; }
    public synchronized long getSentSignedOnTime() { return _sentSignedOnTime; }
    
    public synchronized void prepareSessionCreated() {
        if (_sentSignature == null) signSessionCreated();
    }
    
    public synchronized Signature getSentSignature() { return _sentSignature; }
    
    /**
     * Sign: Alice's IP + Alice's port + Bob's IP + Bob's port + Alice's
     *       new relay tag + Bob's signed on time
     */
    private void signSessionCreated() {
        byte signed[] = new byte[256 + 256 // X + Y
                                 + _aliceIP.length + 2
                                 + _bobIP.length + 2
                                 + 4 // sent relay tag
                                 + 4 // signed on time
                                 ];
        _sentSignedOnTime = _context.clock().now() / 1000;
        
        int off = 0;
        System.arraycopy(_receivedX, 0, signed, off, _receivedX.length);
        off += _receivedX.length;
        getSentY();
        System.arraycopy(_sentY, 0, signed, off, _sentY.length);
        off += _sentY.length;
        System.arraycopy(_aliceIP, 0, signed, off, _aliceIP.length);
        off += _aliceIP.length;
        DataHelper.toLong(signed, off, 2, _alicePort);
        off += 2;
        System.arraycopy(_bobIP, 0, signed, off, _bobIP.length);
        off += _bobIP.length;
        DataHelper.toLong(signed, off, 2, _bobPort);
        off += 2;
        DataHelper.toLong(signed, off, 4, _sentRelayTag);
        off += 4;
        DataHelper.toLong(signed, off, 4, _sentSignedOnTime);
        
        _sentSignature = _context.dsa().sign(signed, _context.keyManager().getSigningPrivateKey());
        
        if (_log.shouldLog(Log.DEBUG)) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("Signing sessionCreated:");
            buf.append(" ReceivedX: ").append(Base64.encode(_receivedX));
            buf.append(" SentY: ").append(Base64.encode(_sentY));
            buf.append(" Alice: ").append(Addresses.toString(_aliceIP, _alicePort));
            buf.append(" Bob: ").append(Addresses.toString(_bobIP, _bobPort));
            buf.append(" RelayTag: ").append(_sentRelayTag);
            buf.append(" SignedOn: ").append(_sentSignedOnTime);
            buf.append(" signature: ").append(Base64.encode(_sentSignature.getData()));
            _log.debug(buf.toString());
        }
    }
    
    /** note that we just sent a SessionCreated packet */
    public synchronized void createdPacketSent() {
        // _lastSend = _context.clock().now();
        if ( (_currentState == STATE_UNKNOWN) || (_currentState == STATE_REQUEST_RECEIVED) )
            _currentState = STATE_CREATED_SENT;
    }
    
    /** how long have we been trying to establish this session? */
    public long getLifetime() { return _context.clock().now() - _establishBegin; }
    public long getEstablishBeginTime() { return _establishBegin; }
    public synchronized long getNextSendTime() { return _nextSend; }
    public synchronized void setNextSendTime(long when) { _nextSend = when; }

    /** RemoteHostId, uniquely identifies an attempt */
    RemoteHostId getRemoteHostId() { return _remoteHostId; }

    public synchronized void receiveSessionConfirmed(UDPPacketReader.SessionConfirmedReader conf) {
        if (_receivedIdentity == null)
            _receivedIdentity = new byte[conf.readTotalFragmentNum()][];
        int cur = conf.readCurrentFragmentNum();
        if (cur >= _receivedIdentity.length) {
            // avoid AIOOBE
            // should do more than this, but what? disconnect?
            fail();
            packetReceived();
            return;
        }
        if (_receivedIdentity[cur] == null) {
            byte fragment[] = new byte[conf.readCurrentFragmentSize()];
            conf.readFragmentData(fragment, 0);
            _receivedIdentity[cur] = fragment;
        }
        
        if (cur == _receivedIdentity.length-1) {
            _receivedSignedOnTime = conf.readFinalFragmentSignedOnTime();
            if (_receivedSignature == null)
                _receivedSignature = new byte[Signature.SIGNATURE_BYTES];
            conf.readFinalSignature(_receivedSignature, 0);
        }
        
        if ( (_currentState == STATE_UNKNOWN) || 
             (_currentState == STATE_REQUEST_RECEIVED) ||
             (_currentState == STATE_CREATED_SENT) ) {
            if (confirmedFullyReceived())
                _currentState = STATE_CONFIRMED_COMPLETELY;
            else
                _currentState = STATE_CONFIRMED_PARTIALLY;
        }
        
        packetReceived();
    }
    
    /** have we fully received the SessionConfirmed messages from Alice? */
    public synchronized boolean confirmedFullyReceived() {
        if (_receivedIdentity != null) {
            for (int i = 0; i < _receivedIdentity.length; i++)
                if (_receivedIdentity[i] == null)
                    return false;
            return true;
        } else {
            return false;
        }
    }
    
    /**
     * Who is Alice (null if forged/unknown)
     */
    public synchronized RouterIdentity getConfirmedIdentity() {
        if (!_verificationAttempted) {
            verifyIdentity();
            _verificationAttempted = true;
        }
        return _receivedConfirmedIdentity;
    }
    
    /**
     * Determine if Alice sent us a valid confirmation packet.  The 
     * identity signs: Alice's IP + Alice's port + Bob's IP + Bob's port
     * + Alice's new relay key + Alice's signed on time
     */
    private synchronized void verifyIdentity() {
        int identSize = 0;
        for (int i = 0; i < _receivedIdentity.length; i++)
            identSize += _receivedIdentity[i].length;
        byte ident[] = new byte[identSize];
        int off = 0;
        for (int i = 0; i < _receivedIdentity.length; i++) {
            int len = _receivedIdentity[i].length;
            System.arraycopy(_receivedIdentity[i], 0, ident, off, len);
            off += len;
        }
        ByteArrayInputStream in = new ByteArrayInputStream(ident); 
        RouterIdentity peer = new RouterIdentity();
        try {
            peer.readBytes(in);
            
            byte signed[] = new byte[256+256 // X + Y
                                     + _aliceIP.length + 2
                                     + _bobIP.length + 2
                                     + 4 // Alice's relay key
                                     + 4 // signed on time
                                     ];

            off = 0;
            System.arraycopy(_receivedX, 0, signed, off, _receivedX.length);
            off += _receivedX.length;
            getSentY();
            System.arraycopy(_sentY, 0, signed, off, _sentY.length);
            off += _sentY.length;
            System.arraycopy(_aliceIP, 0, signed, off, _aliceIP.length);
            off += _aliceIP.length;
            DataHelper.toLong(signed, off, 2, _alicePort);
            off += 2;
            System.arraycopy(_bobIP, 0, signed, off, _bobIP.length);
            off += _bobIP.length;
            DataHelper.toLong(signed, off, 2, _bobPort);
            off += 2;
            DataHelper.toLong(signed, off, 4, _sentRelayTag);
            off += 4;
            DataHelper.toLong(signed, off, 4, _receivedSignedOnTime);
            Signature sig = new Signature(_receivedSignature);
            boolean ok = _context.dsa().verifySignature(sig, signed, peer.getSigningPublicKey());
            if (ok) {
                _receivedConfirmedIdentity = peer;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Signature failed from " + peer);
            }
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Improperly formatted yet fully received ident", dfe);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Improperly formatted yet fully received ident", ioe);
        }
    }
    
    private void packetReceived() {
        _nextSend = _context.clock().now();
    }
    
    @Override
    public String toString() {            
        StringBuilder buf = new StringBuilder(128);
        buf.append(super.toString());
        if (_receivedX != null)
            buf.append(" ReceivedX: ").append(Base64.encode(_receivedX, 0, 4));
        if (_sentY != null)
            buf.append(" SentY: ").append(Base64.encode(_sentY, 0, 4));
        buf.append(" Alice: ").append(Addresses.toString(_aliceIP, _alicePort));
        buf.append(" Bob: ").append(Addresses.toString(_bobIP, _bobPort));
        buf.append(" RelayTag: ").append(_sentRelayTag);
        buf.append(" SignedOn: ").append(_sentSignedOnTime);
        buf.append(" state: ").append(_currentState);
        return buf.toString();
    }
}
