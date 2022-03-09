package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import com.southernstorm.noise.protocol.CipherState;

import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.udp.InboundMessageFragments.ModifiableLong;
import static net.i2p.router.transport.udp.SSU2Util.*;
import net.i2p.util.HexDump;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Contain all of the state about a UDP connection to a peer.
 * This is instantiated only after a connection is fully established.
 *
 * Public only for UI peers page. Not a public API, not for external use.
 *
 * SSU2 only.
 *
 * @since 0.9.54
 */
public class PeerState2 extends PeerState implements SSU2Payload.PayloadCallback, SSU2Bitfield.Callback {
    private final long _sendConnID;
    private final long _rcvConnID;
    private final AtomicInteger _packetNumber = new AtomicInteger();
    private final CipherState _sendCha;
    private final CipherState _rcvCha;
    private final byte[] _sendHeaderEncryptKey1;
    private final byte[] _rcvHeaderEncryptKey1;
    private final byte[] _sendHeaderEncryptKey2;
    private final byte[] _rcvHeaderEncryptKey2;
    private final SSU2Bitfield _receivedMessages;
    /**
     *  PS1 has _ackedMessages which is a map of message ID to sequence number.
     *  Here we have the reverse, a bitfield of acked packet (sequence) numbers,
     *  and map of unacked packet (sequence) numbers to the fragments that packet contained.
     */
    private final SSU2Bitfield _ackedMessages;
    private final ConcurrentHashMap<Long, List<PacketBuilder.Fragment>> _sentMessages;

    // Session Confirmed retransmit
    private byte[] _sessConfForReTX;
    private long _sessConfSentTime;
    private int _sessConfSentCount;

    // As SSU
    public static final int MIN_SSU_IPV4_MTU = 1292;
    public static final int MAX_SSU_IPV4_MTU = 1484;
    public static final int DEFAULT_SSU_IPV4_MTU = MAX_SSU_IPV4_MTU;
    public static final int MIN_SSU_IPV6_MTU = 1280;
    public static final int MAX_SSU_IPV6_MTU = 1488;
    public static final int DEFAULT_SSU_IPV6_MTU = MIN_SSU_IPV6_MTU;  // should always be published
    // As SSU2
    public static final int MIN_MTU = 1280;
    public static final int MAX_MTU = 1500;
    public static final int DEFAULT_MTU = MAX_MTU;

    private static final int BITFIELD_SIZE = 512;
    private static final int MAX_SESS_CONF_RETX = 6;
    private static final int SESS_CONF_RETX_TIME = 1000;



    /**
     *  @param rtt from the EstablishState, or 0 if not available
     */
    public PeerState2(RouterContext ctx, UDPTransport transport,
                     InetSocketAddress remoteAddress, Hash remotePeer, boolean isInbound, int rtt,
                     CipherState sendCha, CipherState rcvCha, long sendID, long rcvID,
                     byte[] sendHdrKey1, byte[] sendHdrKey2, byte[] rcvHdrKey2) {
        super(ctx, transport, remoteAddress, remotePeer, isInbound, rtt);
        _sendConnID = sendID;
        _rcvConnID = rcvID;
        _sendCha = sendCha;
        _rcvCha = rcvCha;
        _sendHeaderEncryptKey1 = sendHdrKey1;
        _rcvHeaderEncryptKey1 = transport.getSSU2StaticIntroKey();
        _sendHeaderEncryptKey2 = sendHdrKey2;
        _rcvHeaderEncryptKey2 = rcvHdrKey2;
        _receivedMessages = new SSU2Bitfield(BITFIELD_SIZE, 0);
        _ackedMessages = new SSU2Bitfield(BITFIELD_SIZE, 0);
        _sentMessages = new ConcurrentHashMap<Long, List<PacketBuilder.Fragment>>(32);
        if (isInbound) {
            // Send immediate ack of Session Confirmed
            _receivedMessages.set(0);
            UDPPacket ack = transport.getBuilder2().buildACK(this);
            transport.send(ack);
        } else {
            // For outbound, SessionConfirmed is packet 0
            _packetNumber.set(1);
        }
    }

