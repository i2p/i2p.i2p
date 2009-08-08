package net.i2p.client.streaming;

import java.util.Properties;

/**
 * Define the current options for the con (and allow custom tweaking midstream)
 *
 */
public class ConnectionOptions extends I2PSocketOptionsImpl {
    private int _connectDelay;
    private boolean _fullySigned;
    private boolean _answerPings;
    private volatile int _windowSize;
    private int _receiveWindow;
    private int _profile;
    private int _rtt;
    private int _rttDev;
    private int _rto;
    private int _resendDelay;
    private int _sendAckDelay;
    private int _maxMessageSize;
    private int _choke;
    private int _maxResends;
    private int _inactivityTimeout;
    private int _inactivityAction;
    private int _inboundBufferSize;
    private int _maxWindowSize;
    private int _congestionAvoidanceGrowthRateFactor;
    private int _slowStartGrowthRateFactor;

    public static final int PROFILE_BULK = 1;
    public static final int PROFILE_INTERACTIVE = 2;
    
    /** on inactivity timeout, do nothing */
    public static final int INACTIVITY_ACTION_NOOP = 0;
    /** on inactivity timeout, close the connection */
    public static final int INACTIVITY_ACTION_DISCONNECT = 1;
    /** on inactivity timeout, send a payload message */
    public static final int INACTIVITY_ACTION_SEND = 2;
    
    public static final String PROP_CONNECT_DELAY = "i2p.streaming.connectDelay";
    public static final String PROP_PROFILE = "i2p.streaming.profile";
    public static final String PROP_MAX_MESSAGE_SIZE = "i2p.streaming.maxMessageSize";
    public static final String PROP_MAX_RESENDS = "i2p.streaming.maxResends";
    public static final String PROP_INITIAL_RTT = "i2p.streaming.initialRTT";
    public static final String PROP_INITIAL_RESEND_DELAY = "i2p.streaming.initialResendDelay";
    public static final String PROP_INITIAL_ACK_DELAY = "i2p.streaming.initialAckDelay";
    public static final String PROP_INITIAL_WINDOW_SIZE = "i2p.streaming.initialWindowSize";
    public static final String PROP_INITIAL_RECEIVE_WINDOW = "i2p.streaming.initialReceiveWindow";
    public static final String PROP_INACTIVITY_TIMEOUT = "i2p.streaming.inactivityTimeout";
    public static final String PROP_INACTIVITY_ACTION = "i2p.streaming.inactivityAction";
    public static final String PROP_MAX_WINDOW_SIZE = "i2p.streaming.maxWindowSize";
    public static final String PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR = "i2p.streaming.congestionAvoidanceGrowthRateFactor";
    public static final String PROP_SLOW_START_GROWTH_RATE_FACTOR = "i2p.streaming.slowStartGrowthRateFactor";
    public static final String PROP_ANSWER_PINGS = "i2p.streaming.answerPings";
    
    private static final int TREND_COUNT = 3;
    static final int INITIAL_WINDOW_SIZE = 6;
    static final int DEFAULT_MAX_SENDS = 8;
    public static final int DEFAULT_INITIAL_RTT = 8*1000;    
    static final int MIN_WINDOW_SIZE = 1;
    private static final boolean DEFAULT_ANSWER_PINGS = true;

    // Syncronization fix, but doing it this way causes NPE...
    // private final int _trend[] = new int[TREND_COUNT];
    private int _trend[];

