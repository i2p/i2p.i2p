package net.i2p.client.streaming.impl;

import net.i2p.client.streaming.I2PSocketOptions;

import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.util.ConvertToHash;
import net.i2p.util.Log;

/**
 * Define the current options for the con (and allow custom tweaking midstream)
 *
 * TODO many of these are not per-connection options, and should be migrated
 * somewhere so they aren't copied for every connection
 */
class ConnectionOptions extends I2PSocketOptionsImpl {
    private int _connectDelay;
    private boolean _fullySigned;
    private boolean _answerPings;
    private boolean _enforceProto;
    private volatile int _windowSize;
    private int _receiveWindow;
    private int _profile;
    private int _rtt;
    private int _rttDev;
    private int _rto = INITIAL_RTO;
    private int _resendDelay;
    private int _sendAckDelay;
    private int _maxMessageSize;
    private int _maxResends;
    private int _inactivityTimeout;
    private int _inactivityAction;
    private int _inboundBufferSize;
    private int _maxWindowSize;
    private int _congestionAvoidanceGrowthRateFactor;
    private int _slowStartGrowthRateFactor;
    private boolean _accessListEnabled;
    private boolean _blackListEnabled;
    private Set<Hash> _accessList;
    private Set<Hash> _blackList;
    private int _maxConnsPerMinute;
    private int _maxConnsPerHour;
    private int _maxConnsPerDay;
    private int _maxTotalConnsPerMinute;
    private int _maxTotalConnsPerHour;
    private int _maxTotalConnsPerDay;
    private int _maxConns;
    private boolean _disableRejectLog;
    private String _limitAction;
    private int _tagsToSend;
    private int _tagThreshold;
    
    /** state of a connection */
    private enum AckInit {
        INIT, // just created
        FIRST, // first received ack
        STEADY 
    }
    
    /** LOCKING: this */
    private AckInit _initState = AckInit.INIT;
    
    // NOTE - almost all the options are below, but see
    // I2PSocketOptions in ministreaming for a few more

    public static final int PROFILE_BULK = 1;
    public static final int PROFILE_INTERACTIVE = 2;
    
    /** on inactivity timeout, do nothing */
    public static final int INACTIVITY_ACTION_NOOP = 0;
    /** on inactivity timeout, close the connection */
    public static final int INACTIVITY_ACTION_DISCONNECT = 1;
    /** on inactivity timeout, send a payload message */
    public static final int INACTIVITY_ACTION_SEND = 2;
    
    /* 
     * These values are specified in RFC 6298
     * Do not change unless you know what you're doing
     */
    private static final double TCP_ALPHA = 1.0/8;
    private static final double TCP_BETA = 1.0/4; 
    private static final double TCP_KAPPA = 4;
    
    private static final String PROP_INITIAL_RTO = "i2p.streaming.initialRTO";
    private static final int INITIAL_RTO = 9000; 
    