    // SSU 1 overrides

    @Override
    public int getVersion() { return 2; }

    /**
     *  how much payload data can we shove in there?
     *  Does NOT leave any room for acks, we'll fit them in when we can.
     *  This is 5 bytes too low for first or only fragment.
     *
     *  @return MTU - 68 (IPv4), MTU - 88 (IPv6)
     */
    @Override
    int fragmentSize() {
        // 20 + 8 + 16 + 3 + 5 + 16 = 68 (IPv4)
        // 40 + 8 + 16 + 3 + 5 + 16 = 88 (IPv6)
        return _mtu -
               (_remoteIP.length == 4 ? PacketBuilder2.MIN_DATA_PACKET_OVERHEAD : PacketBuilder2.MIN_IPV6_DATA_PACKET_OVERHEAD) -
               DATA_FOLLOWON_EXTRA_SIZE; // Followon fragment block overhead (5)
    }

    /**
     *  Packet overhead
     *  Does NOT leave any room for acks, we'll fit them in when we can.
     *  This is 5 bytes too high for first or only fragment.
     *
     *  @return 68 (IPv4), 88 (IPv6)
     */
    @Override
    int fragmentOverhead() {
        // 20 + 8 + 16 + 3 + 5 + 16 = 68 (IPv4)
        // 40 + 8 + 16 + 3 + 5 + 16 = 88 (IPv6)
        return (_remoteIP.length == 4 ? PacketBuilder2.MIN_DATA_PACKET_OVERHEAD : PacketBuilder2.MIN_IPV6_DATA_PACKET_OVERHEAD) +
               DATA_FOLLOWON_EXTRA_SIZE; // Followon fragment block overhead (5)
    }

    /**
     *  All acks have been sent.
     */
    @Override
    void clearWantedACKSendSince() {
        // race prevention
        if (_sentMessages.isEmpty())
            _wantACKSendSince = 0;
    }

    /**
     *  Overridden to use our version of ACKTimer
     */
    @Override
    protected synchronized void messagePartiallyReceived(long now) {
        if (_wantACKSendSince <= 0) {
            _wantACKSendSince = now;
            new ACKTimer();
        }
    }

    /**
     *  Overridden to retransmit SessionConfirmed also
     */
    @Override
    List<OutboundMessageState> allocateSend(long now) {
        if (!_isInbound && _ackedMessages.getOffset() == 0 && !_ackedMessages.get(0)) {
            UDPPacket[] packets = null;
            synchronized(this) {
                if (_sessConfForReTX != null) {
                    // retransmit Session Confirmed when it's time
                    if (_sessConfSentTime + (SESS_CONF_RETX_TIME << (_sessConfSentCount - 1)) < now) {
                        if (_sessConfSentCount >= MAX_SESS_CONF_RETX) {
                            if (_log.shouldWarn())
                                _log.warn("Fail, no Sess Conf ACK rcvd on " + this);
                            _transport.dropPeer(this, false, "No Sess Conf ACK rcvd");
                            _sessConfForReTX = null;
                            return null;
                        }
                        _sessConfSentCount++;
                        packets = getRetransmitSessionConfirmedPackets();
                    }
                }
            }
            if (packets != null) {
                if (_log.shouldInfo())
                    _log.info("ReTX Sess Conf on " + this);
                for (int i = 0; i < packets.length; i++) {
                     _transport.send(packets[i]);
                }
            }
        }
        return super.allocateSend(now);
    }

    // SSU 1 unsupported things

