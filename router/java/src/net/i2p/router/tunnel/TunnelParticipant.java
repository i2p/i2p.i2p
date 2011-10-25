package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Participate in a tunnel at a location other than the gateway or outbound
 * endpoint.  This participant should be provided with the necessary processor
 * if it is an inbound tunnel endpoint, and that will enable the 
 * InboundMessageDistributor to receive defragmented and decrypted messages,
 * which it will then selectively forward.
 */
class TunnelParticipant {
    private final RouterContext _context;
    private final Log _log;
    private final HopConfig _config;
    private final HopProcessor _processor;
    private final InboundEndpointProcessor _inboundEndpointProcessor;
    private final InboundMessageDistributor _inboundDistributor;
    private final FragmentHandler _handler;
    private RouterInfo _nextHopCache;

    private static final long MAX_LOOKUP_TIME = 15*1000;
    /** for next hop when a tunnel is first created */
    private static final long LONG_MAX_LOOKUP_TIME = 30*1000;

    /** not an inbound endpoint */
    public TunnelParticipant(RouterContext ctx, HopConfig config, HopProcessor processor) {
        this(ctx, config, processor, null);
    }

    /** inbound endpoint */
    public TunnelParticipant(RouterContext ctx, InboundEndpointProcessor inEndProc) {
        this(ctx, null, null, inEndProc);
    }

    /**
     * @param config may be null (inbound endpoint if null)
     * @param processor may be null (inbound endpoint if null)
     * @param inEndProc may be null (inbound endpoint if non-null)
     */
    private TunnelParticipant(RouterContext ctx, HopConfig config, HopProcessor processor, InboundEndpointProcessor inEndProc) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelParticipant.class);
        _config = config;
        _processor = processor;
        if ( (config == null) || (config.getSendTo() == null) )
            _handler = new RouterFragmentHandler(ctx, new DefragmentedHandler());
        else
            _handler = null; // final

        _inboundEndpointProcessor = inEndProc;
        if (inEndProc != null)
            _inboundDistributor = new InboundMessageDistributor(ctx, inEndProc.getDestination());
        else
            _inboundDistributor = null; // final

        if ( (_config != null) && (_config.getSendTo() != null) ) {
            _nextHopCache = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (_nextHopCache == null)
                _context.netDb().lookupRouterInfo(_config.getSendTo(), new Found(_context), null, LONG_MAX_LOOKUP_TIME);
        }
        // all createRateStat() in TunnelDispatcher
    }
    
    private class Found extends JobImpl {
        public Found(RouterContext ctx) { super(ctx); }
        public String getName() { return "Next hop info found"; }
        public void runJob() {
            if (_nextHopCache == null) {
                _nextHopCache = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
                // nothing for failure since fail job is null
                _context.statManager().addRateData("tunnel.participantLookupSuccess", 1, 0);
            }
        }
    }
    
    public void dispatch(TunnelDataMessage msg, Hash recvFrom) {
        boolean ok = false;
        if (_processor != null)
            ok = _processor.process(msg.getData(), 0, msg.getData().length, recvFrom);
        else if (_inboundEndpointProcessor != null) 
            ok = _inboundEndpointProcessor.retrievePreprocessedData(msg.getData(), 0, msg.getData().length, recvFrom);
        
        if (!ok) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Failed to dispatch " + msg + ": processor=" + _processor 
                           + " inboundEndpoint=" + _inboundEndpointProcessor);
            if (_config != null)
                _config.incrementProcessedMessages();
            _context.statManager().addRateData("tunnel.corruptMessage", 1, 1);
            return;
        }
        
        if ( (_config != null) && (_config.getSendTo() != null) ) {
            _config.incrementProcessedMessages();
            RouterInfo ri = _nextHopCache;
            if (ri == null)
                ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (ri != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Send off to nextHop directly (" + _config.getSendTo()
                              + " for " + msg);
                send(_config, msg, ri);
                // see comments below
                //if (_config != null)
                //    incrementThroughput(_config.getReceiveFrom());
            } else {
                // It should be rare to forget the router info for the next peer
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Lookup the nextHop (" + _config.getSendTo()
                              + " for " + msg);
                _context.netDb().lookupRouterInfo(_config.getSendTo(), new SendJob(_context, msg),
                                                  new TimeoutJob(_context, msg), MAX_LOOKUP_TIME);
            }
        } else {
            _inboundEndpointProcessor.getConfig().incrementProcessedMessages();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive fragment: on " + _config + ": " + msg);
            _handler.receiveTunnelMessage(msg.getData(), 0, msg.getData().length);
        }
    }
    
