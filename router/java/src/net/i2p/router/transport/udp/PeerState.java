package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.udp.InboundMessageFragments.ModifiableLong;
import net.i2p.router.util.CachedIteratorCollection;
import net.i2p.router.util.CoDelPriorityBlockingQueue;
import net.i2p.router.util.PriBlockingQueue;
import net.i2p.util.BandwidthEstimator;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Contain all of the state about a UDP connection to a peer.
 * This is instantiated only after a connection is fully established.
 *
 * Public only for UI peers page. Not a public API, not for external use.
 *
 */
public class PeerState {
    protected final RouterContext _context;
    protected final Log _log;
    /**
     * The peer are we talking to.  This should be set as soon as this
     * state is created if we are initiating a connection, but if we are
     * receiving the connection this will be set only after the connection
     * is established.
     */
    protected final Hash _remotePeer;
    /**
     * The AES key used to verify packets, set only after the connection is
     * established.
     */
    private final SessionKey _currentMACKey;
    /**
     * The AES key used to encrypt/decrypt packets, set only after the
     * connection is established.
     */
    private final SessionKey _currentCipherKey;
    /**
     * The pending AES key for verifying packets if we are rekeying the
     * connection, or null if we are not in the process of rekeying.
     */
    private SessionKey _nextMACKey;

    /** when were the current cipher and MAC keys established/rekeyed? */
    protected final long _keyEstablishedTime;

    /**
     *  How far off is the remote peer from our clock, in milliseconds?
     *  A positive number means our clock is ahead of theirs.
     */
    private long _clockSkew;
    private final Object _clockSkewLock = new Object();

    /** when did we last send them a packet? */
    private long _lastSendTime;
    /** when did we last send them a message that was ACKed */
    private long _lastSendFullyTime;
    /** when did we last send them a ping? */
    private long _lastPingTime;
    /** when did we last receive a packet from them? */
    private long _lastReceiveTime;
    /** how many consecutive messages have we sent and not received an ACK to */
    private int _consecutiveFailedSends;

    /** when did we last have a failed send (beginning of period) */
    // private long _lastFailedSendPeriod;

    /**
     *  Set of messageIds (Long) that we have received but not yet sent
     *  Since even with the smallest MTU we can fit 131 acks in a message,
     *  we are unlikely to get backed up on acks, so we don't keep
     *  them in any particular order.
     */
    private final Set<Long> _currentACKs;

    /**
     * list of the most recent messageIds (Long) that we have received and sent
     * an ACK for.  We keep a few of these around to retransmit with _currentACKs,
     * hopefully saving some spurious retransmissions
     */
    private final Queue<ResendACK> _currentACKsResend;

    /** when did we last send ACKs to the peer? */
    protected volatile long _lastACKSend;
    /** when did we decide we need to ACK to this peer? */
    protected volatile long _wantACKSendSince;
    /** have we received a packet with the ECN bit set in the current second? */
    private boolean _currentSecondECNReceived;
    /**
     * have all of the packets received in the current second requested that
     * the previous second's ACKs be sent?
     */
    //private boolean _remoteWantsPreviousACKs;
    /** how many bytes should we send to the peer in a second */
    private int _sendWindowBytes;
    /** how many bytes can we send to the peer in the current second */
    private int _sendWindowBytesRemaining;
    private final Object _sendWindowBytesRemainingLock = new Object();
    private final BandwidthEstimator _bwEstimator;
    // smoothed value, for display only
    private int _receiveBps;
    private int _receiveBytes;
    private long _receivePeriodBegin;
    private volatile long _lastCongestionOccurred;
    /**
     * when sendWindowBytes is below this, grow the window size quickly,
     * but after we reach it, grow it slowly
     *
     */
    private volatile int _slowStartThreshold;
    /** what IP is the peer sending and receiving packets on? */
    protected final byte[] _remoteIP;
    /** cached IP address */
    protected volatile InetAddress _remoteIPAddress;
    /** what port is the peer sending and receiving packets on? */
    protected volatile int _remotePort;
    /** cached RemoteHostId, used to find the peerState by remote info */
    protected volatile RemoteHostId _remoteHostId;

    /** if we need to contact them, do we need to talk to an introducer? */
    //private boolean _remoteRequiresIntroduction;

    /**
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer
     */
    private long _weRelayToThemAs;
    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     */
    private long _theyRelayToUsAs;
    /** what is the largest packet we can currently send to the peer? */
    protected int _mtu;
    private int _mtuReceive;
    /** what is the largest packet we will ever send to the peer? */
    private int _largeMTU;
    private final int _minMTU;
    /* how many consecutive packets at or under the min MTU have been received */
    private long _consecutiveSmall;
    private int _mtuIncreases;
    private int _mtuDecreases;
    /** current round trip time estimate */
    protected int _rtt;
    /** smoothed mean deviation in the rtt */
    private int _rttDeviation;
    /** current retransmission timeout */
    private int _rto;

    /** how many packets will be considered within the retransmission rate calculation */
    static final long RETRANSMISSION_PERIOD_WIDTH = 100;

    private int _messagesReceived;
    private int _messagesSent;
    private int _packetsTransmitted;
    /** how many packets were retransmitted within the last RETRANSMISSION_PERIOD_WIDTH packets */
    private int _packetsRetransmitted;
    private long _nextSequenceNumber;
    private final AtomicBoolean _fastRetransmit = new AtomicBoolean();

    /** how many dup packets were received within the last RETRANSMISSION_PERIOD_WIDTH packets */
    protected int _packetsReceivedDuplicate;
    private int _packetsReceived;
    private boolean _mayDisconnect;

    /** list of InboundMessageState for active message */
    protected final Map<Long, InboundMessageState> _inboundMessages;

    /**
     *  Mostly messages that have been transmitted and are awaiting acknowledgement,
     *  although there could be some that have not been sent yet.
     */
    private final CachedIteratorCollection<OutboundMessageState> _outboundMessages;

    /**
     *  Priority queue of messages that have not yet been sent.
     *  They are taken from here and put in _outboundMessages.
     */
    //private final CoDelPriorityBlockingQueue<OutboundMessageState> _outboundQueue;
    private final PriBlockingQueue<OutboundMessageState> _outboundQueue;
    /** Message ID to sequence number */
    private final Map<Integer, Long> _ackedMessages;

    /** when the retransmit timer is about to trigger */
    private long _retransmitTimer;

    protected final UDPTransport _transport;

    /** have we migrated away from this peer to another newer one? */
    private volatile boolean _dead;

    /** The minimum number of outstanding messages (NOT fragments/packets) */
    private static final int MIN_CONCURRENT_MSGS = 8;
    /** @since 0.9.42 */
    private static final int INIT_CONCURRENT_MSGS = 20;
    /** how many concurrent outbound messages do we allow OutboundMessageFragments to send
        This counts full messages, NOT fragments (UDP packets)
     */
    private int _concurrentMessagesAllowed = INIT_CONCURRENT_MSGS;
    /** how many concurrency rejections have we had in a row */
    private int _consecutiveRejections;
    /** is it inbound? **/
    protected final boolean _isInbound;
    /** Last time it was made an introducer **/
    private long _lastIntroducerTime;

    private static final int MAX_SEND_WINDOW_BYTES = 1024*1024;

    /**
     *  Was 32 before 0.9.2, but since the streaming lib goes up to 128,
     *  we would just drop our own msgs right away during slow start.
     *  May need to adjust based on memory.
     */
    private static final int MAX_SEND_MSGS_PENDING = 128;

    /**
     * IPv4 Min MTU
     *
     * 596 gives us 588 IP byes, 568 UDP bytes, and with an SSU data message,
     * 522 fragment bytes, which is enough to send a tunnel data message in 2
     * packets. A tunnel data message sent over the wire is 1044 bytes, meaning
     * we need 522 fragment bytes to fit it in 2 packets - add 46 for SSU, 20
     * for UDP, and 8 for IP, giving us 596.  round up to mod 16, giving a total
     * of 608
     *
     * Well, we really need to count the acks as well, especially
     * 1 + (4 * MAX_RESEND_ACKS_SMALL) which can take up a significant amount of space.
     * We reduce the max acks when using the small MTU but it may not be enough...
     *
     * Goal: VTBM msg fragments 2646 / (620 - 87) fits nicely.
     *
     * Assuming that we can enforce an MTU correctly, this % 16 should be 12,
     * as the IP/UDP header is 28 bytes and data max should be mulitple of 16 for padding efficiency,
     * and so PacketBuilder.buildPacket() works correctly.
     */
    public static final int MIN_MTU = 620;

    /**
     * IPv6/UDP header is 48 bytes, so we want MTU % 16 == 0.
     */
    public static final int MIN_IPV6_MTU = 1280;
    public static final int MAX_IPV6_MTU = 1488;
    private static final int DEFAULT_MTU = MIN_MTU;

    /**
     * IPv4 Max MTU
     *
     * based on measurements, 1350 fits nearly all reasonably small I2NP messages
     * (larger I2NP messages may be up to 1900B-4500B, which isn't going to fit
     * into a live network MTU anyway)
     *
     * TODO
     * VTBM is 2646, it would be nice to fit in two large
     * 2646 / 2 = 1323
     * 1323 + 74 + 46 + 1 + (4 * 9) = 1480
     * So why not make it 1492 (old ethernet is 1492, new is 1500)
     * Changed to 1492 in 0.8.9
     *
     * BUT through 0.8.11,
     * size estimate was bad, actual packet was up to 48 bytes bigger
     * To be figured out. Curse the ACKs.
     * Assuming that we can enforce an MTU correctly, this % 16 should be 12,
     * as the IP/UDP header is 28 bytes and data max should be mulitple of 16 for padding efficiency,
     * and so PacketBuilder.buildPacket() works correctly.
     */
    public static final int LARGE_MTU = 1484;

    /**
     *  Max of IPv4 and IPv6 max MTUs
     *  @since 0.9.28
     */
    public static final int MAX_MTU = Math.max(LARGE_MTU, MAX_IPV6_MTU);

    // amount to adjust up or down in adjustMTU()
    // should be multiple of 16, at least for SSU 1
    private static final int MTU_STEP = 64;

    private static final int MIN_RTO = 1000;
    private static final int INIT_RTO = 1000;
    private static final int INIT_RTT = 0;
    private static final int MAX_RTO = 60*1000;
    /** how frequently do we want to send ACKs to a peer? */
    protected static final int ACK_FREQUENCY = 150;
    protected static final int CLOCK_SKEW_FUDGE = (ACK_FREQUENCY * 2) / 3;

