package net.i2p.router;

import java.io.*;
import java.util.*;
import net.i2p.util.*;
import net.i2p.data.*;
import net.i2p.data.i2np.*;
import net.i2p.router.tunnel.*;
import net.i2p.router.tunnel.pool.*;
import net.i2p.router.transport.udp.UDPTransport;

/**
 * Coordinate some tests of peers to see how much load they can handle.  This
 * test is not safe for use in anonymous environments, but should help pinpoint
 * some performance aspects of the live net.
 * 
 * Each individual load test is conducted by building a single one hop inbound
 * tunnel with the peer in question acting as the inbound gateway.  We then send
 * messages directly to that gateway, which they batch up and send "down the
 * tunnel" (aka directly to us), at which point we then send another message,
 * and so on, until the tunnel expires.  Along the way, we record a few vital
 * stats to the "loadtest.log" file.  If we don't receive a message, we send another
 * after 10 seconds.
 *
 * If "router.loadTestSmall=true", we transmit a tiny DeliveryStatusMessage (~96 bytes
 * at the SSU level), which is sent back to us as a single TunnelDataMessage (~1KB).
 * Otherwise, we transmit a 4KB DataMessage, which is sent back to us as five (1KB)
 * TunnelDataMessages.  This size is chosen because the streaming lib uses 4KB messages
 * by default.
 *
 */
public class LoadTestManager {
    private RouterContext _context;
    private Log _log;
    private Writer _out;
    private List _untestedPeers;
    