    public static final String PROP_CONNECT_DELAY = "i2p.streaming.connectDelay";
    public static final String PROP_PROFILE = "i2p.streaming.profile";
    public static final String PROP_MAX_MESSAGE_SIZE = "i2p.streaming.maxMessageSize";
    public static final String PROP_MAX_RESENDS = "i2p.streaming.maxResends";
    public static final String PROP_INITIAL_RESEND_DELAY = "i2p.streaming.initialResendDelay";
    public static final String PROP_INITIAL_ACK_DELAY = "i2p.streaming.initialAckDelay";
    public static final String PROP_INITIAL_WINDOW_SIZE = "i2p.streaming.initialWindowSize";
    /** unused */
    public static final String PROP_INITIAL_RECEIVE_WINDOW = "i2p.streaming.initialReceiveWindow";
    public static final String PROP_INACTIVITY_TIMEOUT = "i2p.streaming.inactivityTimeout";
    public static final String PROP_INACTIVITY_ACTION = "i2p.streaming.inactivityAction";
    public static final String PROP_MAX_WINDOW_SIZE = "i2p.streaming.maxWindowSize";
    public static final String PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR = "i2p.streaming.congestionAvoidanceGrowthRateFactor";
    public static final String PROP_SLOW_START_GROWTH_RATE_FACTOR = "i2p.streaming.slowStartGrowthRateFactor";
    public static final String PROP_ANSWER_PINGS = "i2p.streaming.answerPings";
    /** all of these are @since 0.7.13 */
    public static final String PROP_ENABLE_ACCESS_LIST = "i2cp.enableAccessList";
    public static final String PROP_ENABLE_BLACKLIST = "i2cp.enableBlackList";
    public static final String PROP_ACCESS_LIST = "i2cp.accessList";
    /** all of these are @since 0.7.14 */
    public static final String PROP_MAX_CONNS_MIN = "i2p.streaming.maxConnsPerMinute";
    public static final String PROP_MAX_CONNS_HOUR = "i2p.streaming.maxConnsPerHour";
    public static final String PROP_MAX_CONNS_DAY = "i2p.streaming.maxConnsPerDay";
    public static final String PROP_MAX_TOTAL_CONNS_MIN = "i2p.streaming.maxTotalConnsPerMinute";
    public static final String PROP_MAX_TOTAL_CONNS_HOUR = "i2p.streaming.maxTotalConnsPerHour";
    public static final String PROP_MAX_TOTAL_CONNS_DAY = "i2p.streaming.maxTotalConnsPerDay";
    /** @since 0.9.1 */
    public static final String PROP_ENFORCE_PROTO = "i2p.streaming.enforceProtocol";
    /**
     *  how many streams will we allow at once?
     *  @since 0.9.3 moved from I2PSocketManagerFull
     */
    public static final String PROP_MAX_STREAMS = "i2p.streaming.maxConcurrentStreams";
    /** @since 0.9.4  default false */
    public static final String PROP_DISABLE_REJ_LOG = "i2p.streaming.disableRejectLogging";
    /** @since 0.9.34 reset,drop,http, or custom string,  default reset */
    public static final String PROP_LIMIT_ACTION = "i2p.streaming.limitAction";
    /** @since 0.9.34 */
    public static final String PROP_TAGS_TO_SEND = "crypto.tagsToSend";
    /** @since 0.9.34 */
    public static final String PROP_TAG_THRESHOLD = "crypto.lowTagThreshold";
    
    
    private static final int TREND_COUNT = 3;
    static final int INITIAL_WINDOW_SIZE = 6;
    static final int DEFAULT_MAX_SENDS = 8;
    public static final int DEFAULT_INITIAL_RTT = 8*1000;    
    private static final int MAX_RTT = 60*1000;    
    private static final int DEFAULT_INITIAL_ACK_DELAY = 750;  
    static final int MIN_WINDOW_SIZE = 1;
    private static final boolean DEFAULT_ANSWER_PINGS = true;
    private static final int DEFAULT_INACTIVITY_TIMEOUT = 90*1000;
    private static final int DEFAULT_INACTIVITY_ACTION = INACTIVITY_ACTION_SEND;
    private static final int DEFAULT_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR = 1;
    private static final int DEFAULT_SLOW_START_GROWTH_RATE_FACTOR = 1;
    /** @since 0.9.34 */
    private static final String DEFAULT_LIMIT_ACTION = "reset";
    /** @since 0.9.34 */
    public static final int DEFAULT_TAGS_TO_SEND = 40;
    /** @since 0.9.34 */
    public static final int DEFAULT_TAG_THRESHOLD = 30;


    /**
     *  If PROTO is enforced, we cannot communicate with destinations earlier than version 0.7.1.
     *  @since 0.9.1
     */
    private static final boolean DEFAULT_ENFORCE_PROTO = false;

    private final int _trend[] = new int[TREND_COUNT];

