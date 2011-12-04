package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * We are the end of an outbound tunnel that we did not create.  Gather fragments
 * and honor the instructions as received.
 *
 */
class OutboundTunnelEndpoint {
    private final RouterContext _context;
    private final Log _log;
    private final HopConfig _config;
    private final HopProcessor _processor;
    private final FragmentHandler _handler;
    private final OutboundMessageDistributor _outDistributor;

    public OutboundTunnelEndpoint(RouterContext ctx, HopConfig config, HopProcessor processor) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundTunnelEndpoint.class);
        _config = config;
        _processor = processor;
        _handler = new RouterFragmentHandler(ctx, new DefragmentedHandler());
        _outDistributor = new OutboundMessageDistributor(ctx, 200);
    }
    public void dispatch(TunnelDataMessage msg, Hash recvFrom) {
        _config.incrementProcessedMessages();
        boolean ok = _processor.process(msg.getData(), 0, msg.getData().length, recvFrom);
        if (!ok) {
            // invalid IV
            // If we pass it on to the handler, it will fail
            // If we don't, the data buf won't get released from the cache... that's ok
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid IV, dropping at OBEP " + _config);
            _context.statManager().addRateData("tunnel.corruptMessage", 1, 1);
            return;
        }
        _handler.receiveTunnelMessage(msg.getData(), 0, msg.getData().length);
    }
    
    private class DefragmentedHandler implements FragmentHandler.DefragmentedReceiver {
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("outbound tunnel " + _config + " received a full message: " + msg
                           + " to be forwarded on to "
                           + (toRouter != null ? toRouter.toBase64().substring(0,4) : "")
                           + (toTunnel != null ? ":" + toTunnel.getTunnelId() : ""));
            int size = msg.getMessageSize();
            // don't drop it if we are the target
            boolean toUs = _context.routerHash().equals(toRouter);
            if ((!toUs) &&
                _context.tunnelDispatcher().shouldDropParticipatingMessage(TunnelDispatcher.Location.OBEP, msg.getType(), size))
                return;
            // this overstates the stat somewhat, but ok for now
            //int kb = (size + 1023) / 1024;
            //for (int i = 0; i < kb; i++)
            //    _config.incrementSentMessages();
            if (!toUs)
                _context.bandwidthLimiter().sentParticipatingMessage(size);
            _outDistributor.distribute(msg, toRouter, toTunnel);
        }
    }
}
