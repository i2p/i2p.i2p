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

import net.i2p.crypto.EncType;

/**
 * Defines the PublicKey as defined by the I2P data structure spec.
 * A public key is 256byte Integer. The public key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 *
 * As of release 0.9.38, keys of arbitrary length and type are supported.
 * See EncType.
 *
 * @author jrandom
 */
public class PublicKey extends SimpleDataStructure {
    private static final EncType DEF_TYPE = EncType.ELGAMAL_2048;
    public final static int KEYSIZE_BYTES = DEF_TYPE.getPubkeyLen();
    private static final int CACHE_SIZE = 1024;

    private static final SDSCache<PublicKey> _cache = new SDSCache<PublicKey>(PublicKey.class, KEYSIZE_BYTES, CACHE_SIZE);

    private final EncType _type;
    private final int _unknownTypeCode;

    /**
     * Pull from cache or return new.
     * Deprecated - used only by deprecated Destination.readBytes(data, off)
     *
     * @throws ArrayIndexOutOfBoundsException if not enough bytes, FIXME should throw DataFormatException
     * @since 0.8.3
     */
    public static PublicKey create(byte[] data, int off) {
        return _cache.get(data, off);
    }

    /**
     * Pull from cache or return new
     * @since 0.8.3
     */
    public static PublicKey create(InputStream in) throws IOException {
        return _cache.get(in);
    }

    public PublicKey() {
        this(DEF_TYPE);
    }

    /**
     *  @param type if null, type is unknown
     *  @since 0.9.38
     */
    public PublicKey(EncType type) {
        super();
        _type = type;
        _unknownTypeCode = (type != null) ? type.getCode() : -1;
    }

    /** @param data must be non-null */
    public PublicKey(byte data[]) {
        this(DEF_TYPE, data);
    }

    /**
     *  @param type if null, type is unknown
     *  @param data must be non-null
     *  @since 0.9.38
     */
    public PublicKey(EncType type, byte data[]) {
        this(type);
        if (data == null)
            throw new IllegalArgumentException("Data must be specified");
        setData(data);
    }

    /**
     *  Unknown type only.
     *  @param typeCode must not match a known type. 1-255
     *  @param data must be non-null
     *  @since 0.9.38
     */
    public PublicKey(int typeCode, byte data[]) {
        _type = null;
        if (data == null)
            throw new IllegalArgumentException("Data must be specified");
        _data = data;
        if (typeCode <= 0 || typeCode > 255)
            throw new IllegalArgumentException();
        _unknownTypeCode = typeCode;
    }

    /**
     * Constructs from base64. ElGamal only.
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of PublicKey
     */
    public PublicKey(String base64Data)  throws DataFormatException {
        this(DEF_TYPE);
        fromBase64(base64Data);
    }
    
    public int length() {
        if (_type != null)
            return _type.getPubkeyLen();
        if (_data != null)
            return _data.length;
        return KEYSIZE_BYTES;
    }

    /**
     *  @return null if unknown
     *  @since 0.9.38
     */
    public EncType getType() {
        return _type;
    }

    /**
     *  Only valid if getType() returns null
     *  @since 0.9.38
     */
    public int getUnknownTypeCode() {
        return _unknownTypeCode;
    }

    /**
     *  @since 0.9.17
     */
    public static void clearCache() {
        _cache.clear();
    }

    /**
     *  @since 0.9.38
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[PublicKey ").append((_type != null) ? _type.toString() : "unknown type: " + _unknownTypeCode).append(' ');
        if (_data == null) {
            buf.append("null");
        } else {
            buf.append("size: ").append(length());
        }
        buf.append(']');
        return buf.toString();
    }
}