    /**
     *  OK, here is the calculation on the message size to fit in a single
     *  tunnel message without fragmentation.
     *  This is based on documentation, the code, and logging, however there are still
     *  some parts that could use more research.
     *
     *  1024 Tunnel Message
     *  - 21 Header (see router/tunnel/BatchedPreprocessor.java)
     * -----
     *  1003 Tunnel Payload
     *  - 39 Unfragmented instructions (see router/tunnel/TrivialPreprocessor.java)
     * -----
     *   964 Unfragmented I2NP Message
     *  - 20 ??
     * -----
     *   944 Garlic Message padded to 16 bytes
     *  -  0 Pad to 16 bytes (why?)
     * -----
     *   944 Garlic Message (assumes no bundled leaseSet or keys)
     *  - 71 Garlic overhead
     * -----
     *   873 Tunnel Data Message
     *  - 84 ??
     * -----
     *   789 Gzipped I2NP message
     *  - 23 Gzip 10 byte header, 5 byte block header, 8 byte trailer (yes we always use gzip, but it
     *       probably isn't really compressing, just adding the headers and trailer, since
     *       HTTP Server already compresses, and most P2P files aren't compressible.
     *       (see client/I2PSessionImpl2.java, util/ReusableGZipOutputStream.java, and the gzip and deflate specs)
     * -----
     *   766
     *  - 28 Streaming header (24 min, but leave room for a nack or other optional things) (See Packet.java)
     * -----
     *   738 Streaming message size
     *
     *
     * FOR TWO TUNNEL MESSAGES:
     *
     *  2048 2 Tunnel Messages
     *  - 42 2 Headers
     * -----
     *  2006 Tunnel Payload
     *  - 50 Fragmented instructions (43 for first + 7 for second)
     * -----
     *  1956 Unfragmented I2NP Message
     *  - 20 ??
     * -----
     *  1936 Garlic Message padded to 16 bytes
     *  1936
     *  -  0 Pad to 16 bytes
     * -----
     *  1936 Garlic Message
     *  - 71 Garlic overhead
     * -----
     *  1865 Tunnel Data Message
     *  - 84 ??
     * -----
     *  1781 Gzipped I2NP message
     *  - 23 Gzip header
     * -----
     *  1758
     *  - 28 Streaming header
     * -----
     *  1730 Streaming message size to fit in 2 tunnel messages
     *
     *
     * Similarly:
     *   3 msgs: 2722
     *   4 msgs: 3714
     *
     *
     * Before release 0.6.1.14 this was 4096.
     * From release 0.6.1.14 through release 0.6.4, this was 960.
     * It was claimed in the comment that this fit in
     * a single tunnel message (and the checkin comment says the goal was to
     * increase reliability at the expense of throughput),
     * clearly from the math above that was not correct.
     * (Before 0.6.2, the reply leaseSet was bundled with every message, so it didn't even
     * fit in TWO tunnel messages - more like 2 1/3)
     *
     * Now, it's not clear how often we will get the ideal situation (no reply leaseSet bundling,
     * no key bundling, and especially not having a small message ahead of you, which will then cause
     * fragmentation for all subsequent messages until the queue is emptied - BatchedPreprocessor
     * doesn't do reordering, and it isn't clear to me if it could). In particular the initial
     * messages in a new stream are much larger due to the leaseSet and key bundling.
     * But for long-lived streams (like with i2psnark) this should pay dividends.
     * The tunnel.batch* stats should provide some data for test comparisons.
     *
     * As MTU and MRU are identical and are negotiated to the lowest value
     * for the two ends, you can't do widespread testing of a higher value.
     * Unless we change to allow MTU and MRU to be different,
     * which would be a pain because it would mess up our buffer scheme.
     * Both 738 and 1730 have been tested to verify that the math above is correct.
     * So let's try 1730 for release 0.6.5. This will allow for 738 testing as well,
     * with i2p.streaming.maxMessageSize=738 (in configadvanced.jsp, or in i2ptunnel, or
     * i2psnark, for example).
     *
     * Not that an isolated single packet is very common, but
     * in this case, 960 was 113.3% total overhead.
     * Compared to 738 (38.8% overhead) and 1730 (18.4%).
     *
     */
    public static final int DEFAULT_MAX_MESSAGE_SIZE = 1730;
    public static final int MIN_MESSAGE_SIZE = 512;

    public ConnectionOptions() {
        super();
    }
    
    public ConnectionOptions(Properties opts) {
        super(opts);
    }
    
    public ConnectionOptions(I2PSocketOptions opts) {
        super(opts);
    }
    
