package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;

/**
 * Override the hop processor to seed the message with a random
 * IV.
 */
class InboundGatewayProcessor extends HopProcessor {
    public InboundGatewayProcessor(I2PAppContext ctx, HopConfig config) {
        super(ctx, config, DummyValidator.getInstance());
    }

    /**
     * Since we are the inbound gateway, use the IV given to us as the first 
     * 16 bytes, ignore the 'prev' hop, and encrypt the message like every 
     * other participant.
     *
     */
    public void process(byte orig[], int offset, int length) {
        boolean ok = super.process(orig, offset, length, null);
        if (!ok) 
            throw new RuntimeException("wtf, we are the gateway, how did it fail?");
    }
}
