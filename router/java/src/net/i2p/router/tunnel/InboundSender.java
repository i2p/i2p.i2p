package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Receive the preprocessed data for an inbound gateway, encrypt it, and forward
 * it on to the first hop.
 *
 */
public class InboundSender implements TunnelGateway.Sender {
    private I2PAppContext _context;
    private Log _log;
    private InboundGatewayProcessor _processor;
    
    static final boolean USE_ENCRYPTION = HopProcessor.USE_ENCRYPTION;
    
    public InboundSender(I2PAppContext ctx, HopConfig config) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundSender.class);
        _processor = new InboundGatewayProcessor(_context, config);
    }
    
    public void sendPreprocessed(byte[] preprocessed, TunnelGateway.Receiver receiver) {
        if (USE_ENCRYPTION)
            _processor.process(preprocessed, 0, preprocessed.length);
        receiver.receiveEncrypted(preprocessed);
    }
}
