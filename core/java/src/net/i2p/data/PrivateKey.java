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
import javax.security.auth.Destroyable;

import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.util.SimpleByteCache;

/**
 * Defines the PrivateKey as defined by the I2P data structure spec.
 * A private key is 256byte Integer. The private key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 *
 * As of release 0.9.38, keys of arbitrary length and type are supported.
 * See EncType.
 *
 * @author jrandom
 */
public class PrivateKey extends SimpleDataStructure implements Destroyable {
    private static final EncType DEF_TYPE = EncType.ELGAMAL_2048;
    public final static int KEYSIZE_BYTES = DEF_TYPE.getPrivkeyLen();

    private final EncType _type;

    public PrivateKey() {
        this(DEF_TYPE);
    }

    /**
     *  @param type non-null
     *  @since 0.9.38
     */
    public PrivateKey(EncType type) {
        super();
        _type = type;
    }

    public PrivateKey(byte data[]) {
        this(DEF_TYPE, data);
    }

    /**
     *  @param type non-null
     *  @param data must be non-null
     *  @since 0.9.38
     */
    public PrivateKey(EncType type, byte data[]) {
        this(type);
        if (data == null)
            throw new IllegalArgumentException("Data must be specified");
        setData(data);
    }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of PrivateKey
     */
    public PrivateKey(String base64Data) throws DataFormatException {
        this(DEF_TYPE);
        fromBase64(base64Data);
    }

    public int length() {
        return _type.getPrivkeyLen();
    }

    /**
     *  @return non-null
     *  @since 0.9.38
     */
    public EncType getType() {
        return _type;
    }

    /** derives a new PublicKey object derived from the secret contents
     * of this PrivateKey
     * @return a PublicKey object
     * @throws IllegalArgumentException on bad key
     */
    public PublicKey toPublic() {
        return KeyGenerator.getPublicKey(this);
    }

    /**
     *  javax.security.auth.Destroyable interface
     *
     *  @since 0.9.40
     */
    public void destroy() {
        byte[] data = _data;
        if (data != null) {
            _data = null;
            Arrays.fill(data, (byte) 0);
            SimpleByteCache.release(data);
        }
    }

    /**
     *  javax.security.auth.Destroyable interface
     *
     *  @since 0.9.40
     */
    public boolean isDestroyed() {
        return _data == null;
    }

    /**
     *  @since 0.9.38
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[PrivateKey ").append(_type).append(' ');
        int length = length();
        if (_data == null) {
            buf.append("null");
        } else {
            buf.append("size: ").append(length);
        }
        buf.append(']');
        return buf.toString();
    }

    /**
     * We assume the data has enough randomness in it, so use the last 4 bytes for speed.
     * Overridden since we use short exponents, so the first 227 bytes are all zero.
     */
    @Override
    public int hashCode() {
        if (_data == null)
            return 0;
        if (_type != DEF_TYPE)
            return DataHelper.hashCode(_data);
        int rv = _data[KEYSIZE_BYTES - 4];
        for (int i = 1; i < 4; i++)
            rv ^= (_data[i + (KEYSIZE_BYTES - 4)] << (i*8));
        return rv;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ((obj == null) || !(obj instanceof PrivateKey)) return false;
        PrivateKey p = (PrivateKey) obj;
        return _type == p._type && Arrays.equals(_data, p._data);
    }
}