    public ConnectionOptions(ConnectionOptions opts) {
        super(opts);
        if (opts != null) {
            setMaxWindowSize(opts.getMaxWindowSize());
            setConnectDelay(opts.getConnectDelay());
            setProfile(opts.getProfile());
            setRTT(opts.getRTT());
            setRequireFullySigned(opts.getRequireFullySigned());
            setWindowSize(opts.getWindowSize());
            setResendDelay(opts.getResendDelay());
            setMaxMessageSize(opts.getMaxMessageSize());
            setChoke(opts.getChoke());
            setMaxResends(opts.getMaxResends());
            setInactivityTimeout(opts.getInactivityTimeout());
            setInactivityAction(opts.getInactivityAction());
            setInboundBufferSize(opts.getInboundBufferSize());
            setCongestionAvoidanceGrowthRateFactor(opts.getCongestionAvoidanceGrowthRateFactor());
            setSlowStartGrowthRateFactor(opts.getSlowStartGrowthRateFactor());
            setWriteTimeout(opts.getWriteTimeout());
            setReadTimeout(opts.getReadTimeout());
            setAnswerPings(opts.getAnswerPings());
        }
    }
    
	@Override
    protected void init(Properties opts) {
        super.init(opts);
        _trend = new int[TREND_COUNT];
        setMaxWindowSize(getInt(opts, PROP_MAX_WINDOW_SIZE, Connection.MAX_WINDOW_SIZE));
        setConnectDelay(getInt(opts, PROP_CONNECT_DELAY, -1));
        setProfile(getInt(opts, PROP_PROFILE, PROFILE_BULK));
        setMaxMessageSize(getInt(opts, PROP_MAX_MESSAGE_SIZE, DEFAULT_MAX_MESSAGE_SIZE));
        setRTT(getInt(opts, PROP_INITIAL_RTT, DEFAULT_INITIAL_RTT));
        setReceiveWindow(getInt(opts, PROP_INITIAL_RECEIVE_WINDOW, 1));
        setResendDelay(getInt(opts, PROP_INITIAL_RESEND_DELAY, 1000));
        setSendAckDelay(getInt(opts, PROP_INITIAL_ACK_DELAY, 2000));
        setWindowSize(getInt(opts, PROP_INITIAL_WINDOW_SIZE, INITIAL_WINDOW_SIZE));
        setMaxResends(getInt(opts, PROP_MAX_RESENDS, DEFAULT_MAX_SENDS));
        setWriteTimeout(getInt(opts, PROP_WRITE_TIMEOUT, -1));
        setInactivityTimeout(getInt(opts, PROP_INACTIVITY_TIMEOUT, 90*1000));
        setInactivityAction(getInt(opts, PROP_INACTIVITY_ACTION, INACTIVITY_ACTION_SEND));
        setInboundBufferSize(getMaxMessageSize() * (Connection.MAX_WINDOW_SIZE + 2));
        setCongestionAvoidanceGrowthRateFactor(getInt(opts, PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR, 1));
        setSlowStartGrowthRateFactor(getInt(opts, PROP_SLOW_START_GROWTH_RATE_FACTOR, 1));
        setConnectTimeout(getInt(opts, PROP_CONNECT_TIMEOUT, Connection.DISCONNECT_TIMEOUT));
        setAnswerPings(getBool(opts, PROP_ANSWER_PINGS, DEFAULT_ANSWER_PINGS));
    }
    