    @Override
    void setCurrentMACKey(SessionKey key) { throw new UnsupportedOperationException(); }
    @Override
    void setCurrentCipherKey(SessionKey key) { throw new UnsupportedOperationException(); }
    @Override
    List<Long> getCurrentFullACKs() { throw new UnsupportedOperationException(); }
    @Override
    List<Long> getCurrentResendACKs() { throw new UnsupportedOperationException(); }
    @Override
    void removeACKMessage(Long messageId) { throw new UnsupportedOperationException(); }
    @Override
    void fetchPartialACKs(List<ACKBitfield> rv) { throw new UnsupportedOperationException(); }

    // SSU 2 things

    /**
     * Next outbound packet number,
     * starts at 1 for Alice (0 is Session Confirmed) and 0 for Bob
     */
    long getNextPacketNumber() { return _packetNumber.getAndIncrement(); }
    long getSendConnID() { return _sendConnID; }
    long getRcvConnID() { return _rcvConnID; }
    /** caller must sync on returned object when encrypting */
    CipherState getSendCipher() { return _sendCha; }
    byte[] getSendHeaderEncryptKey1() { return _sendHeaderEncryptKey1; }
    byte[] getRcvHeaderEncryptKey1() { return _rcvHeaderEncryptKey1; }
    byte[] getSendHeaderEncryptKey2() { return _sendHeaderEncryptKey2; }
    byte[] getRcvHeaderEncryptKey2() { return _rcvHeaderEncryptKey2; }

    SSU2Bitfield getReceivedMessages() {
        // logged in PacketBuilder2
        //if (_log.shouldDebug())
        //    _log.debug("Sending acks " + _receivedMessages + " on " + this);
        return _receivedMessages;
    }
    SSU2Bitfield getAckedMessages() { return _ackedMessages; }

    /**
     *  @param packet fully encrypted, header and body decryption will be done here
     */
    void receivePacket(UDPPacket packet) {
        DatagramPacket dpacket = packet.getPacket();
        byte[] data = dpacket.getData();
        int off = dpacket.getOffset();
        int len = dpacket.getLength();
        //if (_log.shouldDebug())
        //    _log.debug("Packet before header decryption:\n" + HexDump.dump(data, off, len));
        try {
            if (len < MIN_DATA_LEN) {
                if (_log.shouldWarn())
                    _log.warn("Inbound packet too short " + len + " on " + this);
                return;
            }
            SSU2Header.Header header = SSU2Header.trialDecryptShortHeader(packet, _rcvHeaderEncryptKey1, _rcvHeaderEncryptKey2);
            if (header == null) {
                if (_log.shouldWarn())
                    _log.warn("bad data header on " + this);
                return;
            }
            if (header.getType() != DATA_FLAG_BYTE) {
                if (_log.shouldWarn())
                    _log.warn("bad data pkt type " + (header.getType() & 0xff) + " on " + this);
                // TODO if it's early:
                // If inbound, could be a retransmitted Session Confirmed,
                // ack it again.
                // If outbound, and Session Confirmed is not acked yet,
                // could be a retransmitted Session Created,
                // retransmit Session Confirmed.
                // Alternatively, could be a new Session Request or Token Request,
                // we didn't know the session has disconnected yet.
                return;
            }
            if (header.getDestConnID() != _rcvConnID) {
                if (_log.shouldWarn())
                    _log.warn("bad Dest Conn id " + header.getDestConnID() + " on " + this);
                return;
            }
            long n = header.getPacketNumber();
            SSU2Header.acceptTrialDecrypt(packet, header);
            //if (_log.shouldDebug())
            //    _log.debug("Packet " + n + " after header decryption:\n" + HexDump.dump(data, off, len));
            synchronized (_rcvCha) {
                _rcvCha.setNonce(n);
                // decrypt in-place
                _rcvCha.decryptWithAd(header.data, data, off + SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE, len - SHORT_HEADER_SIZE);
            }
            //if (_log.shouldDebug())
            //    _log.debug("Packet " + n + " after full decryption:\n" + HexDump.dump(data, off, len - MAC_LEN));
            if (_receivedMessages.set(n)) {
                synchronized(this) {
                    _packetsReceivedDuplicate++;
                }
                if (_log.shouldWarn())
                    _log.warn("dup pkt rcvd: " + n + " on " + this);
                return;
            }
            int payloadLen = len - (SHORT_HEADER_SIZE + MAC_LEN);
            if (_log.shouldInfo())
                _log.info("New pkt rcvd " + n + " on " + this);
            processPayload(data, off + SHORT_HEADER_SIZE, payloadLen);
            packetReceived(payloadLen);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Bad encrypted packet:\n" + HexDump.dump(data, off, len), gse);
        } catch (IndexOutOfBoundsException ioobe) {
            if (_log.shouldWarn())
                _log.warn("Bad encrypted packet:\n" + HexDump.dump(data, off, len), ioobe);
        }
    }

