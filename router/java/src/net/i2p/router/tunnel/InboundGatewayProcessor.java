package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Override the hop processor to seed the message with a random
 * IV.
 */
public class InboundGatewayProcessor extends HopProcessor {
    public InboundGatewayProcessor(I2PAppContext ctx, HopConfig config) {
        super(ctx, config);
    }

    /** we are the gateway, no need to validate the IV */
    protected IVValidator createValidator() { 
        return DummyValidator.getInstance();
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
