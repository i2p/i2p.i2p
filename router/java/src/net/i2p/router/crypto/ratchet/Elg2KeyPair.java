package net.i2p.router.crypto.ratchet;

import net.i2p.crypto.KeyPair;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;

/**
 *  X25519 keys, with the public key Elligator2 encoding pre-calculated
 *
 *  @since 0.9.44
 */
public class Elg2KeyPair extends KeyPair {

    private final byte[] encoded;

    public Elg2KeyPair(PublicKey publicKey, PrivateKey privateKey, byte[] enc) {
        super(publicKey, privateKey);
        encoded = enc;
    }

    public byte[] getEncoded() {
        return encoded;
    }
}
