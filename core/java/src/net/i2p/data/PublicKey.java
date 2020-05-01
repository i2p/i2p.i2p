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
import java.util.Arrays;

import net.i2p.crypto.EncType;

/**
 * Defines the PublicKey as defined by the I2P data structure spec.
 * A public key is 256byte Integer. The public key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 *
 * As of release 0.9.38, keys of arbitrary length and type are supported.
 * Note: Support for keys longer than 256 bytes unimplemented.
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
     * ELGAMAL_2048 only!
     * Deprecated - used only by deprecated Destination.readBytes(data, off)
     *
     * @throws ArrayIndexOutOfBoundsException if not enough bytes, FIXME should throw DataFormatException
     * @since 0.8.3
     */
    public static PublicKey create(byte[] data, int off) {
        return _cache.get(data, off);
    }

    /**
     * Pull from cache or return new.
     * ELGAMAL_2048 only!
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
     *  Up-convert this from an untyped (type 0) PK to a typed PK based on the Key Cert given.
     *  The type of the returned key will be null if the kcert sigtype is null.
     *
     *  @throws IllegalArgumentException if this is already typed to a different type
     *  @since 0.9.42
     */
    public PublicKey toTypedKey(KeyCertificate kcert) {
        if (_data == null)
            throw new IllegalStateException();
        EncType newType = kcert.getEncType();
        if (_type == newType)
            return this;
        if (_type != EncType.ELGAMAL_2048)
            throw new IllegalArgumentException("Cannot convert " + _type + " to " + newType);
        // unknown type, keep the 256 bytes of data
        if (newType == null)
            return new PublicKey(null, _data);
        int newLen = newType.getPubkeyLen();
        if (newLen == KEYSIZE_BYTES)
            return new PublicKey(newType, _data);
        byte[] newData = new byte[newLen];
        if (newLen < KEYSIZE_BYTES) {
            // LEFT justified, padding at end
            System.arraycopy(_data, 0, newData, 0, newLen);
        } else {
            // full 256 bytes + fragment in kcert
            throw new IllegalArgumentException("TODO");
        }
        return new PublicKey(newType, newData);
    }

    /**
     *  Get the portion of this (type 0) PK that is really padding based on the Key Cert type given,
     *  if any
     *
     *  @return trailing padding length &gt; 0 or null if no padding or type is unknown
     *  @throws IllegalArgumentException if this is already typed to a different type
     *  @since 0.9.42
     */
    public byte[] getPadding(KeyCertificate kcert) {
        if (_data == null)
            throw new IllegalStateException();
        EncType newType = kcert.getEncType();
        if (_type == newType || newType == null)
            return null;
        if (_type != EncType.ELGAMAL_2048)
            throw new IllegalStateException("Cannot convert " + _type + " to " + newType);
        int newLen = newType.getPubkeyLen();
        if (newLen >= KEYSIZE_BYTES)
            return null;
        int padLen = KEYSIZE_BYTES - newLen;
        byte[] pad = new byte[padLen];
        System.arraycopy(_data, _data.length - padLen, pad, 0, padLen);
        return pad;
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
            int length = length();
            if (length <= 32)
                buf.append(toBase64());
            else
                buf.append("size: ").append(length);
        }
        buf.append(']');
        return buf.toString();
    }

    /**
     *  @since 0.9.42
     */
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_type) ^ super.hashCode();
    }

    /**
     *  @since 0.9.42
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || !(obj instanceof PublicKey)) return false;
        PublicKey s = (PublicKey) obj;
        return _type == s._type && Arrays.equals(_data, s._data);
    }
}
