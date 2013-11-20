package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.InputStream;
import java.io.IOException;

import net.i2p.crypto.SigType;

/**
 * Defines the SigningPublicKey as defined by the I2P data structure spec.
 * A signing public key is 128 byte Integer. The public key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 * This key varies from the PrivateKey in its usage (verifying signatures, not encrypting)
 *
 * @author jrandom
 */
public class SigningPublicKey extends SimpleDataStructure {
    private static final SigType DEF_TYPE = SigType.DSA_SHA1;
    public final static int KEYSIZE_BYTES = DEF_TYPE.getPubkeyLen();
    private static final int CACHE_SIZE = 1024;

    private static final SDSCache<SigningPublicKey> _cache = new SDSCache<SigningPublicKey>(SigningPublicKey.class, KEYSIZE_BYTES, CACHE_SIZE);

    private final SigType _type;

    /**
     * Pull from cache or return new.
     * Deprecated - used only by deprecated Destination.readBytes(data, off)
     *
     * @throws AIOOBE if not enough bytes, FIXME should throw DataFormatException
     * @since 0.8.3
     */
    public static SigningPublicKey create(byte[] data, int off) {
        return _cache.get(data, off);
    }

    /**
     * Pull from cache or return new
     * @since 0.8.3
     */
    public static SigningPublicKey create(InputStream in) throws IOException {
        return _cache.get(in);
    }

    public SigningPublicKey() {
        this(DEF_TYPE);
    }

    /**
     *  @since 0.9.8
     */
    public SigningPublicKey(SigType type) {
        super();
        _type = type;
    }

    public SigningPublicKey(byte data[]) {
        this(DEF_TYPE, data);
    }

    /**
     *  @since 0.9.8
     */
    public SigningPublicKey(SigType type, byte data[]) {
        super();
        _type = type;
        setData(data);
    }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of SigningPublicKey
     */
    public SigningPublicKey(String base64Data)  throws DataFormatException {
        this();
        fromBase64(base64Data);
    }

    public int length() {
        return _type.getPubkeyLen();
    }

    /**
     *  @since 0.9.8
     */
    public SigType getType() {
        return _type;
    }

    /**
     *  @since 0.9.8
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append('[').append(getClass().getSimpleName()).append(' ').append(_type).append(": ");
        if (_data == null) {
            buf.append("null");
        } else {
            buf.append("size: ").append(Integer.toString(length()));
        }
        buf.append(']');
        return buf.toString();
    }
}
