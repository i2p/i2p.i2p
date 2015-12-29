package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Arrays;

import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SigType;

/**
 * Defines the SigningPrivateKey as defined by the I2P data structure spec.
 * A signing private key is by default a 20 byte Integer. The private key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 * This key varies from the PrivateKey in its usage (signing, not decrypting)
 *
 * As of release 0.9.8, keys of arbitrary length and type are supported.
 * See SigType.
 *
 * @author jrandom
 */
public class SigningPrivateKey extends SimpleDataStructure {
    private static final SigType DEF_TYPE = SigType.DSA_SHA1;
    public final static int KEYSIZE_BYTES = DEF_TYPE.getPrivkeyLen();

    private final SigType _type;

    public SigningPrivateKey() {
        this(DEF_TYPE);
    }

    /**
     *  @since 0.9.8
     */
    public SigningPrivateKey(SigType type) {
        super();
        _type = type;
    }

    public SigningPrivateKey(byte data[]) {
        this(DEF_TYPE, data);
    }

    /**
     *  @since 0.9.8
     */
    public SigningPrivateKey(SigType type, byte data[]) {
        super();
        _type = type;
        setData(data);
    }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of SigningPrivateKey
     */
    public SigningPrivateKey(String base64Data)  throws DataFormatException {
        this();
        fromBase64(base64Data);
    }


    public int length() {
        return _type.getPrivkeyLen();
    }

    /**
     *  @since 0.9.8
     */
    public SigType getType() {
        return _type;
    }

    /**
     *  Converts this signing private key to its public equivalent.
     *  As of 0.9.16, supports all key types.
     *
     *  @return a SigningPublicKey object derived from this private key
     *  @throws IllegalArgumentException on bad key or unknown or unsupported type
     */
    public SigningPublicKey toPublic() {
        return KeyGenerator.getSigningPublicKey(this);
    }

    /**
     *  @since 0.9.8
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append('[').append(getClass().getSimpleName()).append(' ').append(_type).append(": ");
        int length = length();
        if (_data == null) {
            buf.append("null");
        } else if (length <= 32) {
            buf.append(toBase64());
        } else {
            buf.append("size: ").append(Integer.toString(length));
        }
        buf.append(']');
        return buf.toString();
    }

    /**
     *  @since 0.9.17
     */
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_type) ^ super.hashCode();
    }

    /**
     *  @since 0.9.17
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || !(obj instanceof SigningPrivateKey)) return false;
        SigningPrivateKey s = (SigningPrivateKey) obj;
        return _type == s._type && Arrays.equals(_data, s._data);
    }
}
