package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Serve as the gatekeeper for a tunnel with no hops, either inbound or outbound.
 *
 */
class TunnelGatewayZeroHop extends TunnelGateway {
    private final TunnelCreatorConfig _config;
    private OutboundMessageDistributor _outDistributor;
    private InboundMessageDistributor _inDistributor;
    
    /**
     *
     */
    public TunnelGatewayZeroHop(RouterContext context, TunnelCreatorConfig config) {
        super(context, null, null, null);
        _config = config;
        if (config.isInbound())
            _inDistributor = new InboundMessageDistributor(context, config.getDestination());
        else
            _outDistributor = new OutboundMessageDistributor(context, 400);
    }
    
    /**
     * Add a message to be sent down the tunnel, where we are the inbound gateway.
     *
     * @param msg message received to be sent through the tunnel
     */
    @Override
    public void add(TunnelGatewayMessage msg) {
        add(msg.getMessage(), null, null);
    }
    
    /**
     * Add a message to be sent down the tunnel (immediately forwarding it to the
     * {@link InboundMessageDistributor} or {@link OutboundMessageDistributor}, as
     * necessary).
     *
     * @param msg message to be sent through the tunnel
     * @param toRouter router to send to after the endpoint (or null for endpoint processing)
     * @param toTunnel tunnel to send to after the endpoint (or null for endpoint or router processing)
     */
    @Override
    public void add(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("zero hop gateway: distribute " + (_config.isInbound() ? "inbound " : " outbound ")
                       + " to " + (toRouter != null ? toRouter.toBase64().substring(0,4) : "" )
                       + "." + (toTunnel != null ? toTunnel.getTunnelId() + "" : "")
                       + ": " + msg);
        if (_config.isInbound()) {
            _inDistributor.distribute(msg, toRouter, toTunnel);
        } else {
            _outDistributor.distribute(msg, toRouter, toTunnel);
        }
       _config.incrementProcessedMessages();
    }
}
