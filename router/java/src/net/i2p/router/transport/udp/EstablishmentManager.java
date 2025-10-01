package net.i2p.router.transport.udp;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.southernstorm.noise.protocol.ChaChaPolyCipherState;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Banlist;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportUtil;
import static net.i2p.router.transport.udp.InboundEstablishState.InboundState.*;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.*;
import static net.i2p.router.transport.udp.OutboundEstablishState2.IntroState.*;
import static net.i2p.router.transport.udp.SSU2Util.*;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.router.util.DecayingBloomFilter;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.util.Addresses;
import net.i2p.util.HexDump;
import net.i2p.util.I2PThread;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Coordinate the establishment of new sessions - both inbound and outbound.
 * This has its own thread to add packets to the packet queue when necessary,
 * as well as to drop any failed establishment attempts.
 *
 */
class EstablishmentManager {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final int _networkID;

    // SSU 2
    private final PacketBuilder2 _builder2;
    private final Map<RemoteHostId, Token> _outboundTokens;
    private final Map<RemoteHostId, Token> _inboundTokens;
    private final ObjectCounter<RemoteHostId> _terminationCounter;

    /** map of RemoteHostId to InboundEstablishState */
    private final ConcurrentHashMap<RemoteHostId, InboundEstablishState> _inboundStates;

    /**
     * Map of RemoteHostId to OutboundEstablishState.
     * The key could be either an IP/Port (for direct) or
     * a Hash (for indirect, before the RelayResponse is received).
     * Once the RelayResponse is received we change the key.
     */
    private final ConcurrentHashMap<RemoteHostId, OutboundEstablishState> _outboundStates;

    /** map of RemoteHostId to List of OutNetMessage for messages exceeding capacity */
    private final ConcurrentHashMap<RemoteHostId, List<OutNetMessage>> _queuedOutbound;

    /**
     *  Map of nonce (Long) to OutboundEstablishState.
     *  Only for indirect, before we receive the RelayResponse.
     *  This is so we can lookup state for the RelayResponse.
     *  After we receive the relay response, _outboundStates is keyed by actual IP.
     */
    private final ConcurrentHashMap<Long, OutboundEstablishState> _liveIntroductions;

    /**
     *  Map of claimed IP/port to OutboundEstablishState.
     *  Only for indirect, before we receive the RelayResponse.
     *  This is so we can lookup a pending introduction by IP
     *  even before we know the "real" IP, so we can match an inbound packet.
     *  After we receive the relay response, _outboundStates is keyed by actual IP.
     */
    private final ConcurrentHashMap<RemoteHostId, OutboundEstablishState> _outboundByClaimedAddress;

    /**
     *  Map of router hash to OutboundEstablishState.
     *  Only for indirect, after we receive the RelayResponse.
     *  This is so we can lookup a pending connection by Hash
     *  even after we've got the IP/port, so we can match a subsequent outbound packet.
     *  Before we receive the relay response, _outboundStates is keyed by hash.
     */
    private final ConcurrentHashMap<Hash, OutboundEstablishState> _outboundByHash;

    /**
     *  Temporary inbound bans after previous IB failure, to prevent excessive DH.
     *  SSU 2. Value is expiration time.
     */
    private final Map<RemoteHostId, Long> _inboundBans;

    private volatile boolean _alive;
    private final Object _activityLock;
    private int _activity;

    /** max outbound in progress - max inbound is half of this */
    private final int DEFAULT_MAX_CONCURRENT_ESTABLISH;
    private static final int DEFAULT_LOW_MAX_CONCURRENT_ESTABLISH = SystemVersion.isSlow() ? 20 : 40;
    private static final int DEFAULT_HIGH_MAX_CONCURRENT_ESTABLISH = 150;
    private static final String PROP_MAX_CONCURRENT_ESTABLISH = "i2np.udp.maxConcurrentEstablish";

    /** max pending outbound connections (waiting because we are at MAX_CONCURRENT_ESTABLISH) */
    private static final int MAX_QUEUED_OUTBOUND = 50;

    /** max queued msgs per peer while the peer connection is queued */
    private static final int MAX_QUEUED_PER_PEER = 16;
    
    private static final long MAX_NONCE = 0xFFFFFFFFl;

    /**
     * Kill any outbound that takes more than this.
     * Two round trips (Req-Created-Confirmed-Data) for direct;
     * 3 1/2 round trips (RReq-RResp+Intro-HolePunch-Req-Created-Confirmed-Data) for indirect.
     * Note that this is way too long for us to be able to fall back to NTCP
     * for individual messages unless the message timer fires first.
     * But SSU probably isn't higher priority than NTCP.
     * And it's important to not fail an establishment too soon and waste it.
     */
    private static final int MAX_OB_ESTABLISH_TIME = 25*1000;

    /**
     * Kill any inbound that takes more than this
     * One round trip (Created-Confirmed)
     * Note: could be two round trips for SSU2 with retry
     */
    public static final int MAX_IB_ESTABLISH_TIME = 12*1000;

    /** max before receiving a response to a single message during outbound establishment */
    public static final int OB_MESSAGE_TIMEOUT = 15*1000;

    /** for the DSM and or netdb store */
    private static final int DATA_MESSAGE_TIMEOUT = 10*1000;
    
    private static final int IB_BAN_TIME = 39*60*1000;

    // SSU 2
    private static final int MIN_TOKENS = 128;
    private static final int MAX_TOKENS = 2048;
    public static final long IB_TOKEN_EXPIRATION = 60*60*1000L;
    private static final long MAX_SKEW = 2*60*1000;
    private static final String TOKEN_FILE = "ssu2tokens.txt";
    // max immediate terminations to send to a peer every FAILSAFE_INTERVAL
    private static final int MAX_TERMINATIONS = 2;


