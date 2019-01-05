package net.i2p.crypto.eddsa;

/**
 * Utilities for Blinding EdDSA keys.
 * PRELIMINARY - Subject to change - see proposal 123
 *
 * @since 0.9.38
 */
public final class EdDSABlinding {

    private EdDSABlinding() {}

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param h hash of secret data, same length as this key
     *  @throws UnsupportedOperationException unless supported
     */
    public static EdDSAPublicKey blind(EdDSAPublicKey key, byte[] h) {
        // TODO, test only
        return key;
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param h hash of secret data, same length as this key
     *  @throws UnsupportedOperationException unless supported
     */
    public static EdDSAPrivateKey blind(EdDSAPrivateKey key, byte[] h) {
        // TODO, test only
        return key;
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519.
     *
     *  @param key must be SigType EdDSA_SHA512_Ed25519
     *  @param h hash of secret data, same length as this key
     *  @throws UnsupportedOperationException unless supported
     */
    public static EdDSAPrivateKey unblind(EdDSAPrivateKey key, byte[] h) {
        // TODO, test only
        return key;
    }
}