    /**
     *  OK, here is the calculation on the message size to fit in a single
     *  tunnel message without fragmentation.
     *  This is based on documentation, the code, and logging, however there are still
     *  some parts that could use more research.
     *
     *<pre>
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
     *</pre>
     *
     * Before release 0.6.1.14 this was 4096.
     * From release 0.6.1.14 through release 0.6.4, this was 960.
     * It was claimed in the comment that this fit in
     * a single tunnel message (and the checkin comment says the goal was to
     * increase reliability at the expense of throughput),
     * clearly from the math above that was not correct.
     * (Before 0.6.2, the reply leaseSet was bundled with every message, so it didn't even
     * fit in TWO tunnel messages - more like 2 1/3)
     * <p>
     * Now, it's not clear how often we will get the ideal situation (no reply leaseSet bundling,
     * no key bundling, and especially not having a small message ahead of you, which will then cause
     * fragmentation for all subsequent messages until the queue is emptied - BatchedPreprocessor
     * doesn't do reordering, and it isn't clear to me if it could). In particular the initial
     * messages in a new stream are much larger due to the leaseSet and key bundling.
     * But for long-lived streams (like with i2psnark) this should pay dividends.
     * The tunnel.batch* stats should provide some data for test comparisons.
     * <p>
     * As MTU and MRU are identical and are negotiated to the lowest value
     * for the two ends, you can't do widespread testing of a higher value.
     * Unless we change to allow MTU and MRU to be different,
     * which would be a pain because it would mess up our buffer scheme.
     * Both 738 and 1730 have been tested to verify that the math above is correct.
     * So let's try 1730 for release 0.6.5. This will allow for 738 testing as well,
     * with i2p.streaming.maxMessageSize=738 (in configadvanced.jsp, or in i2ptunnel, or
     * i2psnark, for example).
     * <p>
     * Not that an isolated single packet is very common, but
     * in this case, 960 was 113.3% total overhead.
     * Compared to 738 (38.8% overhead) and 1730 (18.4%).
     *
     */
    public static final int DEFAULT_MAX_MESSAGE_SIZE = 1730;
    public static final int MIN_MESSAGE_SIZE = 512;

    /**
     *  Sets max buffer size, connect timeout, read timeout, and write timeout
     *  from System properties. Does not set local port or remote port.
     */
    public ConnectionOptions() {
        super();
        cinit(System.getProperties());
    }
    
    /**
     *  Sets max buffer size, connect timeout, read timeout, and write timeout
     *  from properties. Does not set local port or remote port.
     *
     *  As of 0.9.19, defaults in opts are honored.
     *
     *  @param opts may be null
     */
    public ConnectionOptions(Properties opts) {
        super(opts);
        cinit(opts);
    }
    
    /**
     *  Initializes from System properties then copies over all options.
     *  @param opts may be null
     */
    public ConnectionOptions(I2PSocketOptions opts) {
        super(opts);
        cinit(System.getProperties());
    }
    
    /**
     *  Initializes from System properties then copies over all options.
     *  @param opts may be null
     */
    public ConnectionOptions(ConnectionOptions opts) {
        super(opts);
        cinit(System.getProperties());
        if (opts != null)
            update(opts);
    }
    
    /**
     *  Update everything by copying over from opts
     *  @param opts non-null
     *  @since 0.9.1
     */
    public void updateAll(ConnectionOptions opts) {
        // user is unlikely to change these 6 between buildOptions() and setDefaultOptions(),
        // since they may be updated directly, but just in case...
        setConnectTimeout(opts.getConnectTimeout());
        setReadTimeout(opts.getReadTimeout());
        setWriteTimeout(opts.getWriteTimeout());
        setMaxBufferSize(opts.getMaxBufferSize());
        setLocalPort(opts.getLocalPort());
        setPort(opts.getPort());
        update(opts);
    }
    