    public EstablishmentManager(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(EstablishmentManager.class);
        _networkID = ctx.router().getNetworkID();
        _transport = transport;
        _builder2 = transport.getBuilder2();
        _inboundStates = new ConcurrentHashMap<RemoteHostId, InboundEstablishState>();
        _outboundStates = new ConcurrentHashMap<RemoteHostId, OutboundEstablishState>();
        _queuedOutbound = new ConcurrentHashMap<RemoteHostId, List<OutNetMessage>>();
        _liveIntroductions = new ConcurrentHashMap<Long, OutboundEstablishState>();
        _outboundByClaimedAddress = new ConcurrentHashMap<RemoteHostId, OutboundEstablishState>();
        _outboundByHash = new ConcurrentHashMap<Hash, OutboundEstablishState>();
        _inboundBans = new LHMCache<RemoteHostId, Long>(32);
        // roughly scale based on expected traffic
        int tokenCacheSize = Math.max(MIN_TOKENS, Math.min(MAX_TOKENS, 3 * _transport.getMaxConnections() / 4));
        _inboundTokens = new InboundTokens(tokenCacheSize);
        _outboundTokens = new LHMCache<RemoteHostId, Token>(tokenCacheSize);
        _terminationCounter = new ObjectCounter<RemoteHostId>();

        _activityLock = new Object();
        DEFAULT_MAX_CONCURRENT_ESTABLISH = Math.max(DEFAULT_LOW_MAX_CONCURRENT_ESTABLISH,
                                                    Math.min(DEFAULT_HIGH_MAX_CONCURRENT_ESTABLISH,
                                                             ctx.bandwidthLimiter().getOutboundKBytesPerSecond() / 2));
        _context.statManager().createRateStat("udp.inboundEstablishTime", "How long it takes for a new inbound session to be established", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.outboundEstablishTime", "How long it takes for a new outbound session to be established", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.inboundEstablishFailedState", "What state a failed inbound establishment request fails in", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.outboundEstablishFailedState", "What state a failed outbound establishment request fails in", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendIntroRelayTimeout", "How often a relay request times out before getting a response (due to the target or intro peer being offline)", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receiveIntroRelayResponse", "How long it took to receive a relay response", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.establishDropped", "Dropped an inbound establish message", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.establishRejected", "How many pending outbound connections are there when we refuse to add any more?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.establishOverflow", "How many messages were queued up on a pending connection when it was too much?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.establishBadIP", "Received IP or port was bad", "udp", UDPTransport.RATES);
        // following are for PeerState
        _context.statManager().createRateStat("udp.congestionOccurred", "How large the cwin was when congestion occurred (duration == sendBps)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.congestedRTO", "retransmission timeout after congestion (duration == rtt dev)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.mtuIncrease", "How many retransmissions have there been to the peer when the MTU was increased", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.mtuDecrease", "How many retransmissions have there been to the peer when the MTU was decreased", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.rejectConcurrentActive", "How many messages are currently being sent to the peer when we reject it (period is how many concurrent packets we allow)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.allowConcurrentActive", "How many messages are currently being sent to the peer when we accept it (period is how many concurrent packets we allow)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.rejectConcurrentSequence", "How many consecutive concurrency rejections have we had when we stop rejecting (period is how many concurrent packets we are on)", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.queueDropSize", "How many messages were queued up when it was considered full, causing a tail drop?", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.queueAllowTotalLifetime", "When a peer is retransmitting and we probabalistically allow a new message, what is the sum of the pending message lifetimes? (period is the new message's lifetime)?", "udp", UDPTransport.RATES);
        _context.statManager().createRequiredRateStat("udp.inboundTokenLifetime", "SSU2 token lifetime (ms)", "udp", new long[] { 5*60*1000L } );
        _context.statManager().createRequiredRateStat("udp.inboundConn", "Inbound UDP Connection", "udp", new long[] { 60*1000L } );
    }
    
    public synchronized void startup() {
        loadTokens();
        _alive = true;
        I2PThread t = new I2PThread(new Establisher(), "UDP Establisher", true);
        t.start();
    }

    public synchronized void shutdown() { 
        _alive = false;
        saveTokens();
        notifyActivity();
    }
    
    /**
     * Grab the active establishing state
     * @return null if none
     */
    InboundEstablishState getInboundState(RemoteHostId from) {
            InboundEstablishState state = _inboundStates.get(from);
            // if ( (state == null) && (_log.shouldLog(Log.DEBUG)) )
            //     _log.debug("No inbound states for " + from + ", with remaining: " + _inboundStates);
            return state;
    }
    
    /**
     * Grab the active establishing state
     * @return null if none
     */
    OutboundEstablishState getOutboundState(RemoteHostId from) {
            OutboundEstablishState state = _outboundStates.get(from);
            if (state == null) {
                state = _outboundByClaimedAddress.get(from);
                if (state != null && _log.shouldLog(Log.INFO))
                    _log.info("Found by claimed address: " + state);
            }
            // if ( (state == null) && (_log.shouldLog(Log.DEBUG)) )
            //     _log.debug("No outbound states for " + from + ", with remaining: " + _outboundStates);
            return state;
    }
    
    /**
     * How many concurrent outbound sessions to deal with
     */
    private int getMaxConcurrentEstablish() {
        return _context.getProperty(PROP_MAX_CONCURRENT_ESTABLISH, DEFAULT_MAX_CONCURRENT_ESTABLISH);
    }
    
    /**
     * Send the message to its specified recipient by establishing a connection
     * with them and sending it off.  This call does not block, and on failure,
     * the message is failed.
     *
     * Note - if we go back to multiple PacketHandler threads, this may need more locking.
     */
    public void establish(OutNetMessage msg) {
        establish(msg, true);
    }

    /**
     *  @param queueIfMaxExceeded true normally, false if called from locked_admit so we don't loop
     *  @since 0.9.2
     */
    private void establish(OutNetMessage msg, boolean queueIfMaxExceeded) {
        RouterInfo toRouterInfo = msg.getTarget();
        RouterAddress ra = _transport.getTargetAddress(toRouterInfo);
        if (ra == null) {
            _transport.failed(msg, "Remote peer has no address, cannot establish");
            return;
        }
        RouterIdentity toIdentity = toRouterInfo.getIdentity();
        Hash toHash = toIdentity.calculateHash();
        int id = toRouterInfo.getNetworkId();
        if (id != _networkID) {
            if (id == -1)
                _context.banlist().banlistRouter(toHash, "No network specified", null, null, _context.clock().now() + Banlist.BANLIST_DURATION_NO_NETWORK);
            else
                _context.banlist().banlistRouterForever(toHash, "Not in our network: " + id);
            if (_log.shouldWarn())
                _log.warn("Not in our network: " + toRouterInfo, new Exception());
            _transport.markUnreachable(toHash);
            _transport.failed(msg, "Not in our network");
            return;
        }
        UDPAddress addr = new UDPAddress(ra);
        RemoteHostId maybeTo = null;
        InetAddress remAddr = addr.getHostAddress();
        int port = addr.getPort();

        // check for validity and existing inbound state, using the
        // claimed address (which we won't be using if indirect)
        if (remAddr != null && port > 0 && port <= 65535) {
            maybeTo = new RemoteHostId(remAddr.getAddress(), port);

            if ((!_transport.isValid(maybeTo.getIP())) ||
                (Arrays.equals(maybeTo.getIP(), _transport.getExternalIP()) && !_transport.allowLocal())) {
                _transport.failed(msg, "Remote peer's IP isn't valid");
                _transport.markUnreachable(toHash);
                //_context.banlist().banlistRouter(msg.getTarget().getIdentity().calculateHash(), "Invalid SSU address", UDPTransport.STYLE);
                _context.statManager().addRateData("udp.establishBadIP", 1);
                return;
            }

            InboundEstablishState inState = _inboundStates.get(maybeTo);
            if (inState != null) {
                // we have an inbound establishment in progress, queue it there instead
                synchronized (inState) {
                    switch (inState.getState()) {
                      case IB_STATE_UNKNOWN:
                      case IB_STATE_REQUEST_RECEIVED:
                      case IB_STATE_CREATED_SENT:
                      case IB_STATE_CONFIRMED_PARTIALLY:
                      case IB_STATE_CONFIRMED_COMPLETELY:
                      case IB_STATE_TOKEN_REQUEST_RECEIVED:
                      case IB_STATE_REQUEST_BAD_TOKEN_RECEIVED:
                      case IB_STATE_RETRY_SENT:
                        // queue it
                        inState.addMessage(msg);
                        if (_log.shouldLog(Log.WARN))
                            _log.debug("OB msg queued to IES");
                        break;

                      case IB_STATE_COMPLETE:
                        // race, send it out (but don't call _transport.send() again and risk a loop)
                        _transport.sendIfEstablished(msg);
                        break;

                      case IB_STATE_FAILED:
                        // race, failed
                        _transport.failed(msg, "OB msg failed during IB establish");
                        break;
                    }
                }
                return;
            }
        }

        RemoteHostId to;
        boolean isIndirect = addr.getIntroducerCount() > 0 || maybeTo == null;
        if (isIndirect) {
            to = new RemoteHostId(toHash);
        } else {
            to = maybeTo;
        }
        
        OutboundEstablishState state = null;
        int deferred = 0;
        boolean rejected = false;
        int queueCount = 0;

            state = _outboundStates.get(to);
            if (state == null) {
                state = _outboundByHash.get(toHash);
                if (state != null && _log.shouldLog(Log.INFO))
                    _log.info("Found by hash: " + state);
            }
            if (state == null) {
                if (queueIfMaxExceeded && _outboundStates.size() >= getMaxConcurrentEstablish()) {
                    if (_queuedOutbound.size() >= MAX_QUEUED_OUTBOUND && !_queuedOutbound.containsKey(to)) {
                        rejected = true;
                    } else {
                        List<OutNetMessage> newQueued = new ArrayList<OutNetMessage>(MAX_QUEUED_PER_PEER);
                        List<OutNetMessage> queued = _queuedOutbound.putIfAbsent(to, newQueued);
                        if (queued == null) {
                            queued = newQueued;
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Queueing outbound establish to " + to + ", increase " + PROP_MAX_CONCURRENT_ESTABLISH);
                        }
                        // this used to be inside a synchronized (_outboundStates) block,
                        // but that's now a CHM, so protect the ArrayList
                        // There are still races possible but this should prevent AIOOBE and NPE
                        synchronized (queued) {
                            queueCount = queued.size();
                            if (queueCount < MAX_QUEUED_PER_PEER) {
                                queued.add(msg);
                                // increment for the stat below
                                queueCount++;
                            } else {
                                rejected = true;
                            }
                            deferred = _queuedOutbound.size();
                        }
                    }
                } else {
                    // must have a valid session key
                    byte[] keyBytes;
                    int version = _transport.getSSUVersion(ra);
                    if (isIndirect && version == 2 && ra.getTransportStyle().equals("SSU")) {
                        boolean v2intros = false;
                        int count = addr.getIntroducerCount();
                        long now = _context.clock().now();
                        for (int i = 0; i < count; i++) {
                            Hash h = addr.getIntroducerHash(i);
                            long exp = addr.getIntroducerExpiration(i);
                            if (h != null && (exp > now || exp == 0)) {
                                v2intros = true;
                                break;
                            }
                            if (!v2intros) {
                                _transport.markUnreachable(toHash);
                                _transport.failed(msg, "No v2 introducers");
                                return;
                            }
                        }
                    }
                    if (version == 2) {
                        int mtu = addr.getMTU();
                        boolean isIPv6 = TransportUtil.isIPv6(ra);
                        int ourMTU = _transport.getMTU(isIPv6);
                        if ((mtu > 0 && mtu < PeerState2.MIN_MTU) ||
                            (ourMTU > 0 && ourMTU < PeerState2.MIN_MTU)) {
                            _transport.markUnreachable(toHash);
                            _transport.failed(msg, "MTU too small");
                            return;
                        }
                    }
                    if (version == 1) {
                        keyBytes = null;
                    } else {
                        String siv = ra.getOption("i");
                        if (siv != null)
                            keyBytes = Base64.decode(siv);
                        else
                            keyBytes = null;
                    }
                    if (keyBytes == null) {
                        if (_log.shouldWarn())
                            _log.warn("No intro key\n" + toRouterInfo);
                        _transport.markUnreachable(toHash);
                        _transport.failed(msg, "Peer has no key, cannot establish");
                        return;
                    }
                    SessionKey sessionKey;
                    try {
                        sessionKey = new SessionKey(keyBytes);
                    } catch (IllegalArgumentException iae) {
                        _transport.markUnreachable(toHash);
                        _transport.failed(msg, "Peer has bad key, cannot establish");
                        return;
                    }
                    if (version == 2) {
                        boolean requestIntroduction = !isIndirect &&
                                                      _transport.introducersMaybeRequired(TransportUtil.isIPv6(ra));
                        try {
                            state = new OutboundEstablishState2(_context, _transport, maybeTo, to,
                                                               toIdentity, requestIntroduction, sessionKey, ra, addr);
                        } catch (IllegalArgumentException iae) {
                            if (_log.shouldWarn())
                                _log.warn("OES2 error: " + toRouterInfo, iae);
                            _transport.markUnreachable(toHash);
                            _transport.failed(msg, iae.getMessage());
                            return;
                        }
                    } else {
                        // shouldn't happen
                        _transport.failed(msg, "OB to bad addr? " + ra);
                        return;
                    }
                    OutboundEstablishState oldState = _outboundStates.putIfAbsent(to, state);
                    boolean isNew = oldState == null;
                    if (isNew) {
                        if (isIndirect && maybeTo != null)
                            _outboundByClaimedAddress.put(maybeTo, state);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Adding new " + state);
                    } else {
                        // whoops, somebody beat us to it, throw out the state we just created
                        state = oldState;
                    }
                }
            }
            if (state != null) {
                state.addMessage(msg);
                List<OutNetMessage> queued = _queuedOutbound.remove(to);
                if (queued != null) {
                    // see comments above
                    synchronized (queued) {
                        for (OutNetMessage m : queued) {
                            state.addMessage(m);
                        }
                    }
                }
            }
        
        if (rejected) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too many pending, rejecting outbound establish to " + to);
            _transport.failed(msg, "Too many pending outbound connections");
            _context.statManager().addRateData("udp.establishRejected", deferred);
            return;
        }
        if (queueCount >= MAX_QUEUED_PER_PEER) {
            _transport.failed(msg, "Too many pending messages for the given peer");
            _context.statManager().addRateData("udp.establishOverflow", queueCount, deferred);
            return;
        }
        
        if (deferred > 0)
            msg.timestamp("too many deferred establishers");
        else if (state != null)
            msg.timestamp("establish state already waiting");
        notifyActivity();
    }
    
    /**
     * How many concurrent inbound sessions to deal with
     */
    private int getMaxInboundEstablishers() { 
        return getMaxConcurrentEstablish()/2; 
    }
    
    /**
     * Should we allow another inbound establishment?
     *
     * @since 0.9.2
     */
    public boolean shouldAllowInboundEstablishment() {
        if (_inboundStates.size() >= getMaxInboundEstablishers())
            return false;
        RateStat rs = _context.statManager().getRate("udp.inboundConn");
        if (rs == null)
            return true;
        Rate r = rs.getRate(60*1000);
        if (r == null)
            return true;
        int last;
        long periodStart;
        RateAverages ra = RateAverages.getTemp();
        synchronized(r) {
            last = (int) r.getLastEventCount();
            periodStart = r.getLastCoalesceDate();
            r.computeAverages(ra, true);
        }
        // compare incoming conns per ms, min of 1 per second or 60/minute
        if (last < 15)
            last = 15;
        int total = (int) ra.getTotalEventCount();
        int current = total - last;
        if (current <= 0)
            return true;
        // getLastEventCount() is normalized to the rate, so we use the canonical period
        int lastPeriod = 60*1000;
        double avg = ra.getAverage();
        int currentTime = (int) (_context.clock().now() - periodStart);
        if (currentTime <= 5*1000)
            return true;
        // compare incoming conns per ms
        // both of these are scaled by actual period in coalesce
        float lastRate = last / (float) lastPeriod;
        float currentRate = (float) (current / (double) currentTime);
        float factor = _transport.haveCapacity(95) ? 1.05f : 0.95f;
        float minThresh = factor * lastRate;
        if (currentRate > minThresh) {
            // chance in 128
            // max out at about 25% over the last rate
            int probAccept = Math.max(1, ((int) (4 * 128 * currentRate / minThresh)) - 512);
            if (probAccept >= 128 || _context.random().nextInt(128) < probAccept) {
                if (_log.shouldWarn())
                    _log.warn("Probabalistic drop incoming (p=" + probAccept  +
                              "/128) last rate " + last + "/min current rate " +
                              (int) (currentRate * 60*1000));
                return false;
            }
        }
        return true;
    }
    
    /**
     * Got a SessionRequest OR a TokenRequest (initiates an inbound establishment)
     *
     * SSU 2 only.
     * @param state as looked up in PacketHandler, but null unless retransmitted or retry sent
     * @param packet header decrypted only
     * @since 0.9.54
     */
    void receiveSessionOrTokenRequest(RemoteHostId from, InboundEstablishState2 state, UDPPacket packet) {
        byte[] fromIP = from.getIP();
        if (!TransportUtil.isValidPort(from.getPort()) || !_transport.isValid(fromIP)) {
            if (_log.shouldWarn())
                _log.warn("Receive session request from invalid: " + from);
            return;
        }
        boolean isNew = false;
        if (state == null) {
            if (_context.blocklist().isBlocklisted(fromIP)) {
                if (_log.shouldInfo())
                    _log.info("Receive session request from blocklisted IP: " + from);
                _context.statManager().addRateData("udp.establishBadIP", 1);
                if (!_context.commSystem().isInStrictCountry())
                    sendTerminationPacket(from, packet, REASON_BANNED);
                // else drop the packet
                return;
            }
            if (!_context.commSystem().isExemptIncoming(Addresses.toString(fromIP))) {
                // TODO this is insufficient to prevent DoSing, especially if
                // IP spoofing is used. For further study.
                if (!shouldAllowInboundEstablishment()) {
                    if (_log.shouldWarn())
                        _log.warn("Dropping inbound establish");
                    _context.statManager().addRateData("udp.establishDropped", 1);
                    sendTerminationPacket(from, packet, REASON_LIMITS);
                    return;
                }
                synchronized (_inboundBans) {
                    Long exp = _inboundBans.get(from);
                    if (exp != null) {
                        if (exp.longValue() >= _context.clock().now()) {
                            // this is common, finally get a packet after the IES2 timeout
                            if (_log.shouldInfo())
                                _log.info("SSU 2 session request from temp. blocked peer: " + from);
                             _context.statManager().addRateData("udp.establishBadIP", 1);
                             // use this code for a temp ban
                             sendTerminationPacket(from, packet, REASON_MSG1);
                             return;
                        }
                        // expired
                        _inboundBans.remove(from);
                    }
                }
                if (!_transport.allowConnection()) {
                    sendTerminationPacket(from, packet, REASON_LIMITS);
                    return;
                }
            }
            try {
                state = new InboundEstablishState2(_context, _transport, packet);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldWarn())
                    _log.warn("Corrupt Session/Token Request from: " + from, gse);
                _context.statManager().addRateData("udp.establishDropped", 1);
                return;
            }

            _context.statManager().addRateData("udp.inboundConn", 1);

          /****
            // A token request or session request with a bad token is
            // inexpensive to reply to.
            // A token can only be used once, so a replayed session request
            // will only generate a retry.
            // So probably don't need a replay detector at all
            if (_replayFilter.add(state.getReceivedX(), 0, 8)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Duplicate X in session request from: " + from);
                _context.statManager().addRateData("udp.dupDHX", 1);
                return; // drop the packet
            }
          ****/

            InboundEstablishState oldState = _inboundStates.putIfAbsent(from, state);
            isNew = oldState == null;
            if (!isNew) {
                // whoops, somebody beat us to it, throw out the state we just created
                if (oldState.getVersion() == 2)
                    state = (InboundEstablishState2) oldState;
                // else don't cast, this is only for printing below
            }
        } else {
            try {
                state.receiveSessionOrTokenRequestAfterRetry(packet);
            } catch (GeneralSecurityException gse) {
                if (_log.shouldWarn())
                    _log.warn("Corrupt Session/Token Request after Retry from: " + state, gse);
                // state called fail()
                _inboundStates.remove(state.getRemoteHostId());
                return;
            }
        }

