package net.i2p.router;

import java.io.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.util.*;
import net.i2p.data.*;
import net.i2p.data.i2np.*;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.*;
import net.i2p.router.tunnel.*;
import net.i2p.router.tunnel.pool.*;
import net.i2p.router.transport.udp.UDPTransport;

/**
 * Coordinate some tests of peers to see how much load they can handle.  If 
 * TEST_LIVE_TUNNELS is set to false, it builds load test tunnels across various
 * peers in ways that are not anonymity sensitive (but may help with testing the net).
 * If it is set to true, however, it runs a few tests at a time for actual tunnels that
 * are built, to help determine whether our peer selection is insufficient.
 * 
 * Load tests of fake tunnels are conducted by building a single one hop inbound
 * tunnel with the peer in question acting as the inbound gateway.  We then send
 * messages directly to that gateway, which they batch up and send "down the
 * tunnel" (aka directly to us), at which point we then send another message,
 * and so on, until the tunnel expires.  Along the way, we record a few vital
 * stats to the "loadtest.log" file.  If we don't receive a message, we send another
 * after 10 seconds.
 *
 * If "router.loadTestSmall=true", we transmit a tiny DeliveryStatusMessage (~96 bytes
 * at the SSU level), which is sent back to us as a single TunnelDataMessage (~1KB).
 * Otherwise, we transmit a 4KB DataMessage wrapped inside a garlic message, which is 
 * sent back to us as five (1KB) TunnelDataMessages.  This size is chosen because the
 * streaming lib uses 4KB messages by default.
 *
 * Load tests of live tunnels pick a random tunnel from the tested pool's pair (e.g. if
 * we are testing an outbound tunnel for a particular destination, it picks an inbound 
 * tunnel from that destination's inbound pool), with each message going down that one
 * randomly paired tunnel for the duration of the load test (varying the paired tunnel
 * with each message had poor results)
 *
 */
public class LoadTestManager {
    private RouterContext _context;
    private Log _log;
    private Writer _out;
    private List _untestedPeers;
    private List _active;
    
    private static final String PROP_LOG_DATA = "router.loadTestLog";
    private static final String DEFAULT_LOG_DATA = "false";
    
