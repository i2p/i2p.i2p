package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;

/**
 * Receive the preprocessed data for an inbound gateway, encrypt it, and forward
 * it on to the first hop.
 *
 */
class InboundSender implements TunnelGateway.Sender {
    private final InboundGatewayProcessor _processor;
    
    static final boolean USE_ENCRYPTION = HopProcessor.USE_ENCRYPTION;
    
    public InboundSender(I2PAppContext ctx, HopConfig config) {
        _processor = new InboundGatewayProcessor(ctx, config);
    }
    
    public long sendPreprocessed(byte[] preprocessed, TunnelGateway.Receiver receiver) {
        if (USE_ENCRYPTION)
            _processor.process(preprocessed, 0, preprocessed.length);
        return receiver.receiveEncrypted(preprocessed);
    }
}