    private void processPayload(byte[] payload, int offset, int length) throws GeneralSecurityException {
        try {
            int blocks = SSU2Payload.processPayload(_context, this, payload, offset, length, false);
        } catch (Exception e) {
            throw new GeneralSecurityException("Data payload error", e);
        }
    }

    /////////////////////////////////////////////////////////
    // begin payload callbacks
    /////////////////////////////////////////////////////////

    public void gotDateTime(long time) {
        // super adds CLOCK_SKEW_FUDGE that doesn't apply here
        adjustClockSkew((_context.clock().now() - time) - CLOCK_SKEW_FUDGE);
    }

    public void gotOptions(byte[] options, boolean isHandshake) {
    }

    public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
    }

    public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags) {
        throw new IllegalStateException("RI fragment in Data phase");
    }

    public void gotAddress(byte[] ip, int port) {
    }

    public void gotIntroKey(byte[] key) {
    }

    public void gotRelayTagRequest() {
    }

    public void gotRelayTag(long tag) {
    }

    public void gotToken(long token, long expires) {
        _transport.getEstablisher().addOutboundToken(_remotePeer, token, expires);
    }

    public void gotI2NP(I2NPMessage msg) {
        if (_log.shouldDebug())
            _log.debug("Got I2NP block: " + msg);
        // 9 byte header
        int size = msg.getMessageSize() - 7;
        messageFullyReceived(msg.getUniqueId(), size);
        // complete message, skip IMF and MessageReceiver
        _transport.messageReceived(msg, null, _remotePeer, 0, size);
    }

    public void gotFragment(byte[] data, int off, int len, long messageId, int frag, boolean isLast) throws DataFormatException {
        if (_log.shouldDebug())
            _log.debug("Got FRAGMENT block: " + messageId + " fragment " + frag + " isLast? " + isLast);
        InboundMessageState state;
        boolean messageComplete = false;
        boolean messageExpired = false;
        boolean messageDup = false;

        synchronized (_inboundMessages) {
            state = _inboundMessages.get(messageId);
            if (state == null) {
                state = new InboundMessageState(_context, messageId, _remotePeer, data, off, len, frag, isLast);
                _inboundMessages.put(messageId, state);
            } else {
                messageDup = state.hasFragment(frag);
                if (!messageDup) {
                    boolean fragmentOK = state.receiveFragment(data, off, len, frag, isLast);
                    if (!fragmentOK)
                        return;
                    if (state.isComplete()) {
                        messageComplete = true;
                        _inboundMessages.remove(messageId);
                    } else if (state.isExpired()) {
                        messageExpired = true;
                        _inboundMessages.remove(messageId);
                    }
                }
            }
        }

        if (messageDup) {
            synchronized(this) {
                _packetsReceivedDuplicate++;
            }
            if (_log.shouldWarn())
                _log.warn("dup fragment rcvd: " + frag + " for " + state);
            return;
        }

        if (messageComplete) {
                messageFullyReceived(messageId, state.getCompleteSize());
                if (_log.shouldDebug())
                _log.debug("Message received completely!  " + state);
            _context.statManager().addRateData("udp.receivedCompleteTime", state.getLifetime(), state.getLifetime());
            _context.statManager().addRateData("udp.receivedCompleteFragments", state.getFragmentCount(), state.getLifetime());
            receiveMessage(state);
        } else if (messageExpired) {
            if (_log.shouldWarn())
                _log.warn("Message expired while only being partially read: " + state);
            _context.messageHistory().droppedInboundMessage(state.getMessageId(), state.getFrom(), "expired while partially read: " + state.toString());
            // all state access must be before this
            state.releaseResources();
        } else {
            messagePartiallyReceived();
        }
    }

    public void gotACK(long ackThru, int acks, byte[] ranges) {
        SSU2Bitfield ackbf;
        ackbf = SSU2Bitfield.fromACKBlock(ackThru, acks, ranges, (ranges != null ? ranges.length / 2 : 0));
        if (_log.shouldDebug())
            _log.debug("Got ACK block: " + SSU2Bitfield.toString(ackThru, acks, ranges, (ranges != null ? ranges.length / 2 : 0)));
        // calls bitSet() below
        ackbf.forEachAndNot(_ackedMessages, this);
    }

    public void gotTermination(int reason, long count) {
        if (_log.shouldWarn())
            _log.warn("Got TERMINATION block, reason: " + reason + " count: " + count);
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

    /**
     *  Do what MessageReceiver does, but inline and for SSU2.
     *  Will always be more than one fragment.
     */
    private void receiveMessage(InboundMessageState state) {
        int sz = state.getCompleteSize();
        try {
            byte buf[] = new byte[sz];
            I2NPMessage m;
            int numFragments = state.getFragmentCount();
            ByteArray fragments[] = state.getFragments();
            int off = 0;
            for (int i = 0; i < numFragments; i++) {
                ByteArray ba = fragments[i];
                int len = ba.getValid();
                System.arraycopy(ba.getData(), 0, buf, off, len);
                off += len;
             }
             if (off != sz) {
                 if (_log.shouldWarn())
                     _log.warn("Hmm, offset of the fragments = " + off + " while the state says " + sz);
                 return;
             }
             I2NPMessage msg = I2NPMessageImpl.fromRawByteArrayNTCP2(_context, buf, 0, sz, null);
             _transport.messageReceived(msg, null, _remotePeer, state.getLifetime(), sz);
        } catch (I2NPMessageException ime) {
            if (_log.shouldWarn())
                _log.warn("Message invalid: " + state + " PeerState: " + this, ime);
        } catch (RuntimeException e) {
            // e.g. AIOOBE
            if (_log.shouldWarn())
                _log.warn("Error handling a message: " + state, e);
        } finally {
            state.releaseResources();
        }
    }

    /**
     *  Record the mapping of packet number to what fragments were in it,
     *  so we can process acks.
     */
    void fragmentsSent(long pktNum, List<PacketBuilder.Fragment> fragments) {
        List<PacketBuilder.Fragment> old = _sentMessages.putIfAbsent(Long.valueOf(pktNum), fragments);
        if (old != null) {
            // shouldn't happen
            if (_log.shouldWarn())
                _log.warn("Dup send of pkt " + pktNum + " on " + this);
        } else {
            if (_log.shouldWarn())
                _log.warn("New data pkt " + pktNum + " sent with " + fragments.size() + " fragments on " + this);
        }
    }

    /**
     *  Callback from SSU2Bitfield.forEachAndNot().
     *  A new ack was received.
     */
    public void bitSet(long pktNum) {
        if (pktNum == 0 && !_isInbound) {
            // we don't need to save the Session Confirmed for retransmission any more
            synchronized(this) {
                _sessConfForReTX = null;
            }
            if (_log.shouldDebug())
                _log.debug("New ACK of Session Confirmed on " + this);
            return;
        }
        List<PacketBuilder.Fragment> fragments = _sentMessages.remove(Long.valueOf(pktNum));
        if (fragments == null) {
            // shouldn't happen
            if (_log.shouldWarn())
                _log.warn("New ACK of pkt " + pktNum + " not found on " + this);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("New ACK of pkt " + pktNum + " containing " + fragments.size() + " fragments on " + this);
        ModifiableLong highestSeqNumAcked = new ModifiableLong(-1);
        for (PacketBuilder.Fragment f : fragments) {
            OutboundMessageState state = f.state;
            if (state.isComplete()) {
                if (_log.shouldWarn())
                    _log.warn("New ACK but state complete? " + state);
                continue;
            }
            int num = f.num;
            if (!state.needsSending(num)) {
                // will happen with retransmission as a different packet number
                if (_log.shouldWarn())
                    _log.warn("New ACK for fragment " + num + " but already acked? " + state);
                continue;
            }
            int ackedSize = state.getUnackedSize();
            boolean complete = state.acked(f.num);
            if (complete) {
                if (_log.shouldDebug())
                    _log.debug("Received ACK of fragment " + num + " of " + state +
                               ", now complete");
                acked(state.getMessageId(), highestSeqNumAcked);
                if (ackedSize > 0) {
                    // TODO acked() will have an ackedSize of 0, so do it here also
                    messageACKed(ackedSize, state.getLifetime(), state.getMaxSends(), false, false);
                }
            } else {
                ackedSize -= state.getUnackedSize();
                if (_log.shouldDebug())
                    _log.debug("Received ACK of fragment " + num + " of " + state +
                               ", still incomplete");
                if (ackedSize > 0) {
                    state.clearNACKs();
                    // this adjusts the rtt/rto/window/etc
                    // flags TODO
                    messageACKed(ackedSize, state.getLifetime(), state.getMaxSends(), false, false);
                }
            }
        }
        long highest = highestSeqNumAcked.value;
        if (highest >= 0)
            highestSeqNumAcked(highest);
    }

    /**
     * note that we just sent the SessionConfirmed packets
     * and save them for retransmission
     */
    public synchronized void confirmedPacketsSent(byte[] data) {
        if (_sessConfForReTX == null)
            _sessConfForReTX = data;
        _sessConfSentTime = _context.clock().now();
        _sessConfSentCount++;
    }

    /**
     * @return null if not sent or already got the ack
     */
    public synchronized UDPPacket[] getRetransmitSessionConfirmedPackets() {
        if (_sessConfForReTX == null)
            return null;
        UDPPacket packet = UDPPacket.acquire(_context, false);
        UDPPacket[] rv = new UDPPacket[1];
        rv[0] = packet;
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        System.arraycopy(_sessConfForReTX, 0, data, off, _sessConfForReTX.length);
        pkt.setAddress(_remoteIPAddress);
        pkt.setPort(_remotePort);
        packet.setMessageType(PacketBuilder2.TYPE_CONF);
        packet.setPriority(PacketBuilder2.PRIORITY_HIGH);
        return rv;
    }

    /**
     *  A timer to send an ack-only packet.
     */
    private class ACKTimer extends SimpleTimer2.TimedEvent {
        public ACKTimer() {
            super(_context.simpleTimer2());
            long delta = Math.min(_rtt/2, ACK_FREQUENCY);
            if (_log.shouldDebug())
                _log.debug("Sending delayed ack in " + delta + ": " + PeerState2.this);
            schedule(delta);
        }

        /**
         *  Send an ack-only packet, unless acks were already sent
         *  as indicated by _wantACKSendSince == 0.
         *  Will not requeue unless the acks don't all fit (unlikely).
         */
        public void timeReached() {
            synchronized(PeerState2.this) {
                if (_wantACKSendSince <= 0) {
                    if (_log.shouldDebug())
                        _log.debug("Already acked:" + PeerState2.this);
                    return;
                }
                _wantACKSendSince = 0;
            }
            UDPPacket ack = _transport.getBuilder2().buildACK(PeerState2.this);
            if (_log.shouldDebug())
                _log.debug("ACKTimer sending acks to " + PeerState2.this);
            _transport.send(ack);
        }
    }
}
