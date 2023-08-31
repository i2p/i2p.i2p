package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.router.OutNetMessage;
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
    private int _lsdsm, _ridsm, _i2npmsg, _totalmsg;

    public OutboundTunnelEndpoint(RouterContext ctx, HopConfig config, HopProcessor processor) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundTunnelEndpoint.class);
        _config = config;
        _processor = processor;
        _handler = new RouterFragmentHandler(ctx, new DefragmentedHandler());
        _outDistributor = new OutboundMessageDistributor(ctx, OutNetMessage.PRIORITY_PARTICIPATING);
        _totalmsg = _lsdsm = _ridsm = _i2npmsg = 0;
    }

    public void dispatch(TunnelDataMessage msg, Hash recvFrom) {
        _config.incrementProcessedMessages();
        byte[] data = msg.getData();
        boolean ok = _processor.process(data, 0, data.length, recvFrom);
        if (!ok) {
            // invalid IV
            // If we pass it on to the handler, it will fail
            // If we don't, the data buf won't get released from the cache... that's ok
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid IV, dropping at OBEP " + _config);
            return;
        }
        ok = _handler.receiveTunnelMessage(data, 0, data.length);
        if (!ok) {
            // blame previous hop
            Hash h = _config.getReceiveFrom();
            if (h != null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": Blaming " + h + " 50%");
                _context.profileManager().tunnelFailed(h, 50);
            }
        }
    }
    
    private class DefragmentedHandler implements FragmentHandler.DefragmentedReceiver {
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            _totalmsg++;
            if (toRouter == null) {
                // Delivery type LOCAL is not supported at the OBEP
                // We don't have any use for it yet.
                // Don't send to OutboundMessageDistributor.distribute() which will NPE or fail
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping msg at OBEP with unsupported delivery instruction type LOCAL");
                return;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("outbound tunnel " + _config + " received a full message: " + msg
                           + " to be forwarded on to "
                           + toRouter.toBase64().substring(0,4)
                           + (toTunnel != null ? ":" + toTunnel.getTunnelId() : ""));
            if (toTunnel == null) {
                int msgtype = msg.getType();
                if (msgtype == DatabaseStoreMessage.MESSAGE_TYPE) {
                    DatabaseStoreMessage dsm = (DatabaseStoreMessage)msg;
                    if (dsm.getEntry().isRouterInfo()) {
                        _ridsm++;
                        _context.statManager().addRateData("tunnel.outboundTunnelEndpointFwdRIDSM", 1);
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("OBEP directly forwarding RI DSM (count: "
                                      + _ridsm + "/" + _totalmsg + ") from tunnel id "
                                      + _config.getReceiveTunnelId()
                                      + " to router "
                                      + toRouter.toBase64().substring(0,4)
                                      + " with message: " + dsm);
                    } else {
                        _lsdsm++;
                        if (_log.shouldLog(Log.INFO))
                            _log.info("OBEP directly forwarding LS DSM (count: "
                                      + _lsdsm + "/" + _totalmsg + ") from tunnel id "
                                      + _config.getReceiveTunnelId()
                                      + " to router "
                                      + toRouter.toBase64().substring(0,4)
                                      + " with message: " + dsm);
                    }
                } else {
                    _i2npmsg++;
                    if (_log.shouldLog(Log.INFO))
                        _log.info("OBEP directly forwarding I2NP Message (count: "
                                  + _i2npmsg + "/" + _totalmsg + ") from tunnel id "
                                  + _config.getReceiveTunnelId()
                                  + " to router "
                                  + toRouter.toBase64().substring(0,4)
                                  + " with message: " + msg);
                }
            }
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
            _outDistributor.distribute(msg, toRouter, toTunnel);
        }
    }

    /** @since 0.9.8 */
    @Override
    public String toString() {
        return "OBEP " + _config.getReceiveTunnelId();
    }
}