        if (_log.shouldDebug()) {
            if (isNew)
                _log.debug("Received NEW session/token request " + state);
            else
                _log.debug("Receive DUP session/token request from: " + state);
        }

        // Wait until we have RI
        // sentRelayTag remains 0 and will not be sent in SessionConfirmed
        // See InboundEstablishState2
        // call for both Session and Token request, why not
        //if (state.isIntroductionRequested() &&
        //    state.getSentRelayTag() == 0 &&     // only set once
        //    state.getSentPort() >= 1024 &&
        //    _transport.canIntroduce(state.getSentIP().length == 16)) {
        //    long tag = 1 + _context.random().nextLong(MAX_TAG_VALUE);
        //    state.setSentRelayTag(tag);
        //}
        notifyActivity();
    }

    /**
     * Send a Retry packet with a termination code, for a rejection
     * of a session/token request. No InboundEstablishState2 required.
     *
     * SSU 2 only.
     * The inbound packet was superficially validated for type, netID, and version,
     * so we have basic probing resistance.
     * The Retry packet encryption is low-cost, chacha only.
     *
     * Rate limited to MAX_TERMINATIONS per peer every FAILSAFE_INTERVAL
     *
     * @param fromPacket header already decrypted, must be session or token request
     * @param terminationCode nonzero
     * @since 0.9.57
     */
    private void sendTerminationPacket(RemoteHostId to, UDPPacket fromPacket, int terminationCode) {
        int count = _terminationCounter.increment(to);
        if (count > MAX_TERMINATIONS) {
            // not everybody listens or backs off...
            if (_log.shouldWarn())
                _log.warn("Rate limit " + count + " not sending termination to: " + to);
            return;
        }
        // very basic validation that this is probably in response to a good packet.
        // we don't bother to decrypt the packet, even if it's only a token request
        if (_transport.isTooClose(to.getIP()))
            return;
        DatagramPacket pkt = fromPacket.getPacket();
        int len = pkt.getLength();
        if (len < MIN_LONG_DATA_LEN)
            return;
        int off = pkt.getOffset();
        byte data[] = pkt.getData();
        int type = data[off + TYPE_OFFSET] & 0xff;
        if (type == SSU2Util.SESSION_REQUEST_FLAG_BYTE && len < MIN_SESSION_REQUEST_LEN)
            return;
        long rcvConnID = DataHelper.fromLong8(data, off);
        long sendConnID = DataHelper.fromLong8(data, off + SRC_CONN_ID_OFFSET);
        if (rcvConnID == 0 || sendConnID == 0 || rcvConnID == sendConnID)
            return;
        if (_log.shouldWarn())
            _log.warn("Send immediate termination " + terminationCode + " on type " + type + " to: " + to);
        UDPPacket packet = _builder2.buildRetryPacket(to, pkt.getSocketAddress(), sendConnID, rcvConnID, terminationCode);
        _transport.send(packet);
    }
    
    /** 
     * got a SessionConfirmed (should only happen as part of an inbound 
     * establishment) 
     *
     * SSU 2 only.
     * @param state non-null
     * @param packet header decrypted only
     * @since 0.9.54
     */
    void receiveSessionConfirmed(InboundEstablishState2 state, UDPPacket packet) {
        try {
            state.receiveSessionConfirmed(packet);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Corrupt Session Confirmed on: " + state, gse);
            // state called fail()
            _inboundStates.remove(state.getRemoteHostId());
            return;
        }
        InboundEstablishState.InboundState istate = state.getState();
        if (istate == IB_STATE_CONFIRMED_COMPLETELY ||
            istate == IB_STATE_COMPLETE) {
            // we are done, go right to ps2
            handleCompletelyEstablished(state);
        } else {
            // More RI blocks to come, TODO
        }
        notifyActivity();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive session confirmed from: " + state);
    }
    
    /**
     * Got a SessionCreated (in response to our outbound SessionRequest)
     *
     * SSU 2 only.
     *
     * @param state non-null
     * @param packet header decrypted only
     * @since 0.9.54
     */
    void receiveSessionCreated(OutboundEstablishState2 state, UDPPacket packet) {
        try {
            state.receiveSessionCreated(packet);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Corrupt Session Created on: " + state, gse);
            // state called fail()
            _outboundStates.remove(state.getRemoteHostId());
            return;
        }
        notifyActivity();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive session created from: " + state);
    }
    
    /**
     * Got a Retry (in response to our outbound SessionRequest or TokenRequest)
     *
     * SSU 2 only.
     * @since 0.9.54
     */
    void receiveRetry(OutboundEstablishState2 state, UDPPacket packet) {
        try {
            state.receiveRetry(packet);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldWarn())
                _log.warn("Corrupt Retry from: " + state, gse);
            // state called fail()
            _outboundStates.remove(state.getRemoteHostId());
            return;
        }
        notifyActivity();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive retry with token " + state.getToken() + " from: " + state);
    }

    /**
     * Got a SessionDestroy on an established conn
     *
     * SSU 2
     *
     * @since 0.8.1
     */
    void receiveSessionDestroy(RemoteHostId from, PeerState state) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive session destroy (EST) from: " + from);
        _transport.dropPeer(state, false, "received destroy message");
    }

    /**
     * Got a SessionDestroy during outbound establish
     *
     * SSU 2
     *
     * @since 0.8.1
     */
    void receiveSessionDestroy(RemoteHostId from, OutboundEstablishState state) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive session destroy (OB) from: " + from);
        _outboundStates.remove(from);
        Hash peer = state.getRemoteIdentity().calculateHash();
        _transport.dropPeer(peer, false, "received destroy message during OB establish");
    }

    /**
     * Got a SessionDestroy - maybe during an inbound establish?
     * TODO - PacketHandler won't look up inbound establishes
     * As this packet was essentially unauthenticated (i.e. intro key, not session key)
     * we just log it as it could be spoofed.
     *
     * SSU 2
     *
     * @since 0.8.1
     */
    void receiveSessionDestroy(RemoteHostId from) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Receive session destroy (none) from: " + from);
        //InboundEstablishState state = _inboundStates.remove(from);
        //if (state != null) {
        //    Hash peer = state.getConfirmedIdentity().calculateHash();
        //    if (peer != null)
        //        _transport.dropPeer(peer, false, "received destroy message");
        //}
    }

    /**
     * A data packet arrived on an outbound connection being established, which
     * means its complete (yay!).  This is a blocking call, more than I'd like...
     *
     * @return the new PeerState
     */
    PeerState receiveData(OutboundEstablishState state) {
        state.dataReceived();
        //int active = 0;
        //int admitted = 0;
        //int remaining = 0;

            //active = _outboundStates.size();
            _outboundStates.remove(state.getRemoteHostId());
                // there shouldn't have been queued messages for this active state, but just in case...
                List<OutNetMessage> queued = _queuedOutbound.remove(state.getRemoteHostId());
                if (queued != null) {
                    // see comments above
                    synchronized (queued) {
                        for (OutNetMessage m : queued) {
                            state.addMessage(m);
                        }
                    }
                }
                
        if (_outboundStates.size() < getMaxConcurrentEstablish() && !_queuedOutbound.isEmpty()) {
            locked_admitQueued();
        }
            //remaining = _queuedOutbound.size();

        //if (admitted > 0)
        //    _log.log(Log.CRIT, "Admitted " + admitted + " with " + remaining + " remaining queued and " + active + " active");
        
        if (_log.shouldDebug())
            _log.debug("Outbound established: " + state);
        PeerState peer = handleCompletelyEstablished(state);
        notifyActivity();
        return peer;
    }

    /**
     *  Move pending OB messages from _queuedOutbound to _outboundStates.
     *  This isn't so great because _queuedOutbound is not a FIFO.
     */
    private int locked_admitQueued() {
        if (_queuedOutbound.isEmpty())
            return 0;
        int admitted = 0;
        int max = getMaxConcurrentEstablish();
        for (Iterator<Map.Entry<RemoteHostId, List<OutNetMessage>>> iter = _queuedOutbound.entrySet().iterator();
             iter.hasNext() && _outboundStates.size() < max; ) {
            // ok, active shrunk, lets let some queued in.

            Map.Entry<RemoteHostId, List<OutNetMessage>> entry = iter.next();
            // java 5 IllegalStateException here
            try {
                iter.remove();
            } catch (IllegalStateException ise) {
                continue;
            }
            List<OutNetMessage> allQueued = entry.getValue();
            List<OutNetMessage> queued = new ArrayList<OutNetMessage>();
            long now = _context.clock().now();
            synchronized (allQueued) {
                for (OutNetMessage msg : allQueued) {
                    if (now - Router.CLOCK_FUDGE_FACTOR > msg.getExpiration()) {
                        _transport.failed(msg, "Took too long in est. mgr OB queue");
                    } else {
                        queued.add(msg);
                    }

                }
            }
            if (queued.isEmpty())
                continue;
            
            for (OutNetMessage m : queued) {
                m.timestamp("no longer deferred... establishing");
                establish(m, false);
            }
            admitted++;
        }
        return admitted;
    }
    
    private void notifyActivity() {
        synchronized (_activityLock) { 
            _activity++;
            _activityLock.notifyAll(); 
        }
    }
    
    /** 
     * ok, fully received, add it to the established cons and queue up a
     * netDb store to them
     *
     */
    private void handleCompletelyEstablished(InboundEstablishState state) {
        if (state.isComplete()) return;
        
        RouterIdentity remote = state.getConfirmedIdentity();
        PeerState peer;

            InboundEstablishState2 state2 = (InboundEstablishState2) state;
            peer = state2.getPeerState();
            // now handled in IES2.createPeerState()
            //peer.setWeRelayToThemAs(state.getSentRelayTag());

        // 0 is the default
        //peer.setTheyRelayToUsAs(0);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle completely established (inbound): " + state
                       + " - " + peer.getRemotePeer());
        
        //if (true) // for now, only support direct
        //    peer.setRemoteRequiresIntroduction(false);
        
        _transport.addRemotePeerState(peer);
        
        boolean isIPv6 = state.getSentIP().length == 16;
        _transport.inboundConnectionReceived(isIPv6);
        _transport.setIP(remote.calculateHash(), state.getSentIP());
        
        _context.statManager().addRateData("udp.inboundEstablishTime", state.getLifetime());
        sendInboundComplete(peer);
        OutNetMessage msg;
        while ((msg = state.getNextQueuedMessage()) != null) {
            if (_context.clock().now() - Router.CLOCK_FUDGE_FACTOR > msg.getExpiration()) {
                msg.timestamp("took too long but established...");
                _transport.failed(msg, "Took too long to establish, but it was established");
            } else {
                msg.timestamp("session fully established and sent");
                _transport.send(msg);
            }
        }
        state.complete();
    }

    /**
     * send our info immediately
     * TODO move to / combine with sendAck0()
     */
    private void sendInboundComplete(PeerState peer) {
        if (_log.shouldDebug())
            _log.debug("IB confirm: " + peer);
        // SSU 2 uses an ACK of packet 0

                DatabaseStoreMessage dbsm = getOurInfo();
                _transport.send(dbsm, peer);
    }
    
    /** 
     * ok, fully received, add it to the established cons and send any
     * queued messages
     *
     * @return the new PeerState
     */
    private PeerState handleCompletelyEstablished(OutboundEstablishState state) {
        if (state.complete()) {
            RouterIdentity rem = state.getRemoteIdentity();
            if (rem != null)
                return _transport.getPeerState(rem.getHash());
        }
        
        long now = _context.clock().now();
        RouterIdentity remote = state.getRemoteIdentity();
        // only if == state
        RemoteHostId claimed = state.getClaimedAddress();
        if (claimed != null)
            _outboundByClaimedAddress.remove(claimed, state);
        _outboundByHash.remove(remote.calculateHash(), state);

            OutboundEstablishState2 state2 = (OutboundEstablishState2) state;
            // OES2 sets PS2 MTU
            PeerState peer = state2.getPeerState();

        peer.setTheyRelayToUsAs(state.getReceivedRelayTag());
        // 0 is the default
        //peer.setWeRelayToThemAs(0);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle completely established (outbound): " + state
                       + " - " + peer.getRemotePeer());
        
        
        _transport.addRemotePeerState(peer);
        _transport.setIP(remote.calculateHash(), state.getSentIP());
        
        _context.statManager().addRateData("udp.outboundEstablishTime", state.getLifetime(now));
        DatabaseStoreMessage dbsm = null;
        
        List<OutNetMessage> msgs = new ArrayList<OutNetMessage>(8);
        OutNetMessage msg;
        while ((msg = state.getNextQueuedMessage()) != null) {
            if (now - Router.CLOCK_FUDGE_FACTOR > msg.getExpiration()) {
                msg.timestamp("took too long but established...");
                _transport.failed(msg, "Took too long to establish, but it was established");
            } else {
                msg.timestamp("session fully established and sent");
                msgs.add(msg);
            }
        }
        _transport.send(dbsm, msgs, peer);
        return peer;
    }
    