    public LoadTestManager(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(LoadTestManager.class);
        try {
            _out = new BufferedWriter(new FileWriter("loadtest.log", true));
        } catch (IOException ioe) {
            _log.log(Log.CRIT, "error creating log", ioe);
        }
        _context.statManager().createRateStat("test.lifetimeSuccessful", "How many messages we can pump through a load test during a tunnel's lifetime", "test", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("test.lifetimeFailed", "How many messages we fail to pump through (period == successful)", "test", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("test.timeoutAfter", "How many messages have we successfully pumped through a tunnel when one particular message times out", "test", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("test.rtt", "How long it takes to get a reply", "test", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("test.rttHigh", "How long it takes to get a reply, if it is a slow rtt", "test", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
    }
    
    public Job getTestJob() { return new TestJob(_context); }
    private class TestJob extends JobImpl {
        public TestJob(RouterContext ctx) { 
            super(ctx);
            // wait 5m to start up
            getTiming().setStartAfter(3*60*1000 + getContext().clock().now());
        }
        public String getName() { return "run load tests"; }
        public void runJob() { 
            runTest();
            getTiming().setStartAfter(10*60*1000 + getContext().clock().now());
            getContext().jobQueue().addJob(TestJob.this);
        }
    }
    
    /** 10 peers at a time */
    private static final int CONCURRENT_PEERS = 10;
    /** 4 messages per peer at a time */
    private static final int CONCURRENT_MESSAGES = 4;
    
    public void runTest() {
        if ( (_untestedPeers == null) || (_untestedPeers.size() <= 0) ) {
            UDPTransport t = UDPTransport._instance();
            if (t != null)
                _untestedPeers = t._getActivePeers();
        }
        int peers = getConcurrency();
        for (int i = 0; i < peers && _untestedPeers.size() > 0; i++)
            buildTestTunnel((Hash)_untestedPeers.remove(0));
    }
    
    private int getConcurrency() {
        int rv = CONCURRENT_PEERS;
        try {
            rv = Integer.parseInt(_context.getProperty("router.loadTestConcurrency", CONCURRENT_PEERS+""));
        } catch (NumberFormatException nfe) {
            rv = CONCURRENT_PEERS;
        }
        if (rv < 1) 
            rv = 1;
        if (rv > 50)
            rv = 50;
        return rv;
    }
    
    private int getPeerMessages() {
        int rv = CONCURRENT_MESSAGES;
        try {
            rv = Integer.parseInt(_context.getProperty("router.loadTestMessagesPerPeer", CONCURRENT_MESSAGES+""));
        } catch (NumberFormatException nfe) {
            rv = CONCURRENT_MESSAGES;
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
        for (int i = 0; i < peerMessages; i++)
            sendTestMessage(tunnel);
    }
    
    private void sendTestMessage(LoadTestTunnelConfig tunnel) {
        if (_context.clock().now() > tunnel.getExpiration())
            return;
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
            return m;
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
                return true;
            }
            return false;
        }
    }
    
    private void log(LoadTestTunnelConfig tunnel, String msg) {
        StringBuffer buf = new StringBuffer(128);
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
        try {
            synchronized (_out) {
                _out.write(buf.toString());
            }
        } catch (IOException ioe) {
            _log.error("error logging [" + msg + "]", ioe);
        }
    }
    
    
    private boolean getBuildOneHop() {
        return Boolean.valueOf(_context.getProperty("router.loadTestOneHop", "false")).booleanValue();        
    }
    
    private void buildTestTunnel(Hash peer) {
        if (getBuildOneHop()) {
            buildOneHop(peer);
        } else {
            buildLonger(peer);
        }
    }
    private void buildOneHop(Hash peer) {
        long expiration = _context.clock().now() + 10*60*1000;
        
        LoadTestTunnelConfig cfg = new LoadTestTunnelConfig(_context, 2, true);
        // cfg.getPeer() is ordered gateway first
        cfg.setPeer(0, peer);
        HopConfig hop = cfg.getConfig(0);
        hop.setExpiration(expiration);
        hop.setIVKey(_context.keyGenerator().generateSessionKey());
        hop.setLayerKey(_context.keyGenerator().generateSessionKey());
        // now for ourselves
        cfg.setPeer(1, _context.routerHash());
        hop = cfg.getConfig(1);
        hop.setExpiration(expiration);
        hop.setIVKey(_context.keyGenerator().generateSessionKey());
        hop.setLayerKey(_context.keyGenerator().generateSessionKey());
        
        cfg.setExpiration(expiration);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Config for " + peer.toBase64() + ": " + cfg);
        
        CreatedJob onCreated = new CreatedJob(_context, cfg);
        FailedJob fail = new FailedJob(_context, cfg);
        RequestTunnelJob req = new RequestTunnelJob(_context, cfg, onCreated, fail, cfg.getLength()-1, false, true);
        _context.jobQueue().addJob(req);
    }
    
    private Hash pickFastPeer(Hash skipPeer) {
        String peers = _context.getProperty("router.loadTestFastPeers");
        if (peers != null) {
            StringTokenizer tok = new StringTokenizer(peers.trim(), ", \t");
            List peerList = new ArrayList();
            while (tok.hasMoreTokens()) {
                String str = tok.nextToken();
                try {
                    Hash h = new Hash();
                    h.fromBase64(str);
                    peerList.add(h);
                } catch (DataFormatException dfe) {
                    // ignore
                }
            }
            Collections.shuffle(peerList);
            while (peerList.size() > 0) {
                Hash cur = (Hash)peerList.remove(0);
                if (!cur.equals(skipPeer))
                    return cur;
            }
        }
        return null;
    }
    
    private void buildLonger(Hash peer) {
        long expiration = _context.clock().now() + 10*60*1000;
        
        LoadTestTunnelConfig cfg = new LoadTestTunnelConfig(_context, 3, true);
        // cfg.getPeer() is ordered gateway first
        cfg.setPeer(0, peer);
        HopConfig hop = cfg.getConfig(0);
        hop.setExpiration(expiration);
        hop.setIVKey(_context.keyGenerator().generateSessionKey());
        hop.setLayerKey(_context.keyGenerator().generateSessionKey());
        // now lets put in a fast peer
        Hash fastPeer = pickFastPeer(peer);
        if (fastPeer == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Unable to pick a fast peer for the load test of " + peer.toBase64());
            buildOneHop(peer);
            return;
        } else if (fastPeer.equals(peer)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Can't test the peer with themselves, going one hop for " + peer.toBase64());
            buildOneHop(peer);
            return;
        }
        cfg.setPeer(1, fastPeer);
        hop = cfg.getConfig(1);
        hop.setExpiration(expiration);
        hop.setIVKey(_context.keyGenerator().generateSessionKey());
        hop.setLayerKey(_context.keyGenerator().generateSessionKey());
        // now for ourselves
        cfg.setPeer(2, _context.routerHash());
        hop = cfg.getConfig(2);
        hop.setExpiration(expiration);
        hop.setIVKey(_context.keyGenerator().generateSessionKey());
        hop.setLayerKey(_context.keyGenerator().generateSessionKey());
        
        cfg.setExpiration(expiration);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Config for " + peer.toBase64() + " with fastPeer: " + fastPeer.toBase64() + ": " + cfg);
        
        CreatedJob onCreated = new CreatedJob(_context, cfg);
        FailedJob fail = new FailedJob(_context, cfg);
        RequestTunnelJob req = new RequestTunnelJob(_context, cfg, onCreated, fail, cfg.getLength()-1, false, true);
        _context.jobQueue().addJob(req);
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
            getContext().tunnelDispatcher().joinInbound(_cfg);
            //log(_cfg, "joined");
            
            Expire j = new Expire(getContext(), _cfg);
            _cfg.setExpireJob(j);
            getContext().jobQueue().addJob(j);
            runTest(_cfg);
        }
    }
    private class Expire extends JobImpl {
        private LoadTestTunnelConfig _cfg;
        public Expire(RouterContext ctx, LoadTestTunnelConfig cfg) {
            super(ctx);
            _cfg = cfg;
            getTiming().setStartAfter(cfg.getExpiration()+60*1000);
        }
        public String getName() { return "expire test tunnel"; } 
        public void runJob() { 
            getContext().tunnelDispatcher().remove(_cfg);
            log(_cfg, "expired after sending " + _cfg.getFullMessageCount() + " / " + _cfg.getFailedMessageCount());
            getContext().statManager().addRateData("test.lifetimeSuccessful", _cfg.getFullMessageCount(), _cfg.getFailedMessageCount());
            if (_cfg.getFailedMessageCount() > 0)
                getContext().statManager().addRateData("test.lifetimeFailed", _cfg.getFailedMessageCount(), _cfg.getFullMessageCount());
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
    
    private class LoadTestTunnelConfig extends PooledTunnelCreatorConfig {
        private long _failed;
        private long _fullMessages;
        public LoadTestTunnelConfig(RouterContext ctx, int length, boolean isInbound) {
            super(ctx, length, isInbound);
            _failed = 0;
            _fullMessages = 0;
        }
        public void incrementFailed() { ++_failed; }
        public long getFailedMessageCount() { return _failed; }
        public void incrementFull() { ++_fullMessages; }
        public long getFullMessageCount() { return _fullMessages; }
    }
}
