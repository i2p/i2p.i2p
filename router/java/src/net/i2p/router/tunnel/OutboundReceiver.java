package net.i2p.router.tunnel;

import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Receive the outbound message after it has been preprocessed and encrypted,
 * then forward it on to the first hop in the tunnel.
 *
 */
class OutboundReceiver implements TunnelGateway.Receiver {
    private final RouterContext _context;
    private final Log _log;
    private final TunnelCreatorConfig _config;
    private RouterInfo _nextHopCache;
    
    public OutboundReceiver(RouterContext ctx, TunnelCreatorConfig cfg) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundReceiver.class);
        _config = cfg;
        _nextHopCache = _context.netDb().lookupRouterInfoLocally(_config.getPeer(1));
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
            // TODO add a stat here
            if (_log.shouldLog(Log.WARN))
                _log.warn("lookup of " + _config.getPeer(1).toBase64().substring(0,4) 
                           + " required for " + msg);
            _context.netDb().lookupRouterInfo(_config.getPeer(1), new SendJob(_context, msg), new FailedJob(_context), 10*1000);
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

        public String getName() { return "forward a tunnel message"; }

        public void runJob() {
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getPeer(1));
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("lookup of " + _config.getPeer(1).toBase64().substring(0,4) 
                           + " successful? " + (ri != null));
            if (ri != null) {
                _nextHopCache = ri;
                send(_msg, ri);
            }
        }
    }
    
    private class FailedJob extends JobImpl {
        public FailedJob(RouterContext ctx) {
            super(ctx);
        }

        public String getName() { return "failed looking for our outbound gateway"; }

        public void runJob() {
            // TODO add a stat here
            if (_log.shouldLog(Log.WARN))
                _log.warn("lookup of " + _config.getPeer(1).toBase64().substring(0,4) 
                           + " failed for " + _config);
        }
    }
}