    /**
     *  Update everything (except super) by copying over from opts
     *  @param opts non-null
     *  @since 0.9.1
     */
    private void update(ConnectionOptions opts) {
            setMaxWindowSize(opts.getMaxWindowSize());
            setConnectDelay(opts.getConnectDelay());
            setProfile(opts.getProfile());
            setRTTDev(opts.getRTTDev());
            setRTT(opts.getRTT());
            setRequireFullySigned(opts.getRequireFullySigned());
            setWindowSize(opts.getWindowSize());
            setResendDelay(opts.getResendDelay());
            setMaxMessageSize(opts.getMaxMessageSize());
            setMaxResends(opts.getMaxResends());
            setInactivityTimeout(opts.getInactivityTimeout());
            setInactivityAction(opts.getInactivityAction());
            setInboundBufferSize(opts.getInboundBufferSize());
            setCongestionAvoidanceGrowthRateFactor(opts.getCongestionAvoidanceGrowthRateFactor());
            setSlowStartGrowthRateFactor(opts.getSlowStartGrowthRateFactor());
            // handled in super()
            // not clear why added by jr 12/22/2005
            //setWriteTimeout(opts.getWriteTimeout());
            //setReadTimeout(opts.getReadTimeout());
            setAnswerPings(opts.getAnswerPings());
            setEnforceProtocol(opts.getEnforceProtocol());
            setDisableRejectLogging(opts.getDisableRejectLogging());
            initLists(opts);
            _maxConnsPerMinute = opts.getMaxConnsPerMinute();
            _maxConnsPerHour = opts.getMaxConnsPerHour();
            _maxConnsPerDay = opts.getMaxConnsPerDay();
            _maxTotalConnsPerMinute = opts.getMaxTotalConnsPerMinute();
            _maxTotalConnsPerHour = opts.getMaxTotalConnsPerHour();
            _maxTotalConnsPerDay = opts.getMaxTotalConnsPerDay();
            _maxConns = opts.getMaxConns();
            _limitAction = opts.getLimitAction();
            _tagsToSend = opts.getTagsToSend();
            _tagThreshold = opts.getTagThreshold();
    }
    
    /**
     * Initialization
     */
    private void cinit(Properties opts) {
        setMaxWindowSize(getInt(opts, PROP_MAX_WINDOW_SIZE, Connection.MAX_WINDOW_SIZE));
        setConnectDelay(getInt(opts, PROP_CONNECT_DELAY, -1));
        setProfile(getInt(opts, PROP_PROFILE, PROFILE_BULK));
        setMaxMessageSize(getInt(opts, PROP_MAX_MESSAGE_SIZE, DEFAULT_MAX_MESSAGE_SIZE));
        setReceiveWindow(getInt(opts, PROP_INITIAL_RECEIVE_WINDOW, 1));
        setResendDelay(getInt(opts, PROP_INITIAL_RESEND_DELAY, 1000));
        setSendAckDelay(getInt(opts, PROP_INITIAL_ACK_DELAY, DEFAULT_INITIAL_ACK_DELAY));
        setWindowSize(getInt(opts, PROP_INITIAL_WINDOW_SIZE, INITIAL_WINDOW_SIZE));
        setMaxResends(getInt(opts, PROP_MAX_RESENDS, DEFAULT_MAX_SENDS));
        // handled in super()
        //setWriteTimeout(getInt(opts, PROP_WRITE_TIMEOUT, -1));
        setInactivityTimeout(getInt(opts, PROP_INACTIVITY_TIMEOUT, DEFAULT_INACTIVITY_TIMEOUT));
        setInactivityAction(getInt(opts, PROP_INACTIVITY_ACTION, DEFAULT_INACTIVITY_ACTION));
        setInboundBufferSize(getMaxMessageSize() * (Connection.MAX_WINDOW_SIZE + 2));
        setCongestionAvoidanceGrowthRateFactor(getInt(opts, PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR,
                                                      DEFAULT_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR));
        setSlowStartGrowthRateFactor(getInt(opts, PROP_SLOW_START_GROWTH_RATE_FACTOR,
                                            DEFAULT_SLOW_START_GROWTH_RATE_FACTOR));
        // overrides default in super()... why?
        //setConnectTimeout(getInt(opts, PROP_CONNECT_TIMEOUT, Connection.DISCONNECT_TIMEOUT));
        setAnswerPings(getBool(opts, PROP_ANSWER_PINGS, DEFAULT_ANSWER_PINGS));
        setEnforceProtocol(getBool(opts, PROP_ENFORCE_PROTO, DEFAULT_ENFORCE_PROTO));
        setDisableRejectLogging(getBool(opts, PROP_DISABLE_REJ_LOG, false));
        initLists(opts);
        _maxConnsPerMinute = getInt(opts, PROP_MAX_CONNS_MIN, 0);
        _maxConnsPerHour = getInt(opts, PROP_MAX_CONNS_HOUR, 0);
        _maxConnsPerDay = getInt(opts, PROP_MAX_CONNS_DAY, 0);
        _maxTotalConnsPerMinute = getInt(opts, PROP_MAX_TOTAL_CONNS_MIN, 0);
        _maxTotalConnsPerHour = getInt(opts, PROP_MAX_TOTAL_CONNS_HOUR, 0);
        _maxTotalConnsPerDay = getInt(opts, PROP_MAX_TOTAL_CONNS_DAY, 0);
        _maxConns = getInt(opts, PROP_MAX_STREAMS, 0);
        if (opts != null)
            _limitAction = opts.getProperty(PROP_LIMIT_ACTION, DEFAULT_LIMIT_ACTION);
        else
            _limitAction = DEFAULT_LIMIT_ACTION;
        
        _rto = getInt(opts, PROP_INITIAL_RTO, INITIAL_RTO);
        _tagsToSend = getInt(opts, PROP_TAGS_TO_SEND, DEFAULT_TAGS_TO_SEND);
        _tagsToSend = getInt(opts, PROP_TAG_THRESHOLD, DEFAULT_TAG_THRESHOLD);
    }
    
