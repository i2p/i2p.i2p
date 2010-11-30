package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.ConcurrentHashSet;

/**
 * Contain all of the state about a UDP connection to a peer.
 *
 */
class PeerState {
    private RouterContext _context;
    private Log _log;
    /**
     * The peer are we talking to.  This should be set as soon as this
     * state is created if we are initiating a connection, but if we are
     * receiving the connection this will be set only after the connection
     * is established.
     */
    private Hash _remotePeer;
    /** 
     * The AES key used to verify packets, set only after the connection is
     * established.  
     */
    private SessionKey _currentMACKey;
    /**
     * The AES key used to encrypt/decrypt packets, set only after the 
     * connection is established.
     */
    private SessionKey _currentCipherKey;
    /** 
     * The pending AES key for verifying packets if we are rekeying the 
     * connection, or null if we are not in the process of rekeying.
     */
    private SessionKey _nextMACKey;
    /** 
     * The pending AES key for encrypting/decrypting packets if we are 
     * rekeying the connection, or null if we are not in the process 
     * of rekeying.
     */
    private SessionKey _nextCipherKey;
    /**
     * The keying material used for the rekeying, or null if we are not in
     * the process of rekeying.
     */
    private byte[] _nextKeyingMaterial;
    /** true if we began the current rekeying, false otherwise */
    private boolean _rekeyBeganLocally;
    /** when were the current cipher and MAC keys established/rekeyed? */
    private long _keyEstablishedTime;

    /**
     *  How far off is the remote peer from our clock, in milliseconds?
     *  A positive number means our clock is ahead of theirs.
     */
    private long _clockSkew;

    /** what is the current receive second, for congestion control? */
    private long _currentReceiveSecond;
    /** when did we last send them a packet? */
    private long _lastSendTime;
    /** when did we last send them a message that was ACKed */
    private long _lastSendFullyTime;
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
    private final Queue<Long> _currentACKsResend;

    /** when did we last send ACKs to the peer? */
    private volatile long _lastACKSend;
    /** when did we decide we need to ACK to this peer? */
    private volatile long _wantACKSendSince;
    /** have we received a packet with the ECN bit set in the current second? */
    private boolean _currentSecondECNReceived;
    /** 
     * have all of the packets received in the current second requested that
     * the previous second's ACKs be sent?
     */
    private boolean _remoteWantsPreviousACKs;
    /** how many bytes should we send to the peer in a second */
    private volatile int _sendWindowBytes;
    /** how many bytes can we send to the peer in the current second */
    private volatile int _sendWindowBytesRemaining;
    private long _lastSendRefill;
    private int _sendBps;
    private int _sendBytes;
    private int _receiveBps;
    private int _receiveBytes;
    private int _sendACKBps;
    private int _sendACKBytes;
    private int _receiveACKBps;
    private int _receiveACKBytes;
    private long _receivePeriodBegin;
    private volatile long _lastCongestionOccurred;
    /** 
     * when sendWindowBytes is below this, grow the window size quickly,
     * but after we reach it, grow it slowly
     *
     */
    private volatile int _slowStartThreshold;
    /** what IP is the peer sending and receiving packets on? */
    private byte[] _remoteIP;
    /** cached IP address */
    private transient InetAddress _remoteIPAddress;
    /** what port is the peer sending and receiving packets on? */
    private int _remotePort;
    /** cached RemoteHostId, used to find the peerState by remote info */
    private RemoteHostId _remoteHostId;
    /** if we need to contact them, do we need to talk to an introducer? */
    private boolean _remoteRequiresIntroduction;
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
    /** what is the largest packet we can send to the peer? */
    private int _mtu;
    private int _mtuReceive;
    /* how many consecutive packets at or under the min MTU have been received */
    private long _consecutiveSmall;
    /** when did we last check the MTU? */
    private long _mtuLastChecked;
    private long _mtuIncreases;
    private long _mtuDecreases;
    /** current round trip time estimate */
    private volatile int _rtt;
    /** smoothed mean deviation in the rtt */
    private volatile int _rttDeviation;
    /** current retransmission timeout */
    private volatile int _rto;
    
    /** how many packets will be considered within the retransmission rate calculation */
    static final long RETRANSMISSION_PERIOD_WIDTH = 100;
    
    private long _messagesReceived;
    private long _messagesSent;
    private long _packetsTransmitted;
    /** how many packets were retransmitted within the last RETRANSMISSION_PERIOD_WIDTH packets */
    private long _packetsRetransmitted;
    /** how many packets were transmitted within the last RETRANSMISSION_PERIOD_WIDTH packets */
    private long _packetsPeriodTransmitted;
    private int _packetsPeriodRetransmitted;
    private int _packetRetransmissionRate;
    /** at what time did we last break off the retransmission counter period */
    private long _retransmissionPeriodStart;
    /** how many dup packets were received within the last RETRANSMISSION_PERIOD_WIDTH packets */
    private long _packetsReceivedDuplicate;
    private long _packetsReceived;
    
    /** list of InboundMessageState for active message */
    private final Map<Long, InboundMessageState> _inboundMessages;
    /** list of OutboundMessageState */
    private final List<OutboundMessageState> _outboundMessages;
    /** which outbound message is currently being retransmitted */
    private OutboundMessageState _retransmitter;
    
    private UDPTransport _transport;
    
    /** have we migrated away from this peer to another newer one? */
    private volatile boolean _dead;

    /** Make sure a 4229 byte TunnelBuildMessage can be sent in one volley with small MTU */
    private static final int MIN_CONCURRENT_MSGS = 8;
    /** how many concurrent outbound messages do we allow throws OutboundMessageFragments to send */
    private volatile int _concurrentMessagesAllowed = MIN_CONCURRENT_MSGS;
    /** 
     * how many outbound messages are currently being transmitted.  Not thread safe, as we're not strict
     */
    private volatile int _concurrentMessagesActive = 0;
    /** how many concurrency rejections have we had in a row */
    private volatile int _consecutiveRejections = 0;
    /** is it inbound? **/
    private boolean _isInbound;
    /** Last time it was made an introducer **/
    private long _lastIntroducerTime;

    private static final int DEFAULT_SEND_WINDOW_BYTES = 8*1024;
    private static final int MINIMUM_WINDOW_BYTES = DEFAULT_SEND_WINDOW_BYTES;
    private static final int MAX_SEND_WINDOW_BYTES = 1024*1024;
    /*
     * 596 gives us 588 IP byes, 568 UDP bytes, and with an SSU data message, 
     * 522 fragment bytes, which is enough to send a tunnel data message in 2 
     * packets. A tunnel data message sent over the wire is 1044 bytes, meaning 
     * we need 522 fragment bytes to fit it in 2 packets - add 46 for SSU, 20 
     * for UDP, and 8 for IP, giving us 596.  round up to mod 16, giving a total
     * of 608
     *
     * Well, we really need to count the acks as well, especially
     * 4 * MAX_RESEND_ACKS which can take up a significant amount of space.
     * We reduce the max acks when using the small MTU but it may not be enough...
     *
     */
    private static final int MIN_MTU = 608;//600; //1500;
    private static final int DEFAULT_MTU = MIN_MTU;
    /* 
     * based on measurements, 1350 fits nearly all reasonably small I2NP messages
     * (larger I2NP messages may be up to 1900B-4500B, which isn't going to fit
     * into a live network MTU anyway)
     */
    private static final int LARGE_MTU = 1350;
    