    public LoadTestManager(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(LoadTestManager.class);
        _active = Collections.synchronizedList(new ArrayList());
        if (Boolean.valueOf(ctx.getProperty(PROP_LOG_DATA, DEFAULT_LOG_DATA)).booleanValue()) {
            try {
                _out = new BufferedWriter(new FileWriter("loadtest.log", true));
                _out.write("startup at " + ctx.clock().now() + "\n");
            } catch (IOException ioe) {
                _log.log(Log.CRIT, "error creating log", ioe);
            }
        }
        _context.statManager().createRateStat("test.lifetimeSuccessful", "How many messages we can pump through a load test during a tunnel's lifetime", "test", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("test.lifetimeFailed", "How many messages we fail to pump through (period == successful)", "test", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("test.timeoutAfter", "How many messages have we successfully pumped through a tunnel when one particular message times out", "test", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("test.rtt", "How long it takes to get a reply", "test", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("test.rttHigh", "How long it takes to get a reply, if it is a slow rtt", "test", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
    }
    
    public static final boolean TEST_LIVE_TUNNELS = true;
    
    /** 1 peer at a time */
    private static final int CONCURRENT_PEERS = 1;
    /** 4 messages per peer at a time */
    private static final int CONCURRENT_MESSAGES = 1;//4;
    
    private static final boolean DEFAULT_ENABLE = false;

    public static boolean isEnabled(I2PAppContext ctx) { 
        String enable = ctx.getProperty("router.enableLoadTesting");
        if ( (DEFAULT_ENABLE) && (enable != null) && (!Boolean.valueOf(enable).booleanValue()) )
            return false;
        else if ( (!DEFAULT_ENABLE) && ((enable == null) || (!Boolean.valueOf(enable).booleanValue()) ) )
            return false;
        return true;
    }
    public static void setEnableLoadTesting(RouterContext ctx, boolean enable) {
        if (enable)
            ctx.router().setConfigSetting("router.enableLoadTesting", "true");
        else
            ctx.router().setConfigSetting("router.enableLoadTesting", "false");
    }
    
    private int getConcurrency() {
        if (!isEnabled(_context)) return 0;
        
        int rv = CONCURRENT_PEERS;
        try {
            rv = Integer.parseInt(_context.getProperty("router.loadTestConcurrency", CONCURRENT_PEERS+""));
        } catch (NumberFormatException nfe) {
            rv = CONCURRENT_PEERS;
        }
        if (rv < 0) 
            rv = 0;
        if (rv > 50)
            rv = 50;
        return rv;
    }
    
    private int getPeerMessages() {
        String msgsPerPeer = _context.getProperty("router.loadTestMessagesPerPeer");
        int rv = CONCURRENT_MESSAGES;
        if (msgsPerPeer != null) {
            try { 
                rv = Integer.parseInt(msgsPerPeer); 
            } catch (NumberFormatException nfe) {
                rv = CONCURRENT_MESSAGES;
            }
        }
        if (rv < 1) 
            rv = 1;
        if (rv > 50)
            rv = 50;
        return rv;
    }
    
    /**
     * Actually send the messages through the given tunnel
     */
    private void runTest(LoadTestTunnelConfig tunnel) {
        log(tunnel, "start");
        int peerMessages = getPeerMessages();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Run test on " + tunnel + " with " + peerMessages + " messages");
        for (int i = 0; i < peerMessages; i++)
            sendTestMessage(tunnel);
    }
    
    private void pickTunnels(LoadTestTunnelConfig tunnel) {
        TunnelInfo inbound = null;
        TunnelInfo outbound = null;
        if (tunnel.getTunnel().isInbound()) {
            inbound = _context.tunnelManager().getTunnelInfo(tunnel.getReceiveTunnelId(0));
            if ( (inbound == null) && (_log.shouldLog(Log.WARN)) )
                _log.warn("where are we?  inbound tunnel isn't known: " + tunnel, new Exception("source"));
            if (tunnel.getTunnel().getDestination() != null)
                outbound = _context.tunnelManager().selectOutboundTunnel(tunnel.getTunnel().getDestination());
            else
                outbound = _context.tunnelManager().selectOutboundTunnel();
        } else {
            outbound = _context.tunnelManager().getTunnelInfo(tunnel.getSendTunnelId(0));
            if ( (outbound == null) && (_log.shouldLog(Log.WARN)) )
                _log.warn("where are we?  outbound tunnel isn't known: " + tunnel, new Exception("source"));
            if (tunnel.getTunnel().getDestination() != null)
                inbound = _context.tunnelManager().selectInboundTunnel(tunnel.getTunnel().getDestination());
            else
                inbound = _context.tunnelManager().selectInboundTunnel();
        }
        tunnel.setInbound(inbound);
        tunnel.setOutbound(outbound);
    }
    
    private void sendTestMessage(LoadTestTunnelConfig tunnel) {
        long now = _context.clock().now();
        if (now > tunnel.getExpiration()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Not sending a test message to " + tunnel + " because it expired");
            tunnel.logComplete();
            _active.remove(tunnel);
            return;
        }
        
        if (TEST_LIVE_TUNNELS) {
            TunnelInfo inbound = tunnel.getInbound();
            TunnelInfo outbound = tunnel.getOutbound();
            if ( (inbound == null) || (outbound == null) ) {
                pickTunnels(tunnel);
                inbound = tunnel.getInbound();
                outbound = tunnel.getOutbound();
            }
            
            if (inbound == null) {
                log(tunnel, "No inbound tunnels found");
                _active.remove(tunnel);
                return;
            } else if (outbound == null) {
                log(tunnel, "No outbound tunnels found");
                tunnel.logComplete();
                _active.remove(tunnel);
                return;
            }

            if ( (now >= inbound.getExpiration()) || (now >= outbound.getExpiration()) ) {
                tunnel.logComplete();
                _active.remove(tunnel);
                return;
            }
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("inbound and outbound found for " + tunnel);
            
            I2NPMessage payloadMessage = createPayloadMessage();

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("testing live tunnels with inbound [" + inbound + "] and outbound [" + outbound + "]");
            
            // this should take into consideration both the inbound and outbound tunnels
            // ... but it doesn't, yet.
            _context.messageRegistry().registerPending(new Selector(tunnel, payloadMessage.getUniqueId()),
                                                       new SendAgain(_context, tunnel, payloadMessage.getUniqueId(), true),
                                                       new SendAgain(_context, tunnel, payloadMessage.getUniqueId(), false),
                                                       10*1000);
            _context.tunnelDispatcher().dispatchOutbound(payloadMessage, outbound.getSendTunnelId(0),
                                                         inbound.getReceiveTunnelId(0),
                                                         inbound.getPeer(0));
            //log(tunnel, payloadMessage.getUniqueId() + " sent via " + inbound + " / " + outbound);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("NOT testing live tunnels for [" + tunnel + "]");
            RouterInfo target = _context.netDb().lookupRouterInfoLocally(tunnel.getPeer(0));
            if (target == null) {
                log(tunnel, "lookup failed");
                return;
            }

            I2NPMessage payloadMessage = createPayloadMessage();

            TunnelGatewayMessage tm = new TunnelGatewayMessage(_context);
            tm.setMessage(payloadMessage);
            tm.setTunnelId(tunnel.getReceiveTunnelId(0));
            tm.setMessageExpiration(payloadMessage.getMessageExpiration());

            OutNetMessage om = new OutNetMessage(_context);
            om.setMessage(tm);
            SendAgain failed = new SendAgain(_context, tunnel, payloadMessage.getUniqueId(), false);
            om.setOnFailedReplyJob(failed);
            om.setOnReplyJob(new SendAgain(_context, tunnel, payloadMessage.getUniqueId(), true));
            //om.setOnFailedSendJob(failed);
            om.setReplySelector(new Selector(tunnel, payloadMessage.getUniqueId()));
            om.setTarget(target);
            om.setExpiration(tm.getMessageExpiration());
            om.setPriority(40);
            _context.outNetMessagePool().add(om);
            //log(tunnel, m.getMessageId() + " sent");
        }
    }
    
    private static final boolean SMALL_PAYLOAD = false;
    
    private boolean useSmallPayload() {
        return Boolean.valueOf(_context.getProperty("router.loadTestSmall", SMALL_PAYLOAD + "")).booleanValue();        
    }
    
    private I2NPMessage createPayloadMessage() {
        // doesnt matter whats in the message, as it gets dropped anyway, since we match 
        // on it with the message.uniqueId
        if (useSmallPayload()) {
            DeliveryStatusMessage m = new DeliveryStatusMessage(_context);
            long now = _context.clock().now();
            m.setArrival(now);
            m.setMessageExpiration(now + 10*1000);
            m.setMessageId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            return m;
        } else {
            DataMessage m = new DataMessage(_context);
            byte data[] = new byte[4096];
            _context.random().nextBytes(data);
            m.setData(data);
            long now = _context.clock().now();
            m.setMessageExpiration(now + 10*1000);
            
            if (true) {
                // garlic wrap the data message to ourselves so the endpoints and gateways
                // can't tell its a test, encrypting it with a random key and tag,
                // remembering that key+tag so that we can decrypt it later without any ElGamal
                DeliveryInstructions instructions = new DeliveryInstructions();
                instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);

                PayloadGarlicConfig payload = new PayloadGarlicConfig();
                payload.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
                payload.setId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
                payload.setId(m.getUniqueId());
                payload.setPayload(m);
                payload.setRecipient(_context.router().getRouterInfo());
                payload.setDeliveryInstructions(instructions);
                payload.setRequestAck(false);
                payload.setExpiration(m.getMessageExpiration());

                SessionKey encryptKey = _context.keyGenerator().generateSessionKey();
                SessionTag encryptTag = new SessionTag(true);
                SessionKey sentKey = new SessionKey();
                Set sentTags = null;
                GarlicMessage msg = GarlicMessageBuilder.buildMessage(_context, payload, sentKey, sentTags, 
                                                                      _context.keyManager().getPublicKey(), 
                                                                      encryptKey, encryptTag);

                Set encryptTags = new HashSet(1);
                encryptTags.add(encryptTag);
                _context.sessionKeyManager().tagsReceived(encryptKey, encryptTags);
                
                return msg;
            } else {
                return m;
            }
        }
    }
    
    private class SendAgain extends JobImpl implements ReplyJob {
        private LoadTestTunnelConfig _cfg;
        private long _messageId;
        private boolean _ok;
        private boolean _run;
        private long _dontStartUntil;
        public SendAgain(RouterContext ctx, LoadTestTunnelConfig cfg, long messageId, boolean ok) {
            super(ctx);
            _cfg = cfg;
            _messageId = messageId;
            _ok = ok;
            _run = false;
            _dontStartUntil = ctx.clock().now() + 10*1000;
        }
        public String getName() { return "send another load test"; }
        public void runJob() {
            if (!_ok) {
                if (!_run) {
                    log(_cfg, _messageId + " " + _cfg.getFullMessageCount() + " TIMEOUT");
                    getContext().statManager().addRateData("test.timeoutAfter", _cfg.getFullMessageCount(), 0);
                    if (getContext().clock().now() >= _dontStartUntil) {
                        sendTestMessage(_cfg);
                        _cfg.incrementFailed();
                    } else {
                        getTiming().setStartAfter(_dontStartUntil);
                        getContext().jobQueue().addJob(SendAgain.this);
                    }
                }
                _run = true;
            } else {
                sendTestMessage(_cfg);
            }
        }
        
        public void setMessage(I2NPMessage message) {}
    }
    
    private class Selector implements MessageSelector {
        private LoadTestTunnelConfig _cfg;
        private long _messageId;
        public Selector(LoadTestTunnelConfig cfg, long messageId) {
            _cfg = cfg;
            _messageId = messageId;
        }
        public boolean continueMatching() { return false; }
        public long getExpiration() { return _cfg.getExpiration(); }
        public boolean isMatch(I2NPMessage message) {
            if (message.getUniqueId() == _messageId) {
                long count = _cfg.getFullMessageCount();
                _cfg.incrementFull();
                long period = _context.clock().now() - (message.getMessageExpiration() - 10*1000);
                log(_cfg, _messageId + " " + count + " after " + period);
                _context.statManager().addRateData("test.rtt", period, count);
                if (period > 2000)
                    _context.statManager().addRateData("test.rttHigh", period, count);
                TunnelInfo info = _cfg.getOutbound();
                if (info != null) 
                    info.incrementVerifiedBytesTransferred(5*1024);
                // the inbound tunnel is incremented by the tunnel management system itself,
                // so don't double count it here
                return true;
            }
            return false;
        }
    }
    
    private void log(LoadTestTunnelConfig tunnel, String msg) {
        if (_out == null) return;
        StringBuffer buf = new StringBuffer(128);
        if (tunnel.getInbound() == null) {
            for (int i = 0; i < tunnel.getLength()-1; i++) {
                Hash peer = tunnel.getPeer(i);
                if ( (peer != null) && (peer.equals(_context.routerHash())) )
                    continue;
                else if (peer != null)
                    buf.append(peer.toBase64());
                else
                    buf.append("[unknown_peer]");
                buf.append(" ");
                TunnelId id = tunnel.getReceiveTunnelId(i);
                if (id != null)
                    buf.append(id.getTunnelId());
                else
                    buf.append("[unknown_tunnel]");
                buf.append(" ");
                buf.append(_context.clock().now()).append(" hop ").append(i).append(" ").append(msg).append("\n");
            }
        } else {
            int hop = 0;
            TunnelInfo info = tunnel.getOutbound();
            for (int i = 0; (info != null) && (i < info.getLength()); i++) {
                Hash peer = info.getPeer(i);
                if ( (peer != null) && (peer.equals(_context.routerHash())) )
                    continue;
                else if (peer != null)
                    buf.append(peer.toBase64());
                else
                    buf.append("[unknown_peer]");
                buf.append(" ");
                TunnelId id = info.getReceiveTunnelId(i);
                if (id != null)
                    buf.append(id.getTunnelId());
                else
                    buf.append("[unknown_tunnel]");
                buf.append(" ");
                buf.append(_context.clock().now()).append(" out_hop ").append(hop).append(" ").append(msg).append("\n");
                hop++;
            }
            info = tunnel.getInbound();
            for (int i = 0; (info != null) && (i < info.getLength()); i++) {
                Hash peer = info.getPeer(i);
                if ( (peer != null) && (peer.equals(_context.routerHash())) )
                    continue;
                else if (peer != null)
                    buf.append(peer.toBase64());
                else
                    buf.append("[unknown_peer]");
                buf.append(" ");
                TunnelId id = info.getReceiveTunnelId(i);
                if (id != null)
                    buf.append(id.getTunnelId());
                else
                    buf.append("[unknown_tunnel]");
                buf.append(" ");
                buf.append(_context.clock().now()).append(" in_hop ").append(hop).append(" ").append(msg).append("\n");
                hop++;
            }
        }
        try {
            synchronized (_out) {
                _out.write(buf.toString());
            }
        } catch (IOException ioe) {
            _log.error("error logging [" + msg + "]", ioe);
        }
    }
    
    /**
     * If we are testing live tunnels, see if we want to test the one that was just created
     * fully.
     */
    public void addTunnelTestCandidate(TunnelCreatorConfig cfg) {
        LoadTestTunnelConfig ltCfg = new LoadTestTunnelConfig(cfg);
        if (wantToTest(ltCfg)) {
            // wait briefly so everyone has their things in order (not really necessary...)
            long delay = _context.random().nextInt(30*1000) + 30*1000;
            SimpleTimer.getInstance().addEvent(new BeginTest(ltCfg), delay);
            if (_log.shouldLog(Log.INFO))
                _log.info("Testing " + cfg + ", with " + _active.size() + " active");
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Not testing " + cfg + " because we have " + _active.size() + " active: " + _active);
        }
    }
    public void removeTunnelTestCandidate(TunnelCreatorConfig cfg) { _active.remove(cfg); }
    
    private class BeginTest implements SimpleTimer.TimedEvent {
        private LoadTestTunnelConfig _cfg;
        public BeginTest(LoadTestTunnelConfig cfg) {
            _cfg = cfg;
        }
        public void timeReached() {
            _context.jobQueue().addJob(new Expire(_context, _cfg, false));
            runTest(_cfg);
        }
    }
    
    private boolean wantToTest(LoadTestTunnelConfig cfg) {
        // wait 10 minutes before testing anything
        if (_context.router().getUptime() <= 10*60*1000) return false;
        if (bandwidthOverloaded()) return false;
        
        if (TEST_LIVE_TUNNELS && _active.size() < getConcurrency()) {
            // length == #hops+1 (as it includes the creator)
            if (cfg.getLength() < 2)
                return false;
            _active.add(cfg);
            return true;
        } else {
            return false;
        }
    }
    
    private boolean bandwidthOverloaded() {
        int msgLoadBps = CONCURRENT_MESSAGES
                         * 5 * 1024 // message size
                         / 10; // 10 seconds before timeout & retransmission
        msgLoadBps *= 2; // buffer
        int curBps = getBps();
        if ((curBps + msgLoadBps)/1024 >= _context.bandwidthLimiter().getOutboundKBytesPerSecond())
            return true;
        if ((curBps + msgLoadBps)/1024 >= _context.bandwidthLimiter().getInboundKBytesPerSecond())
            return true;
        if (_context.throttle().getMessageDelay() > 1000)
            return true;
        return false;
    }
    
    private int getBps() {
        int used1s = RouterThrottleImpl.get1sRate(_context);
        int used1m = RouterThrottleImpl.get1mRate(_context);
        int used5m = RouterThrottleImpl.get5mRate(_context);
        return Math.max(used1s, Math.max(used1m, used5m));
    }
    
    private class CreatedJob extends JobImpl {
        private LoadTestTunnelConfig _cfg;
        public CreatedJob(RouterContext ctx, LoadTestTunnelConfig cfg) {
            super(ctx);
            _cfg = cfg;
        }
        public String getName() { return "Test tunnel created"; }
        public void runJob() { 
            if (_log.shouldLog(Log.INFO))
                _log.info("Tunnel created for testing peer " + _cfg.getPeer(0).toBase64()); 
            getContext().tunnelDispatcher().joinInbound(_cfg.getTunnel());
            //log(_cfg, "joined");
            _active.add(_cfg);
            Expire j = new Expire(getContext(), _cfg);
            //_cfg.setExpireJob(j);
            getContext().jobQueue().addJob(j);
            runTest(_cfg);
        }
    }
    private long TEST_PERIOD_MAX = 5*60*1000;
    private long TEST_PERIOD_MIN = 1*60*1000;
    
    private class Expire extends JobImpl {
        private LoadTestTunnelConfig _cfg;
        private boolean _removeFromDispatcher;
        public Expire(RouterContext ctx, LoadTestTunnelConfig cfg) {
            this(ctx, cfg, true);
        }
        public Expire(RouterContext ctx, LoadTestTunnelConfig cfg, boolean removeFromDispatcher) {
            super(ctx);
            _cfg = cfg;
            _removeFromDispatcher = removeFromDispatcher;
            long duration = ctx.random().nextLong(TEST_PERIOD_MAX);
            if (duration < TEST_PERIOD_MIN)
                duration += TEST_PERIOD_MIN;
            long expiration = duration + ctx.clock().now();
            if (expiration > cfg.getExpiration()+60*1000)
                expiration = cfg.getExpiration()+60*1000;
            getTiming().setStartAfter(expiration);
        }
        public String getName() { return "expire test tunnel"; } 
        public void runJob() { 
            if (_removeFromDispatcher)
                getContext().tunnelDispatcher().remove(_cfg.getTunnel());
            _cfg.logComplete();
            TunnelInfo info = _cfg.getOutbound();
            if (info != null) 
                info.incrementVerifiedBytesTransferred(0); // just to wrap up the test data
            _active.remove(_cfg);
        } 
    }
    private class FailedJob extends JobImpl {
        private LoadTestTunnelConfig _cfg;
        public FailedJob(RouterContext ctx, LoadTestTunnelConfig cfg) {
            super(ctx);
            _cfg = cfg;
        }
        public String getName() { return "Test tunnel failed"; }
        public void runJob() { 
            if (_log.shouldLog(Log.INFO))
                _log.info("Tunnel failed for testing peer " + _cfg.getPeer(0).toBase64());
            log(_cfg, "failed");
        }
    }
    
    private class LoadTestTunnelConfig {
        private TunnelCreatorConfig _cfg;
        private long _failed;
        private long _fullMessages;
        private TunnelInfo _testInbound;
        private TunnelInfo _testOutbound;
        private boolean _completed;
        public LoadTestTunnelConfig(TunnelCreatorConfig cfg) {
            _cfg = cfg;
            _failed = 0;
            _fullMessages = 0;
            _completed = false;
        }
        
        public long getExpiration() { return _cfg.getExpiration(); }
        public Hash getPeer(int peer) { return _cfg.getPeer(peer); }
        public TunnelId getReceiveTunnelId(int peer) { return _cfg.getReceiveTunnelId(peer); }
        public TunnelId getSendTunnelId(int peer) { return _cfg.getSendTunnelId(peer); }
        public int getLength() { return _cfg.getLength(); }
        
        public void incrementFailed() { ++_failed; }
        public long getFailedMessageCount() { return _failed; }
        public void incrementFull() { ++_fullMessages; }
        public long getFullMessageCount() { return _fullMessages; }
        public TunnelCreatorConfig getTunnel() { return _cfg; }
        public void setInbound(TunnelInfo info) { _testInbound = info; }
        public void setOutbound(TunnelInfo info) { _testOutbound = info; }
        public TunnelInfo getInbound() { return _testInbound; }
        public TunnelInfo getOutbound() { return _testOutbound; }
        public String toString() { return _cfg + ": failed=" + _failed + " full=" + _fullMessages; }
        
        void logComplete() {
            if (_completed) return;
            _completed = true;
            LoadTestTunnelConfig cfg = LoadTestTunnelConfig.this;
            log(cfg, "expired after sending " + cfg.getFullMessageCount() + " / " + cfg.getFailedMessageCount() 
                + " in " + (10*60*1000l - (cfg.getExpiration()-_context.clock().now())));
            _context.statManager().addRateData("test.lifetimeSuccessful", cfg.getFullMessageCount(), cfg.getFailedMessageCount());
            if (cfg.getFailedMessageCount() > 0)
                _context.statManager().addRateData("test.lifetimeFailed", cfg.getFailedMessageCount(), cfg.getFullMessageCount());
        }
    }
}
