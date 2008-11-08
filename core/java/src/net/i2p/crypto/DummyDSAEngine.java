package net.i2p.crypto;

import net.i2p.I2PAppContext;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

/**
 * Stub that offers no authentication.
 *
 */
public class DummyDSAEngine extends DSAEngine {
    public DummyDSAEngine(I2PAppContext context) {
        super(context);
    }
    
    @Override
    public boolean verifySignature(Signature signature, byte signedData[], SigningPublicKey verifyingKey) {
        return true;
    }
    
    @Override
    public Signature sign(byte data[], SigningPrivateKey signingKey) {
        Signature sig = new Signature();
        sig.setData(Signature.FAKE_SIGNATURE);
        return sig;
    }
}