package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.southernstorm.noise.protocol.ChaChaPolyCipherState;
import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.crypto.HKDF;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
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
    private final Map<Hash, IntroState> _introducers;
    private long _token;
    private HandshakeState _handshakeState;
    private final byte[] _sendHeaderEncryptKey1;
    private final byte[] _rcvHeaderEncryptKey1;
    private byte[] _sendHeaderEncryptKey2;
    private byte[] _rcvHeaderEncryptKey2;
    private final byte[] _rcvRetryHeaderEncryptKey2;
    private final int _mtu;
    private byte[] _sessReqForReTX;
    private byte[][] _sessConfForReTX;
    private long _timeReceived;
    // not adjusted for RTT
    private long _skew;
    private PeerState2 _pstate;

    private static final boolean SET_TOKEN = false;
    private static final long MAX_SKEW = 2*60*1000L;

    /**
     *  Per-introducer introduction states
     *  @since 0.9.55
     */
    public enum IntroState {
        // pending states
        // we may transition from these to another state
        // See EstablishmentManager.handlePendingIntro() for state machine

        /** nothing happened yet */
        INTRO_STATE_INIT,
        /** lookup for the introducer RI was sent */
        INTRO_STATE_LOOKUP_SENT,
        /** we have the introducer RI */
        INTRO_STATE_HAS_RI,
        /** we are connecting to the introducer */
        INTRO_STATE_CONNECTING,
        /** we are connected to this introducer */
        INTRO_STATE_CONNECTED,
        /** we sent the relay request to this introducer */
        INTRO_STATE_RELAY_REQUEST_SENT,
        /** we got a good relay response via this introducer */
        INTRO_STATE_RELAY_CHARLIE_ACCEPTED,

        // final states
        // we do not transition from these states

        /** introducer has expired */
        INTRO_STATE_EXPIRED,
        /** we tried to lookup the introducer RI, no luck */
        INTRO_STATE_LOOKUP_FAILED,
        /** we rejected this introducer for some reason */
        INTRO_STATE_REJECTED,
        /** we failed to connect to the introducer */
        INTRO_STATE_CONNECT_FAILED,
        /** he disconnected from us along the way */
        INTRO_STATE_DISCONNECTED,
        /** we failed to get a relay response from this introducer */
        INTRO_STATE_RELAY_RESPONSE_TIMEOUT,
        /** we got a rejection from this introducer */
        INTRO_STATE_BOB_REJECT,
        /** we got a rejection from Charlie via this introducer */
        INTRO_STATE_CHARLIE_REJECT,
        /** unspecified failure */
        INTRO_STATE_FAILED,
        /** this peer is not an introducer */
        INTRO_STATE_INVALID,
        /** we got an accept from Charlie via this introducer */
        INTRO_STATE_SUCCESS
    }


    /**
     *  Prepare to start a new handshake with the given peer.
     *
     *  Caller must then check getState() and build a
     *  Token Request or Session Request to send to the peer.
     *
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
                if (_bobIP != null && _bobIP.length == 16)
                    mtu = PeerState2.DEFAULT_SSU_IPV6_MTU;
                else
                    mtu = PeerState2.DEFAULT_SSU_IPV4_MTU;
            }
        } else {
            // If too small, give up now
            if (mtu < PeerState2.MIN_MTU)
                throw new IllegalArgumentException("MTU " + mtu + " too small for " + remotePeer.getHash());
            if (ra.getTransportStyle().equals(UDPTransport.STYLE2)) {
                mtu = Math.min(mtu, PeerState2.MAX_MTU);
            } else {
                if (_bobIP != null && _bobIP.length == 16)
                    mtu = Math.min(Math.max(mtu, PeerState2.MIN_SSU_IPV6_MTU), PeerState2.MAX_SSU_IPV6_MTU);
                else
                    mtu = Math.min(Math.max(mtu, PeerState2.MIN_SSU_IPV4_MTU), PeerState2.MAX_SSU_IPV4_MTU);
            }
        }
        _mtu = mtu;
        _routerAddress = ra;
        int intros = addr.getIntroducerCount();
        if (intros > 0) {
            _currentState = OutboundState.OB_STATE_PENDING_INTRO;
            // we will get a token in the relay response or hole punch
            _introducers = new HashMap<Hash, IntroState>(4);
            // Initial setup of per-introducer state tracking.
            // See EstablishmentManager.handlePendingIntro() for state machine
            for (int i = 0; i < intros; i++) {
                Hash h = addr.getIntroducerHash(i);
                if (h != null) {
                    IntroState istate;
                    long exp = addr.getIntroducerExpiration(i);
                    if (exp != 0 && exp < _establishBegin)
                        istate = IntroState.INTRO_STATE_EXPIRED;
                    else if (_context.banlist().isBanlisted(h))
                        istate = IntroState.INTRO_STATE_REJECTED;
                    else
                        istate = IntroState.INTRO_STATE_INIT;
                    _introducers.put(h, istate);
                }
            }
        } else {
            _token = _transport.getEstablisher().getOutboundToken(_remoteHostId);
            if (_token != 0) {
                _currentState = OutboundState.OB_STATE_UNKNOWN;
                createNewState(ra);
            } else {
                _currentState = OutboundState.OB_STATE_NEEDS_TOKEN;
            }
            _introducers = null;
        }

        _sendConnID = ctx.random().nextLong();
        // rcid == scid is not allowed
        long rcid;
        do {
            rcid = ctx.random().nextLong();
        } while (_sendConnID == rcid);
        _rcvConnID = rcid;

        byte[] ik = introKey.getData();
        _sendHeaderEncryptKey1 = ik;
        _rcvHeaderEncryptKey1 = ik;
        _sendHeaderEncryptKey2 = ik;
        //_rcvHeaderEncryptKey2 will be set after the Session Request message is created
        _rcvRetryHeaderEncryptKey2 = ik;
        if (_log.shouldDebug())
            _log.debug("New " + this);
    }

    /**
     *  After introduction
     *
     *  @since 0.9.55
     */
    public synchronized void introduced(byte[] ip, int port, long token) {
        if (_currentState != OutboundState.OB_STATE_PENDING_INTRO)
            return;
        introduced(ip, port);
        try {
            _bobSocketAddress = new InetSocketAddress(InetAddress.getByAddress(ip), port);
        } catch (UnknownHostException uhe) {
            throw new IllegalArgumentException("bad IP", uhe);
        }
        _token = token;
        createNewState(_routerAddress);
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
    
    private void processPayload(byte[] payload, int offset, int length, boolean isHandshake) throws GeneralSecurityException {
        try {
            int blocks = SSU2Payload.processPayload(_context, this, payload, offset, length, isHandshake, null);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks on " + this);
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

    public void gotRelayTagRequest() {
        throw new IllegalStateException("Relay tag req in Sess Created");
    }

    public void gotRelayTag(long tag) {
        if (!ENABLE_RELAY)
            return;
        if (_log.shouldDebug())
            _log.debug("Got relay tag " + tag);
        _receivedRelayTag = tag;
    }

    public void gotRelayRequest(byte[] data) {
        // won't be called, SSU2Payload will throw
    }

    public void gotRelayResponse(int status, byte[] data) {
        // won't be called, SSU2Payload will throw
    }

    public void gotRelayIntro(Hash aliceHash, byte[] data) {
        // won't be called, SSU2Payload will throw
    }

    public void gotPeerTest(int msg, int status, Hash h, byte[] data) {
        // won't be called, SSU2Payload will throw
    }

    public void gotToken(long token, long expires) {
        if (_log.shouldDebug())
            _log.debug("Got token: " + token + " expires " + DataHelper.formatTime(expires) + " on " + this);
        _transport.getEstablisher().addOutboundToken(_remoteHostId, token, expires);
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
        if (reason == REASON_BANNED) {
            _context.banlist().banlistRouter(_remotePeer.calculateHash(), "They banned us", null, null, _context.clock().now() + 2*60*60*1000);
        } else if (reason == REASON_MSG1) {
            // this is like a short ban
            _context.banlist().banlistRouter(_remotePeer.calculateHash(), "They banned us", null, null, _context.clock().now() + 20*60*1000);
        }
        // TODO handle other cases - skew?
    }

    public void gotPathChallenge(RemoteHostId from, byte[] data) {
        // won't be called, SSU2Payload will throw
    }

    public void gotPathResponse(RemoteHostId from, byte[] data) {
        // won't be called, SSU2Payload will throw
    }

    /////////////////////////////////////////////////////////
    // end payload callbacks
    /////////////////////////////////////////////////////////

    // SSU 1 overrides

    /**
     *  Overridden to destroy the handshake state
     *  @since 0.9.56
     */
    @Override
    public synchronized void fail() {
        if (_handshakeState != null)
            _handshakeState.destroy();
        super.fail();
    }

    @Override
    public synchronized boolean validateSessionCreated() {
        // All validation is in receiveSessionCreated()
        boolean rv = _currentState == OutboundState.OB_STATE_CREATED_RECEIVED ||
                     _currentState == OutboundState.OB_STATE_CONFIRMED_COMPLETELY;
        return rv;
    }

    /**
     *  Overridden because we don't have to wait for Relay Response first.
     *
     *  @return true if we should send the SessionRequest now
     *  @since 0.9.55
     */
    @Override
    synchronized boolean receiveHolePunch() {
        if (_currentState == OutboundState.OB_STATE_PENDING_INTRO)
            _currentState = OutboundState.OB_STATE_INTRODUCED;
        else if (_currentState != OutboundState.OB_STATE_INTRODUCED)
            return false;
        if (_requestSentCount > 0)
            return false;
        long now = _context.clock().now();
        _nextSend = now;
        return true;
    }

    // SSU 2 things

    @Override
    public int getVersion() { return 2; }
    public long getSendConnID() { return _sendConnID; }
    public long getRcvConnID() { return _rcvConnID; }
    public long getToken() { return _token; }
    /**
     *  @return may be null
     */
    public EstablishmentManager.Token getNextToken() {
        if (_bobIP != null && _bobIP.length == 4 && _transport.isSnatted())
            return null;
        return _transport.getEstablisher().getInboundToken(_remoteHostId);
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

    /**
     *  What is the largest packet we can send to the peer?
     *  Only used for Session Confirmed packets.
     *  Session Request is very small.
     */
    public int getMTU() {
        // To avoid PMTU problems on brokered IPv6 tunnels, make it the minimum.
        // Data phase will probe and increase if possible
        if (_bobIP == null || _bobIP.length == 16)
            return PeerState2.MIN_MTU;
        return _mtu;
    }

    public synchronized void receiveRetry(UDPPacket packet) throws GeneralSecurityException {
        try {
            locked_receiveRetry(packet);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("Retry error", gse);
            // fail inside synch rather than have Est. Mgr. do it to prevent races
            fail();
            throw gse;
        }
    }

    /**
     *  @since 0.9.56
     */
    private void locked_receiveRetry(UDPPacket packet) throws GeneralSecurityException {
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
        // continue and decrypt even if token == 0 to get and log termination reason
        if (token != 0)
            _token = token;
        _timeReceived = 0;
        ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
        chacha.initializeKey(_rcvHeaderEncryptKey1, 0);
        long n = DataHelper.fromLong(data, off + PKT_NUM_OFFSET, 4);
        chacha.setNonce(n);
        try {
            // decrypt in-place
            chacha.decryptWithAd(data, off, LONG_HEADER_SIZE,
                                 data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE);
            processPayload(data, off + LONG_HEADER_SIZE, len - (LONG_HEADER_SIZE + MAC_LEN), true);
        } finally {
            chacha.destroy();
        }
        packetReceived();
        if (_currentState == OutboundState.OB_STATE_VALIDATION_FAILED) {
            // termination block received
            return;
        }
        // generally will be with termination, so do this check after
        if (token == 0)
            throw new GeneralSecurityException("Bad token 0 in retry");
        if (_timeReceived == 0)
            throw new GeneralSecurityException("No DateTime block in Retry");
        // _nextSend is now(), from packetReceived()
        _skew = _nextSend - _timeReceived;
        if (_skew > MAX_SKEW || _skew < 0 - MAX_SKEW)
            throw new GeneralSecurityException("Skew exceeded in Retry: " + _skew);
        createNewState(_routerAddress);
        if (_log.shouldDebug())
            _log.debug("Received a retry on " + this);
        _currentState = OutboundState.OB_STATE_RETRY_RECEIVED;
    }

    public synchronized void receiveSessionCreated(UDPPacket packet) throws GeneralSecurityException {
        try {
            locked_receiveSessionCreated(packet);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug()) {
                DatagramPacket pkt = packet.getPacket();
                byte data[] = pkt.getData();
                int off = pkt.getOffset();
                int len = pkt.getLength();
                _log.debug("Session create error, State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
            }
            // fail inside synch rather than have Est. Mgr. do it to prevent races
            fail();
            throw gse;
        }
    }

    /**
     *  @since 0.9.56
     */
    private void locked_receiveSessionCreated(UDPPacket packet) throws GeneralSecurityException {
        if (_currentState != OutboundState.OB_STATE_REQUEST_SENT &&
            _currentState != OutboundState.OB_STATE_REQUEST_SENT_NEW_TOKEN) {
            // ignore dups
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid state for session created: " + this);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("Received a session created on " + this);

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
        //if (_log.shouldDebug())
        //    _log.debug("State after mixHash 2: " + _handshakeState);

        // decrypt in-place
        _handshakeState.readMessage(data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE);
        //if (_log.shouldDebug())
        //    _log.debug("State after sess cr: " + _handshakeState);
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
     * Note that we just sent a token request packet.
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
        OutboundState old = _currentState;
        requestSent();
        if (_sessReqForReTX == null) {
            // store pkt for retx
            byte data[] = pkt.getData();
            int off = pkt.getOffset();
            int len = pkt.getLength();
            _sessReqForReTX = new byte[len];
            System.arraycopy(data, off, _sessReqForReTX, 0, len);
            if (_requestSentCount > 1) {
                // fixup the counter and delay because we also called
                // requestSent() when sending the token request
                _requestSentCount = 1;
                _nextSend = _lastSend + RETRANSMIT_DELAY;
            }
        }
        if (_rcvHeaderEncryptKey2 == null)
            _rcvHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");
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
            // store pkts for retx
            _sessConfForReTX = new byte[packets.length][];
            for (int i = 0; i < packets.length; i++) {
                DatagramPacket pkt = packets[i].getPacket();
                byte data[] = pkt.getData();
                int off = pkt.getOffset();
                int len = pkt.getLength();
                byte[] save = new byte[len];
                System.arraycopy(data, off, save, 0, len);
                _sessConfForReTX[i] = save;
                if (_log.shouldDebug())
                    _log.debug("Sess conf pkt " + i + '/' + packets.length + " bytes: " + len);
            }
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
          /****
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
            ****/
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
            _pstate.setOurAddress(_aliceIP, _alicePort);
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

    /**
     * @return non-null current state for the SSU2 introducer specified,
     *         or INTRO_STATE_INVALID if peer is not an SSU2 introducer
     * @since 0.9.55
     */
    public IntroState getIntroState(Hash h) {
        IntroState rv;
        if (_introducers == null) {
            rv = IntroState.INTRO_STATE_INVALID;
        } else {
            synchronized(_introducers) {
                rv = _introducers.get(h);
            }
            if (rv == null)
                rv = IntroState.INTRO_STATE_INVALID;
        }
        return rv;
    }

    /**
     * Set the current state for the SSU2 introducer specified
     * @since 0.9.55
     */
    public void setIntroState(Hash h, IntroState state) {
        if (_introducers == null)
            return;
        IntroState old;
        synchronized(_introducers) {
            old = _introducers.put(h, state);
        }
        if (old != state && _log.shouldDebug())
            _log.debug("Change state for introducer " + h.toBase64() + " from " + old + " to " + state + " on " + this);
    }

    /**
     * A relay request was sent to the SSU2 introducer specified
     * @since 0.9.55
     */
    public void introSent(Hash h) {
        setIntroState(h, IntroState.INTRO_STATE_RELAY_REQUEST_SENT);
        introSent();
    }

    @Override
    public String toString() {
        return "OES2 " + _remotePeer.getHash().toBase64().substring(0, 6) + ' ' + _remoteHostId +
               " lifetime: " + DataHelper.formatDuration(getLifetime()) +
               " Rcv ID: " + _rcvConnID +
               " Send ID: " + _sendConnID +
               ' ' + _currentState +
               (_introducers != null ? (" Introducers: " + _introducers.toString()) : "");
    }
}
