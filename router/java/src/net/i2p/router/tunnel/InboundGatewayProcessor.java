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
     * Since we are the inbound gateway, pick a random IV, ignore the 'prev'
     * hop, and encrypt the message like every other participant.
     *
     */
    public boolean process(byte orig[], int offset, int length, Hash prev) {
        byte iv[] = new byte[IV_LENGTH];
        _context.random().nextBytes(iv);
        System.arraycopy(iv, 0, orig, offset, IV_LENGTH);
        
        return super.process(orig, offset, length, null);
    }
}
