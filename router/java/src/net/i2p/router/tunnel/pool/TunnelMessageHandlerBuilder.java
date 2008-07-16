package net.i2p.router.tunnel.pool;

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;

/**
 *
 */
public class TunnelMessageHandlerBuilder implements HandlerJobBuilder {
    private RouterContext _context;
    
    public TunnelMessageHandlerBuilder(RouterContext ctx) {
        _context = ctx;
    }
    
    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        if ( (fromHash == null) && (from != null) )
            fromHash = from.calculateHash();
        return new HandleJob(_context, receivedMessage, fromHash);
    }
    
    private class HandleJob extends JobImpl {
        private I2NPMessage _msg;
        private Hash _from;
        public HandleJob(RouterContext ctx, I2NPMessage msg, Hash from) {
            super(ctx);
            _msg = msg;
            _from = from;
        }
        public void runJob() {
            if (_msg instanceof TunnelGatewayMessage) {
                getContext().tunnelDispatcher().dispatch((TunnelGatewayMessage)_msg);
            } else if (_msg instanceof TunnelDataMessage) {
                getContext().tunnelDispatcher().dispatch((TunnelDataMessage)_msg, _from);
            }
        }
        
        public String getName() { return "Dispatch tunnel message"; }
    }
 
}
