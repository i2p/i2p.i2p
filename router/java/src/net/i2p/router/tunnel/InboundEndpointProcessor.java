package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Receive the inbound tunnel message, removing all of the layers
 * added by earlier hops to recover the preprocessed data sent
 * by the gateway.  This delegates the crypto to the 
 * OutboundGatewayProcessor, since the tunnel creator does the 
 * same thing in both instances.
 *
 */
public class InboundEndpointProcessor {
    private I2PAppContext _context;
    private Log _log;
    private TunnelCreatorConfig _config;
    private IVValidator _validator;
    
    public InboundEndpointProcessor(I2PAppContext ctx, TunnelCreatorConfig cfg) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundEndpointProcessor.class);
        _config = cfg;
        _validator = DummyValidator.getInstance();
    }
    
    /**
     * Undo all of the encryption done by the peers in the tunnel, recovering the
     * preprocessed data sent by the gateway.  
     *
     * @return true if the data was recovered (and written in place to orig), false
     *         if it was a duplicate or from the wrong peer.
     */
    public boolean retrievePreprocessedData(byte orig[], int offset, int length, Hash prev) {
        Hash last = _config.getPeer(_config.getLength()-1);
        if (!last.equals(prev)) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid previous peer - attempted hostile loop?  from " + prev 
                           + ", expected " + last);
            return false;
        }
        
        byte iv[] = new byte[HopProcessor.IV_LENGTH];
        System.arraycopy(orig, offset, iv, 0, iv.length);
        boolean ok = _validator.receiveIV(iv);
        if (!ok) {
            if (_log.shouldLog(Log.WARN)) 
                _log.warn("Invalid IV received");
            return false;
        }
        
        // inbound endpoints and outbound gateways have to undo the crypto in the same way
        OutboundGatewayProcessor.decrypt(_context, _config, iv, orig, offset, length);
        return true;
    }
}