/****
    private void sendOurInfo(PeerState peer, boolean isInbound) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Publishing to the peer after confirm: " + 
                      (isInbound ? " inbound con from " + peer : "outbound con to " + peer));
        DatabaseStoreMessage m = getOurInfo();
        _transport.send(m, peer);
    }
****/
    
    /**
     *  A database store message with our router info
     *  @return non-null
     *  @since 0.9.24 split from sendOurInfo(), public since 0.9.55 for UDPTransport
     */
    public DatabaseStoreMessage getOurInfo() {
        DatabaseStoreMessage m = new DatabaseStoreMessage(_context);
        m.setEntry(_context.router().getRouterInfo());
        m.setMessageExpiration(_context.clock().now() + DATA_MESSAGE_TIMEOUT);
        return m;
    }
    
    /** the relay tag is a 4-byte field in the protocol */
    public static final long MAX_TAG_VALUE = 0xFFFFFFFFl;
    
    /**
     *  This handles both initial send and retransmission of Session Created,
     *  and, for SSU2, send of Retry.
     *  Retry is never retransmitted except in response to a retransmitted Token Request.
     *
     *  This may be called more than once.
     *
     *  Caller must synch on state.
     */
    private void sendCreated(InboundEstablishState state) {
        UDPPacket pkt;

            InboundEstablishState2 state2 = (InboundEstablishState2) state;
            InboundEstablishState.InboundState istate = state2.getState();
            switch (istate) {
              case IB_STATE_CREATED_SENT:
                if (_log.shouldInfo())
                    _log.info("Retransmit created to: " + state);
                // if already sent, get from the state to retx
                pkt = state2.getRetransmitSessionCreatedPacket();
                break;

              case IB_STATE_REQUEST_RECEIVED:
                if (_log.shouldDebug())
                    _log.debug("Send created to: " + state);
                pkt = _builder2.buildSessionCreatedPacket(state2);
                break;

              case IB_STATE_TOKEN_REQUEST_RECEIVED:
              case IB_STATE_REQUEST_BAD_TOKEN_RECEIVED:
              case IB_STATE_RETRY_SENT:     // got a retransmitted token request
                if (_log.shouldDebug())
                    _log.debug("Send retry to: " + state);
                pkt = _builder2.buildRetryPacket(state2, 0);
                break;

              default:
                if (_log.shouldWarn())
                    _log.warn("Unhandled state " + istate + " on " + state);
                return;
            }

        if (pkt == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Peer " + state + " sent us an invalid IP?");
            _inboundStates.remove(state.getRemoteHostId());
            state.fail();
            return;
        }
        _transport.send(pkt);
        // PacketBuilder2 told the state
    }

    /**
     *  This handles both initial send and retransmission of Session Request,
     *  and, for SSU2, initial send and retransmission of Token Request.
     *
     *  This may be called more than once.
     *
     *  Caller must synch on state.
     */
    private void sendRequest(OutboundEstablishState state) {
        UDPPacket packet;

            OutboundEstablishState2 state2 = (OutboundEstablishState2) state;
            OutboundEstablishState.OutboundState ostate = state2.getState();
            switch (ostate) {
              case OB_STATE_REQUEST_SENT:
              case OB_STATE_REQUEST_SENT_NEW_TOKEN:
                if (_log.shouldInfo())
                    _log.info("Retransmit Session Request to: " + state);
                // if already sent, get from the state to retx
                packet = state2.getRetransmitSessionRequestPacket();
                break;

              case OB_STATE_NEEDS_TOKEN:
              case OB_STATE_TOKEN_REQUEST_SENT:
                if (_log.shouldDebug())
                    _log.debug("Send Token Request to: " + state);
                packet = _builder2.buildTokenRequestPacket(state2);
                break;

              case OB_STATE_UNKNOWN:
              case OB_STATE_RETRY_RECEIVED:
                if (_log.shouldDebug())
                    _log.debug("Send Session Request to: " + state);
                packet = _builder2.buildSessionRequestPacket(state2);
                break;

              case OB_STATE_INTRODUCED:
                if (_log.shouldDebug())
                    _log.debug("Send Session Request after introduction to: " + state);
                packet = _builder2.buildSessionRequestPacket(state2);
                break;

              default:
                if (_log.shouldWarn())
                    _log.warn("Unhandled state " + ostate + " on " + state);
                return;
            }

        if (packet != null) {
            _transport.send(packet);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build a session request packet for " + state);
        }
        // PacketBuilder2 told the state
    }
    
    /**
     *  Send RelayRequests to multiple introducers.
     *  This may be called multiple times, it sets the nonce the first time only
     *  Caller should probably synch on state.
     *
     *  SSU 2
     *
     *  @param state charlie
     */
    private void handlePendingIntro(OutboundEstablishState state) {
        long nonce = state.getIntroNonce();
        if (nonce < 0) {
            OutboundEstablishState old;
            do {
                nonce = _context.random().nextLong(MAX_NONCE);
                old = _liveIntroductions.putIfAbsent(Long.valueOf(nonce), state);
            } while (old != null);
            state.setIntroNonce(nonce);
        }

            // walk through the state machine for each SSU2 introducer
            OutboundEstablishState2 state2 = (OutboundEstablishState2) state;
            // establish() above ensured there is at least one valid v2 introducer
            // Look for a connected peer, if found, use the first one only.
            UDPAddress addr = state.getRemoteAddress();
            int count = addr.getIntroducerCount();
            for (int i = 0; i < count; i++) {
                Hash h = addr.getIntroducerHash(i);
                if (h != null) {
                    PeerState bob = null;
                    OutboundEstablishState2.IntroState istate = state2.getIntroState(h);
                    switch (istate) {
                        case INTRO_STATE_INIT:
                        case INTRO_STATE_CONNECTING:
                            bob = _transport.getPeerState(h);
                            if (bob != null) {
                                if (bob.getVersion() == 2) {
                                    istate = INTRO_STATE_CONNECTED;
                                    state2.setIntroState(h, istate);
                                } else {
                                    // TODO cross-version relaying, maybe
                                    istate = INTRO_STATE_REJECTED;
                                    state2.setIntroState(h, istate);
                                }
                            }
                            break;

                        case INTRO_STATE_CONNECTED:
                            bob = _transport.getPeerState(h);
                            if (bob == null) {
                                istate = INTRO_STATE_DISCONNECTED;
                                state2.setIntroState(h, istate);
                            }
                            break;

                    }
                    if (bob != null && istate == INTRO_STATE_CONNECTED) {
                        if (_log.shouldDebug())
                            _log.debug("Found connected introducer " + bob + " for " + state);
                        long tag = addr.getIntroducerTag(i);
                        boolean ok = sendRelayRequest(tag, (PeerState2) bob, state);
                        // this transitions the state
                        if (ok)
                            state2.introSent(h);
                        else
                            state2.setIntroState(h, INTRO_STATE_DISCONNECTED);
                        return;
                    }
                }
            }
            // Otherwise, look for ones we already have RIs for, attempt to connect to each.
            boolean sent = false;
            for (int i = 0; i < count; i++) {
                Hash h = addr.getIntroducerHash(i);
                if (h != null) {
                    RouterInfo bob = null;
                    OutboundEstablishState2.IntroState istate = state2.getIntroState(h);
                    OutboundEstablishState2.IntroState oldState = istate;
                    switch (istate) {
                        case INTRO_STATE_INIT:
                        case INTRO_STATE_LOOKUP_SENT:
                        case INTRO_STATE_HAS_RI:
                            bob = _context.netDb().lookupRouterInfoLocally(h);
                            if (bob != null)
                                istate = INTRO_STATE_HAS_RI;
                            break;
                    }
                    if (bob != null && istate == INTRO_STATE_HAS_RI) {
                        List<RouterAddress> addrs = _transport.getTargetAddresses(bob);
                        for (RouterAddress ra : addrs) {
                            byte[] ip = ra.getIP();
                            int port = ra.getPort();
                            if (ip == null || port <= 0)
                                continue;
                            RemoteHostId rhid = new RemoteHostId(ip, port);
                            OutboundEstablishState oes = _outboundStates.get(rhid);
                            if (oes != null) {
                                if (_log.shouldDebug())
                                    _log.debug("Awaiting pending connection to introducer " + oes + " for " + state);
                                break;
                            }
                            int version = _transport.getSSUVersion(ra);
                            if (version == 2) {
                                if (_log.shouldDebug())
                                    _log.debug("Connecting to introducer " + bob + " for " + state);
                                // arbitrary message because we have no way to connect for no reason
                                DatabaseLookupMessage dlm = new DatabaseLookupMessage(_context);
                                dlm.setSearchKey(h);
                                dlm.setSearchType(DatabaseLookupMessage.Type.RI);
                                long now = _context.clock().now();
                                dlm.setMessageExpiration(now + 10*1000);
                                dlm.setFrom(_context.routerHash());
                                OutNetMessage m = new OutNetMessage(_context, dlm, now + 10*1000, OutNetMessage.PRIORITY_MY_NETDB_LOOKUP, bob);
                                establish(m);
                                istate = INTRO_STATE_CONNECTING;
                                // for now, just wait until this method is called again,
                                // hopefully somebody has connected
                                break;
                            }
                        }
                    }
                    // if we didn't try to connect, it must have had a bad RI
                    if (istate == INTRO_STATE_HAS_RI)
                        istate = INTRO_STATE_REJECTED;
                    if (oldState != istate)
                        state2.setIntroState(h, istate);
                }
            }
            if (sent) {
                // not really
                state.introSent();
                return;
            }
            // Otherwise, look up the RIs first.
            for (int i = 0; i < count; i++) {
                Hash h = addr.getIntroducerHash(i);
                if (h != null) {
                    OutboundEstablishState2.IntroState istate = state2.getIntroState(h);
                    if (istate == INTRO_STATE_INIT) {
                        if (_log.shouldDebug())
                            _log.debug("Looking up introducer " + h + " for " + state);
                        istate = INTRO_STATE_LOOKUP_SENT;
                        state2.setIntroState(h, istate);
                        // TODO on success job
                        _context.netDb().lookupRouterInfo(h, null, null, 10*1000);
                        sent = true;
                    }
                }
            }
            if (sent) {
                // not really
                state.introSent();
            } else {
                if (_log.shouldDebug())
                    _log.debug("No valid introducers for " + state);
                processExpired(state);
            }
    }

    /**
     *  We are Alice, send a RelayRequest to Bob.
     *
     *  SSU 2 only.
     *
     *  @param charlie must be SSU2
     *  @return success
     *  @since 0.9.55
     */
    private boolean sendRelayRequest(long tag, PeerState2 bob, OutboundEstablishState charlie) {
        // pick our IP based on what address we're connecting to
        UDPAddress cra = charlie.getRemoteAddress();
        RouterAddress ourra;
        if (cra.isIPv6()) {
            ourra = _transport.getCurrentExternalAddress(true);
            if (ourra == null)
                ourra = _transport.getCurrentExternalAddress(false);
        } else {
            ourra = _transport.getCurrentExternalAddress(false);
        }
        if (ourra == null) {
            if (_log.shouldWarn())
                _log.warn("No address to send in relay request");
            return false;
        }
        byte[] ourIP = ourra.getIP();
        if (ourIP == null) {
            if (_log.shouldWarn())
                _log.warn("No IP to send in relay request");
            return false;
        }
        // Bob should already have our RI, especially if we just connected; we do not resend it here.
        int ourPort = _transport.getRequestedPort();
        byte[] data = SSU2Util.createRelayRequestData(_context, bob.getRemotePeer(), charlie.getRemoteIdentity().getHash(),
                                                      charlie.getIntroNonce(), tag, ourIP, ourPort,
                                                      _context.keyManager().getSigningPrivateKey());
        if (data == null) {
            if (_log.shouldWarn())
                _log.warn("sig fail");
            return false;
        }
        UDPPacket packet;
        try {
            packet = _builder2.buildRelayRequest(data, bob);
        } catch (IOException ioe) {
            return false;
        }
        if (_log.shouldDebug())
            _log.debug("Send relay request to " + bob + " for " + charlie);
        _transport.send(packet);
        bob.setLastSendTime(_context.clock().now());
        return true;
    }

    /**
     *  We are Alice, we sent a RelayRequest to Bob and got a RelayResponse back.
     *  Time and version already checked by caller.
     *
     *  SSU 2 only.
     *
     *  @param data including nonce, including token if code == 0
     *  @since 0.9.55
     */
    void receiveRelayResponse(PeerState2 bob, long nonce, int code, byte[] data) {
        // don't remove unless accepted or rejected by charlie
        OutboundEstablishState charlie;
        Long lnonce = Long.valueOf(nonce);
        if (code > 0 && code < 64)
            charlie = _liveIntroductions.get(lnonce);
        else
            charlie = _liveIntroductions.remove(lnonce);
        if (charlie == null) {
            if (_log.shouldDebug())
                _log.debug("Dup or unknown RelayResponse: " + nonce);
            return; // already established, or we were Bob and got a dup from Charlie
        }
        if (charlie.getVersion() != 2)
            return;
        OutboundEstablishState2 charlie2 = (OutboundEstablishState2) charlie;
        long token;
        if (code == 0) {
            token = DataHelper.fromLong8(data, data.length - 8);
            data = Arrays.copyOfRange(data, 0, data.length - 8);
        } else {
            token = 0;
        }
        Hash bobHash = bob.getRemotePeer();
        Hash charlieHash = charlie.getRemoteIdentity().getHash();
        RouterInfo bobRI = _context.netDb().lookupRouterInfoLocally(bobHash);
        RouterInfo charlieRI = _context.netDb().lookupRouterInfoLocally(charlieHash);
        Hash signer;
        OutboundEstablishState2.IntroState istate;
        if (code > 0 && code < 64) {
            signer = bobHash;
            istate = INTRO_STATE_BOB_REJECT;
        } else {
            signer = charlieHash;
            if (code == 0)
                istate = INTRO_STATE_SUCCESS;
            else
                istate = INTRO_STATE_CHARLIE_REJECT;
        }
        RouterInfo signerRI = _context.netDb().lookupRouterInfoLocally(signer);
        if (signerRI != null) {
            // validate signed data
            SigningPublicKey spk = signerRI.getIdentity().getSigningPublicKey();
            if (!SSU2Util.validateSig(_context, SSU2Util.RELAY_RESPONSE_PROLOGUE,
                                     bobHash, null, data, spk)) {
                if (_log.shouldWarn())
                    _log.warn("Signature failed relay response " + code + " as alice from:\n" + signerRI);
                istate = INTRO_STATE_FAILED;
                charlie2.setIntroState(bobHash, istate);
                charlie.fail();
                return;
            }
        } else {
            if (_log.shouldWarn())
                _log.warn("Signer RI not found " + signer);
            return;
        }
        if (code == 0) {
            int iplen = data[9] & 0xff;
            if (iplen != 6 && iplen != 18) {
                if (_log.shouldWarn())
                    _log.warn("Bad IP length " + iplen + " from " + charlie);
                istate = INTRO_STATE_FAILED;
                charlie2.setIntroState(bobHash, istate);
                charlie.fail();
                return;
            }
            int port = (int) DataHelper.fromLong(data, 10, 2);
            byte[] ip = new byte[iplen - 2];
            System.arraycopy(data, 12, ip, 0, iplen - 2);
            // validate
            if (!TransportUtil.isValidPort(port) ||
                !_transport.isValid(ip) ||
                _transport.isTooClose(ip) ||
                DataHelper.eq(ip, bob.getRemoteIP()) ||
                _context.blocklist().isBlocklisted(ip)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Bad relay resp from " + charlie + " for " + Addresses.toString(ip, port));
                istate = INTRO_STATE_FAILED;
                charlie2.setIntroState(bobHash, istate);
                _context.statManager().addRateData("udp.relayBadIP", 1);
                _context.banlist().banlistRouter(charlieHash, "Bad introduction data", null, null, _context.clock().now() + 6*60*60*1000);
                charlie.fail();
                return;
            }
            if (_log.shouldDebug())
                _log.debug("Received RelayResponse from " + charlie + " - they are on " +
                           Addresses.toString(ip, port));
            if (charlieRI == null) {
                if (_log.shouldWarn())
                    _log.warn("Charlie RI not found " + charlie);
                charlie2.setIntroState(bobHash, istate);
                // maybe it will show up later
                return;
            }
            synchronized (charlie) {
                RemoteHostId oldId = charlie.getRemoteHostId();
                if (oldId.getIP() == null) {
                    // relay response before hole punch
                    ((OutboundEstablishState2) charlie).introduced(ip, port, token);
                    RemoteHostId newId = charlie.getRemoteHostId();
                    addOutboundToken(newId, token, _context.clock().now() + 10*1000);
                    // Swap out the RemoteHostId the state is indexed under.
                    // It was a Hash, change it to a IP/port.
                    // Remove the entry in the byClaimedAddress map as it's now in main map.
                    // Add an entry in the byHash map so additional OB pkts can find it.
                    _outboundByHash.put(charlieHash, charlie);
                    RemoteHostId claimed = charlie.getClaimedAddress();
                    if (!oldId.equals(newId)) {
                        _outboundStates.remove(oldId);
                        _outboundStates.put(newId, charlie);
                        if (_log.shouldLog(Log.INFO))
                            _log.info("RR replaced " + oldId + " with " + newId + ", claimed address was " + claimed);
                    }
                    //
                    if (claimed != null)
                        _outboundByClaimedAddress.remove(oldId, charlie);  // only if == state
                } else {
                    // TODO validate same IP/port as in hole punch?
                }
            }
            charlie2.setIntroState(bobHash, istate);
            notifyActivity();
        } else if (code >= 64) {
            // that's it
            if (_log.shouldDebug())
                _log.debug("Received RelayResponse rejection " + code + " from charlie " + charlie);
            charlie2.setIntroState(bobHash, istate);
            if (code == RELAY_REJECT_CHARLIE_BANNED)
                _context.banlist().banlistRouter(charlieHash, "They banned us", null, null, _context.clock().now() + 6*60*60*1000);
            charlie.fail();
            _liveIntroductions.remove(lnonce);
        } else {
            // don't give up, maybe more bobs out there
            // TODO keep track
            if (_log.shouldDebug())
                _log.debug("Received RelayResponse rejection " + code + " from bob " + bob);
            charlie2.setIntroState(bobHash, istate);
            notifyActivity();
        }
    }

    /**
     *  Called from PacketHandler.
     *  Accelerate response to RelayResponse if we haven't sent it yet.
     *
     *  SSU 2 only.
     *
     *  @param id non-null
     *  @param packet header already decrypted
     *  @since 0.9.55
     */
    void receiveHolePunch(RemoteHostId id, UDPPacket packet) {
        DatagramPacket pkt = packet.getPacket();
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        long rcvConnID = DataHelper.fromLong8(data, off);
        long sendConnID = DataHelper.fromLong8(data, off + SRC_CONN_ID_OFFSET);
        int type = data[off + TYPE_OFFSET] & 0xff;
        if (type != HOLE_PUNCH_FLAG_BYTE)
            return;
        byte[] introKey = _transport.getSSU2StaticIntroKey();
        ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
        chacha.initializeKey(introKey, 0);
        long n = DataHelper.fromLong(data, off + PKT_NUM_OFFSET, 4);
        chacha.setNonce(n);
        HPCallback cb = new HPCallback(id);
        long now = _context.clock().now();
        long nonce;
        try {
            // decrypt in-place
            chacha.decryptWithAd(data, off, LONG_HEADER_SIZE,
                                 data, off + LONG_HEADER_SIZE, data, off + LONG_HEADER_SIZE, len - LONG_HEADER_SIZE);
            int payloadLen = len - (LONG_HEADER_SIZE + MAC_LEN);
            SSU2Payload.processPayload(_context, cb, data, off + LONG_HEADER_SIZE, payloadLen, false, null);
            if (cb._respCode != 0) {
                if (_log.shouldWarn())
                    _log.warn("Bad HolePunch response: " + cb._respCode);
                return;
            }
            long skew = cb._timeReceived - now;
            if (skew > MAX_SKEW || skew < 0 - MAX_SKEW) {
                if (_log.shouldWarn())
                    _log.warn("Too skewed in hole punch from " + id);
                return;
            }
            nonce = DataHelper.fromLong(cb._respData, 0, 4);
            if (nonce != (rcvConnID & 0xFFFFFFFFL) ||
                nonce != ((rcvConnID >> 32) & 0xFFFFFFFFL)) {
                if (_log.shouldWarn())
                    _log.warn("Bad nonce in hole punch from " + id);
                return;
            }
            long time = DataHelper.fromLong(cb._respData, 4, 4) * 1000;
            skew = time - now;
            if (skew > MAX_SKEW || skew < 0 - MAX_SKEW) {
                if (_log.shouldWarn())
                    _log.warn("Too skewed in hole punch from " + id);
                return;
            }
            int ver = cb._respData[8] & 0xff;
            if (ver != 2) {
                if (_log.shouldWarn())
                    _log.warn("Bad hole punch version " + ver + " from " + id);
                return;
            }
            // check signature below
        } catch (Exception e) {
            if (_log.shouldWarn())
                _log.warn("Bad HolePunch packet:\n" + HexDump.dump(data, off, len), e);
            return;
        } finally {
            chacha.destroy();
        }

        // TODO now we can look up by nonce first instead if we want
        OutboundEstablishState state = _outboundStates.get(id);
        if (state != null) {
            if (_log.shouldInfo())
                _log.info("Hole punch after RelayResponse from " + state);
        } else {
            // This is the usual case, we received the HolePunch (1 1/2 RTT)
            // before the RelayResponse (2 RTT), lookup by nonce.
            state = _liveIntroductions.remove(Long.valueOf(nonce));
            if (state != null) {
                if (_log.shouldInfo())
                    _log.info("Hole punch before RelayResponse from " + state);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("No state found for SSU2 hole punch from " + id);
                return;
            }
        }
        if (state.getVersion() != 2)
            return;
        OutboundEstablishState2 state2 = (OutboundEstablishState2) state;
        Hash charlieHash = state.getRemoteIdentity().getHash();
        RouterInfo charlieRI = _context.netDb().lookupRouterInfoLocally(charlieHash);
        if (charlieRI != null) {
            // validate signed data, but we don't necessarily know which Bob
            SigningPublicKey spk = charlieRI.getIdentity().getSigningPublicKey();
            UDPAddress addr = state.getRemoteAddress();
            int count = addr.getIntroducerCount();
            data = Arrays.copyOfRange(cb._respData, 0, cb._respData.length - 8);
            boolean ok = false;
            loop:
            for (int i = 0; i < count; i++) {
                Hash h = addr.getIntroducerHash(i);
                if (h != null) {
                    OutboundEstablishState2.IntroState istate = state2.getIntroState(h);
                    switch (istate) {
                        // probably not signed by this introducer
                        case INTRO_STATE_INIT:
                        case INTRO_STATE_EXPIRED:
                        case INTRO_STATE_REJECTED:
                        case INTRO_STATE_CONNECT_FAILED:
                        case INTRO_STATE_BOB_REJECT:
                        case INTRO_STATE_CHARLIE_REJECT:
                        case INTRO_STATE_FAILED:
                        case INTRO_STATE_INVALID:
                        case INTRO_STATE_DISCONNECTED:
                             continue;

                        // maybe or definitely signed by this introducer
                        case INTRO_STATE_LOOKUP_SENT:
                        case INTRO_STATE_HAS_RI:
                        case INTRO_STATE_CONNECTING:
                        case INTRO_STATE_CONNECTED:
                        case INTRO_STATE_RELAY_REQUEST_SENT:
                        case INTRO_STATE_RELAY_CHARLIE_ACCEPTED:
                        case INTRO_STATE_LOOKUP_FAILED:
                        case INTRO_STATE_RELAY_RESPONSE_TIMEOUT:
                        case INTRO_STATE_SUCCESS:
                        default:
                            if (SSU2Util.validateSig(_context, SSU2Util.RELAY_RESPONSE_PROLOGUE,
                                                     h, null, data, spk)) {
                                if (_log.shouldInfo())
                                    _log.info("Good sig hole punch, credit " + h.toBase64() + " on " + state);
                                state2.setIntroState(h, INTRO_STATE_SUCCESS);
                                ok = true;
                                break loop;
                            }
                            break;
                    }
                }
            }
            if (!ok) {
                if (_log.shouldWarn())
                    _log.warn("Signature failed hole punch on " + state);
                return;
            }

            int iplen = data[9] & 0xff;
            if (iplen != 6 && iplen != 18) {
                if (_log.shouldWarn())
                    _log.warn("Bad IP length " + iplen + " from " + state);
                _context.statManager().addRateData("udp.relayBadIP", 1);
                _context.banlist().banlistRouter(state.getRemoteIdentity().getHash(), "Bad introduction data", null, null, _context.clock().now() + 6*60*60*1000);
                state.fail();
                return;
            }
            int port = (int) DataHelper.fromLong(data, 10, 2);
            byte[] ip = new byte[iplen - 2];
            System.arraycopy(data, 12, ip, 0, iplen - 2);
            // validate
            if (!TransportUtil.isValidPort(port) ||
                !_transport.isValid(ip) ||
                _transport.isTooClose(ip) ||
                !DataHelper.eq(ip, id.getIP()) ||  // IP mismatch
                _context.blocklist().isBlocklisted(ip)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Bad hole punch from " + state + " for " + Addresses.toString(ip, port) + " via " + id);
                _context.statManager().addRateData("udp.relayBadIP", 1);
                _context.banlist().banlistRouter(state.getRemoteIdentity().getHash(), "Bad introduction data", null, null, _context.clock().now() + 6*60*60*1000);
                state.fail();
                return;
            }
            int fromPort = id.getPort();
            if (port != fromPort) {
                // if port mismatch only, use the source port as charlie doesn't know
                // his port or is behind a symmetric NAT
                if (_log.shouldWarn())
                    _log.warn("Hole punch source mismatch on " + state +
                              " resp. block: " + Addresses.toString(ip, port) +
                              " rcvd. from: " + id);
                if (!TransportUtil.isValidPort(fromPort)) {
                    _context.statManager().addRateData("udp.relayBadIP", 1);
                    _context.banlist().banlistRouter(state.getRemoteIdentity().getHash(), "Bad introduction data", null, null, _context.clock().now() + 6*60*60*1000);
                    state.fail();
                    return;
                }
                port = fromPort;
            } else {
                if (_log.shouldDebug())
                    _log.debug("Received hole punch from " + state + " - they are on " +
                               Addresses.toString(ip, port));
            }
            synchronized (state) {
                RemoteHostId oldId = state.getRemoteHostId();
                if (oldId.getIP() == null) {
                    // hole punch before relay response
                    long token = DataHelper.fromLong8(cb._respData, cb._respData.length - 8);
                    state2.introduced(ip, port, token);
                    RemoteHostId newId = state.getRemoteHostId();
                    addOutboundToken(newId, token, now + 10*1000);
                    // Swap out the RemoteHostId the state is indexed under.
                    // It was a Hash, change it to a IP/port.
                    // Remove the entry in the byClaimedAddress map as it's now in main map.
                    // Add an entry in the byHash map so additional OB pkts can find it.
                    _outboundByHash.put(charlieHash, state);
                    RemoteHostId claimed = state.getClaimedAddress();
                    if (!oldId.equals(newId)) {
                        _outboundStates.remove(oldId);
                        _outboundStates.put(newId, state);
                        if (_log.shouldLog(Log.INFO))
                            _log.info("HP replaced " + oldId + " with " + newId + ", claimed address was " + claimed);
                    }
                    //
                    if (claimed != null)
                        _outboundByClaimedAddress.remove(oldId, state);  // only if == state
                } else {
                    // TODO validate same IP/port as in response?
                }
            }
            boolean sendNow = state.receiveHolePunch();
            if (sendNow) {
                if (_log.shouldInfo())
                    _log.info("Send SessionRequest after HolePunch from " + state);
                notifyActivity();
            }
        } else {
            if (_log.shouldWarn())
                _log.warn("Charlie RI not found " + state);
            return;
        }
    }

    /**
     *  Are IP and port valid? This is only for checking the relay response.
     *  Allow IPv6 as of 0.9.50.
     *  Refuse anybody in the same /16
     *  @since 0.9.3, pkg private since 0.9.45 for PacketBuider
     */
    boolean isValid(byte[] ip, int port) {
        return TransportUtil.isValidPort(port) &&
               ip != null &&
               _transport.isValid(ip) &&
               (!_transport.isTooClose(ip)) &&
               (!_context.blocklist().isBlocklisted(ip));
    }

    /**
     *  SSU 2.
     *  For SSU 2, it contains a full router info, so it may be fragmented.
     *
     *  Caller must synch on state.
     */
    private void sendConfirmation(OutboundEstablishState state) {
        boolean valid = state.validateSessionCreated();
        if (!valid) {
            // validate clears fields on failure
            if (_log.shouldLog(Log.WARN))
                _log.warn("SessionCreated validate failed: " + state);
            return;
        }

        byte[] ip = state.getReceivedIP();
        int port = state.getReceivedPort();
        // don't need to revalidate the remoteHostID IP, we did that before we started
        // not isValidPort() because we could be snatted
        if (!_transport.isValid(ip) || port < 1024) {
            state.fail();
            return;
        }

        // gives us the opportunity to "detect" our external addr
        _transport.externalAddressReceived(state.getRemoteIdentity().calculateHash(), ip, port);

        OutboundEstablishState2 state2 = (OutboundEstablishState2) state;
        OutboundEstablishState.OutboundState ostate = state2.getState();
        // shouldn't happen, we go straight to confirmed after sending
        if (ostate == OB_STATE_CONFIRMED_COMPLETELY)
            return;
        UDPPacket[] packets = _builder2.buildSessionConfirmedPackets(state2, _context.router().getRouterInfo());
        if (packets == null) {
            state.fail();
            return;
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send confirm to: " + state);
        
        for (int i = 0; i < packets.length; i++) {
            _transport.send(packets[i]);
        }
        
        // save for retx
        // PacketBuilder2 told the state
        //state2.confirmedPacketsSent(packets);
        // we are done, go right to ps2
        handleCompletelyEstablished(state2);
    }

    /**
     * Drive through the inbound establishment states, adjusting one of them
     * as necessary. Called from Establisher thread only.
     * @return next requested time or Long.MAX_VALUE
     */
    private long handleInbound() {
        long now = _context.clock().now();
        long nextSendTime = Long.MAX_VALUE;
        InboundEstablishState inboundState = null;
        boolean expired = false;

            for (Iterator<InboundEstablishState> iter = _inboundStates.values().iterator(); iter.hasNext(); ) {
                InboundEstablishState cur = iter.next();
                InboundEstablishState.InboundState istate = cur.getState();
                if (istate == IB_STATE_CONFIRMED_COMPLETELY) {
                    // completely received (though the signature may be invalid)
                    iter.remove();
                    inboundState = cur;
                    //if (_log.shouldLog(Log.DEBUG))
                    //    _log.debug("Removing completely confirmed inbound state");
                    break;
                } else if (cur.getLifetime(now) > MAX_IB_ESTABLISH_TIME ||
                           (istate == IB_STATE_RETRY_SENT &&           // limit time to get sess. req after retry
                            cur.getLifetime(now) >= 5 * InboundEstablishState.RETRANSMIT_DELAY)) {
                    // took too long
                    iter.remove();
                    inboundState = cur;
                    //_context.statManager().addRateData("udp.inboundEstablishFailedState", cur.getState(), cur.getLifetime());
                    if (_log.shouldDebug())
                        _log.debug("Expired: " + cur);
                    expired = true;
                    break;
                } else if (istate == IB_STATE_FAILED || istate == IB_STATE_COMPLETE) {
                    iter.remove();
                } else {
                    // this will always be > 0
                    long next = cur.getNextSendTime();
                    if (next <= now) {
                        // our turn...
                        inboundState = cur;
                        // if (_log.shouldLog(Log.DEBUG))
                        //     _log.debug("Processing inbound that wanted activity");
                        break;
                    } else {
                        // nothin to do but wait for them to send us
                        // stuff, so lets move on to the next one being
                        // established
                        if (next < nextSendTime)
                            nextSendTime = next;
                    }
                }
            }

        if (inboundState != null) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Processing for inbound: " + inboundState);
            synchronized (inboundState) {
                InboundEstablishState.InboundState istate = inboundState.getState();
                switch (istate) {
                  case IB_STATE_REQUEST_RECEIVED:
                  case IB_STATE_TOKEN_REQUEST_RECEIVED:      // SSU2
                  case IB_STATE_REQUEST_BAD_TOKEN_RECEIVED:  // SSU2
                    if (expired)
                        processExpired(inboundState);
                    else
                        sendCreated(inboundState);
                    break;

                  // SSU2 only in practice, should only get here if expired
                  case IB_STATE_CONFIRMED_PARTIALLY:
                    if (expired)
                        processExpired(inboundState);
                    break;

                  case IB_STATE_CREATED_SENT: // fallthrough
                  case IB_STATE_RETRY_SENT:                  // SSU2
                    if (expired) {
                        processExpired(inboundState);
                    } else if (inboundState.getNextSendTime() <= now) {
                        // resend created or retry
                        sendCreated(inboundState);
                    }
                    break;

                  case IB_STATE_CONFIRMED_COMPLETELY:
                    RouterIdentity remote = inboundState.getConfirmedIdentity();
                    if (remote != null) {
                        if (_context.banlist().isBanlistedForever(remote.calculateHash())) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Dropping inbound connection from permanently banlisted peer: " + remote.calculateHash());
                            // So next time we will not accept the con, rather than doing the whole handshake
                            _context.blocklist().add(inboundState.getSentIP());
                            inboundState.fail();
                            processExpired(inboundState);
                        } else {
                            handleCompletelyEstablished(inboundState);
                        }
                    } else {
                        // really shouldn't be this state
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("confirmed with invalid? " + inboundState);
                        inboundState.fail();
                        processExpired(inboundState);
                    }
                    break;

                  case IB_STATE_COMPLETE:  // fall through
                  case IB_STATE_FAILED: // leak here if fail() was called in IES???
                    break; // already removed;

                  case IB_STATE_UNKNOWN:
                    // Can't happen, always call receiveSessionRequest() before putting in map
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("hrm, state is unknown for " + inboundState);
                    break;

                  default:
                    if (_log.shouldWarn())
                        _log.warn("Unhandled state on " + inboundState);
                    break;
                }
            }

            // ok, since there was something to do, we want to loop again
            nextSendTime = now;
        }
        
        return nextSendTime;
    }
    
    
    /**
     * Drive through the outbound establishment states, adjusting one of them
     * as necessary. Called from Establisher thread only.
     * @return next requested time or Long.MAX_VALUE
     */
    private long handleOutbound() {
        long now = _context.clock().now();
        long nextSendTime = Long.MAX_VALUE;
        OutboundEstablishState outboundState = null;
        //int admitted = 0;
        //int remaining = 0;
        //int active = 0;

            for (Iterator<OutboundEstablishState> iter = _outboundStates.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState cur = iter.next();
                OutboundEstablishState.OutboundState state = cur.getState();
                if (state == OB_STATE_CONFIRMED_COMPLETELY ||
                    state == OB_STATE_VALIDATION_FAILED) {
                    iter.remove();
                    outboundState = cur;
                    break;
                } else if (cur.getLifetime(now) >= MAX_OB_ESTABLISH_TIME) {
                    // took too long
                    iter.remove();
                    outboundState = cur;
                    //_context.statManager().addRateData("udp.outboundEstablishFailedState", cur.getState(), cur.getLifetime());
                    //if (_log.shouldLog(Log.DEBUG))
                    //    _log.debug("Removing expired outbound: " + cur);
                    break;
                } else {
                    // this will be 0 for a new OES that needs sending, > 0 for others
                    long next = cur.getNextSendTime();
                    if (next <= now) {
                        // our turn...
                        outboundState = cur;
                        // if (_log.shouldLog(Log.DEBUG))
                        //     _log.debug("Outbound wants activity: " + cur);
                        break;
                    } else {
                        // nothin to do but wait for them to send us
                        // stuff, so lets move on to the next one being
                        // established
                        if (next < nextSendTime)
                            nextSendTime = next;
                        // if (_log.shouldLog(Log.DEBUG))
                        //     _log.debug("Outbound doesn't want activity: " + cur + " (next=" + (when-now) + ")");
                    }
                }
            }
            
            //admitted = locked_admitQueued();    
            //remaining = _queuedOutbound.size();

        //if (admitted > 0)
        //    _log.log(Log.CRIT, "Admitted " + admitted + " in push with " + remaining + " remaining queued and " + active + " active");
        
        if (outboundState != null) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Processing for outbound: " + outboundState);
            synchronized (outboundState) {
                boolean expired = outboundState.getLifetime(now) >= MAX_OB_ESTABLISH_TIME;
                switch (outboundState.getState()) {
                    case OB_STATE_UNKNOWN:  // fall thru
                    case OB_STATE_INTRODUCED:
                    case OB_STATE_NEEDS_TOKEN:             // SSU2 only
                        if (expired)
                            processExpired(outboundState);
                        else
                            sendRequest(outboundState);
                        break;

                    case OB_STATE_REQUEST_SENT:
                    case OB_STATE_TOKEN_REQUEST_SENT:      // SSU2 only
                    case OB_STATE_RETRY_RECEIVED:          // SSU2 only
                    case OB_STATE_REQUEST_SENT_NEW_TOKEN:  // SSU2 only
                        // no response yet (or it was invalid), lets retry
                        long rtime = outboundState.getRequestSentTime();
                        if (expired || (rtime > 0 && rtime + OB_MESSAGE_TIMEOUT <= now))
                            processExpired(outboundState);
                        else if (outboundState.getNextSendTime() <= now)
                            sendRequest(outboundState);
                        break;

                    case OB_STATE_CREATED_RECEIVED:
                        if (expired)
                            processExpired(outboundState);
                        else if (outboundState.getNextSendTime() <= now)
                            sendConfirmation(outboundState);
                        break;

                    case OB_STATE_CONFIRMED_PARTIALLY:
                        long ctime = outboundState.getConfirmedSentTime();
                        if (expired || (ctime > 0 && ctime + OB_MESSAGE_TIMEOUT <= now)) {
                            processExpired(outboundState);
                        } else if (outboundState.getNextSendTime() <= now) {
                            sendConfirmation(outboundState);
                        }
                        break;

                    case OB_STATE_CONFIRMED_COMPLETELY:
                        if (expired)
                            processExpired(outboundState);
                        else
                            handleCompletelyEstablished(outboundState);
                        break;

                    case OB_STATE_PENDING_INTRO:
                        long itime = outboundState.getIntroSentTime();
                        if (expired || (itime > 0 && itime + OB_MESSAGE_TIMEOUT <= now))
                            processExpired(outboundState);
                        else if (outboundState.getNextSendTime() <= now)
                            handlePendingIntro(outboundState);
                        break;

                    case OB_STATE_VALIDATION_FAILED:
                        processExpired(outboundState);
                        break;

                    default:
                        if (_log.shouldWarn())
                            _log.warn("Unhandled state on " + outboundState);
                        break;
                }
            }
            
            // ok, since there was something to do, we want to loop again
            nextSendTime = now;
        }
        
        return nextSendTime;
    }
    
    /**
     *  Caller should probably synch on outboundState
     */
    private void processExpired(OutboundEstablishState outboundState) {
        long nonce = outboundState.getIntroNonce();
        if (nonce >= 0) {
            // remove only if value == state
            boolean removed = _liveIntroductions.remove(Long.valueOf(nonce), outboundState);
            if (removed) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Relay request for " + outboundState + " timed out");
                _context.statManager().addRateData("udp.sendIntroRelayTimeout", 1);
            }
        }
        // only if == state
        RemoteHostId claimed = outboundState.getClaimedAddress();
        if (claimed != null)
            _outboundByClaimedAddress.remove(claimed, outboundState);
        _outboundByHash.remove(outboundState.getRemoteIdentity().calculateHash(), outboundState);
        // should have already been removed in handleOutbound() above
        // remove only if value == state
        _outboundStates.remove(outboundState.getRemoteHostId(), outboundState);
        if (outboundState.getState() != OB_STATE_CONFIRMED_COMPLETELY) {
            if (_log.shouldDebug())
                _log.debug("Expired: " + outboundState);
            OutNetMessage msg;
            while ((msg = outboundState.getNextQueuedMessage()) != null) {
                _transport.failed(msg, "Expired during failed establish");
            }
            String err = "Took too long to establish OB connection, state = " + outboundState.getState();
            Hash peer = outboundState.getRemoteIdentity().calculateHash();
            //_context.banlist().banlistRouter(peer, err, UDPTransport.STYLE);
            _transport.markUnreachable(peer);
            _transport.dropPeer(peer, false, err);
            //_context.profileManager().commErrorOccurred(peer);
            outboundState.fail();
        } else {
            OutNetMessage msg;
            while ((msg = outboundState.getNextQueuedMessage()) != null) {
                _transport.send(msg);
            }
        }
    }

    
    /**
     *  Caller should probably synch on inboundState
     *  @since 0.9.2
     */
    private void processExpired(InboundEstablishState inboundState) {
        if (_log.shouldWarn())
            _log.warn("Expired: " + inboundState);
        RemoteHostId id = inboundState.getRemoteHostId();
        _inboundStates.remove(id);
        Long exp = Long.valueOf(_context.clock().now() + IB_BAN_TIME);
        synchronized (_inboundBans) {
            _inboundBans.put(id, exp);
        }
        OutNetMessage msg;
        while ((msg = inboundState.getNextQueuedMessage()) != null) {
            _transport.failed(msg, "Expired during failed establish");
        }
    }

    //// SSU 2 ////

    /**
     *  Remember a token that can be used later to connect to the peer
     *
     *  @param token nonzero
     *  @param expires absolute time
     *  @since 0.9.54
     */
    public void addOutboundToken(RemoteHostId peer, long token, long expires) {
        long now = _context.clock().now();
        if (expires < now)
            return;
        if (expires > now + 2*60*1000) {
            // don't save if symmetric natted
            byte[] ip = peer.getIP();
            if (ip != null && ip.length == 4 && _transport.isSymNatted())
                return;
        }
        Token tok = new Token(token, expires, now);
        synchronized(_outboundTokens) {
            _outboundTokens.put(peer, tok);
        }
    }

    /**
     *  Get a token to connect to the peer
     *
     *  @return 0 if none available
     *  @since 0.9.54
     */
    public long getOutboundToken(RemoteHostId peer) {
        Token tok;
        synchronized(_outboundTokens) {
            tok = _outboundTokens.remove(peer);
        }
        if (tok == null)
            return 0;
        if (tok.getExpiration() < _context.clock().now())
            return 0;
        return tok.getToken();
    }

    /**
     *  Remove our tokens for this length
     *
     *  @since 0.9.54
     */
    public void ipChanged(boolean isIPv6) {
        if (_log.shouldWarn())
            _log.warn("IP changed, ipv6? " + isIPv6);
        int len = isIPv6 ? 16 : 4;
        // expire while we're at it
        long now = _context.clock().now();
        synchronized(_outboundTokens) {
            for (Iterator<Map.Entry<RemoteHostId, Token>> iter = _outboundTokens.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry<RemoteHostId, Token> e = iter.next();
                if (e.getKey().getIP().length == len || e.getValue().expires < now)
                    iter.remove();
            }
        }
        synchronized(_inboundTokens) {
            for (Iterator<Map.Entry<RemoteHostId, Token>> iter = _inboundTokens.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry<RemoteHostId, Token> e = iter.next();
                if (e.getKey().getIP().length == len || e.getValue().expires < now)
                    iter.remove();
            }
        }
    }

    /**
     *  Remove all tokens
     *
     *  @since 0.9.54
     */
    public void portChanged() {
        synchronized(_outboundTokens) {
            _outboundTokens.clear();
        }
        synchronized(_inboundTokens) {
            _inboundTokens.clear();
        }
    }

    /**
     *  Get a token that can be used later for the peer to connect to us
     *
     *  @since 0.9.54
     */
    public Token getInboundToken(RemoteHostId peer) {
        return getInboundToken(peer, IB_TOKEN_EXPIRATION);
    }

    /**
     *  Get a token that can be used later for the peer to connect to us.
     *
     *  @param expiration time from now, will be reduced if necessary based on cache eviction time.
     *  @return non-null
     *  @since 0.9.55
     */
    public Token getInboundToken(RemoteHostId peer, long expiration) {
        long token;
        do {
            token = _context.random().nextLong();
        } while (token == 0);
        long now = _context.clock().now();
        // shorten expiration based on average eviction time
        RateStat rs = _context.statManager().getRate("udp.inboundTokenLifetime");
        if (rs != null) {
            Rate r = rs.getRate(5*60*1000);
            if (r != null) {
                long lifetime = (long) (r.getAverageValue() * 0.9d); // margin
                if (lifetime > 0) {
                    if (lifetime < 2*60*1000)
                        lifetime = 2*60*1000;
                    if (lifetime < expiration)
                        expiration = lifetime;
                }
            }
        }
        long expires = now + expiration;
        Token tok = new Token(token, expires, now);
        synchronized(_inboundTokens) {
            Token old = _inboundTokens.put(peer, tok);
            if (old != null && old.getExpiration() > expires - 2*60*1000) {
                // reuse for the case where we're retransmitting terminations
                // and it expires no sooner than 2 minutes earlier
                if (_log.shouldDebug())
                    _log.debug("Resend inbound " + old + " for " + peer);
                // put it back
                _inboundTokens.put(peer, old);
                return old;
            }
        }
        if (_log.shouldDebug())
            _log.debug("Add inbound " + tok + " for " + peer);
        return tok;
    }

    /**
     *  Is the token from this peer valid?
     *
     *  @return valid
     *  @since 0.9.54
     */
    public boolean isInboundTokenValid(RemoteHostId peer, long token) {
        if (token == 0)
            return false;
        Token tok;
        synchronized(_inboundTokens) {
            tok = _inboundTokens.get(peer);
            if (tok == null)
                return false;
            if (tok.getToken() != token)
                return false;
            _inboundTokens.remove(peer);
        }
        boolean rv = tok.getExpiration() >= _context.clock().now();
        if (rv && _log.shouldDebug())
            _log.debug("Used inbound " + tok + " for " + peer);
        return rv;
    }

    public static class Token {
        private final long token;
        // save space until 2106
        private final int expires;
        private final int added;

        /**
         *  @param exp absolute time, not relative to now
         */
        public Token(long tok, long exp, long now) {
            token = tok;
            expires = (int) (exp >> 10);
            added = (int) (now >> 10);
        }
        /** @since 0.9.57 */
        public long getToken() { return token; }
        /** @since 0.9.57 */
        public long getExpiration() { return (expires & 0xFFFFFFFFL) << 10; }
        /** @since 0.9.57 */
        public long getWhenAdded() { return (added & 0xFFFFFFFFL) << 10; }
        /** @since 0.9.57 */
        public String toString() {
            return "Token " + token + " added " + DataHelper.formatTime(getWhenAdded()) + " expires " + DataHelper.formatTime(getExpiration());
        }
    }

    /**
     *  Not threaded, because we're holding the token cache locks anyway.
     *
     *  Format:
     *
     *<pre>
     *  4 ourIPv4addr ourIPv4port
     *  6 ourIPv6addr ourIPv6port
     *  I addr port token exp
     *  O addr port token exp
     *</pre>
     *
     *  @since 0.9.55
     */
    private void loadTokens() {
        File f = new File(_context.getConfigDir(), TOKEN_FILE);
        String ourV4Port = Integer.toString(_transport.getExternalPort(false));
        String ourV6Port = Integer.toString(_transport.getExternalPort(true));
        String ourV4Addr;
        RouterAddress addr = _transport.getCurrentExternalAddress(false);
        if (addr != null)
            ourV4Addr = addr.getHost();
        else
            ourV4Addr = null;
        String ourV6Addr;
        addr = _transport.getCurrentExternalAddress(true);
        if (addr != null)
            ourV6Addr = addr.getHost();
        else
            ourV6Addr = null;
        if (_log.shouldDebug())
            _log.debug("Loading SSU2 tokens for " + ourV4Addr + ' ' + ourV4Port + ' ' + ourV6Addr + ' ' + ourV6Port);
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(f));
            boolean v4Match = false;
            boolean v6Match = false;
            long now = _context.clock().now();
            int count = 0;
            synchronized(_inboundTokens) {
                synchronized(_outboundTokens) {
                    String line;
                    while ((line = DataHelper.readLine(in)) != null) {
                        if (line.startsWith("#"))
                            continue;
                        String[] s = DataHelper.split(line, " ", 5);
                        if (s.length < 3)
                            continue;
                        if (s[0].equals("4")) {
                            v4Match = s[1].equals(ourV4Addr) && s[2].trim().equals(ourV4Port);
                            //if (_log.shouldWarn())
                            //    _log.warn("V4 match? " + v4Match + ' ' + line);
                        } else if (s[0].equals("6")) {
                            v6Match = s[1].equals(ourV6Addr) && s[2].trim().equals(ourV6Port);
                            //if (_log.shouldWarn())
                            //    _log.warn("V6 match? " + v6Match + ' ' + line);
                        } else if ((s[0].equals("I") || s[0].equals("O"))  && s.length == 5) {
                            boolean isV6 = s[1].contains(":");
                            if ((isV6 && !v6Match) || (!isV6 && !v4Match))
                                continue;
                            try {
                                long exp = Long.parseLong(s[4].trim());
                                if (exp > now) {
                                    byte[] ip = Addresses.getIPOnly(s[1]);
                                    if (ip != null) {
                                        int port = Integer.parseInt(s[2]);
                                        long tok = Long.parseLong(s[3]);
                                        RemoteHostId id = new RemoteHostId(ip, port);
                                        Token token = new Token(tok, exp, now);
                                        if (s[0].equals("I"))
                                            _inboundTokens.put(id, token);
                                        else
                                            _outboundTokens.put(id, token);
                                        count++;
                                    }
                                }
                            } catch (NumberFormatException nfe) {}
                        }
                    }
                }
            }
            if (_log.shouldDebug())
                _log.debug("Loaded " + count + " SSU2 tokens");
        } catch (IOException ioe) {
            if (_log.shouldWarn())
                _log.warn("Failed to load SSU2 tokens", ioe);
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ioe) {}
                f.delete();
            }
        }
    }

    /**
     *  @since 0.9.55
     */
    private void saveTokens() {
        File f = new File(_context.getConfigDir(), TOKEN_FILE);
        PrintWriter out = null;
        try {
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(f), "ISO-8859-1")));
            out.println("# SSU2 tokens, format: IPv4/IPv6/In/Out addr port token expiration");
            RouterAddress addr = _transport.getCurrentExternalAddress(false);
            if (addr != null) {
                String us = addr.getHost();
                if (us != null)
                    out.println("4 " + us + ' ' + _transport.getExternalPort(false));
            }
            addr = _transport.getCurrentExternalAddress(true);
            if (addr != null) {
                String us = addr.getHost();
                if (us != null)
                    out.println("6 " + us + ' ' + _transport.getExternalPort(true));
            }
            long now = _context.clock().now();
            int count = 0;
            // Roughly speaking, the LHMCache will iterate oldest-first,
            // but to be sure,
            // sort them by expiration oldest-first so loadTokens() will
            // put them in the LHMCache in the right order.
            TokenComparator comp = new TokenComparator();
            List<Map.Entry<RemoteHostId, Token>> tmp;
            synchronized(_inboundTokens) {
                tmp = new ArrayList<Map.Entry<RemoteHostId, Token>>(_inboundTokens.entrySet());
            }
            Collections.sort(tmp, comp);
            for (Map.Entry<RemoteHostId, Token> e : tmp) {
                 Token token = e.getValue();
                 long exp = token.getExpiration();
                 if (exp <= now)
                     continue;
                 RemoteHostId id = e.getKey();
                 out.println("I " + Addresses.toString(id.getIP()) + ' ' + id.getPort() + ' ' + token.getToken() + ' ' + exp);
                 count++;
            }
            tmp.clear();
            synchronized(_outboundTokens) {
                tmp.addAll(_outboundTokens.entrySet());
            }
            Collections.sort(tmp, comp);
            for (Map.Entry<RemoteHostId, Token> e : tmp) {
                 Token token = e.getValue();
                 long exp = token.getExpiration();
                 if (exp <= now)
                     continue;
                 RemoteHostId id = e.getKey();
                 out.println("O " + Addresses.toString(id.getIP()) + ' ' + id.getPort() + ' ' + token.getToken() + ' ' + exp);
                 count++;
            }
            if (out.checkError())
                throw new IOException("Failed write to " + f);
            if (_log.shouldDebug())
                _log.debug("Stored " + count + " SSU2 tokens to " + f);
        } catch (IOException ioe) {
            if (_log.shouldWarn())
                _log.warn("Error writing the SSU2 tokens file", ioe);
        } finally {
            if (out != null) out.close();
        }

    }

    /**
     * Soonest expiration first
     * @since 0.9.57
     */
    private static class TokenComparator implements Comparator<Map.Entry<RemoteHostId, Token>> {
        public int compare(Map.Entry<RemoteHostId, Token> l, Map.Entry<RemoteHostId, Token> r) {
             long le = l.getValue().expires;
             long re = r.getValue().expires;
             if (le < re) return -1;
             if (le > re) return 1;
             return 0;
        }
    }

    /**
     * For inbound tokens only, to record eviction time in a stat,
     * for use in setting expiration times.
     *
     * @since 0.9.57
     */
    private class InboundTokens extends LHMCache<RemoteHostId, Token> {

        public InboundTokens(int max) {
            super(max);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<RemoteHostId, Token> eldest) {
            boolean rv = super.removeEldestEntry(eldest);
            if (rv) {
                long lifetime = _context.clock().now() - eldest.getValue().getWhenAdded();
                _context.statManager().addRateData("udp.inboundTokenLifetime", lifetime);
                if (_log.shouldDebug())
                    _log.debug("Remove oldest inbound " + eldest.getValue() + " for " + eldest.getKey());
            }
            return rv;
        }
    }

    /**
     *  Process SSU2 hole punch payload
     *
     *  @since 0.9.55
     */
    private static class HPCallback implements SSU2Payload.PayloadCallback {
        private final RemoteHostId _from;
        public long _timeReceived;
        public byte[] _aliceIP;
        public int _alicePort;
        public int _respCode = 999;
        public byte[] _respData;

        public HPCallback(RemoteHostId from) {
            _from = from;
        }

        public void gotDateTime(long time) {
            _timeReceived = time;
        }

        public void gotOptions(byte[] options, boolean isHandshake) {}

        public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotAddress(byte[] ip, int port) {
            _aliceIP = ip;
            _alicePort = port;
        }

        public void gotRelayTagRequest() {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotRelayTag(long tag) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotRelayRequest(byte[] data) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotRelayResponse(int status, byte[] data) {
            _respCode = status;
            _respData = data;
        }

        public void gotRelayIntro(Hash aliceHash, byte[] data) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotPeerTest(int msg, int status, Hash h, byte[] data) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotToken(long token, long expires) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotI2NP(I2NPMessage msg) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotFragment(byte[] data, int off, int len, long messageId,int frag, boolean isLast) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotACK(long ackThru, int acks, byte[] ranges) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotTermination(int reason, long count) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotPathChallenge(RemoteHostId from, byte[] data) {
            throw new IllegalStateException("Bad block in HP");
        }

        public void gotPathResponse(RemoteHostId from, byte[] data) {
            throw new IllegalStateException("Bad block in HP");
        }
    }


    //// End SSU 2 ////


    /**    
     * Driving thread, processing up to one step for an inbound peer and up to
     * one step for an outbound peer.  This is prodded whenever any peer's state
     * changes as well.
     *
     */    
    private class Establisher implements Runnable {
        public void run() {
            while (_alive) {
                try {
                    doPass();
                } catch (RuntimeException re) {
                    _log.error("Error in the establisher", re);
                    // don't loop too fast
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                }
            }
            _inboundStates.clear();
            _outboundStates.clear();
            _queuedOutbound.clear();
            _outboundByClaimedAddress.clear();
            _outboundByHash.clear();
        }

        private long _lastFailsafe;
        private static final long FAILSAFE_INTERVAL = 3*60*1000;
        // Debugging
        private long _lastPrinted;
        private static final long PRINT_INTERVAL = 5*1000;
    
        private void doPass() {
            long now = _context.clock().now();
            if (_log.shouldLog(Log.DEBUG) && _lastPrinted + PRINT_INTERVAL < now) {
                _lastPrinted = now;
                int iactive = _inboundStates.size();
                int oactive = _outboundStates.size();
                if (iactive > 0 || oactive > 0) {
                    int queued = _queuedOutbound.size();
                    int live = _liveIntroductions.size();
                    int claimed = _outboundByClaimedAddress.size();
                    int hash = _outboundByHash.size();
                    _log.debug("OB states: " + oactive + " IB states: " + iactive +
                               " OB queued: " + queued + " intros: " + live +
                               " OB claimed: " + claimed + " hash: " + hash);
                }
            } 
            _activity = 0;
            if (_lastFailsafe + FAILSAFE_INTERVAL < now) {
                _lastFailsafe = now;
                doFailsafe(now);
            }

            long nextSendTime = Math.min(handleInbound(), handleOutbound());
            long delay = nextSendTime - now;
            if (delay > 0) {
                if (delay > 1000)
                    delay = 1000;
                try {
                    synchronized (_activityLock) {
                        if (_activity > 0)
                            return;
                        _activityLock.wait(delay);
                    }
                } catch (InterruptedException ie) {
                }
                // if (_log.shouldLog(Log.DEBUG))
                //     _log.debug("After waiting w/ nextSend=" + nextSendTime 
                //                + " and delay=" + delay + " and interrupted=" + interrupted);
            }
        }

        /** @since 0.9.2 */
        private void doFailsafe(long now) {
            for (Iterator<OutboundEstablishState> iter = _liveIntroductions.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState state = iter.next();
                if (state.getLifetime(now) > 3*MAX_OB_ESTABLISH_TIME) {
                    iter.remove();
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Failsafe remove LI " + state);
                }
            }
            for (Iterator<OutboundEstablishState> iter = _outboundByClaimedAddress.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState state = iter.next();
                if (state.getLifetime(now) > 3*MAX_OB_ESTABLISH_TIME) {
                    iter.remove();
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Failsafe remove OBBCA " + state);
                }
            }
            for (Iterator<OutboundEstablishState> iter = _outboundByHash.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState state = iter.next();
                if (state.getLifetime(now) > 3*MAX_OB_ESTABLISH_TIME) {
                    iter.remove();
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Failsafe remove OBBH " + state);
                }
            }
            if (_inboundTokens != null) {
                // SSU2 only
                int count = 0;
                synchronized(_inboundTokens) {
                    for (Iterator<Token> iter = _inboundTokens.values().iterator(); iter.hasNext(); ) {
                        Token tok = iter.next();
                        if (tok.getExpiration() < now) {
                            iter.remove();
                            count++;
                        }
                    }
                }
                if (count > 0 && _log.shouldDebug())
                    _log.debug("Expired " + count + " inbound tokens");
                count = 0;
                synchronized(_outboundTokens) {
                    for (Iterator<Token> iter = _outboundTokens.values().iterator(); iter.hasNext(); ) {
                        Token tok = iter.next();
                        if (tok.getExpiration() < now) {
                            iter.remove();
                            count++;
                        }
                    }
                }
                if (count > 0 && _log.shouldDebug())
                    _log.debug("Expired " + count + " outbound tokens");
                _terminationCounter.clear();
                _transport.getIntroManager().cleanup();
            }
        }
    }
}
