package net.i2p.crypto;

import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;

/**
 * Same as java.security.KeyPair, but with I2P keys
 *
 * @since 0.9.38
 */
public class KeyPair {

    private final PublicKey pub;
    private final PrivateKey priv;

    /**
     * @param publicKey non-null, same EncType as privateKey
     * @param privateKey non-null, same EncType as publicKey
     */
    public KeyPair(PublicKey publicKey, PrivateKey privateKey) {
        pub = publicKey;
        priv = privateKey;
        if (pub.getType() != priv.getType())
            throw new IllegalArgumentException();
    }

    public PublicKey getPublic() {
        return pub;
    }

    public PrivateKey getPrivate() {
        return priv;
    }
}