	@Override
    public void setProperties(Properties opts) {
        super.setProperties(opts);
        if (opts == null) return;
        if (opts.containsKey(PROP_MAX_WINDOW_SIZE))
            setMaxWindowSize(getInt(opts, PROP_MAX_WINDOW_SIZE, Connection.MAX_WINDOW_SIZE));
        if (opts.containsKey(PROP_CONNECT_DELAY))
            setConnectDelay(getInt(opts, PROP_CONNECT_DELAY, -1));
        if (opts.containsKey(PROP_PROFILE))
            setProfile(getInt(opts, PROP_PROFILE, PROFILE_BULK));
        if (opts.containsKey(PROP_MAX_MESSAGE_SIZE))
            setMaxMessageSize(getInt(opts, PROP_MAX_MESSAGE_SIZE, Packet.MAX_PAYLOAD_SIZE));
        if (opts.containsKey(PROP_INITIAL_RTT))
            setRTT(getInt(opts, PROP_INITIAL_RTT, DEFAULT_INITIAL_RTT));
        if (opts.containsKey(PROP_INITIAL_RECEIVE_WINDOW))
            setReceiveWindow(getInt(opts, PROP_INITIAL_RECEIVE_WINDOW, 1));
        if (opts.containsKey(PROP_INITIAL_RESEND_DELAY))
            setResendDelay(getInt(opts, PROP_INITIAL_RESEND_DELAY, 1000));
        if (opts.containsKey(PROP_INITIAL_ACK_DELAY))
            setSendAckDelay(getInt(opts, PROP_INITIAL_ACK_DELAY, 2000));
        if (opts.containsKey(PROP_INITIAL_WINDOW_SIZE))
            setWindowSize(getInt(opts, PROP_INITIAL_WINDOW_SIZE, INITIAL_WINDOW_SIZE));
        if (opts.containsKey(PROP_MAX_RESENDS))
            setMaxResends(getInt(opts, PROP_MAX_RESENDS, DEFAULT_MAX_SENDS));
        if (opts.containsKey(PROP_WRITE_TIMEOUT))
            setWriteTimeout(getInt(opts, PROP_WRITE_TIMEOUT, -1));
        if (opts.containsKey(PROP_INACTIVITY_TIMEOUT))
            setInactivityTimeout(getInt(opts, PROP_INACTIVITY_TIMEOUT, 90*1000));
        if (opts.containsKey(PROP_INACTIVITY_ACTION))
            setInactivityAction(getInt(opts, PROP_INACTIVITY_ACTION, INACTIVITY_ACTION_SEND));
        setInboundBufferSize(getMaxMessageSize() * (Connection.MAX_WINDOW_SIZE + 2));
        if (opts.contains(PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR))
            setCongestionAvoidanceGrowthRateFactor(getInt(opts, PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR, 2));
        if (opts.contains(PROP_SLOW_START_GROWTH_RATE_FACTOR))
            setSlowStartGrowthRateFactor(getInt(opts, PROP_SLOW_START_GROWTH_RATE_FACTOR, 2));
        if (opts.containsKey(PROP_CONNECT_TIMEOUT))
            setConnectTimeout(getInt(opts, PROP_CONNECT_TIMEOUT, Connection.DISCONNECT_TIMEOUT));
        if (opts.containsKey(PROP_ANSWER_PINGS))
            setAnswerPings(getBool(opts, PROP_ANSWER_PINGS, DEFAULT_ANSWER_PINGS));
    }
    
    /** 
     * how long will we wait after instantiating a new con 
     * before actually attempting to connect.  If this is
     * set to 0, connect ASAP.  If it is greater than 0, wait
     * until the output stream is flushed, the buffer fills, 
     * or that many milliseconds pass.
     *
     * @return how long to wait before actually attempting to connect
     */
    public int getConnectDelay() { return _connectDelay; }
    public void setConnectDelay(int delayMs) { _connectDelay = delayMs; }
    
    /**
     * Do we want all packets in both directions to be signed,
     * or can we deal with signatures on the SYN and FIN packets
     * only?
     *
     * There is no property name defined for this, so it's safe to
     * say this is unused and always false.
     *
     * @return if we want signatures on all packets.
     */
    public boolean getRequireFullySigned() { return _fullySigned; }
    public void setRequireFullySigned(boolean sign) { _fullySigned = sign; }
    
    /**
     * Do we respond to a ping?
     *
     * @return if we do
     */
    public boolean getAnswerPings() { return _answerPings; }
    public void setAnswerPings(boolean yes) { _answerPings = yes; }
    
    /** 
     * How many messages will we send before waiting for an ACK?
     *
     * @return Maximum amount of messages that can be in-flight
     */
    public int getWindowSize() { return _windowSize; }
    public void setWindowSize(int numMsgs) { 
        if (numMsgs <= 0)
            numMsgs = 1;
        if (numMsgs < MIN_WINDOW_SIZE)
            numMsgs = MIN_WINDOW_SIZE;
        // the stream's max window size may be less than the min window size, for
        // instance, with interactive streams of cwin=1.  This is why we test it here
        // after checking MIN_WINDOW_SIZE
        if (numMsgs > _maxWindowSize)
            numMsgs = _maxWindowSize;
        _windowSize = numMsgs; 
    }
    
