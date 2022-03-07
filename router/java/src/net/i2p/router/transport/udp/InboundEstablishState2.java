package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.List;

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
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import static net.i2p.router.transport.udp.SSU2Util.*;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

/**
 * Data for a new connection being established, where the remote peer has
 * initiated the connection with us.  In other words, they are Alice and
 * we are Bob.
 *
 * SSU2 only.
 *
 * @since 0.9.54
 */
class InboundEstablishState2 extends InboundEstablishState implements SSU2Payload.PayloadCallback {
    private final UDPTransport _transport;
    private final InetSocketAddress _aliceSocketAddress;
    private final long _rcvConnID;
    private final long _sendConnID;
    private final long _token;
    private final HandshakeState _handshakeState;
    private byte[] _sendHeaderEncryptKey1;
    private final byte[] _rcvHeaderEncryptKey1;
    private byte[] _sendHeaderEncryptKey2;
    private byte[] _rcvHeaderEncryptKey2;
    private byte[] _sessCrForReTX;
    private long _timeReceived;
    private PeerState2 _pstate;
    
    // testing
    private static final boolean ENFORCE_TOKEN = true;
    private static final long MAX_SKEW = 2*60*1000L;


    /**
     *  @param packet with all header encryption removed,
     *                either a SessionRequest OR a TokenRequest.
     */
    public InboundEstablishState2(RouterContext ctx, UDPTransport transport,
                                  UDPPacket packet) throws GeneralSecurityException {
        super(ctx, (InetSocketAddress) packet.getPacket().getSocketAddress());
        _transport = transport;
        DatagramPacket pkt = packet.getPacket();
        _aliceSocketAddress = (InetSocketAddress) pkt.getSocketAddress();
        _handshakeState = new HandshakeState(HandshakeState.PATTERN_ID_XK_SSU2, HandshakeState.RESPONDER, transport.getXDHFactory());
        _handshakeState.getLocalKeyPair().setKeys(transport.getSSU2StaticPrivKey(), 0,
                                                  transport.getSSU2StaticPubKey(), 0);
        byte[] introKey = transport.getSSU2StaticIntroKey();
        _sendHeaderEncryptKey1 = introKey;
        _rcvHeaderEncryptKey1 = introKey;
        //_sendHeaderEncryptKey2 set below
        //_rcvHeaderEncryptKey2 set below
        _introductionRequested = false; // todo
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        _rcvConnID = DataHelper.fromLong8(data, off);
        _sendConnID = DataHelper.fromLong8(data, off + SRC_CONN_ID_OFFSET);
        if (_rcvConnID == _sendConnID)
            throw new GeneralSecurityException("Identical Conn IDs");
        int type = data[off + TYPE_OFFSET] & 0xff;
        long token = DataHelper.fromLong8(data, off + TOKEN_OFFSET);
        if (type == TOKEN_REQUEST_FLAG_BYTE) {
            _currentState = InboundState.IB_STATE_TOKEN_REQUEST_RECEIVED;
            // decrypt in-place
            ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
            chacha.initializeKey(_rcvHeaderEncryptKey1, 0);
            long n = DataHelper.fromLong(data, off + PKT_NUM_OFFSET, 4);
            chacha.setNonce(n);
            chacha.decryptWithAd(data, off, LONG_HEADER_SIZE,
                                 data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE);
            processPayload(data, off + LONG_HEADER_SIZE, len - (LONG_HEADER_SIZE + MAC_LEN), true);
            _sendHeaderEncryptKey2 = introKey;
            do {
                token = ctx.random().nextLong();
            } while (token == 0);
            _token = token;
        } else if (type == SESSION_REQUEST_FLAG_BYTE &&
                   (token == 0 ||
                    (ENFORCE_TOKEN && !_transport.getEstablisher().isInboundTokenValid(_remoteHostId, token)))) {
            if (_log.shouldInfo())
                _log.info("Invalid token " + token + " in session request from: " + _aliceSocketAddress);
            _currentState = InboundState.IB_STATE_REQUEST_BAD_TOKEN_RECEIVED;
            _sendHeaderEncryptKey2 = introKey;
            // Generate token for the retry.
            // We do NOT register it with the EstablishmentManager, it must be used immediately.
            do {
                token = ctx.random().nextLong();
            } while (token == 0);
            _token = token;
            // do NOT bother to init the handshake state and decrypt the payload
            _timeReceived = _establishBegin;
        } else {
            // fast MSB check for key < 2^255
            if ((data[off + LONG_HEADER_SIZE + KEY_LEN - 1] & 0x80) != 0)
                throw new GeneralSecurityException("Bad PK msg 1");
            // probably don't need again
            _token = token;
            _handshakeState.start();
            if (_log.shouldDebug())
                _log.debug("State after start: " + _handshakeState);
            _handshakeState.mixHash(data, off, LONG_HEADER_SIZE);
            if (_log.shouldDebug())
                _log.debug("State after mixHash 1: " + _handshakeState);

            byte[] payload = new byte[len - 80]; // 32 hdr, 32 eph. key, 16 MAC
            // decrypt in-place
            try {
                _handshakeState.readMessage(data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldDebug())
                    _log.debug("Session request error, State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
                throw gse;
            }
            if (_log.shouldDebug())
                _log.debug("State after sess req: " + _handshakeState);
            processPayload(data, off + LONG_HEADER_SIZE, len - (LONG_HEADER_SIZE + KEY_LEN + MAC_LEN), true);
            _sendHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");
            _currentState = InboundState.IB_STATE_REQUEST_RECEIVED;
        }
        if (_currentState == InboundState.IB_STATE_FAILED) {
            // termination block received
            throw new GeneralSecurityException("Termination block in Session/Token Request");
        }
        if (_timeReceived == 0)
            throw new GeneralSecurityException("No DateTime block in Session/Token Request");
        long skew = _establishBegin - _timeReceived;
        if (skew > MAX_SKEW || skew < 0 - MAX_SKEW)
            throw new GeneralSecurityException("Skew exceeded in Session/Token Request: " + skew);
        packetReceived();
    }

    @Override
    public int getVersion() { return 2; }
    
    private void processPayload(byte[] payload, int offset, int length, boolean isHandshake) throws GeneralSecurityException {
        try {
            int blocks = SSU2Payload.processPayload(_context, this, payload, offset, length, isHandshake);
            if (_log.shouldDebug())
                _log.debug("Processed " + blocks + " blocks");
        } catch (Exception e) {
            _log.error("IES2 payload error\n" + net.i2p.util.HexDump.dump(payload, 0, length), e);
            throw new GeneralSecurityException("IES2 payload error", e);
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
        if (_log.shouldDebug())
            _log.debug("Got RI block: " + ri);
        if (isHandshake)
            throw new DataFormatException("RI in Sess Req");
        if (_receivedUnconfirmedIdentity != null)
            throw new DataFormatException("DUP RI in Sess Conf");
        _receivedUnconfirmedIdentity = ri.getIdentity();
        if (ri.getNetworkId() != _context.router().getNetworkID()) {
            // TODO ban
            throw new DataFormatException("SSU2 network ID mismatch");
        }
        List<RouterAddress> addrs = _transport.getTargetAddresses(ri);
        RouterAddress ra = null;
        for (RouterAddress addr : addrs) {
            // skip NTCP w/o "s"
            if (addrs.size() > 1 && addr.getTransportStyle().equals("SSU") && addr.getOption("s") == null)
                continue;
            ra = addr;
            break;
        }
        if (ra == null)
            throw new DataFormatException("no SSU2 addr");
        String siv = ra.getOption("i");
        if (siv == null)
            throw new DataFormatException("no SSU2 IKey");
        byte[] ik = Base64.decode(siv);
        if (ik == null)
            throw new DataFormatException("bad SSU2 IKey");
        if (ik.length != 32)
            throw new DataFormatException("bad SSU2 IKey len");
        String ss = ra.getOption("s");
        if (ss == null)
            throw new DataFormatException("no SSU2 S");
        byte[] s = Base64.decode(ss);
        if (s == null)
            throw new DataFormatException("bad SSU2 S");
        if (s.length != 32)
            throw new DataFormatException("bad SSU2 S len");
        if (!"2".equals(ra.getOption("v")))
            throw new DataFormatException("bad SSU2 v");

        Hash h = _receivedUnconfirmedIdentity.calculateHash();
        try {
            RouterInfo old = _context.netDb().store(h, ri);
            if (flood && !ri.equals(old)) {
                FloodfillNetworkDatabaseFacade fndf = (FloodfillNetworkDatabaseFacade) _context.netDb();
                if (fndf.floodConditional(ri)) {
                    if (_log.shouldDebug())
                        _log.debug("Flooded the RI: " + h);
                } else {
                    if (_log.shouldInfo())
                        _log.info("Flood request but we didn't: " + h);
                }
            }
        } catch (IllegalArgumentException iae) {
            // generally expired/future RI
            // don't change reason if already set as clock skew
            throw new DataFormatException("RI store fail: " + ri, iae);
        }

        _receivedConfirmedIdentity = _receivedUnconfirmedIdentity;
        _sendHeaderEncryptKey1 = ik;
        createPeerState();
        //_sendHeaderEncryptKey2 calculated below
    }

    public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags) {
        if (_log.shouldDebug())
            _log.debug("Got RI fragment " + frag + " of " + totalFrags);
        if (isHandshake)
            throw new IllegalStateException("RI in Sess Req");
    }

    public void gotAddress(byte[] ip, int port) {
        if (_log.shouldDebug())
            _log.debug("Got Address: " + Addresses.toString(ip, port));
        _bobIP = ip;
        // final, see super
        //_bobPort = port;
    }

    public void gotIntroKey(byte[] key) {
        if (_log.shouldDebug())
            _log.debug("Got Intro key: " + Base64.encode(key));
    }

    public void gotRelayTagRequest() {
        if (_log.shouldDebug())
            _log.debug("Got relay tag request");
    }

    public void gotRelayTag(long tag) {
        throw new IllegalStateException("Relay tag in Handshake");
    }

    public void gotToken(long token, long expires) {
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RI must be first");
        _transport.getEstablisher().addOutboundToken(_receivedConfirmedIdentity.calculateHash(), token, expires);
    }

    public void gotI2NP(I2NPMessage msg) {
        if (_log.shouldDebug())
            _log.debug("Got I2NP block: " + msg);
        if (getState() != InboundState.IB_STATE_CREATED_SENT)
            throw new IllegalStateException("I2NP in Sess Req");
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RI must be first");
        // pass to PeerState2
        _pstate.gotI2NP(msg);
    }

    public void gotFragment(byte[] data, int off, int len, long messageID, int frag, boolean isLast) throws DataFormatException {
        if (_log.shouldDebug())
            _log.debug("Got FRAGMENT block: " + messageID);
        if (getState() != InboundState.IB_STATE_CREATED_SENT)
            throw new IllegalStateException("I2NP in Sess Req");
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RI must be first");
        // pass to PeerState2
        _pstate.gotFragment(data, off, len, messageID, frag, isLast);
    }

    public void gotACK(long ackThru, int acks, byte[] ranges) {
        throw new IllegalStateException("ACK in Handshake");
    }

    public void gotTermination(int reason, long count) {
        if (_log.shouldWarn())
            _log.warn("Got TERMINATION block, reason: " + reason + " count: " + count);
        // this sets the state to FAILED
        fail();
        _transport.getEstablisher().receiveSessionDestroy(_remoteHostId);
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
    
    // SSU 1 unsupported things

    @Override
    public void generateSessionKey() { throw new UnsupportedOperationException(); }

    // SSU 2 things

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
    public byte[] getRcvHeaderEncryptKey2() { return _rcvHeaderEncryptKey2; }
    public InetSocketAddress getSentAddress() { return _aliceSocketAddress; }

    @Override
    public synchronized void createdPacketSent() {
        /// todo state check
        if (_rcvHeaderEncryptKey2 == null)
            _rcvHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessionConfirmed");
        _lastSend = _context.clock().now();
        long delay;
        if (_createdSentCount == 0) {
            delay = RETRANSMIT_DELAY;
        } else {
            delay = Math.min(RETRANSMIT_DELAY << _createdSentCount, MAX_DELAY);
        }
        _createdSentCount++;
        _nextSend = _lastSend + delay;
        _currentState = InboundState.IB_STATE_CREATED_SENT;
    }

    
    /** note that we just sent a Retry packet */
    public synchronized void retryPacketSent() {
        if (_currentState != InboundState.IB_STATE_REQUEST_BAD_TOKEN_RECEIVED &&
            _currentState != InboundState.IB_STATE_TOKEN_REQUEST_RECEIVED)
            throw new IllegalStateException("Bad state for Retry Sent: " + _currentState);
        _currentState = InboundState.IB_STATE_RETRY_SENT;
        _lastSend = _context.clock().now();
        // Won't really be transmitted, they have 3 sec to respond or
        // EstablishmentManager.handleInbound() will fail the connection
        _nextSend = _lastSend + RETRANSMIT_DELAY;
    }

    /**
     *
     */
    public synchronized void receiveSessionRequestAfterRetry(UDPPacket packet) throws GeneralSecurityException {
        if (_currentState != InboundState.IB_STATE_RETRY_SENT)
            throw new GeneralSecurityException("Bad state for Session Request after Retry: " + _currentState);
        DatagramPacket pkt = packet.getPacket();
        SocketAddress from = pkt.getSocketAddress();
        if (!from.equals(_aliceSocketAddress))
            throw new GeneralSecurityException("Address mismatch: req: " + _aliceSocketAddress + " conf: " + from);
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        long rid = DataHelper.fromLong8(data, off);
        if (rid != _rcvConnID)
            throw new GeneralSecurityException("Conn ID mismatch: 1: " + _rcvConnID + " 2: " + rid);
        long sid = DataHelper.fromLong8(data, off + 16);
        if (sid != _sendConnID)
            throw new GeneralSecurityException("Conn ID mismatch: 1: " + _sendConnID + " 2: " + sid);
        long token = DataHelper.fromLong8(data, off + 24);
        if (token != _token)
            throw new GeneralSecurityException("Token mismatch: 1: " + _token + " 2: " + token);
        _handshakeState.start();
        _handshakeState.mixHash(data, off, 32);
        if (_log.shouldDebug())
            _log.debug("State after mixHash 1: " + _handshakeState);

        // decrypt in-place
        try {
            _handshakeState.readMessage(data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("Session Request error, State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
            throw gse;
        }
        if (_log.shouldDebug())
            _log.debug("State after sess req: " + _handshakeState);
        _timeReceived = 0;
        processPayload(data, off + LONG_HEADER_SIZE, len - (LONG_HEADER_SIZE + KEY_LEN + MAC_LEN), true);
        packetReceived();
        if (_currentState == InboundState.IB_STATE_FAILED) {
            // termination block received
            throw new GeneralSecurityException("Termination block in Session Request");
        }
        if (_timeReceived == 0)
            throw new GeneralSecurityException("No DateTime block in Session Request");
        long skew = _establishBegin - _timeReceived;
        if (skew > MAX_SKEW || skew < 0 - MAX_SKEW)
            throw new GeneralSecurityException("Skew exceeded in Session Request: " + skew);
        _sendHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");
        _currentState = InboundState.IB_STATE_REQUEST_RECEIVED;
        _rtt = (int) ( _context.clock().now() - _lastSend );
    }

    /**
     * Receive the last message in the handshake, and create the PeerState.
     *
     * @return the new PeerState2, may also be retrieved from getPeerState()
     */
    public synchronized PeerState2 receiveSessionConfirmed(UDPPacket packet) throws GeneralSecurityException {
        if (_currentState != InboundState.IB_STATE_CREATED_SENT)
            throw new GeneralSecurityException("Bad state for Session Confirmed: " + _currentState);
        DatagramPacket pkt = packet.getPacket();
        SocketAddress from = pkt.getSocketAddress();
        if (!from.equals(_aliceSocketAddress))
            throw new GeneralSecurityException("Address mismatch: req: " + _aliceSocketAddress + " conf: " + from);
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        long rid = DataHelper.fromLong8(data, off);
        if (rid != _rcvConnID)
            throw new GeneralSecurityException("Conn ID mismatch: req: " + _rcvConnID + " conf: " + rid);
        _handshakeState.mixHash(data, off, 16);
        if (_log.shouldDebug())
            _log.debug("State after mixHash 3: " + _handshakeState);

        // decrypt in-place
        try {
            _handshakeState.readMessage(data, off + SHORT_HEADER_SIZE, len - SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("Session Confirmed error, State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
            throw gse;
        }
        if (_log.shouldDebug())
            _log.debug("State after sess conf: " + _handshakeState);
        processPayload(data, off + SHORT_HEADER_SIZE, len - (SHORT_HEADER_SIZE + KEY_LEN + MAC_LEN + MAC_LEN), false);
        packetReceived();
        if (_currentState == InboundState.IB_STATE_FAILED) {
            // termination block received
            throw new GeneralSecurityException("Termination block in Session Confirmed");
        }
        _sessCrForReTX = null;

        if (_receivedConfirmedIdentity == null)
            throw new GeneralSecurityException("No RI in Session Confirmed");

        // createPeerState() called from gotRI()

        _currentState = InboundState.IB_STATE_CONFIRMED_COMPLETELY;
        return _pstate;
    }

    /**
     *  Creates the PeerState and stores in _pstate.
     *  Called from gotRI() so that we can pass any I2NP messages
     *  or fragments immediately to the PeerState.
     */
    private void createPeerState() {
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
        sender.initializeKey(d_ba, 0);
        ChaChaPolyCipherState rcvr = new ChaChaPolyCipherState();
        rcvr.initializeKey(d_ab, 0);
        if (_log.shouldDebug())
            _log.debug("split()\nGenerated Chain key:              " + Base64.encode(ckd) +
                       "\nGenerated split key for A->B:     " + Base64.encode(k_ab) +
                       "\nGenerated split key for B->A:     " + Base64.encode(k_ba) +
                       "\nGenerated encrypt key for A->B:   " + Base64.encode(d_ab) +
                       "\nGenerated encrypt key for B->A:   " + Base64.encode(d_ba) +
                       "\nIntro key for Alice:              " + Base64.encode(_sendHeaderEncryptKey1) +
                       "\nIntro key for Bob:                " + Base64.encode(_rcvHeaderEncryptKey1) +
                       "\nGenerated header key 2 for A->B:  " + Base64.encode(h_ab) +
                       "\nGenerated header key 2 for B->A:  " + Base64.encode(h_ba));
        _handshakeState.destroy();
        if (_createdSentCount == 1)
            _rtt = (int) ( _context.clock().now() - _lastSend );
        _pstate = new PeerState2(_context, _transport, _aliceSocketAddress,
                                 _receivedConfirmedIdentity.calculateHash(),
                                 true, _rtt, sender, rcvr,
                                 _sendConnID, _rcvConnID,
                                 _sendHeaderEncryptKey1, h_ba, h_ab);
    }

    /**
     * note that we just sent the SessionCreated packet
     * and save it for retransmission
     */
    public synchronized void createdPacketSent(DatagramPacket pkt) {
        if (_sessCrForReTX == null) {
            // store pkt for retx
            byte data[] = pkt.getData();
            int off = pkt.getOffset();
            int len = pkt.getLength();
            _sessCrForReTX = new byte[len];
            System.arraycopy(data, off, _sessCrForReTX, 0, len);
        }
        createdPacketSent();
    }

    /**
     * @return null if not sent or already got the session created
     */
    public synchronized UDPPacket getRetransmitSessionCreatedPacket() {
        if (_sessCrForReTX == null)
            return null;
        UDPPacket packet = UDPPacket.acquire(_context, false);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        System.arraycopy(_sessCrForReTX, 0, data, off, _sessCrForReTX.length);
        pkt.setSocketAddress(_aliceSocketAddress);
        packet.setMessageType(PacketBuilder2.TYPE_CONF);
        packet.setPriority(PacketBuilder2.PRIORITY_HIGH);
        createdPacketSent();
        return packet;
    }

    /**
     * @return null if we have not received the session confirmed
     */
    public synchronized PeerState2 getPeerState() {
        _currentState = InboundState.IB_STATE_COMPLETE;
        return _pstate;
    }
    
    @Override
    public String toString() {            
        StringBuilder buf = new StringBuilder(128);
        buf.append("IES2 ");
        buf.append(Addresses.toString(_aliceIP, _alicePort));
        buf.append(" RelayTag: ").append(_sentRelayTag);
        buf.append(' ').append(_currentState);
        return buf.toString();
    }
}
