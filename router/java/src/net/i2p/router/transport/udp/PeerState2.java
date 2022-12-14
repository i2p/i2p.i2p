package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.udp.InboundMessageFragments.ModifiableLong;
import net.i2p.router.transport.udp.PacketBuilder.Fragment;
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
public class PeerState2 extends PeerState implements SSU2Payload.PayloadCallback, SSU2Bitfield.Callback, SSU2Sender {
    private final long _sendConnID;
    private final long _rcvConnID;
    private final AtomicInteger _packetNumber = new AtomicInteger();
    private final AtomicInteger _lastAckHashCode = new AtomicInteger(-1);
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
    private final ACKTimer _ackTimer;

    private long _sentMessagesLastExpired;
    private byte[] _ourIP;
    private int _ourPort;
    private int _destroyReason;

    // Session Confirmed retransmit
    private byte[][] _sessConfForReTX;
    private long _sessConfSentTime;
    private int _sessConfSentCount;

    // Connection Migration, synch on _migrationLock
    private enum MigrationState {
        MIGRATION_STATE_NONE,
        MIGRATION_STATE_PENDING,
        // unused below here
        MIGRATION_STATE_CANCELLED,
        MIGRATION_STATE_FAILED,
        MIGRATION_STATE_SUCCESS
    }
    private final Object _migrationLock = new Object();
    private MigrationState _migrationState = MigrationState.MIGRATION_STATE_NONE;
    private long _migrationStarted;
    private long _migrationNextSendTime;
    private byte[] _pathChallengeData;
    private long _pathChallengeSendCount;
    private RemoteHostId _pendingRemoteHostId;
    private RemoteHostId _previousRemoteHostId;
    private static final int MAX_PATH_CHALLENGE_SENDS = 4;
    private static final long MAX_PATH_CHALLENGE_TIME = 30*1000;
    private static final long PATH_CHALLENGE_DELAY = 5*1000;


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
    private static final int MAX_SESS_CONF_RETX = 5;
    private static final long SENT_MESSAGES_CLEAN_TIME = 60*1000;


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
        _sentMessagesLastExpired = _keyEstablishedTime;
        if (isInbound) {
            // Send immediate ack of Session Confirmed
            _receivedMessages.set(0);
            UDPPacket ack = transport.getBuilder2().buildACK(this);
            transport.send(ack);
        } else {
            // For outbound, SessionConfirmed is packet 0
            _packetNumber.set(1);
        }
        _ackTimer = new ACKTimer();
    }

    // SSU 1 overrides

    @Override
    public int getVersion() { return 2; }

    /**
     *  how much payload data can we shove in there?
     *  This is 5 bytes too low for first or only fragment,
     *  because the 9 byte I2NP header is included in that fragment.
     *  Does NOT leave any room for acks with a full-size fragment.
     *
     *  @return MTU - 68 (IPv4), MTU - 88 (IPv6)
     */
    @Override
    int fragmentSize() {
        // 20 + 8 + 16 + 3 + 5 + 16 = 68 (IPv4)
        // 40 + 8 + 16 + 3 + 5 + 16 = 88 (IPv6)
        return _mtu -
               (_remoteIP.length == 4 ? PacketBuilder2.MIN_DATA_PACKET_OVERHEAD : PacketBuilder2.MIN_IPV6_DATA_PACKET_OVERHEAD) -
               (SSU2Payload.BLOCK_HEADER_SIZE + DATA_FOLLOWON_EXTRA_SIZE);
    }

    /**
     *  Packet overhead
     *  This is 5 bytes too high for first or only fragment,
     *  because the 9 byte I2NP header is included in that fragment.
     *  Does NOT leave any room for acks with a full-size fragment.
     *
     *  @return 68 (IPv4), 88 (IPv6)
     */
    @Override
    int fragmentOverhead() {
        // 20 + 8 + 16 + 3 + 5 + 16 = 68 (IPv4)
        // 40 + 8 + 16 + 3 + 5 + 16 = 88 (IPv6)
        return (_remoteIP.length == 4 ? PacketBuilder2.MIN_DATA_PACKET_OVERHEAD : PacketBuilder2.MIN_IPV6_DATA_PACKET_OVERHEAD) +
               SSU2Payload.BLOCK_HEADER_SIZE + DATA_FOLLOWON_EXTRA_SIZE;
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
            _ackTimer.schedule();
        }
    }

    /**
     * Overridden to expire unacked packets in _sentMessages.
     * These will remain unacked if lost; fragments will be retransmitted
     * in a new packet.
     *
     * @return number of active outbound messages remaining
     */
    @Override
    int finishMessages(long now) {
        if (now >= _sentMessagesLastExpired + SENT_MESSAGES_CLEAN_TIME) {
            _sentMessagesLastExpired = now;
            if (!_sentMessages.isEmpty()) {
                // TODO is this the right place for this check?
                long ahead =  _packetNumber.get() - _ackedMessages.getHighestSet();
                if (ahead > BITFIELD_SIZE) {
                    if (_log.shouldWarn())
                        _log.warn("Fail after " + ahead + "unacked packets on " + this);
                    _transport.sendDestroy(this, REASON_FRAME_TIMEOUT);
                    _transport.dropPeer(this, true, "Too many unacked packets");
                }
                if (_log.shouldDebug())
                    _log.debug("finishMessages() over " + _sentMessages.size() + " pending acks");
                loop:
                for (Iterator<List<PacketBuilder.Fragment>> iter = _sentMessages.values().iterator(); iter.hasNext(); ) {
                    List<PacketBuilder.Fragment> frags = iter.next();
                    for (PacketBuilder.Fragment f : frags) {
                        OutboundMessageState state = f.state;
                        if (!state.isComplete() && !state.isExpired(now))
                            continue loop;
                    }
                    iter.remove();
                    if (_log.shouldInfo())
                        _log.info("Cleaned from sentMessages: " + frags);
                }
            }
        }
        return super.finishMessages(now);
    }

    /**
     *  Overridden to retransmit SessionConfirmed also
     */
    @Override
    List<OutboundMessageState> allocateSend(long now) {
        if (!_isInbound && _ackedMessages.getOffset() == 0 && !_ackedMessages.get(0)) {
            if (!checkRetransmitSessionConfirmed(_context.clock().now(), false))
                return null;
        }
        return super.allocateSend(now);
    }

    /**
     *  Only call for outbound, if we don't have ack 0 yet.
     *
     *  @param force ignore timer, always send
     *  @return success, false if total fail
     *  @since 0.9.55 split out from above
     */
    private boolean checkRetransmitSessionConfirmed(long now, boolean force) {
        UDPPacket[] packets = null;
        synchronized(this) {
            if (_sessConfForReTX != null) {
                // retransmit Session Confirmed when it's time
                if (force || _sessConfSentTime + (OutboundEstablishState.RETRANSMIT_DELAY << (_sessConfSentCount - 1)) < now) {
                    if (_sessConfSentCount >= MAX_SESS_CONF_RETX) {
                        // note: we generally won't get here, because the
                        // first outbound message will timeout before this
                        // and close the session in super.finishMessages()
                        if (_log.shouldWarn())
                            _log.warn("Fail, no Sess Conf ACK rcvd on " + this);
                        UDPPacket pkt = _transport.getBuilder2().buildSessionDestroyPacket(SSU2Util.REASON_FRAME_TIMEOUT, this);
                        _transport.send(pkt);
                        _transport.dropPeer(this, true, "No Sess Conf ACK rcvd");
                        _sessConfForReTX = null;
                        return false;
                    }
                    _sessConfSentCount++;
                    _sessConfSentTime = now;
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
        return true;
    }

    // SSU 1 unsupported things

    @Override
    List<Long> getCurrentFullACKs() { throw new UnsupportedOperationException(); }
    @Override
    List<Long> getCurrentResendACKs() { throw new UnsupportedOperationException(); }
    @Override
    void removeACKMessage(Long messageId) { throw new UnsupportedOperationException(); }
    @Override
    void fetchPartialACKs(List<ACKBitfield> rv) { throw new UnsupportedOperationException(); }

    // SSU 2 things

    /// begin SSU2Sender interface ///

    /**
     * Next outbound packet number,
     * starts at 1 for Alice (0 is Session Confirmed) and 0 for Bob
     * @since public since 0.9.57 for SSU2Sender interface only
     */
    public long getNextPacketNumber() { return _packetNumber.getAndIncrement(); }
    /**
     * @since public since 0.9.57 for SSU2Sender interface only
     */
    public long getSendConnID() { return _sendConnID; }
    /**
     * Caller must sync on returned object when encrypting
     * @since public since 0.9.57 for SSU2Sender interface only
     */
    public CipherState getSendCipher() { return _sendCha; }
    /**
     * @since public since 0.9.57 for SSU2Sender interface only
     */
    public byte[] getSendHeaderEncryptKey1() { return _sendHeaderEncryptKey1; }
    /**
     * @since public since 0.9.57 for SSU2Sender interface only
     */
    public byte[] getSendHeaderEncryptKey2() { return _sendHeaderEncryptKey2; }
    /**
     * @since 0.9.57
     */
    public void setDestroyReason(int reason) { _destroyReason = reason; }

    /// end SSU2Sender interface ///

    long getRcvConnID() { return _rcvConnID; }
    byte[] getRcvHeaderEncryptKey1() { return _rcvHeaderEncryptKey1; }
    byte[] getRcvHeaderEncryptKey2() { return _rcvHeaderEncryptKey2; }

    /**
     * @return 0 (REASON_UNSPEC) if unset
     * @since 0.9.57 for PeerStateDestroyed
     */
    int getDestroyReason() { return _destroyReason; }

    /**
     * @since 0.9.57 for PeerStateDestroyed
     */
    CipherState getRcvCipher() { return _rcvCha; }


    void setOurAddress(byte[] ip, int port) {
        _ourIP = ip; _ourPort = port;
    }
    byte[] getOurIP() { return _ourIP; }
    int getOurPort() { return _ourPort; }

    /**
     *  @since public since 0.9.57 for SSU2Sender interface only
     */
    public SSU2Bitfield getReceivedMessages() {
        // logged in PacketBuilder2
        //if (_log.shouldDebug())
        //    _log.debug("Sending acks " + _receivedMessages + " on " + this);
        synchronized(this) {
            // cancel the ack timer
            _wantACKSendSince = 0;
            _lastACKSend = _context.clock().now();
        }
        return _receivedMessages;
    }

    /**
     *  @since public since 0.9.57 for SSU2Sender interface only
     */
    public SSU2Bitfield getAckedMessages() { return _ackedMessages; }

    /**
     *  @param packet fully encrypted, header and body decryption will be done here
     */
    void receivePacket(UDPPacket packet) {
        receivePacket(packet.getRemoteHost(), packet);
    }

    /**
     *  From different than expected source IP/port
     *
     *  @param from source address
     *  @param packet fully encrypted, header and body decryption will be done here
     *  @since 0.9.55
     */
    void receivePacket(RemoteHostId from, UDPPacket packet) {
        DatagramPacket dpacket = packet.getPacket();
        byte[] data = dpacket.getData();
        int off = dpacket.getOffset();
        int len = dpacket.getLength();
        //if (_log.shouldDebug())
        //    _log.debug("Packet before header decryption:\n" + HexDump.dump(data, off, len));
        try {
            SSU2Header.Header header = SSU2Header.trialDecryptShortHeader(packet, _rcvHeaderEncryptKey1, _rcvHeaderEncryptKey2);
            if (header == null) {
                if (_log.shouldWarn())
                    _log.warn("Inbound packet too short " + len + " on " + this);
                return;
            }
            if (header.getDestConnID() != _rcvConnID) {
                if (_log.shouldWarn())
                    _log.warn("bad Dest Conn id " + header + " size " + len + " on " + this);
                if (!_isInbound && _ackedMessages.getOffset() == 0 && !_ackedMessages.get(0)) {
                    // this was probably a retransmitted session created,
                    // sent with k_header_1 = bob's intro key, and we're
                    // attempting to decrypt with our intro key.
                    // resend session confirmed in response
                    checkRetransmitSessionConfirmed(_context.clock().now(), true);
                    // alternatively, the session closed and we didn't get the termination,
                    // and this is a new inbound session request? TODO
                }
                return;
            }
            if (header.getType() != DATA_FLAG_BYTE) {
                if (_log.shouldWarn())
                    _log.warn("bad data pkt type " + (header.getType() & 0xff) + " size " + len + " on " + this);
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
            if (_log.shouldDebug())
                _log.debug("New " + len + " byte pkt " + n + " rcvd on " + this);
            SSU2Payload.processPayload(_context, this, data, off + SHORT_HEADER_SIZE, payloadLen, false, from);
            packetReceived(payloadLen);

            if (!_dead) {
                // Connection Migration
                // We process packets regardless of source.
                // This is after all the header checks, decryption, and payload processing.
                // Any failures will have thrown or returned before here.
                // Path Response callback is before this and will reset the state if successful.

                boolean limitSending = false;
                synchronized(_migrationLock) {
                    switch(_migrationState) {
                        case MIGRATION_STATE_NONE:
                            if (!from.equals(_remoteHostId)) {
                                // QUIC: Must be highest set to protect against reordered packets
                                if (SSU2Util.ENABLE_PATH_CHALLENGE &&
                                    from.getIP().length == _remoteHostId.getIP().length &&
                                    n == _receivedMessages.getHighestSet() &&
                                    TransportUtil.isValidPort(from.getPort()) &&
                                    _transport.isValid(from.getIP())) {
                                    // send challenge
                                    if (_log.shouldWarn())
                                        _log.warn("Start migration to " + from + " on " + this);
                                    _migrationState = MigrationState.MIGRATION_STATE_PENDING;
                                    _migrationStarted = _context.clock().now();
                                    _migrationNextSendTime = _migrationStarted + PATH_CHALLENGE_DELAY;
                                    _pathChallengeData = new byte[8];
                                    _context.random().nextBytes(_pathChallengeData);
                                    _pathChallengeSendCount = 1;
                                    _pendingRemoteHostId = from;
                                    sendPathChallenge(dpacket.getAddress(), from.getPort());
                                    setLastSendTime(_migrationStarted);
                                } else {
                                    // don't attempt to switch
                                    if (_log.shouldWarn())
                                        _log.warn("Not migrating to " + from + " on " + this);
                                }
                                limitSending = true;
                            }
                            break;

                        case MIGRATION_STATE_PENDING:
                            if (from.equals(_remoteHostId)) {
                                // cancel
                                _migrationState = MigrationState.MIGRATION_STATE_NONE;
                                if (_log.shouldWarn())
                                    _log.warn("Cancel migration on " + this);
                            } else {
                                // still waiting
                                long now = _context.clock().now();
                                if (now > _migrationStarted + MAX_PATH_CHALLENGE_TIME ||
                                    _pathChallengeSendCount > MAX_PATH_CHALLENGE_SENDS) {
                                    // time exceeded
                                    _migrationState = MigrationState.MIGRATION_STATE_NONE;
                                    if (_log.shouldWarn())
                                        _log.warn("Migration failed on " + this);
                                } else if (from.equals(_pendingRemoteHostId)) {
                                    if (_log.shouldWarn())
                                        _log.warn("Migration pending, got another packet from " + from + " on " + this);
                                    if (now > _migrationNextSendTime) {
                                        // retransmit challenge
                                        _migrationNextSendTime = now + (PATH_CHALLENGE_DELAY << _pathChallengeSendCount);
                                        _pathChallengeSendCount++;
                                        sendPathChallenge(dpacket.getAddress(), from.getPort());
                                        setLastSendTime(now);
                                    }
                                    limitSending = true;
                                } else {
                                    // a third ip/port ???
                                    if (_log.shouldWarn())
                                        _log.warn("Migration pending, got packet from 3rd address " + from + " on " + this);
                                    limitSending = true;
                                }
                            }
                            break;
                    }
                }
                if (limitSending)
                    ECNReceived();
            }   //// !_dead

            boolean ackImmediate = (header.data[SHORT_HEADER_FLAGS_OFFSET] & 0x01) != 0;
            if (ackImmediate) {
                _ackTimer.scheduleImmediate();
            }

        } catch (Exception e) {
            if (_log.shouldWarn())
                _log.warn("Bad encrypted packet on: " + this + '\n' + HexDump.dump(data, off, len), e);
        }
    }

    /**
     *  Caller must synch on _migrationState
     *  @since 0.9.56
     */
    private void sendPathChallenge(InetAddress toIP, int toPort) {
        if (_log.shouldWarn())
            _log.warn("Send path challenge to " + toIP + ' ' + toPort + " on " + this);
        List<SSU2Payload.Block> blocks = new ArrayList<SSU2Payload.Block>(3);
        blocks.add(new SSU2Payload.DateTimeBlock(_context));
        blocks.add(new SSU2Payload.AddressBlock(toIP.getAddress(), toPort));
        blocks.add(new SSU2Payload.PathChallengeBlock(_pathChallengeData));
        UDPPacket packet = _transport.getBuilder2().buildPacket(Collections.<Fragment>emptyList(), blocks, this);
        // fix up IP/port
        DatagramPacket pkt = packet.getPacket();
        pkt.setAddress(toIP);
        pkt.setPort(toPort);
        _transport.send(packet);
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
        if (_log.shouldDebug())
            _log.debug("Got RI in data phase " + ri + "\non: " + this);
        try {
            Hash h = ri.getHash();
            if (h.equals(_context.routerHash()))
                return;
            RouterInfo old = _context.netDb().store(h, ri);
            if (flood && !ri.equals(old)) {
                FloodfillNetworkDatabaseFacade fndf = (FloodfillNetworkDatabaseFacade) _context.netDb();
                if ((old == null || ri.getPublished() > old.getPublished()) &&
                    fndf.floodConditional(ri)) {
                    if (_log.shouldDebug())
                        _log.debug("Flooded the RI: " + h);
                } else {
                    if (_log.shouldInfo())
                        _log.info("Flood request but we didn't: " + h);
                }
            }
        } catch (IllegalArgumentException iae) {
            if (_log.shouldWarn())
                _log.warn("RI store fail: " + ri, iae);
        }
    }

    public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags) {
        throw new IllegalStateException("RI fragment in Data phase");
    }

    public void gotAddress(byte[] ip, int port) {
        _ourIP = ip; _ourPort = port;
    }

    public void gotRelayTagRequest() {
        if (!ENABLE_RELAY)
            return;
        if (_log.shouldInfo())
            _log.info("Got RELAY TAG REQUEST on " + this);
        long tag = getWeRelayToThemAs();
        if (tag <= 0) {
            if (_transport.canIntroduce(isIPv6())) {
                tag = 1 + _context.random().nextLong(EstablishmentManager.MAX_TAG_VALUE);
                setWeRelayToThemAs(tag);
                _transport.getIntroManager().add(this);
            }
        }
        if (tag > 0) {
            SSU2Payload.Block block = new SSU2Payload.RelayTagBlock(tag);
            UDPPacket pkt = _transport.getBuilder2().buildPacket(Collections.<Fragment>emptyList(),
                                                                 Collections.singletonList(block),
                                                                 this);
            _transport.send(pkt);
        }
    }

    public void gotRelayTag(long tag) {
        if (!ENABLE_RELAY)
            return;
        long old = getTheyRelayToUsAs();
        if (old != 0) {
            if (_log.shouldWarn())
                _log.warn("Got new tag " + tag + " but had previous tag " + old + " on " + this);
            return;
        }
        setTheyRelayToUsAs(tag);
        _transport.getIntroManager().add(this);
    }

    public void gotRelayRequest(byte[] data) {
        if (!ENABLE_RELAY)
            return;
        _transport.getIntroManager().receiveRelayRequest(this, data);
        // Relay blocks are ACK-eliciting
        messagePartiallyReceived();
    }

    public void gotRelayResponse(int status, byte[] data) {
        if (!ENABLE_RELAY)
            return;
        _transport.getIntroManager().receiveRelayResponse(this, status, data);
        // Relay blocks are ACK-eliciting
        messagePartiallyReceived();
    }

    public void gotRelayIntro(Hash aliceHash, byte[] data) {
        if (!ENABLE_RELAY)
            return;
        _transport.getIntroManager().receiveRelayIntro(this, aliceHash, data);
        // Relay blocks are ACK-eliciting
        messagePartiallyReceived();
    }

    public void gotPeerTest(int msg, int status, Hash h, byte[] data) {
        if (!ENABLE_PEER_TEST)
            return;
        _transport.getPeerTestManager().receiveTest(_remoteHostId, this, msg, status, h, data);
        // Peer Test block is ACK-eliciting
        messagePartiallyReceived();
    }

    public void gotToken(long token, long expires) {
        if (_log.shouldInfo())
            _log.info("Got TOKEN block: " + token + " expires " + DataHelper.formatTime(expires) + " on " + this);
        _transport.getEstablisher().addOutboundToken(_remoteHostId, token, expires);
    }

    public void gotI2NP(I2NPMessage msg) {
        if (_log.shouldDebug())
            _log.debug("Got I2NP block: " + msg);
        // 9 byte header
        int size = msg.getMessageSize() - 7;
        long messageId = msg.getUniqueId();
        messageFullyReceived(messageId, size);
        if (_transport.getInboundFragments().messageReceived(messageId)) {
            _context.statManager().addRateData("udp.ignoreRecentDuplicate", 1);
            if (_log.shouldInfo())
                _log.info("Got dup msg: " + messageId + " on " + this);
            return;
        }
        // complete message, skip IMF and MessageReceiver
        _transport.messageReceived(msg, null, _remotePeer, 0, size);
    }

    public void gotFragment(byte[] data, int off, int len, long messageId, int frag, boolean isLast) throws DataFormatException {
        if (_log.shouldDebug())
            _log.debug("Got FRAGMENT block: " + messageId + " fragment " + frag + " len " + len +
                      " isLast? " + isLast + " on " + _remotePeer.toBase64());
        InboundMessageState state;
        boolean messageComplete = false;
        boolean messageExpired = false;
        boolean messageDup;

        synchronized (_inboundMessages) {
            state = _inboundMessages.get(messageId);
            if (state == null) {
                // Bloom filter in router will catch it, but use IMF's Bloom filter
                // to save resources here
                messageDup = _transport.getInboundFragments().wasRecentlyReceived(messageId);
                if (messageDup) {
                    state = null;
                } else {
                    state = new InboundMessageState(_context, messageId, _remotePeer, data, off, len, frag, isLast);
                    _inboundMessages.put(messageId, state);
                }
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
            messagePartiallyReceived();
            // Only update stats for the first fragment,
            // otherwise it wildly overstates things
            if (frag == 0)
                _context.statManager().addRateData("udp.ignoreRecentDuplicate", 1);
            synchronized(this) {
                _packetsReceivedDuplicate++;
            }
            if (_log.shouldInfo()) {
                if (state != null)
                    _log.info("dup fragment rcvd: " + frag + " for " + state);
                else
                    _log.info("dup fragment rcvd: " + messageId + ' ' + frag + " on " + this);
            }
            return;
        }

        if (messageComplete) {
                messageFullyReceived(messageId, state.getCompleteSize());
                if (_transport.getInboundFragments().messageReceived(messageId)) {
                    _context.statManager().addRateData("udp.ignoreRecentDuplicate", 1);
                    if (_log.shouldInfo())
                        _log.info("Got dup msg: " + messageId + " on " + this);
                    return;
                }
                if (_log.shouldDebug())
                _log.debug("Message received completely!  " + state);
            _context.statManager().addRateData("udp.receivedCompleteTime", state.getLifetime(), state.getLifetime());
            _context.statManager().addRateData("udp.receivedCompleteFragments", state.getFragmentCount(), state.getLifetime());
            receiveMessage(state);
        } else if (messageExpired) {
            messagePartiallyReceived();
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
        int hc = (((int) ackThru) << 8) ^ (acks << 24) ^ DataHelper.hashCode(ranges);
        if (_lastAckHashCode.getAndSet(hc) == hc) {
            //if (_log.shouldDebug())
            //    _log.debug("Got dup ACK block: " + SSU2Bitfield.toString(ackThru, acks, ranges, (ranges != null ? ranges.length / 2 : 0)));
            return;
        }
        try {
            SSU2Bitfield ackbf = SSU2Bitfield.fromACKBlock(ackThru, acks, ranges, (ranges != null ? ranges.length / 2 : 0));
            if (_log.shouldDebug())
                _log.debug("Got new ACK block from " +
                           _remotePeer.toBase64().substring(0,6) + ' ' +
                           SSU2Bitfield.toString(ackThru, acks, ranges, (ranges != null ? ranges.length / 2 : 0)));
            // calls bitSet() below
            ackbf.forEachAndNot(_ackedMessages, this);
        } catch (Exception e) {
            // IllegalArgumentException, buggy ack block, let the other blocks get processed
            if (_log.shouldWarn())
                _log.warn("Bad ACK block\n" + SSU2Bitfield.toString(ackThru, acks, ranges, (ranges != null ? ranges.length / 2 : 0)) +
                          "\nAck through " + ackThru + " acnt " + acks + (ranges != null ? " Ranges:\n" + HexDump.dump(ranges) : "") +
                          "from " + this, e);
        }
    }

    public void gotTermination(int reason, long count) {
        if (_log.shouldInfo())
            _log.info("Got TERMINATION block, reason: " + reason + " count: " + count + " on " + this);
        if (reason == SSU2Util.REASON_TERMINATION) {
            // this should only happen at shutdown, where we don't have a post-termination handler
        } else {
            UDPPacket pkt = _transport.getBuilder2().buildSessionDestroyPacket(SSU2Util.REASON_TERMINATION, this);
            _transport.send(pkt);
        }
        if (!_dead) {
            _transport.getEstablisher().receiveSessionDestroy(_remoteHostId, this);
            _dead = true;
        }
    }

    public void gotPathChallenge(RemoteHostId from, byte[] data) {
        if (_log.shouldInfo())
            _log.info("Got PATH CHALLENGE block, length: " + data.length + " on " + this);
        SSU2Payload.Block block = new SSU2Payload.PathResponseBlock(data);
        UDPPacket pkt = _transport.getBuilder2().buildPacket(Collections.<Fragment>emptyList(),
                                                             Collections.singletonList(block),
                                                             this);
        // TODO send to from address?
        _transport.send(pkt);
        long now = _context.clock().now();
        setLastSendTime(now);
        setLastReceiveTime(now);
    }

    public void gotPathResponse(RemoteHostId from, byte[] data) {
        if (_log.shouldInfo())
            _log.info("Got PATH RESPONSE block, length: " + data.length + " on " + this);
        synchronized(_migrationLock) {
            switch(_migrationState) {
                case MIGRATION_STATE_PENDING:
                    if (from.equals(_pendingRemoteHostId) && DataHelper.eq(data, _pathChallengeData)) {
                        // success
                        _migrationState = MigrationState.MIGRATION_STATE_NONE;
                        _pathChallengeData = null;
                        if (_log.shouldWarn())
                            _log.warn("Migration successful, changed address from " + _remoteHostId + " to " + from + " for " + this);
                        _transport.changePeerAddress(this, from);
                        _mtu = MIN_MTU;
                        if (isIPv6() || !_transport.isSnatted()) {
                            EstablishmentManager.Token token = _transport.getEstablisher().getInboundToken(from);
                            SSU2Payload.Block block = new SSU2Payload.NewTokenBlock(token);
                            UDPPacket pkt = _transport.getBuilder2().buildPacket(Collections.<Fragment>emptyList(),
                                                                                 Collections.singletonList(block),
                                                                                 this);
                            _transport.send(pkt);
                            long now = _context.clock().now();
                            setLastSendTime(now);
                            setLastReceiveTime(now);
                        } else {
                            messagePartiallyReceived();
                        }
                    } else {
                        // caller will handle
                        // ACK-eliciting
                        messagePartiallyReceived();
                    }
                    break;

                default:
                    messagePartiallyReceived();
                    break;
            }
        }
    }

    /////////////////////////////////////////////////////////
    // end payload callbacks
    /////////////////////////////////////////////////////////

    /**
     *  Caller should sync; UDPTransport must remove and add to peersByRemoteHost map
     *  @since 0.9.56
     */
    void changeAddress(RemoteHostId id) {
        _previousRemoteHostId = _remoteHostId;
        _remoteHostId = id;
        _remotePort = id.getPort();
    }

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
     *
     *  @param length including ip/udp header, for logging only
     *  @since public since 0.9.57 for SSU2Sender interface only
     *
     */
    public void fragmentsSent(long pktNum, int length, List<PacketBuilder.Fragment> fragments) {
        List<PacketBuilder.Fragment> old = _sentMessages.putIfAbsent(Long.valueOf(pktNum), fragments);
        if (old != null) {
            // shouldn't happen
            if (_log.shouldWarn())
                _log.warn("Dup send of pkt " + pktNum + " on " + this);
        } else {
            if (_log.shouldDebug())
                _log.debug("New " + length + " byte data pkt " + pktNum + " sent with " + fragments.size() + " fragments on " + this);
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
        long highest = -1;
        for (PacketBuilder.Fragment f : fragments) {
            OutboundMessageState state = f.state;
            if (acked(f)) {
                if (_log.shouldDebug())
                    _log.debug("New ACK of fragment " + f.num + " of " + state);
            } else {
                // will happen with retransmission as a different packet number
                if (_log.shouldInfo())
                    _log.info("Dup ACK of fragment " + f.num + " of " + state + " on " + this);
            }
            long sn = state.getSeqNum();
            if (sn > highest)
                highest = sn;
        }
        if (highest >= 0)
            highestSeqNumAcked(highest);
    }

    /**
     * Note that we just sent the SessionConfirmed packets
     * and save them for retransmission.
     * This is only called the first time.
     * For retransmit see allocateSend() above.
     */
    synchronized void confirmedPacketsSent(byte[][] data) {
        if (_sessConfForReTX == null)
            _sessConfForReTX = data;
        _sessConfSentTime = _context.clock().now();
        _sessConfSentCount++;
    }

    /**
     * @return null if not sent or already got the ack
     */
    private synchronized UDPPacket[] getRetransmitSessionConfirmedPackets() {
        if (_sessConfForReTX == null)
            return null;
        UDPPacket[] rv = new UDPPacket[_sessConfForReTX.length];
        InetAddress addr = getRemoteIPAddress();
        for (int i = 0; i < rv.length; i++) {
            UDPPacket packet = UDPPacket.acquire(_context, false);
            rv[i] = packet;
            DatagramPacket pkt = packet.getPacket();
            byte data[] = pkt.getData();
            int off = pkt.getOffset();
            System.arraycopy(_sessConfForReTX[i], 0, data, off, _sessConfForReTX[i].length);
            pkt.setLength(_sessConfForReTX.length);
            pkt.setAddress(addr);
            pkt.setPort(_remotePort);
            packet.setMessageType(PacketBuilder2.TYPE_CONF);
            packet.setPriority(PacketBuilder2.PRIORITY_HIGH);
        }
        return rv;
    }

    /**
     *  Flag byte to be sent in header
     *
     *  @since 0.9.56, public since 0.9.57 for SSU2Sender interface
     */
    public byte getFlags() {
        return shouldRequestImmediateAck() ? (byte) 0x01 : 0;
    }

    /**
     *  A timer to send an ack-only packet.
     */
    private class ACKTimer extends SimpleTimer2.TimedEvent {

        /**
         *  Caller must schedule
         */
        public ACKTimer() {
            super(_context.simpleTimer2());
        }

        /**
         *  Ack soon, based on the current RTT
         *
         *  @since 0.9.56
         */
        public void schedule() {
            long delta = Math.max(10, Math.min(_rtt/6, ACK_FREQUENCY));
            if (_log.shouldDebug())
                _log.debug("Sending delayed ack in " + delta + ": " + PeerState2.this);
            reschedule(delta, true);
        }

        /**
         *  Ack almost immediately
         *
         *  @since 0.9.56
         */
        public void scheduleImmediate() {
            _wantACKSendSince = _context.clock().now();
            long delta = Math.min(_rtt/16, 5);
            if (_log.shouldDebug())
                _log.debug("Sending immediate ack in " + delta + ": " + PeerState2.this);
            reschedule(delta, true);
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
