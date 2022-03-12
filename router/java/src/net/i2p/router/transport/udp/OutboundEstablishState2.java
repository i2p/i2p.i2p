package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;

import com.southernstorm.noise.protocol.ChaChaPolyCipherState;
import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.crypto.HKDF;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import static net.i2p.router.transport.udp.SSU2Util.*;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

/**
 * Data for a new connection being established, where we initiated the 
 * connection with a remote peer.  In other words, we are Alice and
 * they are Bob.
 *
 * SSU2 only.
 *
 * @since 0.9.54
 */
class OutboundEstablishState2 extends OutboundEstablishState implements SSU2Payload.PayloadCallback {
    private InetSocketAddress _bobSocketAddress;
    private final UDPTransport _transport;
    private final long _sendConnID;
    private final long _rcvConnID;
    private final RouterAddress _routerAddress;
    private long _token;
    private HandshakeState _handshakeState;
    private final byte[] _sendHeaderEncryptKey1;
    private final byte[] _rcvHeaderEncryptKey1;
    private byte[] _sendHeaderEncryptKey2;
    private byte[] _rcvHeaderEncryptKey2;
    private final byte[] _rcvRetryHeaderEncryptKey2;
    private int _mtu;
    private byte[] _sessReqForReTX;
    private byte[] _sessConfForReTX;
    private long _timeReceived;
    // not adjusted for RTT
    private long _skew;
    private PeerState2 _pstate;

    private static final boolean SET_TOKEN = false;
    private static final long MAX_SKEW = 2*60*1000L;

    /**
     *  @param claimedAddress an IP/port based RemoteHostId, or null if unknown
     *  @param remoteHostId non-null, == claimedAddress if direct, or a hash-based one if indirect
     *  @param remotePeer must have supported sig type
     *  @param needIntroduction should we ask Bob to be an introducer for us?
               ignored unless allowExtendedOptions is true
     *  @param introKey Bob's introduction key, as published in the netdb
     *  @param addr non-null
     */
    public OutboundEstablishState2(RouterContext ctx, UDPTransport transport, RemoteHostId claimedAddress,
                                   RemoteHostId remoteHostId, RouterIdentity remotePeer,
                                   boolean needIntroduction,
                                   SessionKey introKey, RouterAddress ra, UDPAddress addr) throws IllegalArgumentException {
        super(ctx, claimedAddress, remoteHostId, remotePeer, needIntroduction, introKey, addr);
        _transport = transport;
        if (claimedAddress != null) {
            try {
                _bobSocketAddress = new InetSocketAddress(InetAddress.getByAddress(_bobIP), _bobPort);
            } catch (UnknownHostException uhe) {
                throw new IllegalArgumentException("bad IP", uhe);
            }
        }
        // We need the MTU so the Session Confirmed can fit the RI in
        int mtu = addr.getMTU();
        if (mtu == 0) {
            if (ra.getTransportStyle().equals(UDPTransport.STYLE2)) {
                mtu = PeerState2.DEFAULT_MTU;
            } else {
                if (_bobIP.length == 16)
                    mtu = PeerState2.DEFAULT_SSU_IPV6_MTU;
                else
                    mtu = PeerState2.DEFAULT_SSU_IPV4_MTU;
            }
        } else {
            // TODO if too small, give up now
            if (ra.getTransportStyle().equals(UDPTransport.STYLE2)) {
                mtu = Math.min(Math.max(mtu, PeerState2.MIN_MTU), PeerState2.MAX_MTU);
            } else {
                if (_bobIP.length == 16)
                    mtu = Math.min(Math.max(mtu, PeerState2.MIN_SSU_IPV6_MTU), PeerState2.MAX_SSU_IPV6_MTU);
                else
                    mtu = Math.min(Math.max(mtu, PeerState2.MIN_SSU_IPV4_MTU), PeerState2.MAX_SSU_IPV4_MTU);
            }
        }
        _mtu = mtu;
        // TODO if RI too big, give up now
        if (addr.getIntroducerCount() > 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("new outbound establish to " + remotePeer.calculateHash() + ", with address: " + addr);
            _currentState = OutboundState.OB_STATE_PENDING_INTRO;
        } else {
            _currentState = OutboundState.OB_STATE_UNKNOWN;
        }

        _sendConnID = ctx.random().nextLong();
        // rcid == scid is not allowed
        long rcid;
        do {
            rcid = ctx.random().nextLong();
        } while (_sendConnID == rcid);
        _rcvConnID = rcid;

        _token = _transport.getEstablisher().getOutboundToken(_remotePeer.calculateHash());
        _routerAddress = ra;
        if (_token != 0)
            createNewState(ra);
        else
            _currentState = OutboundState.OB_STATE_NEEDS_TOKEN;

        byte[] ik = introKey.getData();
        _sendHeaderEncryptKey1 = ik;
        _rcvHeaderEncryptKey1 = ik;
        _sendHeaderEncryptKey2 = ik;
        //_rcvHeaderEncryptKey2 will be set after the Session Request message is created
        _rcvRetryHeaderEncryptKey2 = ik;
    }

