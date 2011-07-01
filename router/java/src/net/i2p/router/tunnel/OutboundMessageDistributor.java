package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * When a message arrives at the outbound tunnel endpoint, this distributor
 * honors the instructions.
 */
public class OutboundMessageDistributor {
    private final RouterContext _context;
    private final int _priority;
    private final Log _log;
    
    private static final int MAX_DISTRIBUTE_TIME = 10*1000;
    
    public OutboundMessageDistributor(RouterContext ctx, int priority) {
        _context = ctx;
        _priority = priority;
        _log = ctx.logManager().getLog(OutboundMessageDistributor.class);
    }
    
    public void distribute(I2NPMessage msg, Hash target) {
        distribute(msg, target, null);
    }

    public void distribute(I2NPMessage msg, Hash target, TunnelId tunnel) {
        RouterInfo info = _context.netDb().lookupRouterInfoLocally(target);
        if (info == null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("outbound distributor to " + target.toBase64().substring(0,4)
                           + "." + (tunnel != null ? tunnel.getTunnelId() + "" : "")
                           + ": no info locally, searching...");
            _context.netDb().lookupRouterInfo(target, new DistributeJob(_context, msg, target, tunnel), null, MAX_DISTRIBUTE_TIME);
            return;
        } else {
            distribute(msg, info, tunnel);
        }
    }
    
    public void distribute(I2NPMessage msg, RouterInfo target, TunnelId tunnel) {
        I2NPMessage m = msg;
        if (tunnel != null) {
            TunnelGatewayMessage t = new TunnelGatewayMessage(_context);
            t.setMessage(msg);
            t.setTunnelId(tunnel);
            t.setMessageExpiration(m.getMessageExpiration());
            m = t;
        }
        
        if (_context.routerHash().equals(target.getIdentity().calculateHash())) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("queueing inbound message to ourselves: " + m);
            _context.inNetMessagePool().add(m, null, null); 
            return;
        } else {
            OutNetMessage out = new OutNetMessage(_context);
            out.setExpiration(_context.clock().now() + MAX_DISTRIBUTE_TIME);
            out.setTarget(target);
            out.setMessage(m);
            out.setPriority(_priority);

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("queueing outbound message to " + target.getIdentity().calculateHash().toBase64().substring(0,4));
            _context.outNetMessagePool().add(out);
        }
    }
    
    private class DistributeJob extends JobImpl {
        private final I2NPMessage _message;
        private final Hash _target;
        private final TunnelId _tunnel;

        public DistributeJob(RouterContext ctx, I2NPMessage msg, Hash target, TunnelId id) {
            super(ctx);
            _message = msg;
            _target = target;
            _tunnel = id;
        }

        public String getName() { return "Distribute outbound message"; }

        public void runJob() {
            RouterInfo info = getContext().netDb().lookupRouterInfoLocally(_target);
            if (info != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("outbound distributor to " + _target.toBase64().substring(0,4)
                           + "." + (_tunnel != null ? _tunnel.getTunnelId() + "" : "")
                           + ": found on search");
                distribute(_message, info, _tunnel);
            } else {
                // TODO add a stat here
                if (_log.shouldLog(Log.WARN))
                    _log.warn("outbound distributor to " + _target.toBase64().substring(0,4)
                           + "." + (_tunnel != null ? _tunnel.getTunnelId() + "" : "")
                           + ": NOT found on search");
            }
        }
        
    }
}