    /** after how many consecutive messages should we ack?
     *  This doesn't appear to be used.
     * @return receive window size.
     */
    public int getReceiveWindow() { return _receiveWindow; } 
    public void setReceiveWindow(int numMsgs) { _receiveWindow = numMsgs; }
    
    /**
     * What to set the round trip time estimate to (in milliseconds)
     * @return round trip time estimate in ms
     */
    public int getRTT() { return _rtt; }
    public void setRTT(int ms) { 
        if (_rto == 0) {
            _rttDev = ms / 2;
            _rto = ms + ms / 2;
        }
        synchronized (_trend) {
            _trend[0] = _trend[1];
            _trend[1] = _trend[2];
            if (ms > _rtt)
                _trend[2] = 1;
            else if (ms < _rtt)
                _trend[2] = -1;
            else
                _trend[2] = 0;
        }
        
        _rtt = ms; 
        if (_rtt > 60*1000)
            _rtt = 60*1000;
    }
    public int getRTO() { return _rto; }
    
    /**
     * If we have 3 consecutive rtt increases, we are trending upwards (1), or if we have
     * 3 consecutive rtt decreases, we are trending downwards (-1), else we're stable.
     *
     * @return positive/flat/negative trend in round trip time
     */
    public int getRTTTrend() {
        synchronized (_trend) {
            for (int i = 0; i < TREND_COUNT - 1; i++) {
                if (_trend[i] != _trend[i+1])
                    return 0;
            }
            return _trend[0];
        }
    }
    
    /** rtt = rtt*RTT_DAMPENING + (1-RTT_DAMPENING)*currentPacketRTT */
    /** This is the value specified in RFC 2988, let's try it */
    private static final double RTT_DAMPENING = 0.875;
    
    public void updateRTT(int measuredValue) {
        _rttDev = _rttDev + (int)(0.25d*(Math.abs(measuredValue-_rtt)-_rttDev));
        int smoothed = (int)(RTT_DAMPENING*_rtt + (1-RTT_DAMPENING)*measuredValue);        
        _rto = smoothed + (_rttDev<<2);
        if (_rto < Connection.MIN_RESEND_DELAY) 
            _rto = (int)Connection.MIN_RESEND_DELAY;
        else if (_rto > Connection.MAX_RESEND_DELAY)
            _rto = (int)Connection.MAX_RESEND_DELAY;

        setRTT(smoothed);
    }
    
    /** How long after sending a packet will we wait before resending?
     * @return delay for a retransmission in ms
     */
    public int getResendDelay() { return _resendDelay; }
    public void setResendDelay(int ms) { _resendDelay = ms; }
    
    /** 
     * if there are packets we haven't ACKed yet and we don't 
     * receive _receiveWindow messages before 
     * (_lastSendTime+_sendAckDelay), send an ACK of what
     * we have received so far.
     *
     * @return ACK delay in ms
     */
    public int getSendAckDelay() { return _sendAckDelay; }
    public void setSendAckDelay(int delayMs) { _sendAckDelay = delayMs; }
    
    /** What is the largest message we want to send or receive?
     * @return Maximum message size (MTU/MRU)
     */
    public int getMaxMessageSize() { return _maxMessageSize; }
    public void setMaxMessageSize(int bytes) { _maxMessageSize = Math.max(bytes, MIN_MESSAGE_SIZE); }
    
    /** 
     * how long we want to wait before any data is transferred on the
     * connection in either direction
     *
     * @return how long to wait before any data is transferred in either direction in ms
     */
    public int getChoke() { return _choke; }
    public void setChoke(int ms) { _choke = ms; }

    /**
     * What profile do we want to use for this connection?
     * TODO: Only bulk is supported so far.
     * @return the profile of the connection.
     */
    public int getProfile() { return _profile; }
    public void setProfile(int profile) { 
        if (profile != PROFILE_BULK) 
            throw new IllegalArgumentException("Only bulk is supported so far");
        _profile = profile; 
    }
    
