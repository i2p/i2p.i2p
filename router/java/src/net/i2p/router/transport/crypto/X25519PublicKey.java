package net.i2p.router.transport.crypto;

import java.security.PublicKey;

/**
 *  A PublicKey we can stick in a KeyPair.
 *  Raw data is accessible via getEncoded().
 *
 *  @since 0.9.36
 */
public class X25519PublicKey implements PublicKey {

    private final byte[] _data;

    /**
     *  Montgomery representation, little-endian
     *  @param data 32 bytes
     *  @throws IllegalArgumentException if not 32 bytes
     */
    public X25519PublicKey(byte[] data) {
        if (data.length != 32)
            throw new IllegalArgumentException();
        _data = data;
    }

    /**
     *  The raw byte array, there is no encoding.
     *  @return the data passed in
     */
    public byte[] getEncoded() {
        return _data;
    }

    public String getAlgorithm() {
        return "X25519";
    }

    public String getFormat() {
        return "raw";
    }
}
