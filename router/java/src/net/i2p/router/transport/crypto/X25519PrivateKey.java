package net.i2p.router.transport.crypto;

import java.security.PrivateKey;

import com.southernstorm.noise.crypto.Curve25519;

/**
 *  A PrivateKey we can stick in a KeyPair.
 *  Raw data is accessible via getEncoded().
 *  Also provides a toPublic() method.
 *
 *  @since 0.9.36
 */
public class X25519PrivateKey implements PrivateKey {

    private final byte[] _data;

    /**
     *  Montgomery representation, little-endian
     *  @param data 32 bytes
     *  @throws IllegalArgumentException if not 32 bytes
     */
    public X25519PrivateKey(byte[] data) {
        if (data.length != 32)
            throw new IllegalArgumentException();
        _data = data;
    }

    public X25519PublicKey toPublic() {
        byte[] pub = new byte[32];
        Curve25519.eval(pub, 0, _data, null);
        return new X25519PublicKey(pub);
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