    /**
     * How many times will we try to send a message before giving up?
     *
     * @return Maximum retrys before failing a sent message.
     */
    public int getMaxResends() { return _maxResends; }
    public void setMaxResends(int numSends) { _maxResends = Math.max(numSends, 0); }
    
    /**
     * What period of inactivity qualifies as "too long"?
     *
     * @return period of inactivity qualifies as "too long"
     */
    public int getInactivityTimeout() { return _inactivityTimeout; }
    public void setInactivityTimeout(int timeout) { _inactivityTimeout = timeout; }
    
    public int getInactivityAction() { return _inactivityAction; }
    public void setInactivityAction(int action) { _inactivityAction = action; }
    
    public int getMaxWindowSize() { return _maxWindowSize; }
    public void setMaxWindowSize(int msgs) { 
        if (msgs > Connection.MAX_WINDOW_SIZE)
            _maxWindowSize = Connection.MAX_WINDOW_SIZE;
        else if (msgs < 1)
            _maxWindowSize = 1;
        else
            _maxWindowSize = msgs; 
    }
    
    /** 
     * how much data are we willing to accept in our buffer?
     *
     * @return size of the buffer used to accept data
     */
    public int getInboundBufferSize() { return _inboundBufferSize; }
    public void setInboundBufferSize(int bytes) { _inboundBufferSize = bytes; }
    
    /**
     * When we're in congestion avoidance, we grow the window size at the rate
     * of 1/(windowSize*factor).  In standard TCP, window sizes are in bytes,
     * while in I2P, window sizes are in messages, so setting factor=maxMessageSize
     * mimics TCP, but using a smaller factor helps grow a little more rapidly.
     * @return window size to grow by to attempt to avoid congestion.
     */
    public int getCongestionAvoidanceGrowthRateFactor() { return _congestionAvoidanceGrowthRateFactor; }
    public void setCongestionAvoidanceGrowthRateFactor(int factor) { _congestionAvoidanceGrowthRateFactor = factor; }
    
    /**
     * When we're in slow start, we grow the window size at the rate
     * of 1/(factor).  In standard TCP, window sizes are in bytes,
     * while in I2P, window sizes are in messages, so setting factor=maxMessageSize
     * mimics TCP, but using a smaller factor helps grow a little more rapidly.
     * @return slow start window size to grow by to attempt to avoid sending many small packets.
     */
    public int getSlowStartGrowthRateFactor() { return _slowStartGrowthRateFactor; }
    public void setSlowStartGrowthRateFactor(int factor) { _slowStartGrowthRateFactor = factor; }
    
	@Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("conDelay=").append(_connectDelay);
        buf.append(" maxSize=").append(_maxMessageSize);
        buf.append(" rtt=").append(_rtt);
        buf.append(" rwin=").append(_receiveWindow);
        buf.append(" resendDelay=").append(_resendDelay);
        buf.append(" ackDelay=").append(_sendAckDelay);
        buf.append(" cwin=").append(_windowSize);
        buf.append(" maxResends=").append(_maxResends);
        buf.append(" writeTimeout=").append(getWriteTimeout());
        buf.append(" readTimeout=").append(getReadTimeout());
        buf.append(" inactivityTimeout=").append(_inactivityTimeout);
        buf.append(" inboundBuffer=").append(_inboundBufferSize);
        buf.append(" maxWindowSize=").append(_maxWindowSize);
        return buf.toString();
    }
    
    private static boolean getBool(Properties opts, String name, boolean defaultVal) {
        if (opts == null) return defaultVal;
        String val = opts.getProperty(name);
        if (val == null)  return defaultVal;
        return Boolean.valueOf(val).booleanValue();
    }

    public static void main(String args[]) {
        Properties p = new Properties();
        
        p.setProperty(PROP_CONNECT_DELAY, "1000");
        ConnectionOptions c = new ConnectionOptions(p);
        System.out.println("opts: " + c);
        
        c = new ConnectionOptions(new I2PSocketOptionsImpl(p));
        System.out.println("opts: " + c);
    }
}