    /**
     *  The max number of acks we save to send as duplicates
     */
    private static final int MAX_RESEND_ACKS = 32;
    /**
     *  The max number of duplicate acks sent in each ack-only messge.
     *  Doesn't really matter, we have plenty of room...
     *  @since 0.7.13
     */
    private static final int MAX_RESEND_ACKS_LARGE = MAX_RESEND_ACKS * 2 / 3;
    /** for small MTU */
    private static final int MAX_RESEND_ACKS_SMALL = MAX_RESEND_ACKS * 2 / 5;

    private static final long RESEND_ACK_TIMEOUT = 60*1000;

    /** if this many acks arrive out of order, fast rtx */
    private static final int FAST_RTX_ACKS = 3;

    /**
     *  SSU 1 only.
     *
     *  @param rtt from the EstablishState, or 0 if not available
     */
    public PeerState(RouterContext ctx, UDPTransport transport,
                     byte[] remoteIP, int remotePort, Hash remotePeer, boolean isInbound, int rtt,
                     SessionKey cipherKey, SessionKey macKey) {
        _context = ctx;
        _log = ctx.logManager().getLog(PeerState.class);
        _transport = transport;
        long now = ctx.clock().now();
        _keyEstablishedTime = now;
        _lastSendTime = now;
        _lastReceiveTime = now;
        _currentACKs = new ConcurrentHashSet<Long>();
        _currentACKsResend = new LinkedBlockingQueue<ResendACK>();
        _slowStartThreshold = MAX_SEND_WINDOW_BYTES/2;
        _receivePeriodBegin = now;
        _remotePort = remotePort;
        if (remoteIP.length == 4) {
            _mtu = DEFAULT_MTU;
            _mtuReceive = DEFAULT_MTU;
            _largeMTU = transport.getMTU(false);
            _minMTU = MIN_MTU;
        } else {
            _mtu = MIN_IPV6_MTU;
            _mtuReceive = MIN_IPV6_MTU;
            _largeMTU = transport.getMTU(true);
            _minMTU = MIN_IPV6_MTU;
        }
        // RFC 5681 sec. 3.1
        if (_mtu > 1095)
            _sendWindowBytes = 3 * _mtu;
        else
            _sendWindowBytes = 4 * _mtu;
        _sendWindowBytesRemaining = _sendWindowBytes;

        _rto = INIT_RTO;
        _rtt = INIT_RTT;
        if (rtt > 0)
            recalculateTimeouts(rtt);
        else
            _rttDeviation = _rtt;

        _inboundMessages = new HashMap<Long, InboundMessageState>(8);
        _outboundMessages = new CachedIteratorCollection<OutboundMessageState>();
        //_outboundQueue = new CoDelPriorityBlockingQueue(ctx, "UDP-PeerState", 32);
        _outboundQueue = new PriBlockingQueue<OutboundMessageState>(ctx, "UDP-PeerState", 32);
        _ackedMessages = new AckedMessages();
        // all createRateStat() moved to EstablishmentManager
        _remoteIP = remoteIP;
        _remotePeer = remotePeer;
        _isInbound = isInbound;
        _remoteHostId = new RemoteHostId(remoteIP, remotePort);
        _bwEstimator = new SimpleBandwidthEstimator(ctx, this);
        _currentCipherKey = cipherKey;
        _currentMACKey = macKey;
    }

    /**
     *  For SSU2
     *
     *  @since 0.9.54
     */
    protected PeerState(RouterContext ctx, UDPTransport transport,
                        InetSocketAddress addr, Hash remotePeer, boolean isInbound, int rtt) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        long now = ctx.clock().now();
        _keyEstablishedTime = now;
        _lastSendTime = now;
        _lastReceiveTime = now;
        _slowStartThreshold = MAX_SEND_WINDOW_BYTES/2;
        _receivePeriodBegin = now;
        _remoteIP = addr.getAddress().getAddress();
        _remotePort = addr.getPort();
        _mtu = PeerState2.MIN_MTU;
        _mtuReceive = PeerState2.MIN_MTU;
        if (_remoteIP.length == 4) {
            _largeMTU = transport.getSSU2MTU(false);
        } else {
            _largeMTU = transport.getSSU2MTU(true);
        }
        _minMTU = PeerState2.MIN_MTU;
        // RFC 5681 sec. 3.1
        _sendWindowBytes = 3 * _mtu;
        _sendWindowBytesRemaining = _sendWindowBytes;

        _rto = INIT_RTO;
        _rtt = INIT_RTT;
        if (rtt > 0)
            recalculateTimeouts(rtt);
        else
            _rttDeviation = _rtt;

