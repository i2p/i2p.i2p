package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.RouterContext;

/**
 * Same as PTG, but check to see if a message should be dropped before queueing it.
 * Used for IBGWs.
 *
 * @since 0.7.9
 */
class ThrottledPumpedTunnelGateway extends PumpedTunnelGateway {
    /** saved so we can note messages that get dropped */
    private final HopConfig _config;
    
    public ThrottledPumpedTunnelGateway(RouterContext context, QueuePreprocessor preprocessor, Sender sender,
                                        Receiver receiver, TunnelGatewayPumper pumper, HopConfig config) {
        super(context, preprocessor, sender, receiver, pumper);
        _config = config;
    }
    
    /**
     * Possibly drop a message due to bandwidth before adding it to the preprocessor queue.
     * We do this here instead of in the InboundGatewayReceiver because it is much smarter to drop
     * whole I2NP messages, where we know the message type and length, rather than
     * tunnel messages containing I2NP fragments.
     */
    @Override
    public void add(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
        //_log.error("IBGW count: " + _config.getProcessedMessagesCount() + " type: " + msg.getType() + " size: " + msg.getMessageSize());

        // Hard to do this exactly, but we'll assume 2:1 batching
        // for the purpose of estimating outgoing size.
        // We assume that it's the outbound bandwidth that is the issue...
        int size = Math.max(msg.getMessageSize(), 1024/2);
        if (_context.tunnelDispatcher().shouldDropParticipatingMessage("IBGW " + msg.getType(), size)) {
            // this overstates the stat somewhat, but ok for now
            int kb = (size + 1023) / 1024;
            for (int i = 0; i < kb; i++)
                _config.incrementProcessedMessages();
            return;
        }
        super.add(msg, toRouter,toTunnel);
    }
}
