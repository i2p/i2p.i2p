package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.util.Log;

/**
 * Receive the preprocessed data for an outbound gateway, encrypt all of the 
 * layers, and forward it on to the first hop.
 *
 */
public class OutboundSender implements TunnelGateway.Sender {
    private I2PAppContext _context;
    private Log _log;
    private OutboundGatewayProcessor _processor;
    
    static final boolean USE_ENCRYPTION = HopProcessor.USE_ENCRYPTION;
    
    public OutboundSender(I2PAppContext ctx, TunnelCreatorConfig config) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundSender.class);
        _processor = new OutboundGatewayProcessor(_context, config);
    }
    
    public void sendPreprocessed(byte[] preprocessed, TunnelGateway.Receiver receiver) {
        if (USE_ENCRYPTION)
            _processor.process(preprocessed, 0, preprocessed.length);
        receiver.receiveEncrypted(preprocessed);
    }
}