        _inboundMessages = new HashMap<Long, InboundMessageState>(8);
        _outboundMessages = new CachedIteratorCollection<OutboundMessageState>();
        _outboundQueue = new PriBlockingQueue<OutboundMessageState>(ctx, "UDP-PeerState", 32);
        _remotePeer = remotePeer;
        _isInbound = isInbound;
        _remoteHostId = new RemoteHostId(_remoteIP, _remotePort);
        _bwEstimator = new SimpleBandwidthEstimator(ctx, this);
        // Unused in SSU2
        _currentACKs = null;
        _currentACKsResend = null;
        _ackedMessages = null;
        _currentCipherKey = null;
        _currentMACKey = null;
    }
    
    /**
     * @since 0.9.54
     */
    public int getVersion() { return 1; }

    /**
     *  Caller should sync; UDPTransport must remove and add to peersByRemoteHost map
     *  @since 0.9.3
     */
    void changePort(int newPort) {
        if (newPort != _remotePort) {
            _remoteHostId = new RemoteHostId(_remoteIP, newPort);
            _remotePort = newPort;
        }
    }

    /**
     * The peer are we talking to. Non-null.
     */
    public Hash getRemotePeer() { return _remotePeer; }
    /**
     * The AES key used to verify packets, set only after the connection is
     * established.
     *
     * SSU 1 only.
     */
    SessionKey getCurrentMACKey() { return _currentMACKey; }
    /**
     * The AES key used to encrypt/decrypt packets, set only after the
     * connection is established.
     *
     * SSU 1 only.
     */
    SessionKey getCurrentCipherKey() { return _currentCipherKey; }

    /**
     * The pending AES key for verifying packets if we are rekeying the
     * connection, or null if we are not in the process of rekeying.
     *
     * SSU 1 only.
     *
     * @return null always, rekeying unimplemented
     */
    SessionKey getNextMACKey() { return _nextMACKey; }

    /**
     * The pending AES key for encrypting/decrypting packets if we are
     * rekeying the connection, or null if we are not in the process
     * of rekeying.
     *
     * SSU 1 only.
     *
     * @return null always, rekeying unimplemented
     */
    SessionKey getNextCipherKey() { return null; }

    /**
     * When were the current cipher and MAC keys established/rekeyed?
     * This is the connection uptime.
     */
    public long getKeyEstablishedTime() { return _keyEstablishedTime; }

    /**
     *  How far off is the remote peer from our clock, in milliseconds?
     *  A positive number means our clock is ahead of theirs.
     */
    public long getClockSkew() { return _clockSkew ; }

    /** when did we last send them a packet? */
    public long getLastSendTime() { return _lastSendTime; }
    /** when did we last send them a message that was ACKed? */
    public long getLastSendFullyTime() { return _lastSendFullyTime; }
    /** when did we last receive a packet from them? */
    public long getLastReceiveTime() { return _lastReceiveTime; }
    /** how many seconds have we sent packets without any ACKs received? */
    public int getConsecutiveFailedSends() { return _consecutiveFailedSends; }

    /**
     *  how many bytes should we send to the peer in a second
     *  1st stat in CWND column, otherwise unused,
     *  candidate for removal
     */
    public int getSendWindowBytes() {
        synchronized(_outboundMessages) {
            return _sendWindowBytes;
        }
    }

    /** how many bytes can we send to the peer in the current second */
    public int getSendWindowBytesRemaining() {
        synchronized(_sendWindowBytesRemainingLock) {
            return _sendWindowBytesRemaining;
        }
    }

    /** what IP is the peer sending and receiving packets on? */
    public byte[] getRemoteIP() { return _remoteIP; }

    /**
     *  @return may be null if IP is invalid
     */
    public InetAddress getRemoteIPAddress() {
        if (_remoteIPAddress == null) {
            try {
                _remoteIPAddress = InetAddress.getByAddress(_remoteIP);
            } catch (UnknownHostException uhe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Invalid IP? ", uhe);
            }
        }
        return _remoteIPAddress;
    }

    /** what port is the peer sending and receiving packets on? */
    public int getRemotePort() { return _remotePort; }

    /**
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer
     * @return 0 (no relay) if unset previously
     */
    public long getWeRelayToThemAs() { return _weRelayToThemAs; }

    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     * @return 0 (no relay) if unset previously
     */
    public long getTheyRelayToUsAs() { return _theyRelayToUsAs; }

    /** what is the largest packet we can send to the peer? */
    public int getMTU() { return _mtu; }

    /**
     *  Estimate how large the other side's MTU is.
     *  This could be wrong.
     *  It is used only for the HTML status.
     */
    public int getReceiveMTU() { return _mtuReceive; }

    /**
     *  Update the moving-average clock skew based on the current difference.
     *  The raw skew will be adjusted for RTT/2 here.
     *  A positive number means our clock is ahead of theirs.
     *  @param skew milliseconds, NOT adjusted for RTT.
     */
    void adjustClockSkew(long skew) {
        // the real one-way delay is much less than RTT / 2, due to ack delays,
        // so add a fudge factor
        long actualSkew = skew + CLOCK_SKEW_FUDGE - (_rtt / 2);
        // First time...
        // This is important because we need accurate
        // skews right from the beginning, since the median is taken
        // and fed to the timestamper. Lots of connections only send a few packets.
        if (_packetsReceived <= 1) {
            synchronized(_clockSkewLock) {
                _clockSkew = actualSkew;
            }
            return;
        }
        double adj = 0.1 * actualSkew;
        synchronized(_clockSkewLock) {
            _clockSkew = (long) (0.9*_clockSkew + adj);
        }
    }

    /** when did we last send them a packet? */
    void setLastSendTime(long when) { _lastSendTime = when; }
    /** when did we last receive a packet from them? */
    void setLastReceiveTime(long when) { _lastReceiveTime = when; }

    /**
     *  Note ping sent. Does not update last send time.
     *  @since 0.9.3
     */
    void setLastPingTime(long when) { _lastPingTime = when; }

    /**
     *  Latest of last sent, last ACK, last ping
     *  @since 0.9.3
     */
    long getLastSendOrPingTime() {
        return Math.max(Math.max(_lastSendTime, _lastACKSend), _lastPingTime);
    }

    /**
     * The Westwood+ bandwidth estimate
     * @return the smoothed send transfer rate
     */
    public int getSendBps() { return (int) (_bwEstimator.getBandwidthEstimate() * 1000); }

    /**
     * An approximation, for display only
     * @return the smoothed receive transfer rate
     */
    public synchronized int getReceiveBps() { return _receiveBps; }

    int incrementConsecutiveFailedSends() {
        synchronized(_outboundMessages) {
            //long now = _context.clock().now()/(10*1000);
            //if (_lastFailedSendPeriod >= now) {
            //    // ignore... too fast
            //} else {
            //    _lastFailedSendPeriod = now;
                _consecutiveFailedSends++;
            //}
            return _consecutiveFailedSends;
        }
    }

    public long getInactivityTime() {
        long now = _context.clock().now();
        long lastActivity = Math.max(_lastReceiveTime, _lastSendFullyTime);
        return now - lastActivity;
    }

    /**
     * Decrement the remaining bytes in the current period's window,
     * returning true if the full size can be decremented, false if it
     * cannot.  If it is not decremented, the window size remaining is
     * not adjusted at all.
     *
     *  Caller should synch
     */
    private boolean allocateSendingBytes(OutboundMessageState state, long now) {
        int messagePushCount = state.getPushCount();
        if (messagePushCount == 0 && _outboundMessages.size() > _concurrentMessagesAllowed) {
            _consecutiveRejections++;
            _context.statManager().addRateData("udp.rejectConcurrentActive", _outboundMessages.size(), _consecutiveRejections);
            return false;
        }
        final int sendRemaining = getSendWindowBytesRemaining();
        if (sendRemaining <= fragmentOverhead())
            return false;

        int size = state.getSendSize(sendRemaining);
        if (size > 0) {
            if (messagePushCount == 0) {
                _context.statManager().addRateData("udp.allowConcurrentActive", _outboundMessages.size(), _concurrentMessagesAllowed);
                if (_consecutiveRejections > 0)
                    _context.statManager().addRateData("udp.rejectConcurrentSequence", _consecutiveRejections, _outboundMessages.size());
                _consecutiveRejections = 0;
            }
            synchronized(_sendWindowBytesRemainingLock) {
                _sendWindowBytesRemaining -= size;
            }
            _lastSendTime = now;
            return true;
        } else {
            return false;
        }
    }

    /**
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer
     * @param tag 1 to Integer.MAX_VALUE, or 0 if relaying disabled
     */
    void setWeRelayToThemAs(long tag) { _weRelayToThemAs = tag; }

    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     * @param tag 1 to Integer.MAX_VALUE, or 0 if relaying disabled
     */
    void setTheyRelayToUsAs(long tag) { _theyRelayToUsAs = tag; }

    /**
     *  stat in SST column, otherwise unused,
     *  candidate for removal
     */
    public int getSlowStartThreshold() { return _slowStartThreshold; }

    /**
     *  2nd stat in CWND column, otherwise unused,
     *  candidate for removal
     */
    public int getConcurrentSends() {
        synchronized(_outboundMessages) {
            return _outboundMessages.size();
        }
    }

    /**
     *  3rd stat in CWND column, otherwise unused,
     *  candidate for removal
     */
    public int getConcurrentSendWindow() {
        synchronized(_outboundMessages) {
            return _concurrentMessagesAllowed;
        }
    }

    /**
     *  4th stat in CWND column, otherwise unused,
     *  candidate for removal
     */
    public int getConsecutiveSendRejections() {
        synchronized(_outboundMessages) {
            return _consecutiveRejections;
        }
    }

    public boolean isInbound() { return _isInbound; }

    /** @since IPv6 */
    public boolean isIPv6() {
        return _remoteIP.length == 16;
    }

    /** the last time we used them as an introducer, or 0 */
    long getIntroducerTime() { return _lastIntroducerTime; }

    /** set the last time we used them as an introducer to now */
    void setIntroducerTime() { _lastIntroducerTime = _context.clock().now(); }

    /**
     *  We received the message specified completely.
     *  @param bytes if less than or equal to zero, message is a duplicate.
     */
    synchronized void messageFullyReceived(Long messageId, int bytes) {
        if (bytes > 0) {
            _receiveBytes += bytes;
            _messagesReceived++;
        } else {
                _packetsReceivedDuplicate++;
        }

        long now = _context.clock().now();
        long duration = now - _receivePeriodBegin;
        if (duration >= 1000) {
            _receiveBps = (int)(0.9f*_receiveBps + 0.1f*(_receiveBytes * (1000f/duration)));
            _receiveBytes = 0;
            _receivePeriodBegin = now;
        }
        // null for PeerState2
        if (_currentACKs != null)
            _currentACKs.add(messageId);
        messagePartiallyReceived(now);
    }

    /**
     *  We received a partial message, or we want to send some acks.
     */
    void messagePartiallyReceived() {
        messagePartiallyReceived(_context.clock().now());
    }

    /**
     *  We received a partial message, or we want to send some acks.
     *  @since 0.9.52
     */
    protected synchronized void messagePartiallyReceived(long now) {
        if (_wantACKSendSince <= 0) {
            _wantACKSendSince = now;
            new ACKTimer();
        }
    }

    /**
     * Fetch the internal id (Long) to InboundMessageState for incomplete inbound messages.
     * Access to this map must be synchronized explicitly!
     */
    Map<Long, InboundMessageState> getInboundMessages() { return _inboundMessages; }

    /**
     * Expire partially received inbound messages, returning how many are still pending.
     * This should probably be fired periodically, in case a peer goes silent and we don't
     * try to send them any messages (and don't receive any messages from them either)
     *
     */
    int expireInboundMessages() {
        int rv = 0;

        synchronized (_inboundMessages) {
            for (Iterator<InboundMessageState> iter = _inboundMessages.values().iterator(); iter.hasNext(); ) {
                InboundMessageState state = iter.next();
                if (state.isExpired() || _dead) {
                    iter.remove();
                    // state.releaseResources() ??
                } else {
                    if (state.isComplete()) {
                        _log.error("inbound message is complete, but wasn't handled inline? " + state + " with " + this);
                        iter.remove();
                        // state.releaseResources() ??
                    } else {
                        rv++;
                    }
                }
            }
        }
        return rv;
    }

    /**
     * either they told us to back off, or we had to resend to get
     * the data through.
     *  Caller should synch on this
     */
    private void congestionOccurred() {
        long now = _context.clock().now();
        if (_lastCongestionOccurred + _rto > now)
            return; // only shrink once every few seconds
        _lastCongestionOccurred = now;
        // 1. Double RTO and backoff (RFC 6298 section 5.5 & 5.6)
        // 2. cut ssthresh to bandwidth estimate, window to 1 MTU
        // 3. Retransmit up to half of the packets in flight (RFC 6298 section 5.4 and RFC 5681 section 4.3)
        int congestionAt = _sendWindowBytes;
        // If we reduced the MTU, then we won't be able to send any previously-fragmented messages,
        // so set to the max MTU. This is the easiest fix, although it violates the RFC.
        //_sendWindowBytes = _mtu;
        int oldsst = _slowStartThreshold;
        float bwe;
        if (_fastRetransmit.get()) {
            // window and SST set in highestSeqNumAcked()
            bwe = -1;  // for log below
        } else {
            _sendWindowBytes = getVersion() == 2 ? PeerState2.MAX_MTU : (isIPv6() ? MAX_IPV6_MTU : LARGE_MTU);
            bwe = _bwEstimator.getBandwidthEstimate();
            _slowStartThreshold = Math.max( (int)(bwe * _rtt), 2 * _mtu);
        }

        int oldRto = _rto;
        long oldTimer = _retransmitTimer - now;
        _rto = Math.min(MAX_RTO, Math.max(MIN_RTO, _rto << 1 ));
        _retransmitTimer = now + _rto;
        if (_log.shouldInfo())
            _log.info(_remotePeer + " Congestion, RTO: " + oldRto + " -> " + _rto + " timer: " + oldTimer + " -> " + _rto +
                                    " window: " + congestionAt + " -> " + _sendWindowBytes +
                                    " SST: " + oldsst + " -> " + _slowStartThreshold +
                                    " FRTX? " + _fastRetransmit +
                                    " BWE: " + DataHelper.formatSize2Decimal((long) (bwe * 1000), false) + "bps");
    }

    /**
     * Grab a list of message ids (Long) that we want to send to the remote
     * peer, regardless of the packet size, but don't remove it from our
     * "want to send" list.  If the message id is transmitted to the peer,
     * removeACKMessage(Long) should be called.
     *
     * The returned list contains acks not yet sent only.
     * The caller should NOT transmit all of them all the time,
     * even if there is room,
     * or the packets will have way too much overhead.
     *
     * SSU 1 only.
     *
     * @return a new list, do as you like with it
     */
    List<Long> getCurrentFullACKs() {
            // no such element exception seen here
            List<Long> rv = new ArrayList<Long>(_currentACKs);
            return rv;
    }

    /**
     * Grab a list of message ids (Long) that we want to send to the remote
     * peer, regardless of the packet size, but don't remove it from our
     * "want to send" list.
     *
     * The returned list contains
     * a random assortment of acks already sent.
     * The caller should NOT transmit all of them all the time,
     * even if there is room,
     * or the packets will have way too much overhead.
     *
     * SSU 1 only.
     *
     * @return a new list, do as you like with it
     * @since 0.8.12 was included in getCurrentFullACKs()
     */
    List<Long> getCurrentResendACKs() {
            int sz = _currentACKsResend.size();
            List<Long> randomResends = new ArrayList<Long>(sz);
            if (sz > 0) {
                long cutoff = _context.clock().now() - RESEND_ACK_TIMEOUT;
                int i = 0;
                for (Iterator<ResendACK> iter = _currentACKsResend.iterator(); iter.hasNext(); ) {
                    ResendACK rack  = iter.next();
                    if (rack.time > cutoff && i++ < MAX_RESEND_ACKS) {
                        randomResends.add(rack.id);
                    } else {
                        iter.remove();
                        if (_log.shouldDebug())
                            _log.debug("Expired ack " + rack.id + " sent " + (cutoff + RESEND_ACK_TIMEOUT - rack.time) +
                                      " ago, now " + _currentACKsResend.size()  + " resend acks");
                    }
                }
                if (i > 1)
                    Collections.shuffle(randomResends, _context.random());
            }
            return randomResends;
    }

    /**
     * The ack was sent.
     * Side effect - sets _lastACKSend
     *
     * SSU 1 only.
     */
    void removeACKMessage(Long messageId) {
            boolean removed = _currentACKs.remove(messageId);
            if (removed) {
                // only add if removed from current, as this may be called for
                // acks already in _currentACKsResend.
                _currentACKsResend.offer(new ResendACK(messageId, _context.clock().now()));
                // trim happens in getCurrentResendACKs above
                if (_log.shouldDebug())
                    _log.debug("Sent ack " + messageId + " now " + _currentACKs.size() + " current and " +
                              _currentACKsResend.size() + " resend acks");
            }
            // should we only do this if removed?
            _lastACKSend = _context.clock().now();
    }

    /**
     * Only called by ACKTimer with alwaysIncludeRetransmissions = false.
     * So this is only for ACK-only packets, so all the size limiting is useless.
     * FIXME.
     *
     * Caller should sync on this.
     *
     * Side effect - sets _lastACKSend to now if rv is non-empty.
     * Side effect - sets _wantACKSendSince to 0 if _currentACKs is now empty.
     *
     * SSU 1 only.
     *
     * @return non-null, possibly empty
     */
    private List<ACKBitfield> retrieveACKBitfields(boolean alwaysIncludeRetransmissions) {
        int bytesRemaining = countMaxACKData();

            // Limit the overhead of all the resent acks when using small MTU
            // 64 bytes in a 608-byte packet is too much...
            // Send a random subset of all the queued resend acks.
            int resendSize = _currentACKsResend.size();
            int maxResendAcks;
            if (bytesRemaining < MIN_MTU)
                maxResendAcks = MAX_RESEND_ACKS_SMALL;
            else
                maxResendAcks = MAX_RESEND_ACKS_LARGE;
            List<ACKBitfield> rv = new ArrayList<ACKBitfield>(maxResendAcks);

            // save to add to currentACKsResend later so we don't include twice
            List<Long> currentACKsRemoved = new ArrayList<Long>(_currentACKs.size());
            // As explained above, we include the acks in any order
            // since we are unlikely to get backed up -
            // just take them using the Set iterator.
            Iterator<Long> iter = _currentACKs.iterator();
            while (bytesRemaining >= 4 && iter.hasNext()) {
                Long val = iter.next();
                iter.remove();
                long id = val.longValue();
                rv.add(new FullACKBitfield(id));
                currentACKsRemoved.add(val);
                bytesRemaining -= 4;
            }
            if (_currentACKs.isEmpty())
                _wantACKSendSince = 0;
            if (alwaysIncludeRetransmissions || !rv.isEmpty()) {
                List<Long> randomResends = getCurrentResendACKs();
                // now repeat by putting in some old ACKs
                // randomly selected from the Resend queue.
                // Maybe we should only resend each one a certain number of times...
                int oldIndex = Math.min(resendSize, maxResendAcks);
                iter = randomResends.iterator();
                while (bytesRemaining >= 4 && oldIndex-- > 0 && iter.hasNext()) {
                    Long cur = iter.next();
                    long c = cur.longValue();
                    FullACKBitfield bf = new FullACKBitfield(c);
                    // try to avoid duplicates ??
                    // ACKsResend is not checked for dups at add time
                    //if (rv.contains(bf)) {
                    //    iter.remove();
                    //} else {
                        rv.add(bf);
                        bytesRemaining -= 4;
                    //}
                }
                if (!currentACKsRemoved.isEmpty()) {
                    long now = _context.clock().now();
                    for (Long val : currentACKsRemoved) {
                        _currentACKsResend.offer(new ResendACK(val, now));
                    }
                    // trim happens in getCurrentResendACKs above
                }
            }

        int partialIncluded = 0;
        if (bytesRemaining > 4) {
            // ok, there's room to *try* to fit in some partial ACKs, so
            // we should try to find some packets to partially ACK
            // (preferably the ones which have the most received fragments)
            List<ACKBitfield> partial = new ArrayList<ACKBitfield>();
            fetchPartialACKs(partial);
            // we may not be able to use them all, but lets try...
            for (int i = 0; (bytesRemaining > 4) && (i < partial.size()); i++) {
                ACKBitfield bitfield = partial.get(i);
                int bytes = (bitfield.fragmentCount() / 7) + 1;
                if (bytesRemaining > bytes + 4) { // msgId + bitfields
                    rv.add(bitfield);
                    bytesRemaining -= bytes + 4;
                    partialIncluded++;
                } else {
                    // continue on to another partial, in case there's a
                    // smaller one that will fit
                }
            }
        }

        if (!rv.isEmpty())
            _lastACKSend = _context.clock().now();
        if (partialIncluded > 0)
            _context.statManager().addRateData("udp.sendACKPartial", partialIncluded, rv.size() - partialIncluded);
        return rv;
    }

    /**
     *  SSU 1 only.
     *
     *  @param rv out parameter, populated with true partial ACKBitfields.
     *            no full bitfields are included.
     */
    void fetchPartialACKs(List<ACKBitfield> rv) {
        List<InboundMessageState> states = null;
        int curState = 0;
        synchronized (_inboundMessages) {
            int numMessages = _inboundMessages.size();
            if (numMessages <= 0)
                return;
            // todo: make this a list instead of a map, so we can iterate faster w/out the memory overhead?
            for (Iterator<InboundMessageState> iter = _inboundMessages.values().iterator(); iter.hasNext(); ) {
                InboundMessageState state = iter.next();
                if (state.isExpired()) {
                    //if (_context instanceof RouterContext)
                    //    ((RouterContext)_context).messageHistory().droppedInboundMessage(state.getMessageId(), state.getFrom(), "expired partially received: " + state.toString());
                    iter.remove();
                    // state.releaseResources() ??
                } else {
                    if (!state.isComplete()) {
                        if (states == null)
                            states = new ArrayList<InboundMessageState>(numMessages);
                        states.add(state);
                    }
                }
            }
        }
        if (states != null) {
            for (InboundMessageState ims : states) {
                ACKBitfield abf = ims.createACKBitfield();
                rv.add(abf);
            }
        }
    }

    /**
     *  A dummy "partial" ack which represents a full ACK of a message
     *
     *  SSU 1 only.
     */
    private static class FullACKBitfield implements ACKBitfield {
        private final long _msgId;

        public FullACKBitfield(long id) { _msgId = id; }

        public int fragmentCount() { return 1; }
        public int ackCount() { return 1; }
        public int highestReceived() { return 0; }
        public long getMessageId() { return _msgId; }
        public boolean received(int fragmentNum) { return true; }
        public boolean receivedComplete() { return true; }
        @Override
        public int hashCode() { return (int) _msgId; }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FullACKBitfield)) return false;
            return _msgId == ((ACKBitfield)o).getMessageId();
        }
        @Override
        public String toString() { return "Full ACK " + _msgId; }
    }

    /**
     *  We sent a message which was ACKed containing the given # of bytes.
     *  Caller should synch on this
     */
    private void locked_messageACKed(int bytesACKed, int maxPktSz, long lifetime, int numSends, boolean anyPending, boolean anyQueued) {
        _consecutiveFailedSends = 0;
        // _lastFailedSendPeriod = -1;
        if (numSends < 2) {
            if (_context.random().nextInt(_concurrentMessagesAllowed) <= 0)
                _concurrentMessagesAllowed++;

            if (_sendWindowBytes <= _slowStartThreshold) {
                _sendWindowBytes += bytesACKed;
                synchronized(_sendWindowBytesRemainingLock) {
                    _sendWindowBytesRemaining += bytesACKed;
                }
            } else {
                    float prob = ((float)bytesACKed) / ((float)(_sendWindowBytes<<1));
                    float v = _context.random().nextFloat();
                    if (v < 0) v = 0-v;
                    if (v <= prob) {
                        _sendWindowBytes += bytesACKed;
                        synchronized(_sendWindowBytesRemainingLock) {
                            _sendWindowBytesRemaining += bytesACKed;
                        }
                    }
            }
        } else {
            int allow = _concurrentMessagesAllowed - 1;
            if (allow < MIN_CONCURRENT_MSGS)
                allow = MIN_CONCURRENT_MSGS;
            _concurrentMessagesAllowed = allow;
        }
        if (_sendWindowBytes > MAX_SEND_WINDOW_BYTES)
            _sendWindowBytes = MAX_SEND_WINDOW_BYTES;
        _lastReceiveTime = _context.clock().now();
        _lastSendFullyTime = _lastReceiveTime;

        synchronized(_sendWindowBytesRemainingLock) {
            _sendWindowBytesRemaining += bytesACKed;
            if (_sendWindowBytesRemaining > _sendWindowBytes)
                _sendWindowBytesRemaining = _sendWindowBytes;
        }

        if (numSends < 2) {
            // caller synchs
            recalculateTimeouts(lifetime);
            adjustMTU(maxPktSz, true);
        }

        if (!anyPending) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug(_remotePeer + " nothing pending, cancelling timer");
            _retransmitTimer = 0;
            exitFastRetransmit();
        } else {
            // any time new data gets acked, push out the timer
            long now = _context.clock().now();
            long oldTimer = _retransmitTimer - now;
            _retransmitTimer = now + getRTO();
            if (_log.shouldLog(Log.DEBUG))
               _log.debug(_remotePeer + " ACK, timer: " + oldTimer + " -> " + (_retransmitTimer - now));
        }
        if (anyPending || anyQueued)
            _transport.getOMF().nudge();
    }

    /**
     *  We sent a message which was ACKed containing the given # of bytes.
     */
    private void messageACKed(int bytesACKed, int maxPktSz, long lifetime, int numSends, boolean anyPending, boolean anyQueued) {
        synchronized(this) {
            locked_messageACKed(bytesACKed, maxPktSz, lifetime, numSends, anyPending, anyQueued);
        }
        _bwEstimator.addSample(bytesACKed);
        if (numSends >= 2 && _log.shouldDebug())
            _log.debug(_remotePeer + " acked after numSends=" + numSends + " w/ lifetime=" + lifetime + " and size=" + bytesACKed);
    }

    /** This is the value specified in RFC 2988 */
    private static final float RTT_DAMPENING = 0.125f;

    /**
     *  Adjust the tcp-esque timeouts.
     *  Caller should synch on this
     */
    private void recalculateTimeouts(long lifetime) {
        if (_rtt <= 0) {
            // first measurement
            _rtt = (int) lifetime;
            _rttDeviation = _rtt /  2;
        } else {
            // the rttDev calculation matches that recommended in RFC 2988 (beta = 1/4)
            _rttDeviation = (int)((0.75 * _rttDeviation) + (0.25 * Math.abs(lifetime - _rtt)));
            _rtt = (int)((_rtt * (1.0f - RTT_DAMPENING)) + (RTT_DAMPENING * lifetime));
        }
        // K = 4
        _rto = Math.min(MAX_RTO, Math.max(MIN_RTO, _rtt + (_rttDeviation<<2)));
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Recalculating timeouts w/ lifetime=" + lifetime + ": rtt=" + _rtt
        //               + " rttDev=" + _rttDeviation + " rto=" + _rto);
    }

    /**
     *  Adjust upward if a large packet was successfully sent without retransmission.
     *  Adjust downward if a packet was retransmitted.
     *
     *  Caller should synch on this
     *
     *  @param maxPktSz the largest packet that was sent
     *  @param success was it sent successfully?
     */
    private void adjustMTU(int maxPktSz, boolean success) {
        if (_packetsTransmitted > 0) {
            // heuristic to allow fairly lossy links to use large MTUs
            boolean wantLarge = success &&
                                (float)_packetsRetransmitted / (float)_packetsTransmitted < 0.10f;
            // we only increase if the size was close to the limit
            if (wantLarge) {
                if (_mtu < _largeMTU && maxPktSz > _mtu - (MTU_STEP * 2) &&
                    (_mtuDecreases <= 1 || _context.random().nextInt(_mtuDecreases) <= 0)){
                    _mtu = Math.min(_mtu + MTU_STEP, _largeMTU);
                    _mtuIncreases++;
                    _mtuDecreases = 0;
                    _context.statManager().addRateData("udp.mtuIncrease", _mtuIncreases);
                    if (_log.shouldDebug())
                        _log.debug("Increased MTU after " + maxPktSz + " byte packet acked on " + this);
                }
            } else {
                if (_mtu > _minMTU) {
                    _mtu = Math.max(_mtu - MTU_STEP, _minMTU);
                    _mtuDecreases++;
                    _mtuIncreases = 0;
                    _context.statManager().addRateData("udp.mtuDecrease", _mtuDecreases);
                    if (_log.shouldDebug())
                        _log.debug("Decreased MTU after " + maxPktSz + " byte packet retx on " + this);
                }
            }
        }
    }

    /**
     *  @since 0.9.2
     */
    synchronized void setHisMTU(int mtu) {
        if (mtu <= _minMTU || mtu >= _largeMTU)
            return;
        if (mtu < _largeMTU)
            _largeMTU = mtu;
        if (mtu < _mtu)
            _mtu = mtu;
    }

    /** we are resending a packet, so lets jack up the rto */
    synchronized void messageRetransmitted(int packets, int maxPktSz) {
        _context.statManager().addRateData("udp.congestionOccurred", _sendWindowBytes);
        _context.statManager().addRateData("udp.congestedRTO", _rto, _rttDeviation);
        _packetsRetransmitted += packets;
        congestionOccurred();
        adjustMTU(maxPktSz, false);
    }

    synchronized void packetsTransmitted(int packets) {
        _packetsTransmitted += packets;
    }

    /** how long does it usually take to get a message ACKed? */
    public synchronized int getRTT() { return _rtt; }
    /** how soon should we retransmit an unacked packet? */
    public synchronized int getRTO() { return _rto; }
    /** how skewed are the measured RTTs? */
    public synchronized int getRTTDeviation() { return _rttDeviation; }

    /**
     *  I2NP messages sent.
     *  Does not include duplicates.
     *  As of 0.9.24, incremented when bandwidth is allocated just before sending, not when acked.
     */
    public int getMessagesSent() {
        synchronized (_outboundMessages) {
            return _messagesSent;
        }
    }

    /**
     *  I2NP messages received.
     *  As of 0.9.24, does not include duplicates.
     */
    public synchronized int getMessagesReceived() { return _messagesReceived; }

    public synchronized int getPacketsTransmitted() { return _packetsTransmitted; }
    public synchronized int getPacketsRetransmitted() { return _packetsRetransmitted; }

    public synchronized int getPacketsReceived() { return _packetsReceived; }
    public synchronized int getPacketsReceivedDuplicate() { return _packetsReceivedDuplicate; }

    private static final int MTU_RCV_DISPLAY_THRESHOLD = 20;
    /** 60 */
    private static final int OVERHEAD_SIZE = PacketBuilder.IP_HEADER_SIZE + PacketBuilder.UDP_HEADER_SIZE +
                                             UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
    /** 80 */
    private static final int IPV6_OVERHEAD_SIZE = PacketBuilder.IPV6_HEADER_SIZE + PacketBuilder.UDP_HEADER_SIZE +
                                             UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;

    /**
     *  @param size not including IP header, UDP header, MAC or IV
     */
    synchronized void packetReceived(int size) {
        _packetsReceived++;
        // SSU2 overhead header + MAC == SSU overhead IV + MAC
        if (_remoteIP.length == 4) {
            size += OVERHEAD_SIZE;
        } else {
            size += IPV6_OVERHEAD_SIZE;
        }
        if (size <= _minMTU) {
            _consecutiveSmall++;
            if (_consecutiveSmall >= MTU_RCV_DISPLAY_THRESHOLD)
                _mtuReceive = _minMTU;
        } else {
            _consecutiveSmall = 0;
            if (size > _mtuReceive)
                _mtuReceive = size;
        }
    }

    /**
     *  We received a backoff request, so cut our send window.
     *  NOTE: ECN sending is unimplemented, this is never called.
     */
    void ECNReceived() {
        synchronized(this) {
            congestionOccurred();
        }
        _context.statManager().addRateData("udp.congestionOccurred", _sendWindowBytes);
        _currentSecondECNReceived = true;
        _lastReceiveTime = _context.clock().now();
    }

    void dataReceived() {
        _lastReceiveTime = _context.clock().now();
    }

    /** when did we last send an ACK to the peer? */
    public long getLastACKSend() { return _lastACKSend; }

    /**
     *  All acks have been sent.
     *
     *  SSU 1 only.
     *
     *  @since 0.9.52
     */
    synchronized void clearWantedACKSendSince() {
        // race prevention
        if (_currentACKs.isEmpty())
            _wantACKSendSince = 0;
    }

    /**
     *  Are we out of room to send all the current unsent acks in a single packet?
     *  This is a huge threshold (134 for small MTU and 255 for large MTU)
     *  that is rarely if ever exceeded in practice.
     *  So just use a fixed threshold of half the resend acks, so that if the
     *  packet is lost the acks have a decent chance of getting retransmitted.
     *  Used only by ACKSender.
     *
     *  SSU 1 only.
     */
    boolean unsentACKThresholdReached() {
        return _currentACKs.size() >= MAX_RESEND_ACKS / 2;
    }

    /**
     *  SSU 1 only.
     *
     *  @return how many bytes available for acks in an ack-only packet, == MTU - 83
     *          Max of 1020
     */
    private int countMaxACKData() {
        return Math.min(PacketBuilder.ABSOLUTE_MAX_ACKS * 4,
                _mtu
                - (_remoteIP.length == 4 ? PacketBuilder.IP_HEADER_SIZE : PacketBuilder.IPV6_HEADER_SIZE)
                - PacketBuilder.UDP_HEADER_SIZE
                - UDPPacket.IV_SIZE
                - UDPPacket.MAC_SIZE
                - 1 // type flag
                - 4 // timestamp
                - 1 // data flag
                - 1 // # ACKs
                - 16); // padding safety
    }

    /** @return non-null */
    RemoteHostId getRemoteHostId() { return _remoteHostId; }

    /**
     *  TODO should this use a queue, separate from the list of msgs pending an ack?
     *  TODO bring back tail drop?
     *  TODO priority queue? (we don't implement priorities in SSU now)
     *  TODO backlog / pushback / block instead of dropping? Can't really block here.
     *  TODO SSU does not support isBacklogged() now
     */
    void add(OutboundMessageState state) {
        if (_dead) {
            _transport.failed(state, false);
            return;
	}
        if (state.getPeer() != this) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not for me!", new Exception("I did it"));
            _transport.failed(state, false);
            return;
	}
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Adding to " + _remotePeer + ": " + state.getMessageId());
        int rv = 0;
        // will never fail for CDPQ
        boolean fail;
        synchronized (_outboundQueue) {
            fail = !_outboundQueue.offer(state);
            // reuse of CDPQ value, don't do both
            state.setSeqNum(_nextSequenceNumber++);
        }
        if (fail) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping msg, OB queue full for " + toString());
            _transport.failed(state, false);
        }
    }

    /** drop all outbound messages */
    void dropOutbound() {
        //if (_dead) return;
        _dead = true;
        //_outboundMessages = null;

            List<OutboundMessageState> tempList;
            synchronized (_outboundMessages) {
                    tempList = new ArrayList<OutboundMessageState>(_outboundMessages);
                    _outboundMessages.clear();
            }
            //_outboundQueue.drainAllTo(tempList);
            synchronized (_outboundQueue) {
                _outboundQueue.drainTo(tempList);
            }
            for (OutboundMessageState oms : tempList) {
                _transport.failed(oms, false);
            }

        // so the ACKSender will drop this peer from its queue
        _wantACKSendSince = 0;
    }

    /**
     * @return number of active outbound messages remaining (unsynchronized)
     */
    public int getOutboundMessageCount() {
        if (_dead) return 0;
        return _outboundMessages.size() + _outboundQueue.size();
    }

    /**
     * Sets to true.
     * @since 0.9.24
     */
    public void setMayDisconnect() { _mayDisconnect = true; }

    /**
     * @since 0.9.24
     */
    public boolean getMayDisconnect() { return _mayDisconnect; }


    /**
     * Expire / complete any outbound messages
     * High usage -
     * OutboundMessageFragments.getNextVolley() calls this 1st.
     * TODO combine finishMessages(), allocateSend(), and getNextDelay() so we don't iterate 3 times.
     *
     * @return number of active outbound messages remaining
     */
    int finishMessages(long now) {
        // short circuit, unsynchronized
        if (_outboundMessages.isEmpty())
            return _outboundQueue.size();

        if (_dead) {
            dropOutbound();
            return 0;
	}

        int rv = 0;
        List<OutboundMessageState> succeeded = null;
        List<OutboundMessageState> failed = null;
        synchronized (_outboundMessages) {
            for (Iterator<OutboundMessageState> iter = _outboundMessages.iterator(); iter.hasNext(); ) {
                OutboundMessageState state = iter.next();
                if (state.isComplete()) {
                    iter.remove();
                    if (succeeded == null) succeeded = new ArrayList<OutboundMessageState>(4);
                    succeeded.add(state);
                } else if (state.isExpired(now)) {
                    iter.remove();
                    _context.statManager().addRateData("udp.sendFailed", state.getPushCount());
                    if (failed == null) failed = new ArrayList<OutboundMessageState>(4);
                    failed.add(state);
                } else if (state.getMaxSends() > OutboundMessageFragments.MAX_VOLLEYS) {
                    iter.remove();
                    _context.statManager().addRateData("udp.sendAggressiveFailed", state.getPushCount());
                    if (failed == null) failed = new ArrayList<OutboundMessageState>(4);
                    failed.add(state);
                } // end (pushCount > maxVolleys)
            } // end iterating over outbound messages
            rv = _outboundMessages.size();
        }

        for (int i = 0; succeeded != null && i < succeeded.size(); i++) {
            OutboundMessageState state = succeeded.get(i);
            _transport.succeeded(state);
            OutNetMessage msg = state.getMessage();
            if (msg != null)
                msg.timestamp("sending complete");
        }

        if (failed != null) {
            int failedSize = 0;
            int failedCount = 0;
            boolean totalFail = false;
            for (int i = 0; i < failed.size(); i++) {
                OutboundMessageState state = failed.get(i);
                failedSize += state.getUnackedSize();
                failedCount += state.getUnackedFragments();
                OutNetMessage msg = state.getMessage();
                if (msg != null) {
                    msg.timestamp("expired in the active pool");
                    _transport.failed(state);
                    if (_log.shouldWarn())
                        _log.warn("Message expired: " + state + " to: " + this);
                    if (!_isInbound && msg.getSeqNum() == 0)
                        totalFail = true; // see below
                } else {
                    // it can not have an OutNetMessage if the source is the
                    // final after establishment message
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Unable to send a direct message: " + state + " to: " + this);
                }
            }
            if (failedSize > 0) {
                if (totalFail) {
                    // first outbound message failed
                    // This also ensures that in SSU2 if we never get an ACK of the
                    // Session Confirmed, we will fail quickly (because we don't have
                    // a separate timer for retransmitting it)
                    if (_log.shouldWarn())
                        _log.warn("First message failed on " + this);
                    _transport.sendDestroy(this, SSU2Util.REASON_FRAME_TIMEOUT);
                    _transport.dropPeer(this, true, "OB First Message Fail");
                    return 0;
                }
                // restore the window
                synchronized(_sendWindowBytesRemainingLock) {
                    // this isn't exactly right, because some fragments may not have been sent at all,
                    // but that should be unlikely
                    _sendWindowBytesRemaining += failedSize;
                    _sendWindowBytesRemaining += failedCount * fragmentOverhead();
                    if (_sendWindowBytesRemaining > _sendWindowBytes)
                        _sendWindowBytesRemaining = _sendWindowBytes;
                }
                // no need to nudge(), this is called from OMF loop before allocateSend()
            }
            if (rv <= 0) {
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug(_remotePeer + " nothing pending, cancelling timer");
                synchronized(this) {
                    _retransmitTimer = 0;
                    exitFastRetransmit();
                }
            }
        }

        return rv + _outboundQueue.size();
    }

    /**
     * Pick one or more messages we want to send and allocate them out of our window
     * Adjusts the retransmit timer if necessary.
     * High usage -
     * OutboundMessageFragments.getNextVolley() calls this 2nd, if finishMessages() returned &gt; 0.
     * TODO combine finishMessages() and allocateSend() so we don't iterate 2 times.
     *
     * @return allocated messages to send (never empty), or null if no messages or no resources
     */
    List<OutboundMessageState> allocateSend(long now) {
        long retransmitTimer;
        synchronized(this) {
            retransmitTimer = _retransmitTimer;
        }
        boolean canSendOld = retransmitTimer > 0 && now >= retransmitTimer;
        List<OutboundMessageState> rv = allocateSend2(canSendOld, now);
        if (rv != null && !rv.isEmpty()) {
            synchronized(this) {
                long old = _retransmitTimer;
                if (_retransmitTimer == 0) {
                    _retransmitTimer = now + getRTO();
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(_remotePeer + " allocated " + rv.size() + " pushing retransmitter from " + old + " to " + _retransmitTimer);
                } else if (_fastRetransmit.get()) {
                    // right?
                    _retransmitTimer = now + getRTO();
                }
            }
        } else if (canSendOld) {
            // failsafe - push out or cancel timer to prevent looping
            boolean isEmpty;
            synchronized (_outboundMessages) {
                isEmpty = _outboundMessages.isEmpty();
            }
            synchronized(this) {
                if (isEmpty) {
                    _retransmitTimer = 0;
                    exitFastRetransmit();
                } else {
                    _retransmitTimer = now + 250;
                }
            }
        }
        return rv;
    }

    /**
     * Pick one or more messages to send.  This will alloace either old or new messages, but not both.
     * @param canSendOld if any already sent messages can be sent.  If false, only new messages will be considered
     * @param now what time is it now
     * @since 0.9.48
     */
    private List<OutboundMessageState> allocateSend2(boolean canSendOld, long now) {
        if (_dead) return null;
        List<OutboundMessageState> rv = null;
        synchronized (_outboundMessages) {
            if (canSendOld) {
                for (OutboundMessageState state : _outboundMessages) {
                    if (_fastRetransmit.get()) {
                        // If fast retx flag set, just add those
                        if (state.getNACKs() < FAST_RTX_ACKS)
                            continue;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Allocate sending (FAST) to " + _remotePeer + ": " + state);
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Allocate sending (OLD) to " + _remotePeer + ": " + state.getMessageId());
                    }
                    if (rv == null) {
                        rv = new ArrayList<OutboundMessageState>((1 + _outboundMessages.size()) / 2);
                        _lastSendTime = now;
                    }
                    rv.add(state);
                    // Retransmit up to half of the packets in flight (RFC 6298 section 5.4 and RFC 5681 section 4.3)
                    // TODO this is fragments from half the messages... OK as is?
                    if (rv.size() >= _outboundMessages.size() / 2 && !_fastRetransmit.get())
                        return rv;
                }
                return rv;
            } else if (!_outboundMessages.isEmpty()) {
                // send some unsent fragments of pending messages, if any
                for (OutboundMessageState state : _outboundMessages) {
                    if (!state.hasUnsentFragments())
                        continue;
                    boolean should = locked_shouldSend(state, now);
                    if (should) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Allocate sending more fragments to " + _remotePeer + ": " + state.getMessageId());
                        if (rv == null)
                            rv = new ArrayList<OutboundMessageState>(_concurrentMessagesAllowed);
                        rv.add(state);
                    } else {
                        // no more bandwidth available
                        if (_log.shouldLog(Log.DEBUG)) {
                            if (rv == null)
                                _log.debug("Nothing to send (BW) to " + _remotePeer + ", with " + _outboundMessages.size() +
                                       " / " + _outboundQueue.size() + " remaining");
                            else
                               _log.debug(_remotePeer + " ran out of BW, but managed to send " + rv.size());
                        }
                        return rv;
                    }
                }
                // fall through to new messages
            }
            // Peek at head of _outboundQueue and see if we can send it.
            // If so, pull it off, put it in _outbundMessages, test
            // again for bandwidth if necessary, and return it.
            OutboundMessageState state;
            synchronized (_outboundQueue) {
                while ((state = _outboundQueue.peek()) != null &&
                       locked_shouldSend(state, now)) {
                    // This is guaranted to be the same as what we got in peek(),
                    // due to locking and because we aren't using the dropping CDPBQ.
                    // If we do switch to CDPBQ,
                    // we could get a different state, or null, when we poll,
                    // due to AQM drops, so we test again if necessary
                    OutboundMessageState dequeuedState = _outboundQueue.poll();
                    if (dequeuedState != null) {
                        _outboundMessages.add(dequeuedState);
                        // TODO if we switch to CDPBQ, see ticket #2582
                        //if (dequeuedState != state) {
                        //    // ignore result, always send?
                        //    locked_shouldSend(dequeuedState);
                        //}
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Allocate sending (NEW) to " + _remotePeer + ": " + dequeuedState.getMessageId());
                        if (rv == null)
                            rv = new ArrayList<OutboundMessageState>(_concurrentMessagesAllowed);
                        rv.add(dequeuedState);
                        if (rv.size() >= _concurrentMessagesAllowed)
                            return rv;
                    }
                }
            }
        }
      /****
        if ( rv == null && _log.shouldLog(Log.DEBUG))
            _log.debug("Nothing to send to " + _remotePeer + ", with " + _outboundMessages.size() +
                       " / " + _outboundQueue.size() + " remaining, rtx timer in " + (_retransmitTimer - now));
       ****/
        return rv;
    }

    /**
     * High usage -
     * OutboundMessageFragments.getNextVolley() calls this 3rd, if allocateSend() returned null.
     * TODO combine finishMessages(), allocateSend() so we don't iterate 2 times.
     *
     * @param now what time it is now
     * @return how long to wait before sending, or Integer.MAX_VALUE if we have nothing to send.
     *         If ready now, will return 0.
     */
    int getNextDelay(long now) {
        int rv = Integer.MAX_VALUE;
        if (_dead) return rv;
        synchronized(this) {
            if (_retransmitTimer > 0)
                rv = Math.max(0, (int) (_retransmitTimer - now));
        }
        return rv;
    }

    /**
     *  @since 0.9.3
     */
    public boolean isBacklogged() {
        return _dead || _outboundQueue.isBacklogged();
    }

    /**
     *  Always leave room for this many explicit acks.
     *  Only for data packets. Does not affect ack-only packets.
     *  This directly affects data packet overhead, adjust with care.
     */
    private static final int MIN_EXPLICIT_ACKS = 3;
    /** this is room for three explicit acks or two partial acks or one of each = 13 */
    private static final int MIN_ACK_SIZE = 1 + (4 * MIN_EXPLICIT_ACKS);

    /**
     *  how much payload data can we shove in there?
     *  @return MTU - 87, i.e. 533 or 1397 (IPv4), MTU - 107 (IPv6)
     */
    int fragmentSize() {
        // 46 + 20 + 8 + 13 = 74 + 13 = 87 (IPv4)
        // 46 + 40 + 8 + 13 = 94 + 13 = 107 (IPv6)
        return _mtu -
               (_remoteIP.length == 4 ? PacketBuilder.MIN_DATA_PACKET_OVERHEAD : PacketBuilder.MIN_IPV6_DATA_PACKET_OVERHEAD) -
               MIN_ACK_SIZE;
    }

    /**
     *  Packet overhead plus room for acks
     *  @return 87 (IPv4), 107 (IPv6)
     *  @since 0.9.49
     */
    int fragmentOverhead() {
        // 46 + 20 + 8 + 13 = 74 + 13 = 87 (IPv4)
        // 46 + 40 + 8 + 13 = 94 + 13 = 107 (IPv6)
        return (_remoteIP.length == 4 ? PacketBuilder.MIN_DATA_PACKET_OVERHEAD : PacketBuilder.MIN_IPV6_DATA_PACKET_OVERHEAD) +
               MIN_ACK_SIZE;
    }

    /**
     *  Locks this
     */
    private boolean locked_shouldSend(OutboundMessageState state, long now) {
        synchronized(this) {
            if (allocateSendingBytes(state, now)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(_remotePeer + " Allocation allowed with "
                              + getSendWindowBytesRemaining()
                              + "/" + getSendWindowBytes()
                              + " remaining"
                              + " for message " + state.getMessageId() + ": " + state);
                if (state.getPushCount() == 0)
                    _messagesSent++;
                return true;
            } else {
                _context.statManager().addRateData("udp.sendRejected", state.getPushCount());
                if (_log.shouldLog(Log.INFO))
                    _log.info(_remotePeer + " Allocation rejected w/ wsize=" + getSendWindowBytes()
                              + " available=" + getSendWindowBytesRemaining()
                              + " for message " + state.getMessageId() + ": " + state);
                return false;
            }
        }
    }

    /**
     *  A full ACK was received.
     *  TODO if messages awaiting ack were a HashMap&lt;Long, OutboundMessageState&gt; this would be faster.
     *
     *  SSU 1 only.
     *
     *  @param highestSeqNumAcked in/out param, will modify if this seq. number is higher
     *  @return true if the message was acked for the first time
     */
    boolean acked(long messageId, ModifiableLong highestSeqNumAcked) {
        if (_dead) return false;
        OutboundMessageState state = null;
        boolean anyPending;
        synchronized (_outboundMessages) {
            for (Iterator<OutboundMessageState> iter = _outboundMessages.iterator(); iter.hasNext(); ) {
                state = iter.next();
                if (state.getMessageId() == messageId) {
                    iter.remove();
                    break;
                } else if (state.getPushCount() <= 0) {
                    // _outboundMessages is ordered, so once we get to a msg that
                    // hasn't been transmitted yet, we can stop
                    state = null;
                    break;
                } else {
                    state = null;
                }
            }
            anyPending = !_outboundMessages.isEmpty();
        }

        if (state != null) {
            int numSends = state.getMaxSends();
            long lifetime = state.getLifetime();
            if (_log.shouldDebug())
                _log.debug("Received ack of " + messageId + " by " + _remotePeer
                          + " after " + lifetime + " and " + numSends + " sends");
            _context.statManager().addRateData("udp.sendConfirmTime", lifetime);
            if (state.getFragmentCount() > 1)
                _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount());
            _context.statManager().addRateData("udp.sendConfirmVolley", numSends);
            _transport.succeeded(state);
            boolean anyQueued;
            if (anyPending) {
                // locked_messageACKed will nudge()
                anyQueued = false;
            } else {
                synchronized (_outboundQueue) {
                    anyQueued = !_outboundQueue.isEmpty();
                }
            }
            long sn = state.getSeqNum();
            if (sn > highestSeqNumAcked.value)
                highestSeqNumAcked.value = sn;
            synchronized(_ackedMessages) {
                _ackedMessages.put(Integer.valueOf((int) messageId), Long.valueOf(sn));
            }
            // this adjusts the rtt/rto/window/etc
            int maxPktSz = state.fragmentSize(0) +
                           (isIPv6() ? PacketBuilder.MIN_IPV6_DATA_PACKET_OVERHEAD : PacketBuilder.MIN_DATA_PACKET_OVERHEAD);
            messageACKed(state.getUnackedSize(), maxPktSz, lifetime, numSends, anyPending, anyQueued);
        } else {
            // dupack, likely
            Long seq;
            synchronized(_ackedMessages) {
                seq = _ackedMessages.get(Integer.valueOf((int) messageId));
            }
            if (seq != null) {
                long sn = seq.longValue();
                if (sn > highestSeqNumAcked.value)
                    highestSeqNumAcked.value = sn;
            }
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Received an ACK for a message not pending: " + messageId);
        }
        return state != null;
    }

    /**
     *  A partial ACK was received. This is much less common than full ACKs.
     *
     *  SSU 1 only.
     *
     *  @param highestSeqNumAcked in/out param, will modify if this seq. number is higher
     *  @return true if any fragment of the message was completely acked for the first time
     */
    boolean acked(ACKBitfield bitfield, ModifiableLong highestSeqNumAcked) {
        if (_dead)
            return false;

        final long messageId = bitfield.getMessageId();
        if (bitfield.receivedComplete()) {
            return acked(messageId, highestSeqNumAcked);
        }

        OutboundMessageState state = null;
        boolean isComplete = false;
        boolean anyPending;
        int ackedSize = 0;
        synchronized (_outboundMessages) {
            for (Iterator<OutboundMessageState> iter = _outboundMessages.iterator(); iter.hasNext(); ) {
                state = iter.next();
                if (state.getMessageId() == messageId) {
                    ackedSize = state.getUnackedSize();
                    boolean complete = state.acked(bitfield);
                    if (complete) {
                        isComplete = true;
                        iter.remove();
                    } else {
                        ackedSize -= state.getUnackedSize();
                    }
                    break;
                } else if (state.getPushCount() <= 0) {
                    // _outboundMessages is ordered, so once we get to a msg that
                    // hasn't been transmitted yet, we can stop
                    state = null;
                    break;
                } else {
                    state = null;
                }
            }
            anyPending = !_outboundMessages.isEmpty();
        }

        if (state != null) {
            int numSends = state.getMaxSends();

            int numACKed = bitfield.ackCount();
            _context.statManager().addRateData("udp.partialACKReceived", numACKed);

            long lifetime = state.getLifetime();
            if (isComplete) {
                _context.statManager().addRateData("udp.sendConfirmTime", lifetime);
                if (state.getFragmentCount() > 1)
                    _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount());
                _context.statManager().addRateData("udp.sendConfirmVolley", numSends);
                _transport.succeeded(state);
                if (_log.shouldDebug())
                    _log.debug("Received partial ack of " + messageId + " by " + _remotePeer
                          + " newly-acked: " + ackedSize
                          + ", now complete for: " + state);
            } else {
                if (_log.shouldDebug())
                    _log.debug("Received partial ack of " + messageId + " by " + _remotePeer
                          + " after " + lifetime + " and " + numSends + " sends"
                          + " complete? false"
                          + " newly-acked: " + ackedSize
                          + ' ' + bitfield
                          + " for: " + state);
            }
            if (ackedSize > 0) {
                state.clearNACKs();
                boolean anyQueued;
                if (anyPending) {
                    // locked_messageACKed will nudge()
                    anyQueued = false;
                } else {
                    synchronized (_outboundQueue) {
                        anyQueued = !_outboundQueue.isEmpty();
                    }
                }
                // this adjusts the rtt/rto/window/etc
                messageACKed(ackedSize, 0, lifetime, numSends, anyPending, anyQueued);
            }
            // we do this even if only partial
            long sn = state.getSeqNum();
            if (sn > highestSeqNumAcked.value)
                highestSeqNumAcked.value = sn;
            if (isComplete) {
                synchronized(_ackedMessages) {
                    _ackedMessages.put(Integer.valueOf((int) messageId), Long.valueOf(sn));
                }
            }
            return ackedSize > 0;
        } else {
            // dupack
            Long seq;
            synchronized(_ackedMessages) {
                seq = _ackedMessages.get(Integer.valueOf((int) messageId));
            }
            if (seq != null) {
                long sn = seq.longValue();
                if (sn > highestSeqNumAcked.value)
                    highestSeqNumAcked.value = sn;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received an ACK for a message not pending: " + bitfield);
            return false;
        }
    }

    /**
     *  An ACK of a fragment was received.
     *
     *  SSU 2 only.
     *
     *  @return true if this fragment of the message was acked for the first time
     */
    protected boolean acked(PacketBuilder.Fragment f) {
        if (_dead)
            return false;

        final OutboundMessageState state = f.state;
        boolean isComplete;
        int ackedSize;
        synchronized(state) {
            ackedSize = state.getUnackedSize();
            if (ackedSize <= 0)
                return false;
            isComplete = state.acked(f.num);
            if (!isComplete)
                ackedSize -= state.getUnackedSize();
        }
        if (ackedSize <= 0)
            return false;
        boolean anyPending;
        synchronized (_outboundMessages) {
            if (isComplete) {
                long sn = state.getSeqNum();
                boolean found = false;
                // we don't do _outboundMessages.remove() so we can use the cached iterator
                // and break out early
                for (Iterator<OutboundMessageState> iter = _outboundMessages.iterator(); iter.hasNext(); ) {
                    OutboundMessageState state2 = iter.next();
                    if (state == state2) {
                        iter.remove();
                        found = true;
                        break;
                    } else if (state2.getSeqNum() > sn) {
                        // _outboundMessages is ordered, so once we get to a msg
                        // with a higher sequence number, we can stop
                        break;
                    }
                }
                if (!found) {
                    // shouldn't happen except on race
                    if (_log.shouldWarn())
                        _log.warn("Acked but not found in outbound messages: " + state);
                    return false;
                }
            }
            anyPending = !_outboundMessages.isEmpty();
        }

        int numSends = state.getMaxSends();
        _context.statManager().addRateData("udp.partialACKReceived", 1);
        long lifetime = state.getLifetime();
        if (isComplete) {
            _context.statManager().addRateData("udp.sendConfirmTime", lifetime);
            if (state.getFragmentCount() > 1)
                _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount());
            _context.statManager().addRateData("udp.sendConfirmVolley", numSends);
            _transport.succeeded(state);
            if (_log.shouldDebug()) {
                if (state.getFragmentCount() > 1) {
                    _log.debug("Received partial ack of " + state.getMessageId() + " by " + _remotePeer
                               + " newly-acked: " + ackedSize
                               + ", now complete for: " + state);
                } else {
                    _log.debug("Received ack of " + state.getMessageId() + " by " + _remotePeer
                               + " after " + lifetime + " and " + numSends + " sends");
                }
            }
        } else {
            if (_log.shouldDebug())
                _log.debug("Received partial ack of " + state.getMessageId() + " by " + _remotePeer
                      + " after " + lifetime + " and " + numSends + " sends"
                      + " complete? false"
                      + " newly-acked: " + ackedSize
                      + " fragment: " + f.num
                      + " for: " + state);
        }
        state.clearNACKs();
        boolean anyQueued;
        if (anyPending) {
            // locked_messageACKed will nudge()
            anyQueued = false;
        } else {
            synchronized (_outboundQueue) {
                anyQueued = !_outboundQueue.isEmpty();
            }
        }
        // this adjusts the rtt/rto/window/etc
        int maxPktSz = state.fragmentSize(0) +
                       SSU2Payload.BLOCK_HEADER_SIZE +
                       (isIPv6() ? PacketBuilder2.MIN_IPV6_DATA_PACKET_OVERHEAD : PacketBuilder2.MIN_DATA_PACKET_OVERHEAD);
        messageACKed(ackedSize, maxPktSz, lifetime, numSends, anyPending, anyQueued);
        return true;
    }

    /**
     *  Enter or leave fast retransmit mode, and adjust
     *  SST and window variables accordingly.
     *  See RFC 5681 sec. 2.4
     *
     *  @param highest the highest sequence number that was acked
     *  @return true if we have something to fast-retransmit
     *  @since 0.9.49
     */
    boolean highestSeqNumAcked(long highest) {
        boolean rv = false;
        boolean startFast = false;
        boolean continueFast = false;
        synchronized(_outboundMessages) {
            for (Iterator<OutboundMessageState> iter = _outboundMessages.iterator(); iter.hasNext(); ) {
                OutboundMessageState state = iter.next();
                long sn = state.getSeqNum();
                if (sn >= highest)
                    break;
                if (sn < highest) {
                    // this will also increment NACKs for a state that was just partially acked... ok?
                    int nacks = state.incrementNACKs();
                    if (nacks == FAST_RTX_ACKS) {
                        startFast = true;
                        rv = true;
                    } else if (nacks > FAST_RTX_ACKS) {
                        continueFast = true;
                        rv = true;
                    }
                    if (_log.shouldDebug())
                        _log.debug("Message NACKed: " + state);
                }
            }
            if (rv) {
                // set the variables for fast retransmit
                // timer will be reset below
                _fastRetransmit.set(true);
                // caller (IMF) will wakeup OMF
                if (continueFast) {
                  // RFC 5681 sec. 3.2 #4 increase cwnd
                   _sendWindowBytes += _mtu;
                    synchronized(_sendWindowBytesRemainingLock) {
                       _sendWindowBytesRemaining += _mtu;
                    }
                   if (_log.shouldDebug())
                       _log.debug("Continue FAST RTX, inflated window: " + this);
                } else if (startFast) {
                   // RFC 5681 sec. 3.2 #2 set SST (equation 4)
                   // But use W+ BWE instead
                   float bwe = _bwEstimator.getBandwidthEstimate();
                   _slowStartThreshold = Math.max((int)(bwe * _rtt), 2 * _mtu);
                   // RFC 5681 sec. 3.2 #3 set cwnd
                   _sendWindowBytes = _slowStartThreshold + (3 * _mtu);
                    synchronized(_sendWindowBytesRemainingLock) {
                        _sendWindowBytesRemaining = _sendWindowBytes;
                    }
                   if (_log.shouldDebug())
                       _log.debug("Start of FAST RTX, inflated window: " + this);
                }
            } else {
                exitFastRetransmit();
            }
        }
        if (rv) {
            synchronized(this) {
                _retransmitTimer = _context.clock().now();
            }
        }
        return rv;
    }

    /**
     *  Leave fast retransmit mode if we were in it, and adjust
     *  SST and window variables accordingly.
     *  See RFC 5681 sec. 2.4
     *
     *  @since 0.9.49
     */
    private void exitFastRetransmit() {
        if (_fastRetransmit.compareAndSet(true, false)) {
            synchronized(this) {
                // RFC 5681 sec. 2.4 #6 deflate the window
                _sendWindowBytes = _slowStartThreshold;
                synchronized(_sendWindowBytesRemainingLock) {
                    _sendWindowBytesRemaining = _sendWindowBytes;
                }
            }
            if (_log.shouldDebug())
                _log.debug("End of FAST RTX, deflated window: " + this);
        }
    }

    /**
     * Transfer the basic activity/state from the old peer to the current peer
     *
     *  SSU 1 or 2.
     *
     * @param oldPeer non-null
     */
    void loadFrom(PeerState oldPeer) {
        _rto = oldPeer._rto;
        _rtt = oldPeer._rtt;
        _rttDeviation = oldPeer._rttDeviation;
        _slowStartThreshold = oldPeer._slowStartThreshold;
        _sendWindowBytes = oldPeer._sendWindowBytes;
        oldPeer._dead = true;

        if (getVersion() == 1 && oldPeer.getVersion() == 1) {
            List<Long> tmp = new ArrayList<Long>();
            // AIOOBE from concurrent access
            //tmp.addAll(oldPeer._currentACKs);
            for (Long l : oldPeer._currentACKs) {
                tmp.add(l);
            }
            oldPeer._currentACKs.clear();

            if (!_dead) {
                _currentACKs.addAll(tmp);
            }

            List<ResendACK> tmp3 = new ArrayList<ResendACK>();
            tmp3.addAll(oldPeer._currentACKsResend);
            oldPeer._currentACKsResend.clear();

            if (!_dead) {
                _currentACKsResend.addAll(tmp3);
            }
        }

        if (getVersion() == oldPeer.getVersion()) {
            Map<Long, InboundMessageState> msgs = new HashMap<Long, InboundMessageState>();
            synchronized (oldPeer._inboundMessages) {
                msgs.putAll(oldPeer._inboundMessages);
                oldPeer._inboundMessages.clear();
            }
            if (!_dead) {
                synchronized (_inboundMessages) { _inboundMessages.putAll(msgs); }
            }
            msgs.clear();

            List<OutboundMessageState> tmp2 = new ArrayList<OutboundMessageState>();
            OutboundMessageState retransmitter = null;
            synchronized (oldPeer._outboundMessages) {
                tmp2.addAll(oldPeer._outboundMessages);
                oldPeer._outboundMessages.clear();
            }
            if (!_dead) {
                synchronized (_outboundMessages) {
                    _outboundMessages.addAll(tmp2);
                }
            }
        }
    }

    /**
     *  Convenience for OutboundMessageState so it can fail itself
     *  @since 0.9.3
     */
    UDPTransport getTransport() {
        return _transport;
    }

    /**
     *  A message ID and a timestamp. Used for the resend ACKS.
     *
     *  SSU 1 only.
     *
     *  @since 0.9.17
     */
    private static class ResendACK {
        public final Long id;
        public final long time;

        public ResendACK(Long id, long time) {
            this.id = id;
            this.time = time;
        }
    }

    /**
     *  Message ID to sequence number.
     *  Insertion order. Caller must synch.
     *
     *  SSU 1 only.
     *
     *  @since 0.9.49
     */
    private static class AckedMessages extends LinkedHashMap<Integer, Long> {

        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) {
            return size() > MAX_SEND_MSGS_PENDING;
        }
    }

    /**
     *  A timer to send an ack-only packet.
     *
     *  SSU 1 only.
     *
     *  @since 0.9.52
     */
    private class ACKTimer extends SimpleTimer2.TimedEvent {
        public ACKTimer() {
            super(_context.simpleTimer2());
            long delta = Math.max(10, Math.min(_rtt/6, ACK_FREQUENCY));
            if (_log.shouldDebug())
                _log.debug("Sending delayed ack in " + delta + ": " + PeerState.this);
            schedule(delta);
        }

        /**
         *  Send an ack-only packet, unless acks were already sent
         *  as indicated by _wantACKSendSince == 0.
         *  Will not requeue unless the acks don't all fit (unlikely).
         */
        public void timeReached() {
            synchronized(PeerState.this) {
                long wanted = _wantACKSendSince;
                if (wanted <= 0) {
                    if (_log.shouldDebug())
                        _log.debug("Already acked:" + PeerState.this);
                    return;
                }
                List<ACKBitfield> ackBitfields = retrieveACKBitfields(false);

                if (!ackBitfields.isEmpty()) {
                    UDPPacket ack = _transport.getBuilder().buildACK(PeerState.this, ackBitfields);
                    ack.markType(1);
                    ack.setFragmentCount(-1);
                    ack.setMessageType(PacketBuilder.TYPE_ACK);

                    if (_log.shouldDebug()) {
                        //_log.debug("Sending " + ackBitfields + " to " + PeerState.this);
                        _log.debug("Sending " + ackBitfields.size() + " acks to " + PeerState.this);
                    }
                    // locking issues, we ignore the result, and acks are small,
                    // so don't even bother allocating
                    //peer.allocateSendingBytes(ack.getPacket().getLength(), true);
                    // ignore whether its ok or not, its a bloody ack.  this should be fixed, probably.
                    _transport.send(ack);

                    if (_wantACKSendSince > 0) {
                        // still full packets left to be ACKed, since wanted time
                        // is reset by retrieveACKBitfields when all of the IDs are
                        // removed
                        if (_log.shouldInfo())
                            _log.info("Requeueing more ACKs for " + PeerState.this);
                        reschedule(25);
                    }
                } else {
                    if (_log.shouldDebug())
                        _log.debug("No more acks:" + PeerState.this);
                }
           }
        }
    }

    // why removed? Some risk of dups in OutboundMessageFragments._activePeers ???

    /*
    public int hashCode() {
        if (_remotePeer != null)
            return _remotePeer.hashCode();
        else
            return super.hashCode();
    }
    public boolean equals(Object o) {
        if (o == this) return true;
        if (o == null) return false;
        if (o instanceof PeerState) {
            PeerState s = (PeerState)o;
            if (_remotePeer == null)
                return o == this;
            else
                return _remotePeer.equals(s.getRemotePeer());
        } else {
            return false;
        }
    }
    */

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append(_remoteHostId.toString());
        buf.append(' ').append(_remotePeer.toBase64().substring(0,6));

        if (getVersion() == 2)
            buf.append(_isInbound? " IB2 " : " OB2 ");
        else
            buf.append(_isInbound? " IB " : " OB ");
        long now = _context.clock().now();
        buf.append(" recvAge: ").append(DataHelper.formatDuration(now - _lastReceiveTime));
        buf.append(" sendAge: ").append(DataHelper.formatDuration(now - _lastSendFullyTime));
        buf.append(" sendAttemptAge: ").append(DataHelper.formatDuration(now - _lastSendTime));
        buf.append(" sendACKAge: ").append(DataHelper.formatDuration(now - _lastACKSend));
        buf.append(" lifetime: ").append(DataHelper.formatDuration(now - _keyEstablishedTime));
        buf.append(" RTT: ").append(_rtt);
        buf.append(" RTO: ").append(_rto);
        buf.append(" MTU: ").append(_mtu);
        buf.append(" LMTU: ").append(_largeMTU);
        buf.append(" cwin: ").append(_sendWindowBytes);
        buf.append(" acwin: ").append(_sendWindowBytesRemaining);
        buf.append(" SST: ").append(_slowStartThreshold);
        buf.append(" FRTX? ").append(_fastRetransmit);
        buf.append(" consecFail: ").append(_consecutiveFailedSends);
        buf.append(" msgs rcvd: ").append(_messagesReceived);
        buf.append(" msgs sent: ").append(_messagesSent);
        buf.append(" pkts rcvd OK/Dup: ").append(_packetsReceived).append('/').append(_packetsReceivedDuplicate);
        buf.append(" pkts sent OK/Dup: ").append(_packetsTransmitted).append('/').append(_packetsRetransmitted);
        buf.append(" IBM: ").append(_inboundMessages.size());
        buf.append(" OBQ: ").append(_outboundQueue.size());
        buf.append(" OBL: ").append(_outboundMessages.size());
        if (_weRelayToThemAs > 0)
            buf.append(" weRelayToThemAs: ").append(_weRelayToThemAs);
        if (_theyRelayToUsAs > 0)
            buf.append(" theyRelayToUsAs: ").append(_theyRelayToUsAs);
        return buf.toString();
    }
}