/****
    private int _periodMessagesTransferred;
    private long _lastCoallesced = System.currentTimeMillis();
****/

    /** 
     * take note that the peers specified were able to push us data.  hmm, is this safe?
     * this could be easily gamed to get us to rank some peer of their choosing as quite
     * fast.  That peer would have to actually be quite fast, but having a remote peer
     * influence who we spend our time profiling is dangerous, so this will be disabled for
     * now.
     */
/****
    private void incrementThroughput(Hash prev) {
        if (true) return;
        long now = System.currentTimeMillis();
        long timeSince = now - _lastCoallesced;
        if (timeSince >= 60*1000) {
            int amount = 1024 * _periodMessagesTransferred;
            int normalized = (int)((double)amount * 60d*1000d / (double)timeSince);
            _periodMessagesTransferred = 0;
            _lastCoallesced = now;
            _context.profileManager().tunnelDataPushed1m(prev, normalized);
        } else {
            _periodMessagesTransferred++;
        }
    }
****/
    
    public int getCompleteCount() { 
        if (_handler != null)
            return _handler.getCompleteCount();
        else
            return 0;
    }

    public int getFailedCount() { 
        if (_handler != null)
            return _handler.getFailedCount();
        else
            return 0;
    }
    
    private class DefragmentedHandler implements FragmentHandler.DefragmentedReceiver {
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive complete: on " + _config + ": " + msg);
            _inboundDistributor.distribute(msg, toRouter, toTunnel);
        }
        
    }

    private void send(HopConfig config, TunnelDataMessage msg, RouterInfo ri) {
        if (_context.tunnelDispatcher().shouldDropParticipatingMessage("TDM", 1024))
            return;
        _config.incrementSentMessages();
        long oldId = msg.getUniqueId();
        long newId = _context.random().nextLong(I2NPMessage.MAX_ID_VALUE);
        _context.messageHistory().wrap("TunnelDataMessage", oldId, "TunnelDataMessage", newId);
        msg.setUniqueId(newId);
        msg.setMessageExpiration(_context.clock().now() + 10*1000);
        OutNetMessage m = new OutNetMessage(_context);
        msg.setTunnelId(config.getSendTunnel());
        m.setMessage(msg);
        m.setExpiration(msg.getMessageExpiration());
        m.setTarget(ri);
        m.setPriority(200);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Forward on from " + _config + ": " + msg);
        _context.outNetMessagePool().add(m);
    }

    private class SendJob extends JobImpl {
        private final TunnelDataMessage _msg;

        public SendJob(RouterContext ctx, TunnelDataMessage msg) {
            super(ctx);
            _msg = msg;
        }

        public String getName() { return "Participant send after lookup"; }

        public void runJob() {
            if (_nextHopCache != null) {
                send(_config, _msg, _nextHopCache);
            } else {
                RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
                int stat;
                if (ri != null) {
                    _nextHopCache = ri;
                    send(_config, _msg, ri);
                    stat = 1;
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Lookup the nextHop (" + _config.getSendTo()
                                  + " failed!  where do we go for " + _config + "?  msg dropped: " + _msg);
                    stat = 0;
                }
                _context.statManager().addRateData("tunnel.participantLookupSuccess", stat, 0);
            }
        }
    }

    private class TimeoutJob extends JobImpl {
        private final TunnelDataMessage _msg;

        public TimeoutJob(RouterContext ctx, TunnelDataMessage msg) {
            super(ctx);
            _msg = msg;
        }

        public String getName() { return "Participant next hop lookup timeout"; }

        public void runJob() {
            if (_nextHopCache != null)
                return;
            
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (ri != null) {
                _nextHopCache = ri;
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Lookup the nextHop (" + _config.getSendTo()
                              + " failed, but we found it!!  where do we go for " + _config + "?  msg dropped: " + _msg);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Lookup the nextHop (" + _config.getSendTo()
                              + " failed!  where do we go for " + _config + "?  msg dropped: " + _msg);
            }
            _context.statManager().addRateData("tunnel.participantLookupSuccess", 0, 0);
        }
    }
    
    @Override
    public String toString() { 
        if (_config != null) {
            StringBuilder buf = new StringBuilder(64);
            buf.append("participant at ").append(_config.toString());
            return buf.toString();
        } else {
            return "inbound endpoint";
        }
    }
}
