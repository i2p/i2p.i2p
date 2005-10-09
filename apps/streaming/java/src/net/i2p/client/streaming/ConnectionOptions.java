package net.i2p.client.streaming;

import java.util.Properties;

/**
 * Define the current options for the con (and allow custom tweaking midstream)
 *
 */
public class ConnectionOptions extends I2PSocketOptionsImpl {
    private int _connectDelay;
    private boolean _fullySigned;
    private volatile int _windowSize;
    private int _receiveWindow;
    private int _profile;
    private int _rtt;
    private int _rttDev;
    private int _rto;
    private int _trend[];
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
    
    private static final int TREND_COUNT = 3;
    static final int INITIAL_WINDOW_SIZE = 4;
    static final int DEFAULT_MAX_SENDS = 8;
    
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
        }
    }
    
    protected void init(Properties opts) {
        super.init(opts);
        _trend = new int[TREND_COUNT];
        
        setMaxWindowSize(getInt(opts, PROP_MAX_WINDOW_SIZE, Connection.MAX_WINDOW_SIZE));
        setConnectDelay(getInt(opts, PROP_CONNECT_DELAY, -1));
        setProfile(getInt(opts, PROP_PROFILE, PROFILE_BULK));
        setMaxMessageSize(getInt(opts, PROP_MAX_MESSAGE_SIZE, 4*1024));
        setRTT(getInt(opts, PROP_INITIAL_RTT, 10*1000));
        setReceiveWindow(getInt(opts, PROP_INITIAL_RECEIVE_WINDOW, 1));
        setResendDelay(getInt(opts, PROP_INITIAL_RESEND_DELAY, 1000));
        setSendAckDelay(getInt(opts, PROP_INITIAL_ACK_DELAY, 500));
        setWindowSize(getInt(opts, PROP_INITIAL_WINDOW_SIZE, INITIAL_WINDOW_SIZE));
        setMaxResends(getInt(opts, PROP_MAX_RESENDS, DEFAULT_MAX_SENDS));
        setWriteTimeout(getInt(opts, PROP_WRITE_TIMEOUT, -1));
        setInactivityTimeout(getInt(opts, PROP_INACTIVITY_TIMEOUT, 90*1000));
        setInactivityAction(getInt(opts, PROP_INACTIVITY_ACTION, INACTIVITY_ACTION_SEND));
        setInboundBufferSize(getMaxMessageSize() * (Connection.MAX_WINDOW_SIZE + 2));
        setCongestionAvoidanceGrowthRateFactor(getInt(opts, PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR, 1));
        setSlowStartGrowthRateFactor(getInt(opts, PROP_SLOW_START_GROWTH_RATE_FACTOR, 1));
        
        setConnectTimeout(getInt(opts, PROP_CONNECT_TIMEOUT, Connection.DISCONNECT_TIMEOUT));
    }
    
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
            setRTT(getInt(opts, PROP_INITIAL_RTT, 10*1000));
        if (opts.containsKey(PROP_INITIAL_RECEIVE_WINDOW))
            setReceiveWindow(getInt(opts, PROP_INITIAL_RECEIVE_WINDOW, 1));
        if (opts.containsKey(PROP_INITIAL_RESEND_DELAY))
            setResendDelay(getInt(opts, PROP_INITIAL_RESEND_DELAY, 1000));
        if (opts.containsKey(PROP_INITIAL_ACK_DELAY))
            setSendAckDelay(getInt(opts, PROP_INITIAL_ACK_DELAY, 500));
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
    }
    
    /** 
     * how long will we wait after instantiating a new con 
     * before actually attempting to connect.  If this is
     * set to 0, connect ASAP.  If it is greater than 0, wait
     * until the output stream is flushed, the buffer fills, 
     * or that many milliseconds pass.
     *
     */
    public int getConnectDelay() { return _connectDelay; }
    public void setConnectDelay(int delayMs) { _connectDelay = delayMs; }
    
    /**
     * Do we want all packets in both directions to be signed,
     * or can we deal with signatures on the SYN and FIN packets
     * only?
     *
     */
    public boolean getRequireFullySigned() { return _fullySigned; }
    public void setRequireFullySigned(boolean sign) { _fullySigned = sign; }
    
    /** 
     * How many messages will we send before waiting for an ACK?
     *
     */
    public int getWindowSize() { return _windowSize; }
    public void setWindowSize(int numMsgs) { 
        if (numMsgs > _maxWindowSize)
            numMsgs = _maxWindowSize;
        else if (numMsgs <= 0)
            numMsgs = 1;
        _windowSize = numMsgs; 
    }
    
    /** after how many consecutive messages should we ack? */
    public int getReceiveWindow() { return _receiveWindow; } 
    public void setReceiveWindow(int numMsgs) { _receiveWindow = numMsgs; }
    
    /**
     * What to set the round trip time estimate to (in milliseconds)
     */
    public int getRTT() { return _rtt; }
    public void setRTT(int ms) { 
        if (_rto == 0) {
            _rttDev = ms;
            _rto = (int)Connection.MAX_RESEND_DELAY;
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
    private static final double RTT_DAMPENING = 0.9;
    
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
    
    /** How long after sending a packet will we wait before resending? */
    public int getResendDelay() { return _resendDelay; }
    public void setResendDelay(int ms) { _resendDelay = ms; }
    
    /** 
     * if there are packets we haven't ACKed yet and we don't 
     * receive _receiveWindow messages before 
     * (_lastSendTime+_sendAckDelay), send an ACK of what
     * we have received so far.
     *
     */
    public int getSendAckDelay() { return _sendAckDelay; }
    public void setSendAckDelay(int delayMs) { _sendAckDelay = delayMs; }
    
    /** What is the largest message we want to send or receive? */
    public int getMaxMessageSize() { return _maxMessageSize; }
    public void setMaxMessageSize(int bytes) { _maxMessageSize = bytes; }
    
    /** 
     * how long we want to wait before any data is transferred on the
     * connection in either direction
     *
     */
    public int getChoke() { return _choke; }
    public void setChoke(int ms) { _choke = ms; }

    /**
     * What profile do we want to use for this connection?
     *
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
     */
    public int getMaxResends() { return _maxResends; }
    public void setMaxResends(int numSends) { _maxResends = numSends; }
    
    /**
     * What period of inactivity qualifies as "too long"?
     *
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
     */
    public int getInboundBufferSize() { return _inboundBufferSize; }
    public void setInboundBufferSize(int bytes) { _inboundBufferSize = bytes; }
    
    /**
     * When we're in congestion avoidance, we grow the window size at the rate
     * of 1/(windowSize*factor).  In standard TCP, window sizes are in bytes,
     * while in I2P, window sizes are in messages, so setting factor=maxMessageSize
     * mimics TCP, but using a smaller factor helps grow a little more rapidly.
     */
    public int getCongestionAvoidanceGrowthRateFactor() { return _congestionAvoidanceGrowthRateFactor; }
    public void setCongestionAvoidanceGrowthRateFactor(int factor) { _congestionAvoidanceGrowthRateFactor = factor; }
    
    /**
     * When we're in slow start, we grow the window size at the rate
     * of 1/(factor).  In standard TCP, window sizes are in bytes,
     * while in I2P, window sizes are in messages, so setting factor=maxMessageSize
     * mimics TCP, but using a smaller factor helps grow a little more rapidly.
     */
    public int getSlowStartGrowthRateFactor() { return _slowStartGrowthRateFactor; }
    public void setSlowStartGrowthRateFactor(int factor) { _slowStartGrowthRateFactor = factor; }
    
    public String toString() {
        StringBuffer buf = new StringBuffer(128);
        buf.append("conDelay=").append(_connectDelay);
        buf.append(" maxSize=").append(_maxMessageSize);
        buf.append(" rtt=").append(_rtt);
        buf.append(" rwin=").append(_receiveWindow);
        buf.append(" resendDelay=").append(_resendDelay);
        buf.append(" ackDelay=").append(_sendAckDelay);
        buf.append(" cwin=").append(_windowSize);
        buf.append(" maxResends=").append(_maxResends);
        buf.append(" writeTimeout=").append(getWriteTimeout());
        buf.append(" inactivityTimeout=").append(_inactivityTimeout);
        buf.append(" inboundBuffer=").append(_inboundBufferSize);
        buf.append(" maxWindowSize=").append(_maxWindowSize);
        return buf.toString();
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