    /**
     *  Note: NOT part of the interface
     *
     *  As of 0.9.19, defaults in opts are honored.
     */
    @Override
    public void setProperties(Properties opts) {
        super.setProperties(opts);
        if (opts == null) return;
        if (opts.getProperty(PROP_MAX_WINDOW_SIZE) != null)
            setMaxWindowSize(getInt(opts, PROP_MAX_WINDOW_SIZE, Connection.MAX_WINDOW_SIZE));
        if (opts.getProperty(PROP_CONNECT_DELAY) != null)
            setConnectDelay(getInt(opts, PROP_CONNECT_DELAY, -1));
        if (opts.getProperty(PROP_PROFILE) != null)
            setProfile(getInt(opts, PROP_PROFILE, PROFILE_BULK));
        if (opts.getProperty(PROP_MAX_MESSAGE_SIZE) != null)
            setMaxMessageSize(getInt(opts, PROP_MAX_MESSAGE_SIZE, Packet.MAX_PAYLOAD_SIZE));
        if (opts.getProperty(PROP_INITIAL_RECEIVE_WINDOW) != null)
            setReceiveWindow(getInt(opts, PROP_INITIAL_RECEIVE_WINDOW, 1));
        if (opts.getProperty(PROP_INITIAL_RESEND_DELAY) != null)
            setResendDelay(getInt(opts, PROP_INITIAL_RESEND_DELAY, 1000));
        if (opts.getProperty(PROP_INITIAL_ACK_DELAY) != null)
            setSendAckDelay(getInt(opts, PROP_INITIAL_ACK_DELAY, DEFAULT_INITIAL_ACK_DELAY));
        if (opts.getProperty(PROP_INITIAL_WINDOW_SIZE) != null)
            setWindowSize(getInt(opts, PROP_INITIAL_WINDOW_SIZE, INITIAL_WINDOW_SIZE));
        if (opts.getProperty(PROP_MAX_RESENDS) != null)
            setMaxResends(getInt(opts, PROP_MAX_RESENDS, DEFAULT_MAX_SENDS));
        // handled in super()
        //if (opts.getProperty(PROP_WRITE_TIMEOUT))
        //    setWriteTimeout(getInt(opts, PROP_WRITE_TIMEOUT, -1));
        if (opts.getProperty(PROP_INACTIVITY_TIMEOUT) != null)
            setInactivityTimeout(getInt(opts, PROP_INACTIVITY_TIMEOUT, DEFAULT_INACTIVITY_TIMEOUT));
        if (opts.getProperty(PROP_INACTIVITY_ACTION) != null)
            setInactivityAction(getInt(opts, PROP_INACTIVITY_ACTION, DEFAULT_INACTIVITY_ACTION));
        setInboundBufferSize(getMaxMessageSize() * (Connection.MAX_WINDOW_SIZE + 2));
        if (opts.getProperty(PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR) != null)
            setCongestionAvoidanceGrowthRateFactor(getInt(opts, PROP_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR,
                                                          DEFAULT_CONGESTION_AVOIDANCE_GROWTH_RATE_FACTOR));
        if (opts.getProperty(PROP_SLOW_START_GROWTH_RATE_FACTOR) != null)
            setSlowStartGrowthRateFactor(getInt(opts, PROP_SLOW_START_GROWTH_RATE_FACTOR,
                                                DEFAULT_SLOW_START_GROWTH_RATE_FACTOR));
        if (opts.getProperty(PROP_CONNECT_TIMEOUT) != null)
            // overrides default in super()
            setConnectTimeout(getInt(opts, PROP_CONNECT_TIMEOUT, Connection.DEFAULT_CONNECT_TIMEOUT));
        if (opts.getProperty(PROP_ANSWER_PINGS) != null)
            setAnswerPings(getBool(opts, PROP_ANSWER_PINGS, DEFAULT_ANSWER_PINGS));
        if (opts.getProperty(PROP_ENFORCE_PROTO) != null)
            setEnforceProtocol(getBool(opts, PROP_ENFORCE_PROTO, DEFAULT_ENFORCE_PROTO));
        if (opts.getProperty(PROP_DISABLE_REJ_LOG) != null)
            setDisableRejectLogging(getBool(opts, PROP_DISABLE_REJ_LOG, false));
        initLists(opts);
        if (opts.getProperty(PROP_MAX_CONNS_MIN) != null)
            _maxConnsPerMinute = getInt(opts, PROP_MAX_CONNS_MIN, 0);
        if (opts.getProperty(PROP_MAX_CONNS_HOUR) != null)
            _maxConnsPerHour = getInt(opts, PROP_MAX_CONNS_HOUR, 0);
        if (opts.getProperty(PROP_MAX_CONNS_DAY) != null)
            _maxConnsPerDay = getInt(opts, PROP_MAX_CONNS_DAY, 0);
        if (opts.getProperty(PROP_MAX_TOTAL_CONNS_MIN) != null)
            _maxTotalConnsPerMinute = getInt(opts, PROP_MAX_TOTAL_CONNS_MIN, 0);
        if (opts.getProperty(PROP_MAX_TOTAL_CONNS_HOUR) != null)
            _maxTotalConnsPerHour = getInt(opts, PROP_MAX_TOTAL_CONNS_HOUR, 0);
        if (opts.getProperty(PROP_MAX_TOTAL_CONNS_DAY) != null)
            _maxTotalConnsPerDay = getInt(opts, PROP_MAX_TOTAL_CONNS_DAY, 0);
        if (opts.getProperty(PROP_MAX_STREAMS) != null)
            _maxConns = getInt(opts, PROP_MAX_STREAMS, 0);
        if (opts.getProperty(PROP_LIMIT_ACTION) != null)
            _limitAction = opts.getProperty(PROP_LIMIT_ACTION);
        if (opts.getProperty(PROP_TAGS_TO_SEND) != null)
            _maxConns = getInt(opts, PROP_TAGS_TO_SEND, DEFAULT_TAGS_TO_SEND);
        if (opts.getProperty(PROP_TAG_THRESHOLD) != null)
            _maxConns = getInt(opts, PROP_TAG_THRESHOLD, DEFAULT_TAG_THRESHOLD);
        
        _rto = getInt(opts, PROP_INITIAL_RTO, INITIAL_RTO);
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
    /** unused, see above */
    public void setRequireFullySigned(boolean sign) { _fullySigned = sign; }
    
    /**
     * Do we respond to a ping?
     *
     * @return if we do
     */
    public boolean getAnswerPings() { return _answerPings; }
    public void setAnswerPings(boolean yes) { _answerPings = yes; }
    
    /**
     * Do we receive all traffic, or only traffic marked with I2PSession.PROTO_STREAMING (6) ?
     * Default false.
     * If PROTO is enforced, we cannot communicate with destinations earlier than version 0.7.1
     * (released March 2009), which is when streaming started sending the PROTO_STREAMING indication.
     * Set to true if you are running multiple protocols on a single Destination.
     *
     * @return if we do
     * @since 0.9.1
     */
    public boolean getEnforceProtocol() { return _enforceProto; }
    public void setEnforceProtocol(boolean yes) { _enforceProto = yes; }
    
    /**
     * Do we disable connection rejected logging? Default false.
     *
     * @return if we do
     * @since 0.9.4
     */
    public boolean getDisableRejectLogging() { return _disableRejectLog; }
    public void setDisableRejectLogging(boolean yes) { _disableRejectLog = yes; }

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
     * @deprecated This doesn't appear to be used.
     * @return receive window size.
     */
    @Deprecated
    public int getReceiveWindow() { return _receiveWindow; } 
    public void setReceiveWindow(int numMsgs) { _receiveWindow = numMsgs; }
    
    /**
     * What to set the round trip time estimate to (in milliseconds)
     * @return round trip time estimate in ms
     */
    public synchronized int getRTT() { return _rtt; }

    /**
     *  not public, use updateRTT()
     */
    private void setRTT(int ms) { 
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
        
        synchronized(this) {
            _rtt = ms; 
            if (_rtt > MAX_RTT)
                _rtt = MAX_RTT;
        }
    }

    public synchronized int getRTO() { return _rto; }

    /** used in TCB @since 0.9.8 */
    synchronized int getRTTDev() { return _rttDev; }

    private synchronized void setRTTDev(int rttDev) { _rttDev = rttDev; }
    
    /** 
     * Loads options from TCB cache.
     */
    synchronized void loadFromCache(int rtt, int rttDev, int wdw) {
        _initState = AckInit.STEADY;
        setRTT(rtt);
        setRTTDev(rttDev);
        setWindowSize(wdw);
        computeRTO();
    }
    
    /** 
     * computes RTO based on formula in RFC
     */
    private synchronized void computeRTO() {
        switch(_initState) {
        case INIT :
            throw new IllegalStateException();
        case FIRST :
            _rto = _rtt + _rtt / 2;
            break;
        case STEADY :
            _rto = _rtt + (int) (_rttDev * TCP_KAPPA);
            break;
        }
        
        if (_rto < Connection.MIN_RESEND_DELAY) 
            _rto = (int)Connection.MIN_RESEND_DELAY;
        else if (_rto > Connection.MAX_RESEND_DELAY)
            _rto = (int)Connection.MAX_RESEND_DELAY;
    }
    
    /** 
     * Double the RTO (after congestion).
     * See RFC 6298 section 5 item 5.5
     *
     * @since 0.9.33
     */
    synchronized void doubleRTO() {
        // we don't need to switch on _initState, _rto is set in constructor
        _rto *= 2;
        if (_rto > Connection.MAX_RESEND_DELAY)
            _rto = (int)Connection.MAX_RESEND_DELAY;
    }
    
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
    
    /**
     *  @param measuredValue must be positive
     */
    public synchronized void updateRTT(int measuredValue) {
        switch(_initState) {
        case INIT:
            _initState = AckInit.FIRST;
            setRTT(measuredValue); // no smoothing first sample
            _rttDev = _rtt / 2;
            break;
        case FIRST:
            _initState = AckInit.STEADY; // fall through
        case STEADY:
            // calculation matches that recommended in RFC 6298
            _rttDev = (int) ((1-TCP_BETA) *_rttDev  + TCP_BETA * Math.abs(measuredValue-_rtt));
            int smoothed = (int)((1-TCP_ALPHA)*_rtt + TCP_ALPHA*measuredValue);        
            setRTT(smoothed);
        }
        computeRTO();
    }
    
    public synchronized boolean receivedAck() {
        return _initState != AckInit.INIT;
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
    /**
     *  Unused except here, so expect the default initial delay of 2000 ms unless set by the user
     *  to remain constant.
     */
    public void setSendAckDelay(int delayMs) { _sendAckDelay = delayMs; }
    
    /** What is the largest message we want to send or receive?
     * @return Maximum message size (MTU/MRU)
     */
    public int getMaxMessageSize() { return _maxMessageSize; }
    public void setMaxMessageSize(int bytes) { _maxMessageSize = Math.max(bytes, MIN_MESSAGE_SIZE); }
    
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
    
    /** all of these are @since 0.7.14; no public setters */
    public int getMaxConnsPerMinute() { return _maxConnsPerMinute; }
    public int getMaxConnsPerHour() { return _maxConnsPerHour; }
    public int getMaxConnsPerDay() { return _maxConnsPerDay; }
    public int getMaxTotalConnsPerMinute() { return _maxTotalConnsPerMinute; }
    public int getMaxTotalConnsPerHour() { return _maxTotalConnsPerHour; }
    public int getMaxTotalConnsPerDay() { return _maxTotalConnsPerDay; }
    /** @since 0.9.3; no public setter */
    public int getMaxConns() { return _maxConns; }

    public boolean isAccessListEnabled() { return _accessListEnabled; }
    public boolean isBlacklistEnabled() { return _blackListEnabled; }
    public Set<Hash> getAccessList() { return _accessList; }
    public Set<Hash> getBlacklist() { return _blackList; }

    /**
     * "reset", "drop", "http", or custom string.
     * Default "reset".
     *
     * @since 0.9.34
     */
    public String getLimitAction() { return _limitAction; }

    /**
     * This option is mostly handled on the router side,
     * but PacketQueue also needs to know, so that when
     * it overrides, it doesn't exceed the setting.
     *
     * @since 0.9.34
     */
    public int getTagsToSend() { return _tagsToSend; }

    /**
     * This option is mostly handled on the router side,
     * but PacketQueue also needs to know, so that when
     * it overrides, it doesn't exceed the setting.
     *
     * @since 0.9.34
     */
    public int getTagThreshold() { return _tagThreshold; }

    private void initLists(ConnectionOptions opts) {
        _accessList = opts.getAccessList();
        _blackList = opts.getBlacklist();
        _accessListEnabled = opts.isAccessListEnabled();
        _blackListEnabled = opts.isBlacklistEnabled();
    }

    private void initLists(Properties opts) {
        boolean accessListEnabled = getBool(opts, PROP_ENABLE_ACCESS_LIST, false);
        boolean blackListEnabled = getBool(opts, PROP_ENABLE_BLACKLIST, false);
        // Don't think these would ever be accessed simultaneously,
        // but avoid concurrent modification just in case
        Set<Hash> accessList, blackList;
        if (accessListEnabled)
            accessList = new HashSet<Hash>();
        else
            accessList = Collections.emptySet();
        if (blackListEnabled)
            blackList = new HashSet<Hash>();
        else
            blackList = Collections.emptySet();
        if (accessListEnabled || blackListEnabled) {
            String hashes = opts.getProperty(PROP_ACCESS_LIST);
            if (hashes == null)
                return;
            StringTokenizer tok = new StringTokenizer(hashes, ",; ");
            while (tok.hasMoreTokens()) {
                String hashstr = tok.nextToken();
                Hash h = ConvertToHash.getHash(hashstr);
                if (h == null)
                    error("bad list hash: " + hashstr);
                else if (blackListEnabled)
                    blackList.add(h);
                else
                    accessList.add(h);
            }
        }
        _accessList = accessList;
        _blackList = blackList;
        _accessListEnabled = accessListEnabled;
        _blackListEnabled = blackListEnabled;
        if (_accessListEnabled && _accessList.isEmpty())
            error("Connection access list enabled but no valid entries; no peers can connect");
        else if (_blackListEnabled && _blackList.isEmpty())
            error("Connection blacklist enabled but no valid entries; all peers can connect");
    }

    private static void error(String s) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        Log log = ctx.logManager().getLog(ConnectionOptions.class);
        log.error(s);
    }

    /** doesn't include everything */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
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
        buf.append(" blacklistSize=").append(_blackList.size());
        buf.append(" whitelistSize=").append(_accessList.size());
        buf.append(" maxConns=").append(_maxConnsPerMinute).append('/')
                                .append(_maxConnsPerHour).append('/')
                                .append(_maxConnsPerDay);
        buf.append(" maxTotalConns=").append(_maxTotalConnsPerMinute).append('/')
                                .append(_maxTotalConnsPerHour).append('/')
                                .append(_maxTotalConnsPerDay);
        return buf.toString();
    }
    
    private static boolean getBool(Properties opts, String name, boolean defaultVal) {
        if (opts == null) return defaultVal;
        String val = opts.getProperty(name);
        if (val == null)  return defaultVal;
        return Boolean.parseBoolean(val);
    }

/****
    public static void main(String args[]) {
        Properties p = new Properties();
        
        p.setProperty(PROP_CONNECT_DELAY, "1000");
        ConnectionOptions c = new ConnectionOptions(p);
        System.out.println("opts: " + c);
        
        c = new ConnectionOptions(new I2PSocketOptionsImpl(p));
        System.out.println("opts: " + c);
    }
****/
}