    private static final int MIN_RTO = 100 + ACKSender.ACK_FREQUENCY;
    private static final int MAX_RTO = 3000; // 5000;
    /** override the default MTU */
    private static final String PROP_DEFAULT_MTU = "i2np.udp.mtu";
    
    public PeerState(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(PeerState.class);
        _transport = transport;
        _keyEstablishedTime = -1;
        _currentReceiveSecond = -1;
        _lastSendTime = -1;
        _lastReceiveTime = -1;
        _currentACKs = new ConcurrentHashSet();
        _currentACKsResend = new LinkedBlockingQueue();
        _sendWindowBytes = DEFAULT_SEND_WINDOW_BYTES;
        _sendWindowBytesRemaining = DEFAULT_SEND_WINDOW_BYTES;
        _slowStartThreshold = MAX_SEND_WINDOW_BYTES/2;
        _lastSendRefill = _context.clock().now();
        _receivePeriodBegin = _lastSendRefill;
        _lastCongestionOccurred = -1;
        _remotePort = -1;
        _mtu = getDefaultMTU();
        _mtuReceive = _mtu;
        _mtuLastChecked = -1;
        _lastACKSend = -1;
        _rto = MIN_RTO;
        _rtt = _rto/2;
        _rttDeviation = _rtt;
        _inboundMessages = new HashMap(8);
        _outboundMessages = new ArrayList(32);
        _context.statManager().createRateStat("udp.congestionOccurred", "How large the cwin was when congestion occurred (duration == sendBps)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.congestedRTO", "retransmission timeout after congestion (duration == rtt dev)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendACKPartial", "Number of partial ACKs sent (duration == number of full ACKs in that ack packet)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendBps", "How fast we are transmitting when a packet is acked", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveBps", "How fast we are receiving when a packet is fully received (at most one per second)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.mtuIncrease", "How many retransmissions have there been to the peer when the MTU was increased (period is total packets transmitted)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.mtuDecrease", "How many retransmissions have there been to the peer when the MTU was decreased (period is total packets transmitted)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.rejectConcurrentActive", "How many messages are currently being sent to the peer when we reject it (period is how many concurrent packets we allow)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.allowConcurrentActive", "How many messages are currently being sent to the peer when we accept it (period is how many concurrent packets we allow)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.rejectConcurrentSequence", "How many consecutive concurrency rejections have we had when we stop rejecting (period is how many concurrent packets we are on)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.queueDropSize", "How many messages were queued up when it was considered full, causing a tail drop?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.queueAllowTotalLifetime", "When a peer is retransmitting and we probabalistically allow a new message, what is the sum of the pending message lifetimes? (period is the new message's lifetime)?", "udp", UDPTransport.RATES);
    }
    
    private int getDefaultMTU() {
        return _context.getProperty(PROP_DEFAULT_MTU, DEFAULT_MTU);
    }
    
    /**
     * The peer are we talking to.  This should be set as soon as this
     * state is created if we are initiating a connection, but if we are
     * receiving the connection this will be set only after the connection
     * is established.
     */
    public Hash getRemotePeer() { return _remotePeer; }
    /** 
     * The AES key used to verify packets, set only after the connection is
     * established.  
     */
    public SessionKey getCurrentMACKey() { return _currentMACKey; }
    /**
     * The AES key used to encrypt/decrypt packets, set only after the 
     * connection is established.
     */
    public SessionKey getCurrentCipherKey() { return _currentCipherKey; }
    /** 
     * The pending AES key for verifying packets if we are rekeying the 
     * connection, or null if we are not in the process of rekeying.
     */
    public SessionKey getNextMACKey() { return _nextMACKey; }
    /** 
     * The pending AES key for encrypting/decrypting packets if we are 
     * rekeying the connection, or null if we are not in the process 
     * of rekeying.
     */
    public SessionKey getNextCipherKey() { return _nextCipherKey; }
    /**
     * The keying material used for the rekeying, or null if we are not in
     * the process of rekeying.
     */
    public byte[] getNextKeyingMaterial() { return _nextKeyingMaterial; }
    /** true if we began the current rekeying, false otherwise */
    public boolean getRekeyBeganLocally() { return _rekeyBeganLocally; }
    /** when were the current cipher and MAC keys established/rekeyed? */
    public long getKeyEstablishedTime() { return _keyEstablishedTime; }

    /**
     *  How far off is the remote peer from our clock, in milliseconds?
     *  A positive number means our clock is ahead of theirs.
     */
    public long getClockSkew() { return _clockSkew ; }

    /** what is the current receive second, for congestion control? */
    public long getCurrentReceiveSecond() { return _currentReceiveSecond; }
    /** when did we last send them a packet? */
    public long getLastSendTime() { return _lastSendTime; }
    /** when did we last send them a message that was ACKed? */
    public long getLastSendFullyTime() { return _lastSendFullyTime; }
    /** when did we last receive a packet from them? */
    public long getLastReceiveTime() { return _lastReceiveTime; }
    /** how many seconds have we sent packets without any ACKs received? */
    public int getConsecutiveFailedSends() { return _consecutiveFailedSends; }
    /** have we received a packet with the ECN bit set in the current second? */
    public boolean getCurrentSecondECNReceived() { return _currentSecondECNReceived; }
    /** 
     * have all of the packets received in the current second requested that
     * the previous second's ACKs be sent?
     */
    public boolean getRemoteWantsPreviousACKs() { return _remoteWantsPreviousACKs; }
    /** how many bytes should we send to the peer in a second */
    public int getSendWindowBytes() { return _sendWindowBytes; }
    /** how many bytes can we send to the peer in the current second */
    public int getSendWindowBytesRemaining() { return _sendWindowBytesRemaining; }
    /** what IP is the peer sending and receiving packets on? */
    public byte[] getRemoteIP() { return _remoteIP; }
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
    /** if we need to contact them, do we need to talk to an introducer? */
    public boolean getRemoteRequiresIntroduction() { return _remoteRequiresIntroduction; }
    /** 
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer 
     */
    public long getWeRelayToThemAs() { return _weRelayToThemAs; }
    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     */
    public long getTheyRelayToUsAs() { return _theyRelayToUsAs; }
    /** what is the largest packet we can send to the peer? */
    public int getMTU() { return _mtu; }
    /** estimate how large the other side is sending packets */
    public int getReceiveMTU() { return _mtuReceive; }
    /** when did we last check the MTU? */
    public long getMTULastChecked() { return _mtuLastChecked; }
    public long getMTUIncreases() { return _mtuIncreases; }
    public long getMTUDecreases() { return _mtuDecreases; }
    
    
    /**
     * The peer are we talking to.  This should be set as soon as this
     * state is created if we are initiating a connection, but if we are
     * receiving the connection this will be set only after the connection
     * is established.
     */
    public void setRemotePeer(Hash peer) { _remotePeer = peer; }
    /** 
     * The AES key used to verify packets, set only after the connection is
     * established.  
     */
    public void setCurrentMACKey(SessionKey key) { _currentMACKey = key; }
    /**
     * The AES key used to encrypt/decrypt packets, set only after the 
     * connection is established.
     */
    public void setCurrentCipherKey(SessionKey key) { _currentCipherKey = key; }
    /** 
     * The pending AES key for verifying packets if we are rekeying the 
     * connection, or null if we are not in the process of rekeying.
     */
    public void setNextMACKey(SessionKey key) { _nextMACKey = key; }
    /** 
     * The pending AES key for encrypting/decrypting packets if we are 
     * rekeying the connection, or null if we are not in the process 
     * of rekeying.
     */
    public void setNextCipherKey(SessionKey key) { _nextCipherKey = key; }
    /**
     * The keying material used for the rekeying, or null if we are not in
     * the process of rekeying.
     */
    public void setNextKeyingMaterial(byte data[]) { _nextKeyingMaterial = data; }
    /** true if we began the current rekeying, false otherwise */
    public void setRekeyBeganLocally(boolean local) { _rekeyBeganLocally = local; }
    /** when were the current cipher and MAC keys established/rekeyed? */
    public void setKeyEstablishedTime(long when) { _keyEstablishedTime = when; }

    /**
     *  Update the moving-average clock skew based on the current difference.
     *  The raw skew will be adjusted for RTT/2 here.
     *  @param skew milliseconds, NOT adjusted for RTT.
     *              A positive number means our clock is ahead of theirs.
     */
    public void adjustClockSkew(long skew) { 
        _clockSkew = (long) (0.9*(float)_clockSkew + 0.1*(float)(skew - (_rtt / 2))); 
    }

    /** what is the current receive second, for congestion control? */
    public void setCurrentReceiveSecond(long sec) { _currentReceiveSecond = sec; }
    /** when did we last send them a packet? */
    public void setLastSendTime(long when) { _lastSendTime = when; }
    /** when did we last receive a packet from them? */
    public void setLastReceiveTime(long when) { _lastReceiveTime = when; }
    /** return the smoothed send transfer rate */
    public int getSendBps() { return _sendBps; }
    public int getReceiveBps() { return _receiveBps; }
    public int incrementConsecutiveFailedSends() { 
        _concurrentMessagesActive--;
        if (_concurrentMessagesActive < 0)
            _concurrentMessagesActive = 0;
        
        //long now = _context.clock().now()/(10*1000);
        //if (_lastFailedSendPeriod >= now) {
        //    // ignore... too fast
        //} else {
        //    _lastFailedSendPeriod = now;
            _consecutiveFailedSends++; 
        //}
        return _consecutiveFailedSends;
    }
    public long getInactivityTime() {
        long now = _context.clock().now();
        long lastActivity = Math.max(_lastReceiveTime, _lastSendFullyTime);
        return now - lastActivity;
    }
    
    /** how fast we are sending *ack* packets */
    public int getSendACKBps() { return _sendACKBps; }
    public int getReceiveACKBps() { return _receiveACKBps; }
    
    /** 
     * have all of the packets received in the current second requested that
     * the previous second's ACKs be sent?
     */
    public void remoteDoesNotWantPreviousACKs() { _remoteWantsPreviousACKs = false; }
    
    /** should we ignore the peer state's congestion window, and let anything through? */
    private static final boolean IGNORE_CWIN = false;
    /** should we ignore the congestion window on the first push of every message? */
    private static final boolean ALWAYS_ALLOW_FIRST_PUSH = false;
    
    /** 
     * Decrement the remaining bytes in the current period's window,
     * returning true if the full size can be decremented, false if it
     * cannot.  If it is not decremented, the window size remaining is 
     * not adjusted at all.
     */
    public boolean allocateSendingBytes(int size, int messagePushCount) { return allocateSendingBytes(size, false, messagePushCount); }
    public boolean allocateSendingBytes(int size, boolean isForACK) { return allocateSendingBytes(size, isForACK, -1); }
    public boolean allocateSendingBytes(int size, boolean isForACK, int messagePushCount) { 
        long now = _context.clock().now();
        long duration = now - _lastSendRefill;
        if (duration >= 1000) {
            _sendWindowBytesRemaining = _sendWindowBytes;
            _sendBytes += size;
            _sendBps = (int)(0.9f*(float)_sendBps + 0.1f*((float)_sendBytes * (1000f/(float)duration)));
            if (isForACK) {
                _sendACKBytes += size;
                _sendACKBps = (int)(0.9f*(float)_sendACKBps + 0.1f*((float)_sendACKBytes * (1000f/(float)duration)));
            }
            _sendBytes = 0;
            _sendACKBytes = 0;
            _lastSendRefill = now;
        }
        //if (true) return true;
        if (IGNORE_CWIN || size <= _sendWindowBytesRemaining || (ALWAYS_ALLOW_FIRST_PUSH && messagePushCount == 0)) {
            if ( (messagePushCount == 0) && (_concurrentMessagesActive > _concurrentMessagesAllowed) ) {
                _consecutiveRejections++;
                _context.statManager().addRateData("udp.rejectConcurrentActive", _concurrentMessagesActive, _consecutiveRejections);
                return false;
            } else if (messagePushCount == 0) {
                _context.statManager().addRateData("udp.allowConcurrentActive", _concurrentMessagesActive, _concurrentMessagesAllowed);
                _concurrentMessagesActive++;
                if (_consecutiveRejections > 0) 
                    _context.statManager().addRateData("udp.rejectConcurrentSequence", _consecutiveRejections, _concurrentMessagesActive);
                _consecutiveRejections = 0;
            }
            _sendWindowBytesRemaining -= size; 
            _sendBytes += size;
            _lastSendTime = now;
            if (isForACK) 
                _sendACKBytes += size;
            return true;
        } else {
            return false;
        }
    }
    
    /** what IP+port is the peer sending and receiving packets on? */
    public void setRemoteAddress(byte ip[], int port) { 
        _remoteIP = ip;
        _remoteIPAddress = null;
        _remotePort = port; 
        _remoteHostId = new RemoteHostId(ip, port);
    }
    /** if we need to contact them, do we need to talk to an introducer? */
    public void setRemoteRequiresIntroduction(boolean required) { _remoteRequiresIntroduction = required; }
    /** 
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer 
     */
    public void setWeRelayToThemAs(long tag) { _weRelayToThemAs = tag; }
    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     */
    public void setTheyRelayToUsAs(long tag) { _theyRelayToUsAs = tag; }
    /** what is the largest packet we can send to the peer? */
    public void setMTU(int mtu) { 
        _mtu = mtu; 
        _mtuLastChecked = _context.clock().now();
    }
    public int getSlowStartThreshold() { return _slowStartThreshold; }
    public int getConcurrentSends() { return _concurrentMessagesActive; }
    public int getConcurrentSendWindow() { return _concurrentMessagesAllowed; }
    public int getConsecutiveSendRejections() { return _consecutiveRejections; }
    public boolean isInbound() { return _isInbound; }
    public void setInbound() { _isInbound = true; }
    public long getIntroducerTime() { return _lastIntroducerTime; }
    public void setIntroducerTime() { _lastIntroducerTime = _context.clock().now(); }
    
    /** we received the message specified completely */
    public void messageFullyReceived(Long messageId, int bytes) { messageFullyReceived(messageId, bytes, false); }
    public void messageFullyReceived(Long messageId, int bytes, boolean isForACK) {
        if (bytes > 0) {
            _receiveBytes += bytes;
            if (isForACK)
                _receiveACKBytes += bytes;
        } else {
            if (true || _retransmissionPeriodStart + 1000 < _context.clock().now()) {
                _packetsReceivedDuplicate++;
            } else {
                _retransmissionPeriodStart = _context.clock().now();
                _packetsReceivedDuplicate = 1;
            }
        }
        
        long now = _context.clock().now();
        long duration = now - _receivePeriodBegin;
        if (duration >= 1000) {
            _receiveBps = (int)(0.9f*(float)_receiveBps + 0.1f*((float)_receiveBytes * (1000f/(float)duration)));
            if (isForACK)
                _receiveACKBps = (int)(0.9f*(float)_receiveACKBps + 0.1f*((float)_receiveACKBytes * (1000f/(float)duration)));
            _receiveACKBytes = 0;
            _receiveBytes = 0;
            _receivePeriodBegin = now;
           _context.statManager().addRateData("udp.receiveBps", _receiveBps, 0);
        }
        
        if (_wantACKSendSince <= 0)
            _wantACKSendSince = now;
        _currentACKs.add(messageId);
        _messagesReceived++;
    }
    
    public void messagePartiallyReceived() {
        if (_wantACKSendSince <= 0)
            _wantACKSendSince = _context.clock().now();
    }
    
    /** 
     * Fetch the internal id (Long) to InboundMessageState for incomplete inbound messages.
     * Access to this map must be synchronized explicitly!
     */
    public Map<Long, InboundMessageState> getInboundMessages() { return _inboundMessages; }

    /**
     * Expire partially received inbound messages, returning how many are still pending.
     * This should probably be fired periodically, in case a peer goes silent and we don't
     * try to send them any messages (and don't receive any messages from them either)
     *
     */
    public int expireInboundMessages() { 
        int rv = 0;
        
        synchronized (_inboundMessages) {
            int remaining = _inboundMessages.size();
            for (Iterator iter = _inboundMessages.values().iterator(); remaining > 0; remaining--) {
                InboundMessageState state = (InboundMessageState)iter.next();
                if (state.isExpired() || _dead) {
                    iter.remove();
                } else {
                    if (state.isComplete()) {
                        _log.error("inbound message is complete, but wasn't handled inline? " + state + " with " + this);
                        iter.remove();
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
     *
     */
    private boolean congestionOccurred() {
        long now = _context.clock().now();
        if (_lastCongestionOccurred + _rto > now)
            return false; // only shrink once every few seconds
        _lastCongestionOccurred = now;
        
        _context.statManager().addRateData("udp.congestionOccurred", _sendWindowBytes, _sendBps);
        
        int congestionAt = _sendWindowBytes;
        //if (true)
        //    _sendWindowBytes -= 10000;
        //else
            _sendWindowBytes = _sendWindowBytes/2; //(_sendWindowBytes*2) / 3;
        if (_sendWindowBytes < MINIMUM_WINDOW_BYTES)
            _sendWindowBytes = MINIMUM_WINDOW_BYTES;
        //if (congestionAt/2 < _slowStartThreshold)
            _slowStartThreshold = congestionAt/2;
        return true;
    }
    
    /**
     * Grab a list of message ids (Long) that we want to send to the remote
     * peer, regardless of the packet size, but don't remove it from our 
     * "want to send" list.  If the message id is transmitted to the peer,
     * removeACKMessage(Long) should be called.
     *
     */
    public List<Long> getCurrentFullACKs() {
            // no such element exception seen here
            ArrayList<Long> rv = new ArrayList(_currentACKs);
            // include some for retransmission
            rv.addAll(_currentACKsResend);
            return rv;
    }

    public void removeACKMessage(Long messageId) {
            _currentACKs.remove(messageId);
            _currentACKsResend.offer(messageId);
            // trim down the resends
            while (_currentACKsResend.size() > MAX_RESEND_ACKS)
                _currentACKsResend.poll();
            _lastACKSend = _context.clock().now();
    }
    
    /**
     *  The max number of acks we save to send as duplicates
     */
    private static final int MAX_RESEND_ACKS = 16;
    /**
     *  The number of duplicate acks sent in each messge -
     *  Warning, this directly affects network overhead
     *  Was 16 but that's too much (64 bytes in a max 608 byte packet,
     *  and often much smaller)
     *  @since 0.7.13
     */
    private static final int MAX_RESEND_ACKS_LARGE = 9;
    /** for small MTU */
    private static final int MAX_RESEND_ACKS_SMALL = 4;
    
    /** 
     * grab a list of ACKBitfield instances, some of which may fully 
     * ACK a message while others may only partially ACK a message.  
     * the values returned are limited in size so that they will fit within
     * the peer's current MTU as an ACK - as such, not all messages may be
     * ACKed with this call.  Be sure to check getWantedACKSendSince() which
     * will be unchanged if there are ACKs remaining.
     *
     */
    public List<ACKBitfield> retrieveACKBitfields() { return retrieveACKBitfields(true); }

    public List<ACKBitfield> retrieveACKBitfields(boolean alwaysIncludeRetransmissions) {
        List<ACKBitfield> rv = new ArrayList(MAX_RESEND_ACKS);
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
            List<Long> randomResends = new ArrayList(_currentACKsResend);

            // As explained above, we include the acks in any order
            // since we are unlikely to get backed up -
            // just take them using the Set iterator.
            Iterator<Long> iter = _currentACKs.iterator();
            while (bytesRemaining >= 4 && iter.hasNext()) {
                Long val = iter.next();
                iter.remove();
                long id = val.longValue();
                rv.add(new FullACKBitfield(id));
                _currentACKsResend.offer(val);
                bytesRemaining -= 4;
            }
            if (_currentACKs.isEmpty())
                _wantACKSendSince = -1;
            if (alwaysIncludeRetransmissions || !rv.isEmpty()) {
                // now repeat by putting in some old ACKs
                // randomly selected from the Resend queue.
                // Maybe we should only resend each one a certain number of times...
                int oldIndex = Math.min(resendSize, maxResendAcks);
                if (oldIndex > 0 && oldIndex < resendSize)
                    Collections.shuffle(randomResends, _context.random());
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
            }
            // trim down the resends
            while (_currentACKsResend.size() > MAX_RESEND_ACKS)
                _currentACKsResend.poll();

        int partialIncluded = 0;
        if (bytesRemaining > 4) {
            // ok, there's room to *try* to fit in some partial ACKs, so
            // we should try to find some packets to partially ACK 
            // (preferably the ones which have the most received fragments)
            List<ACKBitfield> partial = new ArrayList();
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

        _lastACKSend = _context.clock().now();
        if (rv == null)
            rv = Collections.EMPTY_LIST;
        if (partialIncluded > 0)
            _context.statManager().addRateData("udp.sendACKPartial", partialIncluded, rv.size() - partialIncluded);
        return rv;
    }
    
    void fetchPartialACKs(List<ACKBitfield> rv) {
        InboundMessageState states[] = null;
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
                } else {
                    if (!state.isComplete()) {
                        if (states == null)
                            states = new InboundMessageState[numMessages];
                        states[curState++] = state;
                    }
                }
            }
        }
        if (states != null) {
            for (int i = curState-1; i >= 0; i--) {
                if (states[i] != null)
                    rv.add(states[i].createACKBitfield());
            }
        }
    }
    
    /** represent a full ACK of a message */
    private static class FullACKBitfield implements ACKBitfield {
        private long _msgId;
        public FullACKBitfield(long id) { _msgId = id; }
        public int fragmentCount() { return 0; }
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
        public String toString() { return "Full ACK of " + _msgId; }
    }
        
    /** we sent a message which was ACKed containing the given # of bytes */
    public void messageACKed(int bytesACKed, long lifetime, int numSends) {
        _concurrentMessagesActive--;
        if (_concurrentMessagesActive < 0)
            _concurrentMessagesActive = 0;
        
        _consecutiveFailedSends = 0;
        // _lastFailedSendPeriod = -1;
        if (numSends < 2) {
            if (_context.random().nextInt(_concurrentMessagesAllowed) <= 0)
                _concurrentMessagesAllowed++;
            
            if (_sendWindowBytes <= _slowStartThreshold) {
                _sendWindowBytes += bytesACKed;
            } else {
                if (false) {
                    _sendWindowBytes += 16; // why 16?
                } else {
                    float prob = ((float)bytesACKed) / ((float)(_sendWindowBytes<<1));
                    float v = _context.random().nextFloat();
                    if (v < 0) v = 0-v;
                    if (v <= prob)
                        _sendWindowBytes += bytesACKed; //512; // bytesACKed;
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
        
        if (true) {
            if (_sendWindowBytesRemaining + bytesACKed <= _sendWindowBytes)
                _sendWindowBytesRemaining += bytesACKed;
            else
                _sendWindowBytesRemaining = _sendWindowBytes;
        }
        
        _messagesSent++;
        if (numSends < 2) {
            recalculateTimeouts(lifetime);
            adjustMTU();
        }
        else if (_log.shouldLog(Log.INFO))
            _log.info("acked after numSends=" + numSends + " w/ lifetime=" + lifetime + " and size=" + bytesACKed);
        
        _context.statManager().addRateData("udp.sendBps", _sendBps, lifetime);
    }

    /** adjust the tcp-esque timeouts */
    private void recalculateTimeouts(long lifetime) {
        _rttDeviation = _rttDeviation + (int)(0.25d*(Math.abs(lifetime-_rtt)-_rttDeviation));
        
        // the faster we are going, the slower we want to reduce the rtt
        float scale = 0.1f;
        if (_sendBps > 0)
            scale = ((float)lifetime) / (float)((float)lifetime + (float)_sendBps);
        if (scale < 0.001f) scale = 0.001f;
        
        _rtt = (int)(((float)_rtt)*(1.0f-scale) + (scale)*(float)lifetime);
        _rto = _rtt + (_rttDeviation<<2);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Recalculating timeouts w/ lifetime=" + lifetime + ": rtt=" + _rtt
                       + " rttDev=" + _rttDeviation + " rto=" + _rto);
        if (_rto < minRTO())
            _rto = minRTO();
        if (_rto > MAX_RTO)
            _rto = MAX_RTO;
    }
    
    private void adjustMTU() {
        double retransPct = 0;
        if (_packetsTransmitted > 10) {
            retransPct = (double)_packetsRetransmitted/(double)_packetsTransmitted;
            boolean wantLarge = retransPct < .30d; // heuristic to allow fairly lossy links to use large MTUs
            if (wantLarge && _mtu != LARGE_MTU) {
                if (_context.random().nextLong(_mtuDecreases) <= 0) {
                    _mtu = LARGE_MTU;
                    _mtuIncreases++;
                    _context.statManager().addRateData("udp.mtuIncrease", _mtuIncreases, _mtuDecreases);
		}
	    } else if (!wantLarge && _mtu == LARGE_MTU) {
                _mtu = MIN_MTU;
                _mtuDecreases++;
                _context.statManager().addRateData("udp.mtuDecrease", _mtuDecreases, _mtuIncreases);
	    }
        } else {
            _mtu = DEFAULT_MTU;
        }
    }
    
    /** we are resending a packet, so lets jack up the rto */
    public void messageRetransmitted(int packets) { 
        //long now = _context.clock().now();
        //if (true || _retransmissionPeriodStart + 1000 <= now) {
            _packetsRetransmitted += packets;
        /*****
        } else {
            _packetRetransmissionRate = (int)((float)(0.9f*_packetRetransmissionRate) + (float)(0.1f*_packetsRetransmitted));
            //_packetsPeriodTransmitted = _packetsTransmitted - _retransmissionPeriodStart;
            _packetsPeriodRetransmitted = (int)_packetsRetransmitted;
            _retransmissionPeriodStart = now;
            _packetsRetransmitted = packets;
        }
        *****/
        congestionOccurred();
        _context.statManager().addRateData("udp.congestedRTO", _rto, _rttDeviation);
        adjustMTU();
        //_rto *= 2; 
    }
    public void packetsTransmitted(int packets) { 
        //long now = _context.clock().now();
        _packetsTransmitted += packets; 
        //_packetsPeriodTransmitted += packets;
        /*****
        if (false && _retransmissionPeriodStart + 1000 <= now) {
            _packetRetransmissionRate = (int)((float)(0.9f*_packetRetransmissionRate) + (float)(0.1f*_packetsRetransmitted));
            _retransmissionPeriodStart = 0;
            _packetsPeriodRetransmitted = (int)_packetsRetransmitted;
            _packetsRetransmitted = 0;
        }
        *****/
    }
    /** how long does it usually take to get a message ACKed? */
    public int getRTT() { return _rtt; }
    /** how soon should we retransmit an unacked packet? */
    public int getRTO() { return _rto; }
    /** how skewed are the measured RTTs? */
    public long getRTTDeviation() { return _rttDeviation; }
    
    public long getMessagesSent() { return _messagesSent; }
    public long getMessagesReceived() { return _messagesReceived; }
    public long getPacketsTransmitted() { return _packetsTransmitted; }
    public long getPacketsRetransmitted() { return _packetsRetransmitted; }
    public long getPacketsPeriodTransmitted() { return _packetsPeriodTransmitted; }
    public int getPacketsPeriodRetransmitted() { return _packetsPeriodRetransmitted; }
    /** avg number of packets retransmitted for every 100 packets */
    public long getPacketRetransmissionRate() { return _packetRetransmissionRate; }
    public long getPacketsReceived() { return _packetsReceived; }
    public long getPacketsReceivedDuplicate() { return _packetsReceivedDuplicate; }
    public void packetReceived(int size) { 
        _packetsReceived++; 
        if (size <= MIN_MTU)
            _consecutiveSmall++;
        else
            _consecutiveSmall = 0;
        
	if (_packetsReceived > 50) {
            if (_consecutiveSmall < 50)
                _mtuReceive = LARGE_MTU;
            else
                _mtuReceive = MIN_MTU;
	}
    }
    
    /** 
     * we received a backoff request, so cut our send window
     */
    public void ECNReceived() {
        congestionOccurred();
        _currentSecondECNReceived = true;
        _lastReceiveTime = _context.clock().now();
    }
    
    public void dataReceived() {
        _lastReceiveTime = _context.clock().now();
    }
    
    /** when did we last send an ACK to the peer? */
    public long getLastACKSend() { return _lastACKSend; }
    public void setLastACKSend(long when) { _lastACKSend = when; }
    public long getWantedACKSendSince() { return _wantACKSendSince; }
    public boolean unsentACKThresholdReached() {
        int threshold = countMaxACKData() / 4;
        return _currentACKs.size() >= threshold;
    }

    /** @return MTU - 83 */
    private int countMaxACKData() {
        return _mtu 
                - IP_HEADER_SIZE
                - UDP_HEADER_SIZE
                - UDPPacket.IV_SIZE 
                - UDPPacket.MAC_SIZE
                - 1 // type flag
                - 4 // timestamp
                - 1 // data flag
                - 1 // # ACKs
                - 16; // padding safety
    }

    private int minRTO() {
        if (_packetRetransmissionRate < 10)
            return MIN_RTO;
        else if (_packetRetransmissionRate < 50)
            return 2*MIN_RTO;
        else
            return MAX_RTO;
    }
    
    RemoteHostId getRemoteHostId() { return _remoteHostId; }
    
    public int add(OutboundMessageState state) {
        if (_dead) { 
            _transport.failed(state, false);
            return 0;
	}
        state.setPeer(this);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Adding to " + _remotePeer.toBase64() + ": " + state.getMessageId());
        List<OutboundMessageState> msgs = _outboundMessages;
        if (msgs == null) return 0;
        int rv = 0;
        boolean fail = false;
        synchronized (msgs) {
            rv = msgs.size() + 1;
            if (rv > 32) { 
                // 32 queued messages?  to *one* peer?  nuh uh.
                fail = true;
                rv--;
            } else if (_retransmitter != null) {
                long lifetime = _retransmitter.getLifetime();
                long totalLifetime = lifetime;
                for (int i = 1; i < msgs.size(); i++) { // skip the first, as thats the retransmitter
                    OutboundMessageState cur = (OutboundMessageState)msgs.get(i);
                    totalLifetime += cur.getLifetime();
                }
                long remaining = -1;
                OutNetMessage omsg = state.getMessage();
                if (omsg != null)
                    remaining = omsg.getExpiration() - _context.clock().now();
                else
                    remaining = 10*1000 - state.getLifetime();
                
                if (remaining <= 0)
                    remaining = 1; // total lifetime will exceed it anyway, guaranteeing failure
                float pDrop = totalLifetime / (float)remaining;
                pDrop = pDrop * pDrop * pDrop;
                if (false && (pDrop >= _context.random().nextFloat())) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Proactively tail dropping for " + _remotePeer.toBase64() + " (messages=" + msgs.size() 
                                  + " headLifetime=" + lifetime + " totalLifetime=" + totalLifetime + " curLifetime=" + state.getLifetime() 
                                  + " remaining=" + remaining + " pDrop=" + pDrop + ")");
                    _context.statManager().addRateData("udp.queueDropSize", msgs.size(), totalLifetime);
                    fail = true;
                } else { 
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Probabalistically allowing for " + _remotePeer.toBase64() + " (messages=" + msgs.size() 
                                   + " headLifetime=" + lifetime + " totalLifetime=" + totalLifetime + " curLifetime=" + state.getLifetime() 
                                   + " remaining=" + remaining + " pDrop=" + pDrop + ")");
                    _context.statManager().addRateData("udp.queueAllowTotalLifetime", totalLifetime, lifetime);
                    msgs.add(state);
                }
            } else {
                msgs.add(state);
            }
        }
        if (fail)
            _transport.failed(state, false);
        return rv;
    }
    /** drop all outbound messages */
    public void dropOutbound() {
        //if (_dead) return;
        _dead = true;
        List<OutboundMessageState> msgs = _outboundMessages;
        //_outboundMessages = null;
        _retransmitter = null;
        if (msgs != null) {
            int sz = 0;
            List<OutboundMessageState> tempList = null;
            synchronized (msgs) {
                sz = msgs.size();
                if (sz > 0) {
                    tempList = new ArrayList(msgs);
                    msgs.clear();
		}
            }
            for (int i = 0; i < sz; i++)
                _transport.failed(tempList.get(i), false);
        }
        // so the ACKSender will drop this peer from its queue
        _wantACKSendSince = -1;
    }
    
    public int getOutboundMessageCount() {
        List<OutboundMessageState> msgs = _outboundMessages;
        if (_dead) return 0;
        if (msgs != null) {
            synchronized (msgs) {
                return msgs.size();
            }
        } else {
            return 0;
        }
    }
    
    /**
     * Expire / complete any outbound messages
     * @return number of active outbound messages remaining
     */
    public int finishMessages() {
        int rv = 0;
        List<OutboundMessageState> msgs = _outboundMessages;
        if (_dead) {
            dropOutbound();
            return 0;
	}
        List<OutboundMessageState> succeeded = null;
        List<OutboundMessageState> failed = null;
        synchronized (msgs) {
            int size = msgs.size();
            for (int i = 0; i < size; i++) {
                OutboundMessageState state = msgs.get(i);
                if (state.isComplete()) {
                    msgs.remove(i);
                    i--;
                    size--;
                    if (_retransmitter == state)
                        _retransmitter = null;
                    if (succeeded == null) succeeded = new ArrayList(4);
                    succeeded.add(state);
                } else if (state.isExpired()) {
                    msgs.remove(i);
                    i--;
                    size--;
                    if (_retransmitter == state)
                        _retransmitter = null;
                    _context.statManager().addRateData("udp.sendFailed", state.getPushCount(), state.getLifetime());
                    if (failed == null) failed = new ArrayList(4);
                    failed.add(state);
                } else if (state.getPushCount() > OutboundMessageFragments.MAX_VOLLEYS) {
                    msgs.remove(i);
                    i--;
                    size--;
                    if (state == _retransmitter)
                        _retransmitter = null;
                    _context.statManager().addRateData("udp.sendAggressiveFailed", state.getPushCount(), state.getLifetime());
                    if (failed == null) failed = new ArrayList(4);
                    failed.add(state);
                } // end (pushCount > maxVolleys)
            } // end iterating over outbound messages
            rv = msgs.size();
        }
        
        for (int i = 0; succeeded != null && i < succeeded.size(); i++) {
            OutboundMessageState state = succeeded.get(i);
            _transport.succeeded(state);
            state.releaseResources();
            OutNetMessage msg = state.getMessage();
            if (msg != null)
                msg.timestamp("sending complete");
        }
        
        for (int i = 0; failed != null && i < failed.size(); i++) {
            OutboundMessageState state = failed.get(i);
            OutNetMessage msg = state.getMessage();
            if (msg != null) {
                msg.timestamp("expired in the active pool");
                _transport.failed(state);
            } else {
                // it can not have an OutNetMessage if the source is the
                // final after establishment message
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to send a direct message: " + state);
            }
            state.releaseResources();
        }
        
        return rv;
    }
    
    /**
     * Pick a message we want to send and allocate it out of our window
     * @return allocated message to send, or null if no messages or no resources
     *
     */
    public OutboundMessageState allocateSend() {
        int total = 0;
        List<OutboundMessageState> msgs = _outboundMessages;
        if (_dead) return null;
        synchronized (msgs) {
            int size = msgs.size();
            for (int i = 0; i < size; i++) {
                OutboundMessageState state = msgs.get(i);
                if (locked_shouldSend(state)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Allocate sending to " + _remotePeer.toBase64() + ": " + state.getMessageId());
                    /*
                    while (iter.hasNext()) {
                        OutboundMessageState later = (OutboundMessageState)iter.next();
                        OutNetMessage msg = later.getMessage();
                        if (msg != null)
                            msg.timestamp("not reached for allocation " + msgs.size() + " other peers");
                    }
                     */
                    return state;
                } /* else {
                    OutNetMessage msg = state.getMessage();
                    if (msg != null)
                        msg.timestamp("passed over for allocation with " + msgs.size() + " peers");
                } */
            }
            total = msgs.size();
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Nothing to send to " + _remotePeer.toBase64() + ", with " + total + " remaining");
        return null;
    }
    
    /**
     * return how long to wait before sending, or -1 if we have nothing to send
     */
    public int getNextDelay() {
        int rv = -1;
        long now = _context.clock().now();
        List<OutboundMessageState> msgs = _outboundMessages;
        if (_dead) return -1;
        synchronized (msgs) {
            if (_retransmitter != null) {
                rv = (int)(_retransmitter.getNextSendTime() - now);
                if (rv <= 0)
                    return 1;
                else
                    return rv;
            }
            int size = msgs.size();
            for (int i = 0; i < size; i++) {
                OutboundMessageState state = msgs.get(i);
                int delay = (int)(state.getNextSendTime() - now);
                if (delay <= 0)
                    delay = 1;
                if ( (rv <= 0) || (delay < rv) )
                    rv = delay;
            }
        }
        return rv;
    }

    /**
     * If set to true, we should throttle retransmissions of all but the first message in
     * flight to a peer.  If set to false, we will only throttle the initial flight of a
     * message to a peer while a retransmission is going on.
     */
    private static final boolean THROTTLE_RESENDS = true;
    /** 
     * if true, throttle the initial volley of a message if there is a resend in progress.
     * if false, always send the first volley, regardless of retransmissions (but keeping in
     * mind bw/cwin throttle, etc)
     *
     */
    private static final boolean THROTTLE_INITIAL_SEND = true;
    
    private static final int SSU_HEADER_SIZE = 46;
    static final int UDP_HEADER_SIZE = 8;
    static final int IP_HEADER_SIZE = 20;
    /** how much payload data can we shove in there? */
    private static final int fragmentSize(int mtu) {
        return mtu - SSU_HEADER_SIZE - UDP_HEADER_SIZE - IP_HEADER_SIZE;
    }
    
    private boolean locked_shouldSend(OutboundMessageState state) {
        long now = _context.clock().now();
        if (state.getNextSendTime() <= now) {
            if (!state.isFragmented()) {
                state.fragment(fragmentSize(getMTU()));
                if (state.getMessage() != null)
                    state.getMessage().timestamp("fragment into " + state.getFragmentCount());

                if (_log.shouldLog(Log.INFO))
                    _log.info("Fragmenting " + state);
            }

            
            OutboundMessageState retrans = _retransmitter;
            if ( (retrans != null) && ( (retrans.isExpired() || retrans.isComplete()) ) ) {
                _retransmitter = null;
                retrans = null;
	    }
            
            if ( (retrans != null) && (retrans != state) ) {
                // choke it, since there's already another message retransmitting to this
                // peer.
                _context.statManager().addRateData("udp.blockedRetransmissions", _packetsRetransmitted, _packetsTransmitted);
                int max = state.getMaxSends();
                if ( (max <= 0) && (!THROTTLE_INITIAL_SEND) ) {
                    //if (state.getMessage() != null)
                    //    state.getMessage().timestamp("another message is retransmitting, but we want to send our first volley...");
                } else if ( (max <= 0) || (THROTTLE_RESENDS) ) {
                    //if (state.getMessage() != null)
                    //    state.getMessage().timestamp("choked, with another message retransmitting");
                    return false;
                } else {
                    //if (state.getMessage() != null)
                    //    state.getMessage().timestamp("another message is retransmitting, but since we've already begun sending...");                    
                }
            }

            int size = state.getUnackedSize();
            if (allocateSendingBytes(size, state.getPushCount())) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Allocation of " + size + " allowed with " 
                              + getSendWindowBytesRemaining() 
                              + "/" + getSendWindowBytes() 
                              + " remaining"
                              + " for message " + state.getMessageId() + ": " + state);

                if (state.getPushCount() > 0)
                    _retransmitter = state;

                state.push();
            
                int rto = getRTO();
                state.setNextSendTime(now + rto);

                //if (peer.getSendWindowBytesRemaining() > 0)
                //    _throttle.unchoke(peer.getRemotePeer());
                return true;
            } else {
                _context.statManager().addRateData("udp.sendRejected", state.getPushCount(), state.getLifetime());
                //if (state.getMessage() != null)
                //    state.getMessage().timestamp("send rejected, available=" + getSendWindowBytesRemaining());
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Allocation of " + size + " rejected w/ wsize=" + getSendWindowBytes()
                              + " available=" + getSendWindowBytesRemaining()
                              + " for message " + state.getMessageId() + ": " + state);
                state.setNextSendTime(now + (ACKSender.ACK_FREQUENCY / 2) +
                                      _context.random().nextInt(ACKSender.ACK_FREQUENCY)); //(now + 1024) & ~SECOND_MASK);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Retransmit after choke for next send time in " + (state.getNextSendTime()-now) + "ms");
                //_throttle.choke(peer.getRemotePeer());

                //if (state.getMessage() != null)
                //    state.getMessage().timestamp("choked, not enough available, wsize=" 
                //                                 + getSendWindowBytes() + " available="
                //                                 + getSendWindowBytesRemaining());
                return false;
            }
        } // nextTime <= now 

        return false;
    }
    
    public int acked(long messageId) {
        OutboundMessageState state = null;
        List<OutboundMessageState> msgs = _outboundMessages;
        if (_dead) return 0;
        synchronized (msgs) {
            int sz = msgs.size();
            for (int i = 0; i < sz; i++) {
                state = msgs.get(i);
                if (state.getMessageId() == messageId) {
                    msgs.remove(i);
                    break;
                } else {
                    state = null;
                }
            }
            if ( (state != null) && (state == _retransmitter) )
                _retransmitter = null;
        }
        
        if (state != null) {
            int numSends = state.getMaxSends();
            //if (state.getMessage() != null) {
            //    state.getMessage().timestamp("acked after " + numSends
            //                                 + " lastReceived: " 
            //                                 + (_context.clock().now() - getLastReceiveTime())
            //                                 + " lastSentFully: " 
            //                                 + (_context.clock().now() - getLastSendFullyTime()));
            //}

            if (_log.shouldLog(Log.INFO))
                _log.info("Received ack of " + messageId + " by " + _remotePeer.toBase64() 
                          + " after " + state.getLifetime() + " and " + numSends + " sends");
            _context.statManager().addRateData("udp.sendConfirmTime", state.getLifetime(), state.getLifetime());
            if (state.getFragmentCount() > 1)
                _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount(), state.getLifetime());
            if (numSends > 1)
                _context.statManager().addRateData("udp.sendConfirmVolley", numSends, state.getFragmentCount());
            _transport.succeeded(state);
            int numFragments = state.getFragmentCount();
            // this adjusts the rtt/rto/window/etc
            messageACKed(numFragments*state.getFragmentSize(), state.getLifetime(), numSends);
            //if (getSendWindowBytesRemaining() > 0)
            //    _throttle.unchoke(peer.getRemotePeer());
            
            state.releaseResources();
            return numFragments;
        } else {
            // dupack, likely
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received an ACK for a message not pending: " + messageId);
            return 0;
        }
    }
    
    public void acked(ACKBitfield bitfield) {
        if (_dead)
            return;
        
        if (bitfield.receivedComplete()) {
            acked(bitfield.getMessageId());
            return;
        }
    
        List<OutboundMessageState> msgs = _outboundMessages;
        
        OutboundMessageState state = null;
        boolean isComplete = false;
        synchronized (msgs) {
            for (int i = 0; i < msgs.size(); i++) {
                state = msgs.get(i);
                if (state.getMessageId() == bitfield.getMessageId()) {
                    boolean complete = state.acked(bitfield);
                    if (complete) {
                        isComplete = true;
                        msgs.remove(i);
                        if (state == _retransmitter)
                            _retransmitter = null;
                    }
                    break;
                } else {
                    state = null;
                }
            }
        }
        
        if (state != null) {
            int numSends = state.getMaxSends();
                        
            int bits = bitfield.fragmentCount();
            int numACKed = 0;
            for (int i = 0; i < bits; i++)
                if (bitfield.received(i))
                    numACKed++;
            
            _context.statManager().addRateData("udp.partialACKReceived", numACKed, state.getLifetime());
            
            if (_log.shouldLog(Log.INFO))
                _log.info("Received partial ack of " + state.getMessageId() + " by " + _remotePeer.toBase64() 
                          + " after " + state.getLifetime() + " and " + numSends + " sends: " + bitfield + ": completely removed? " 
                          + isComplete + ": " + state);
            
            if (isComplete) {
                _context.statManager().addRateData("udp.sendConfirmTime", state.getLifetime(), state.getLifetime());
                if (state.getFragmentCount() > 1)
                    _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount(), state.getLifetime());
                if (numSends > 1)
                    _context.statManager().addRateData("udp.sendConfirmVolley", numSends, state.getFragmentCount());
                //if (state.getMessage() != null)
                //    state.getMessage().timestamp("partial ack to complete after " + numSends);
                _transport.succeeded(state);
                
                // this adjusts the rtt/rto/window/etc
                messageACKed(state.getFragmentCount()*state.getFragmentSize(), state.getLifetime(), 0);
                //if (state.getPeer().getSendWindowBytesRemaining() > 0)
                //    _throttle.unchoke(state.getPeer().getRemotePeer());

                state.releaseResources();
            } else {
                //if (state.getMessage() != null)
                //    state.getMessage().timestamp("partial ack after " + numSends + ": " + bitfield.toString());
            }
            return;
        } else {
            // dupack
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received an ACK for a message not pending: " + bitfield);
            return;
        }
    }
    
    /**
     * Transfer the basic activity/state from the old peer to the current peer
     *
     */
    public void loadFrom(PeerState oldPeer) {
        _rto = oldPeer._rto;
        _rtt = oldPeer._rtt;
        _rttDeviation = oldPeer._rttDeviation;
        _slowStartThreshold = oldPeer._slowStartThreshold;
        _sendWindowBytes = oldPeer._sendWindowBytes;
        oldPeer._dead = true;
        
        List<Long> tmp = new ArrayList();
        // AIOOBE from concurrent access
        //tmp.addAll(oldPeer._currentACKs);
        for (Long l : oldPeer._currentACKs) {
            tmp.add(l);
        }
        oldPeer._currentACKs.clear();

        if (!_dead) {
            _currentACKs.addAll(tmp);
	}
        tmp.clear();
        
        tmp.addAll(oldPeer._currentACKsResend);
        oldPeer._currentACKsResend.clear();

        if (!_dead) {
            _currentACKsResend.addAll(tmp);
	}
        
        Map<Long, InboundMessageState> msgs = new HashMap();
        synchronized (oldPeer._inboundMessages) {
            msgs.putAll(oldPeer._inboundMessages);
            oldPeer._inboundMessages.clear();
        }
        if (!_dead) {
            synchronized (_inboundMessages) { _inboundMessages.putAll(msgs); }
	}
        msgs.clear();
        
        List<OutboundMessageState> tmp2 = new ArrayList();
        OutboundMessageState retransmitter = null;
        synchronized (oldPeer._outboundMessages) {
            tmp2.addAll(oldPeer._outboundMessages);
            oldPeer._outboundMessages.clear();
            retransmitter = oldPeer._retransmitter;
            oldPeer._retransmitter = null;
        }
        if (!_dead) {
            synchronized (_outboundMessages) {
                _outboundMessages.addAll(tmp2);
                _retransmitter = retransmitter;
            }
        }
    }

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
        StringBuilder buf = new StringBuilder(64);
        buf.append(_remoteHostId.toString());
        if (_remotePeer != null)
            buf.append(" ").append(_remotePeer.toBase64().substring(0,6));

        long now = _context.clock().now();
        buf.append(" recvAge: ").append(now-_lastReceiveTime);
        buf.append(" sendAge: ").append(now-_lastSendFullyTime);
        buf.append(" sendAttemptAge: ").append(now-_lastSendTime);
        buf.append(" sendACKAge: ").append(now-_lastACKSend);
        buf.append(" lifetime: ").append(now-_keyEstablishedTime);
        buf.append(" cwin: ").append(_sendWindowBytes);
        buf.append(" acwin: ").append(_sendWindowBytesRemaining);
        buf.append(" consecFail: ").append(_consecutiveFailedSends);
        buf.append(" recv OK/Dup: ").append(_packetsReceived).append('/').append(_packetsReceivedDuplicate);
        buf.append(" send OK/Dup: ").append(_packetsTransmitted).append('/').append(_packetsRetransmitted);
        return buf.toString();
    }
}