    private void createNewState(RouterAddress addr) {
        String ss = addr.getOption("s");
        if (ss == null)
            throw new IllegalArgumentException("no SSU2 S");
        byte[] publicKey = Base64.decode(ss);
        if (publicKey == null)
            throw new IllegalArgumentException("bad SSU2 S");
        if (publicKey.length != 32)
            throw new IllegalArgumentException("bad SSU2 S len");
        try {
            _handshakeState = new HandshakeState(HandshakeState.PATTERN_ID_XK_SSU2, HandshakeState.INITIATOR, _transport.getXDHFactory());
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("bad proto", gse);
        }
        _handshakeState.getRemotePublicKey().setPublicKey(publicKey, 0);
        _handshakeState.getLocalKeyPair().setKeys(_transport.getSSU2StaticPrivKey(), 0,
                                                  _transport.getSSU2StaticPubKey(), 0);
    }
    
    public synchronized void restart(long token) {
        _token = token;
        HandshakeState old = _handshakeState;
        if (old != null) {
            // TODO pass the old keys over to createNewState()
            old.destroy();
        }
        createNewState(_routerAddress);
        //_rcvHeaderEncryptKey2 will be set after the Session Request message is created
        _rcvHeaderEncryptKey2 = null;
    }

    private void processPayload(byte[] payload, int offset, int length, boolean isHandshake) throws GeneralSecurityException {
        try {
            int blocks = SSU2Payload.processPayload(_context, this, payload, offset, length, isHandshake);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks");
        } catch (Exception e) {
            throw new GeneralSecurityException("Session Created payload error", e);
        }
    }

    /////////////////////////////////////////////////////////
    // begin payload callbacks
    /////////////////////////////////////////////////////////

    public void gotDateTime(long time) {
        _timeReceived = time;
    }

    public void gotOptions(byte[] options, boolean isHandshake) {
        if (_log.shouldDebug())
            _log.debug("Got OPTIONS block");
    }

