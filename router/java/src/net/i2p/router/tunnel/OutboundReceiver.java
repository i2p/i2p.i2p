package net.i2p.router.tunnel;

import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * We are the outbound gateway - we created this outbound tunnel.
 * Receive the outbound message after it has been preprocessed and encrypted,
 * then forward it on to the first hop in the tunnel.
 *
 */
class OutboundReceiver implements TunnelGateway.Receiver {
    private final RouterContext _context;
    private final Log _log;
    private final TunnelCreatorConfig _config;
    private RouterInfo _nextHopCache;
    
    private static final long MAX_LOOKUP_TIME = 15*1000;

    public OutboundReceiver(RouterContext ctx, TunnelCreatorConfig cfg) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundReceiver.class);
        _config = cfg;
        _nextHopCache = _context.netDb().lookupRouterInfoLocally(_config.getPeer(1));
        // all createRateStat() in TunnelDispatcher
    }
    
    public long receiveEncrypted(byte encrypted[]) {
        TunnelDataMessage msg = new TunnelDataMessage(_context);
        msg.setData(encrypted);
        msg.setTunnelId(_config.getConfig(0).getSendTunnel());

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("received encrypted, sending out " + _config + ": " + msg);
        RouterInfo ri = _nextHopCache;
        if (ri == null)
            ri = _context.netDb().lookupRouterInfoLocally(_config.getPeer(1));
        if (ri != null) {
            _nextHopCache = ri;
            send(msg, ri);
            return msg.getUniqueId();
        } else {
            // It should be rare to forget the router info for a peer in our own tunnel.
            if (_log.shouldLog(Log.WARN))
                _log.warn("lookup of " + _config.getPeer(1)
                           + " required for " + msg);
            _context.netDb().lookupRouterInfo(_config.getPeer(1), new SendJob(_context, msg),
                                              new FailedJob(_context), MAX_LOOKUP_TIME);
            return -1;
        }
    }

    private void send(TunnelDataMessage msg, RouterInfo ri) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("forwarding encrypted data out " + _config + ": " + msg.getUniqueId());
        OutNetMessage m = new OutNetMessage(_context);
        m.setMessage(msg);
        m.setExpiration(msg.getMessageExpiration());
        m.setTarget(ri);
        m.setPriority(400);
        _context.outNetMessagePool().add(m);
        _config.incrementProcessedMessages();
    }

    private class SendJob extends JobImpl {
        private final TunnelDataMessage _msg;

        public SendJob(RouterContext ctx, TunnelDataMessage msg) {
            super(ctx);
            _msg = msg;
        }

        public String getName() { return "OBGW send after lookup"; }

        public void runJob() {
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getPeer(1));
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("lookup of " + _config.getPeer(1)
                           + " successful? " + (ri != null));
            int stat;
            if (ri != null) {
                _nextHopCache = ri;
                send(_msg, ri);
                stat = 1;
            } else {
                stat = 0;
            }
            _context.statManager().addRateData("tunnel.outboundLookupSuccess", stat, 0);
        }
    }
    
    private class FailedJob extends JobImpl {
        public FailedJob(RouterContext ctx) {
            super(ctx);
        }

        public String getName() { return "OBGW lookup fail"; }

        public void runJob() {
            if (_log.shouldLog(Log.WARN))
                _log.warn("lookup of " + _config.getPeer(1)
                           + " failed for " + _config);
            _context.statManager().addRateData("tunnel.outboundLookupSuccess", 0, 0);
        }
    }
}
