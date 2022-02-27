package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.util.List;

import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
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
    
    // testing
    private static final boolean ENFORCE_TOKEN = false;


    /**
     *  @param localPort Must be our external port, otherwise the signature of the
     *                   SessionCreated message will be bad if the external port != the internal port.
     *  @param packet with all header encryption removed
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
        //_bobIP = TODO
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Receive sessionRequest, BobIP = " + Addresses.toString(_bobIP));
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        _rcvConnID = DataHelper.fromLong8(data, off);
        _sendConnID = DataHelper.fromLong8(data, off + 16);
        if (_rcvConnID == _sendConnID)
            throw new GeneralSecurityException("Identical Conn IDs");
        int type = data[off + 12] & 0xff;
        long token = DataHelper.fromLong8(data, off + 24);
        if (type == 10) {
            _currentState = InboundState.IB_STATE_TOKEN_REQUEST_RECEIVED;
            // TODO decrypt chacha?
            _sendHeaderEncryptKey2 = introKey;
            do {
                token = ctx.random().nextLong();
            } while (token == 0);
            _token = token;
        } else if (type == 0 &&
                   (token == 0 ||
                    (ENFORCE_TOKEN && !_transport.getEstablisher().isInboundTokenValid(_remoteHostId, token)))) {
            _currentState = InboundState.IB_STATE_REQUEST_BAD_TOKEN_RECEIVED;
            _sendHeaderEncryptKey2 = introKey;
            do {
                token = ctx.random().nextLong();
            } while (token == 0);
            _token = token;
        } else {
            // fast MSB check for key < 2^255
            if ((data[off + 32 + 32 - 1] & 0x80) != 0)
                throw new GeneralSecurityException("Bad PK msg 1");
            // probably don't need again
            _token = token;
            _handshakeState.start();
            if (_log.shouldDebug())
                _log.debug("State after start: " + _handshakeState);
            _handshakeState.mixHash(data, off, 32);
            if (_log.shouldDebug())
                _log.debug("State after mixHash 1: " + _handshakeState);

            byte[] payload = new byte[len - 80]; // 32 hdr, 32 eph. key, 16 MAC
            try {
                _handshakeState.readMessage(data, off + 32, len - 32, payload, 0);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldDebug())
                    _log.debug("Session request error, State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
                throw gse;
            }
            if (_log.shouldDebug())
                _log.debug("State after sess req: " + _handshakeState);
            processPayload(payload, payload.length, true);
            _sendHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");
            _currentState = InboundState.IB_STATE_REQUEST_RECEIVED;
        }
        packetReceived();
    }

    @Override
    public int getVersion() { return 2; }
    
    private void processPayload(byte[] payload, int length, boolean isHandshake) throws GeneralSecurityException {
        try {
            int blocks = SSU2Payload.processPayload(_context, this, payload, 0, length, isHandshake);
            System.out.println("Processed " + blocks + " blocks");
        } catch (Exception e) {
            _log.error("IES2 payload error\n" + net.i2p.util.HexDump.dump(payload, 0, length));
            throw new GeneralSecurityException("IES2 payload error", e);
        }
    }

    /////////////////////////////////////////////////////////
    // begin payload callbacks
    /////////////////////////////////////////////////////////

    public void gotDateTime(long time) {
        System.out.println("Got DATE block: " + DataHelper.formatTime(time));
    }

    public void gotOptions(byte[] options, boolean isHandshake) {
        System.out.println("Got OPTIONS block");
    }

    public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
        System.out.println("Got RI block: " + ri);
        if (isHandshake)
            throw new DataFormatException("RI in Sess Req");
        _receivedUnconfirmedIdentity = ri.getIdentity();
        List<RouterAddress> addrs = ri.getTargetAddresses("SSU", "SSU2");
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

        _receivedConfirmedIdentity = _receivedUnconfirmedIdentity;
        _sendHeaderEncryptKey1 = ik;
        //_sendHeaderEncryptKey2 calculated below

    }

    public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags) {
            System.out.println("Got RI fragment " + frag + " of " + totalFrags);
        if (isHandshake)
            throw new IllegalStateException("RI in Sess Req");
    }

    public void gotAddress(byte[] ip, int port) {
        throw new IllegalStateException("Address in Handshake");
    }

    public void gotIntroKey(byte[] key) {
        System.out.println("Got Intro key: " + Base64.encode(key));
    }

    public void gotRelayTagRequest() {
        System.out.println("Got relay tag request");
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
        System.out.println("Got I2NP block: " + msg);
        if (getState() != InboundState.IB_STATE_CREATED_SENT)
            throw new IllegalStateException("I2NP in Sess Req");
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RI must be first");
    }

    public void gotFragment(byte[] data, int off, int len, long messageID, int frag, boolean isLast) throws DataFormatException {
        System.out.println("Got FRAGMENT block: " + messageID);
        if (getState() != InboundState.IB_STATE_CREATED_SENT)
            throw new IllegalStateException("I2NP in Sess Req");
        if (_receivedConfirmedIdentity == null)
            throw new IllegalStateException("RI must be first");
    }

    public void gotACK(long ackThru, int acks, byte[] ranges) {
        throw new IllegalStateException("ACK in Handshake");
    }

    public void gotTermination(int reason, long count) {
        throw new IllegalStateException("Termination in Handshake");
    }

    public void gotUnknown(int type, int len) {
        System.out.println("Got UNKNOWN block, type: " + type + " len: " + len);
    }

    public void gotPadding(int paddingLength, int frameLength) {
    }

    /////////////////////////////////////////////////////////
    // end payload callbacks
    /////////////////////////////////////////////////////////
    
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
        if ( (_currentState == InboundState.IB_STATE_UNKNOWN) || (_currentState == InboundState.IB_STATE_REQUEST_RECEIVED) )
            _currentState = InboundState.IB_STATE_CREATED_SENT;
    }

    
    /** note that we just sent a Retry packet */
    public synchronized void retryPacketSent() {
        if (_currentState != InboundState.IB_STATE_REQUEST_BAD_TOKEN_RECEIVED &&
            _currentState != InboundState.IB_STATE_TOKEN_REQUEST_RECEIVED)
            throw new IllegalStateException("Bad state for Retry Sent: " + _currentState);
        _currentState = InboundState.IB_STATE_RETRY_SENT;
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

        byte[] payload = new byte[len - 80]; // 16 hdr, 32 static key, 16 MAC, 16 MAC
        try {
            _handshakeState.readMessage(data, off + 32, len - 32, payload, 0);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("Session Request error, State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
            throw gse;
        }
        if (_log.shouldDebug())
            _log.debug("State after sess req: " + _handshakeState);
        processPayload(payload, payload.length, true);
        _sendHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");
        _currentState = InboundState.IB_STATE_REQUEST_RECEIVED;
        
        if (_createdSentCount == 1) {
            _rtt = (int) ( _context.clock().now() - _lastSend );
        }	

        packetReceived();
    }

    /**
     *
     *
     *
     */
    public synchronized void receiveSessionConfirmed(UDPPacket packet) throws GeneralSecurityException {
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

        byte[] payload = new byte[len - 80]; // 16 hdr, 32 static key, 16 MAC, 16 MAC
        try {
            _handshakeState.readMessage(data, off + 16, len - 16, payload, 0);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("Session Confirmed error, State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
            throw gse;
        }
        if (_log.shouldDebug())
            _log.debug("State after sess conf: " + _handshakeState);
        processPayload(payload, payload.length, false);

        // TODO split, calculate keys

        
        // TODO fix state
        if ( (_currentState == InboundState.IB_STATE_UNKNOWN) || 
             (_currentState == InboundState.IB_STATE_REQUEST_RECEIVED) ||
             (_currentState == InboundState.IB_STATE_CREATED_SENT) ) {
            if (confirmedFullyReceived())
                _currentState = InboundState.IB_STATE_CONFIRMED_COMPLETELY;
            else
                _currentState = InboundState.IB_STATE_CONFIRMED_PARTIALLY;
        }
        
        if (_createdSentCount == 1) {
            _rtt = (int) ( _context.clock().now() - _lastSend );
        }	

        packetReceived();
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