    public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
        throw new DataFormatException("RI in Sess Created");
    }

    public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags) {
        throw new IllegalStateException("RI in Sess Created");
    }

    public void gotAddress(byte[] ip, int port) {
        if (_log.shouldDebug())
            _log.debug("Got Address: " + Addresses.toString(ip, port));
        _aliceIP = ip;
        _alicePort = port;
    }

    public void gotIntroKey(byte[] key) {
        if (_log.shouldDebug())
            _log.debug("Got Intro key: " + Base64.encode(key));
    }

    public void gotRelayTagRequest() {
        throw new IllegalStateException("Relay tag req in Sess Created");
    }

    public void gotRelayTag(long tag) {
        if (_log.shouldDebug())
            _log.debug("Got relay tag " + tag);
    }

    public void gotToken(long token, long expires) {
        _transport.getEstablisher().addOutboundToken(_remotePeer.calculateHash(), token, expires);
    }

    public void gotI2NP(I2NPMessage msg) {
        throw new IllegalStateException("I2NP in Sess Created");
    }

    public void gotFragment(byte[] data, int off, int len, long messageId,int frag, boolean isLast) throws DataFormatException {
        throw new DataFormatException("I2NP in Sess Created");
    }

    public void gotACK(long ackThru, int acks, byte[] ranges) {
        throw new IllegalStateException("ACK in Sess Created");
    }

    public void gotTermination(int reason, long count) {
        if (_log.shouldWarn())
            _log.warn("Got TERMINATION block, reason: " + reason + " count: " + count);
        // this sets the state to FAILED
        fail();
        _transport.getEstablisher().receiveSessionDestroy(_remoteHostId, this);
    }

    public void gotUnknown(int type, int len) {
        if (_log.shouldDebug())
            _log.debug("Got UNKNOWN block, type: " + type + " len: " + len);
    }

    public void gotPadding(int paddingLength, int frameLength) {
    }

    /////////////////////////////////////////////////////////
    // end payload callbacks
    /////////////////////////////////////////////////////////
    
    // SSU 1 overrides

    @Override
    public synchronized boolean validateSessionCreated() {
        // All validation is in receiveSessionCreated()
        boolean rv = _currentState == OutboundState.OB_STATE_CREATED_RECEIVED ||
                     _currentState == OutboundState.OB_STATE_CONFIRMED_COMPLETELY;
        return rv;
    }

    // SSU 2 things

    @Override
    public int getVersion() { return 2; }
    public long getSendConnID() { return _sendConnID; }
    public long getRcvConnID() { return _rcvConnID; }
    public long getToken() { return _token; }
    public long getNextToken() {
        // generate on the fly, this will only be called once
        long token;
        do {
            token = _context.random().nextLong();
        } while (token == 0);
        _transport.getEstablisher().addInboundToken(_remoteHostId, token);
        return token;
    }
    public HandshakeState getHandshakeState() { return _handshakeState; }
    public byte[] getSendHeaderEncryptKey1() { return _sendHeaderEncryptKey1; }
    public byte[] getRcvHeaderEncryptKey1() { return _rcvHeaderEncryptKey1; }
    public byte[] getSendHeaderEncryptKey2() { return _sendHeaderEncryptKey2; }
    /**
     *  @return null before Session Request is sent (i.e. we sent a Token Request first)
     */
    public byte[] getRcvHeaderEncryptKey2() { return _rcvHeaderEncryptKey2; }
    public byte[] getRcvRetryHeaderEncryptKey2() { return _rcvRetryHeaderEncryptKey2; }
    public InetSocketAddress getSentAddress() { return _bobSocketAddress; }

    /** what is the largest packet we can send to the peer? */
    public int getMTU() { return _mtu; }

    public synchronized void receiveRetry(UDPPacket packet) throws GeneralSecurityException {
        ////// TODO state check
        DatagramPacket pkt = packet.getPacket();
        SocketAddress from = pkt.getSocketAddress();
        if (!from.equals(_bobSocketAddress))
            throw new GeneralSecurityException("Address mismatch: req: " + _bobSocketAddress + " conf: " + from);
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        long rid = DataHelper.fromLong8(data, off);
        if (rid != _rcvConnID)
            throw new GeneralSecurityException("Conn ID mismatch: 1: " + _rcvConnID + " 2: " + rid);
        long sid = DataHelper.fromLong8(data, off + SRC_CONN_ID_OFFSET);
        if (sid != _sendConnID)
            throw new GeneralSecurityException("Conn ID mismatch: 1: " + _sendConnID + " 2: " + sid);
        long token = DataHelper.fromLong8(data, off + TOKEN_OFFSET);
        if (token == 0)
            throw new GeneralSecurityException("Bad token 0 in retry");
        _token = token;
        _timeReceived = 0;
        try {
            // decrypt in-place
            ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
            chacha.initializeKey(_rcvHeaderEncryptKey1, 0);
            long n = DataHelper.fromLong(data, off + PKT_NUM_OFFSET, 4);
            chacha.setNonce(n);
            chacha.decryptWithAd(data, off, LONG_HEADER_SIZE,
                                 data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE);
            processPayload(data, off + LONG_HEADER_SIZE, len - (LONG_HEADER_SIZE + MAC_LEN), true);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("Retry error", gse);
            throw gse;
        }
        packetReceived();
        if (_currentState == OutboundState.OB_STATE_VALIDATION_FAILED) {
            // termination block received
            return;
        }
        if (_timeReceived == 0)
            throw new GeneralSecurityException("No DateTime block in Session/Token Request");
        // _nextSend is now(), from packetReceived()
        _skew = _nextSend - _timeReceived;
        if (_skew > MAX_SKEW || _skew < 0 - MAX_SKEW)
            throw new GeneralSecurityException("Skew exceeded in Session/Token Request: " + _skew);
        createNewState(_routerAddress);
        _currentState = OutboundState.OB_STATE_RETRY_RECEIVED;
    }

    public synchronized void receiveSessionCreated(UDPPacket packet) throws GeneralSecurityException {
        ////// todo fix state check
        if (_currentState == OutboundState.OB_STATE_VALIDATION_FAILED) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Session created already failed");
            return;
        }

        DatagramPacket pkt = packet.getPacket();
        SocketAddress from = pkt.getSocketAddress();
        if (!from.equals(_bobSocketAddress))
            throw new GeneralSecurityException("Address mismatch: req: " + _bobSocketAddress + " created: " + from);
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        long rid = DataHelper.fromLong8(data, off);
        if (rid != _rcvConnID)
            throw new GeneralSecurityException("Conn ID mismatch: 1: " + _rcvConnID + " 2: " + rid);
        long sid = DataHelper.fromLong8(data, off + SRC_CONN_ID_OFFSET);
        if (sid != _sendConnID)
            throw new GeneralSecurityException("Conn ID mismatch: 1: " + _sendConnID + " 2: " + sid);

        _handshakeState.mixHash(data, off, LONG_HEADER_SIZE);
        if (_log.shouldDebug())
            _log.debug("State after mixHash 2: " + _handshakeState);

        // decrypt in-place
        try {
            _handshakeState.readMessage(data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("Session create error, State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
            throw gse;
        }
        if (_log.shouldDebug())
            _log.debug("State after sess cr: " + _handshakeState);
        _timeReceived = 0;
        processPayload(data, off + LONG_HEADER_SIZE, len - (LONG_HEADER_SIZE + KEY_LEN + MAC_LEN), true);
        packetReceived();
        if (_currentState == OutboundState.OB_STATE_VALIDATION_FAILED) {
            // termination block received
            return;
        }
        if (_timeReceived == 0)
            throw new GeneralSecurityException("No DateTime block in Session/Token Request");
        // _nextSend is now(), from packetReceived()
        _skew = _nextSend - _timeReceived;
        if (_skew > MAX_SKEW || _skew < 0 - MAX_SKEW)
            throw new GeneralSecurityException("Skew exceeded in Session/Token Request: " + _skew);
        _sessReqForReTX = null;
        _sendHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessionConfirmed");

        _currentState = OutboundState.OB_STATE_CREATED_RECEIVED;

        if (_requestSentCount == 1) {
            _rtt = (int) (_nextSend - _requestSentTime);
        }
    }

    /**
     * note that we just sent the SessionConfirmed packets
     * and save them for retransmission
     */
    public synchronized void tokenRequestSent(DatagramPacket packet) {
        OutboundState old = _currentState;
        requestSent();
        if (old == OutboundState.OB_STATE_NEEDS_TOKEN)
            _currentState = OutboundState.OB_STATE_TOKEN_REQUEST_SENT;
        // don't bother saving for retx, just make a new one every time
    }

    /**
     * note that we just sent the SessionRequest packet
     * and save it for retransmission
     */
    public synchronized void requestSent(DatagramPacket pkt) {
        if (_sessReqForReTX == null) {
            // store pkt for retx
            byte data[] = pkt.getData();
            int off = pkt.getOffset();
            int len = pkt.getLength();
            _sessReqForReTX = new byte[len];
            System.arraycopy(data, off, _sessReqForReTX, 0, len);
        }
        if (_rcvHeaderEncryptKey2 == null)
            _rcvHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");
        OutboundState old = _currentState;
        requestSent();
        if (old == OutboundState.OB_STATE_RETRY_RECEIVED)
            _currentState = OutboundState.OB_STATE_REQUEST_SENT_NEW_TOKEN;
    }

    /**
     * note that we just sent the SessionConfirmed packets
     * and save them for retransmission
     *
     * @return the new PeerState2, may also be retrieved from getPeerState()
     */
    public synchronized PeerState2 confirmedPacketsSent(UDPPacket[] packets) {
        if (_sessConfForReTX == null) {
            // store pkt for retx
            // only one supported right now
            DatagramPacket pkt = packets[0].getPacket();
            byte data[] = pkt.getData();
            int off = pkt.getOffset();
            int len = pkt.getLength();
            _sessConfForReTX = new byte[len];
            System.arraycopy(data, off, _sessConfForReTX, 0, len);
            if (_rcvHeaderEncryptKey2 == null)
                _rcvHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");

            // split()
            // The CipherStates are from d_ab/d_ba,
            // not from k_ab/k_ba, so there's no use for
            // HandshakeState.split()
            byte[] ckd = _handshakeState.getChainingKey();
            byte[] k_ab = new byte[32];
            byte[] k_ba = new byte[32];
            HKDF hkdf = new HKDF(_context);
            hkdf.calculate(ckd, ZEROLEN, k_ab, k_ba, 0);
            // generate keys
            byte[] d_ab = new byte[32];
            byte[] h_ab = new byte[32];
            byte[] d_ba = new byte[32];
            byte[] h_ba = new byte[32];
            hkdf.calculate(k_ab, ZEROLEN, INFO_DATA, d_ab, h_ab, 0);
            hkdf.calculate(k_ba, ZEROLEN, INFO_DATA, d_ba, h_ba, 0);
            ChaChaPolyCipherState sender = new ChaChaPolyCipherState();
            sender.initializeKey(d_ab, 0);
            ChaChaPolyCipherState rcvr = new ChaChaPolyCipherState();
            rcvr.initializeKey(d_ba, 0);
            if (_log.shouldDebug())
                _log.debug("split()\nGenerated Chain key:              " + Base64.encode(ckd) +
                           "\nGenerated split key for A->B:     " + Base64.encode(k_ab) +
                           "\nGenerated split key for B->A:     " + Base64.encode(k_ba) +
                           "\nGenerated encrypt key for A->B:   " + Base64.encode(d_ab) +
                           "\nGenerated encrypt key for B->A:   " + Base64.encode(d_ba) +
                           "\nIntro key for Alice:              " + Base64.encode(_transport.getSSU2StaticIntroKey()) +
                           "\nIntro key for Bob:                " + Base64.encode(_sendHeaderEncryptKey1) +
                           "\nGenerated header key 2 for A->B:  " + Base64.encode(h_ab) +
                           "\nGenerated header key 2 for B->A:  " + Base64.encode(h_ba));
            _handshakeState.destroy();
            if (_requestSentCount == 1)
                _rtt = (int) ( _context.clock().now() - _lastSend );
            _pstate = new PeerState2(_context, _transport, _bobSocketAddress,
                                     _remotePeer.calculateHash(),
                                     false, _rtt, sender, rcvr,
                                     _sendConnID, _rcvConnID,
                                     _sendHeaderEncryptKey1, h_ab, h_ba);
            _currentState = OutboundState.OB_STATE_CONFIRMED_COMPLETELY;
            _pstate.confirmedPacketsSent(_sessConfForReTX);
            // PS2.super adds CLOCK_SKEW_FUDGE that doesn't apply here
            _pstate.adjustClockSkew(_skew - (_rtt / 2) - PeerState.CLOCK_SKEW_FUDGE);
            _pstate.setHisMTU(_mtu);
        }
        confirmedPacketsSent();
        return _pstate;
    }

    /**
     * @return null if not sent or already got the session created
     */
    public synchronized UDPPacket getRetransmitSessionRequestPacket() {
        if (_sessReqForReTX == null)
            return null;
        UDPPacket packet = UDPPacket.acquire(_context, false);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        System.arraycopy(_sessReqForReTX, 0, data, off, _sessReqForReTX.length);
        pkt.setLength(_sessReqForReTX.length);
        pkt.setSocketAddress(_bobSocketAddress);
        packet.setMessageType(PacketBuilder2.TYPE_SREQ);
        packet.setPriority(PacketBuilder2.PRIORITY_HIGH);
        requestSent();
        return packet;
    }

    /**
     * @return null if we have not sent the session confirmed
     */
    public synchronized PeerState2 getPeerState() {
        _currentState = OutboundState.OB_STATE_CONFIRMED_COMPLETELY;
        return _pstate;
    }

    @Override
    public String toString() {
        return "OES2 " + _remoteHostId + ' ' + _currentState;
    }
}
