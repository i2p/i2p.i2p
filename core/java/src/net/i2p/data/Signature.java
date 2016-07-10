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

import net.i2p.crypto.SigType;

/**
 * Defines the signature as defined by the I2P data structure spec.
 * By default, a signature is a 40-byte array verifying the authenticity of some data 
 * using the DSA-SHA1 algorithm.
 *
 * The signature is the 20-byte R followed by the 20-byte S,
 * both are unsigned integers.
 *
 * As of release 0.9.8, signatures of arbitrary length and type are supported.
 * See SigType.
 *
 * @author jrandom
 */
public class Signature extends SimpleDataStructure {
    private static final SigType DEF_TYPE = SigType.DSA_SHA1;
    /** 40 */
    public final static int SIGNATURE_BYTES = DEF_TYPE.getSigLen();

    /**
     * all zeros
     * @deprecated to be removed
     */
    @Deprecated
    public final static byte[] FAKE_SIGNATURE = new byte[SIGNATURE_BYTES];

    private final SigType _type;

    public Signature() {
        this(DEF_TYPE);
    }

    /**
     *  Unknown type not allowed as we won't know the length to read in the data.
     *
     *  @param type non-null
     *  @since 0.9.8
     */
    public Signature(SigType type) {
        super();
        if (type == null)
            throw new IllegalArgumentException("unknown type");
        _type = type;
    }

    public Signature(byte data[]) {
        this(DEF_TYPE, data);
    }

    /**
     *  Should we allow an unknown type here?
     *
     *  @param type non-null
     *  @since 0.9.8
     */
    public Signature(SigType type, byte data[]) {
        super();
        if (type == null)
            throw new IllegalArgumentException("unknown type");
        _type = type;
        setData(data);
    }

    public int length() {
        return _type.getSigLen();
    }

    /**
     *  @return non-null
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
        if (obj == null || !(obj instanceof Signature)) return false;
        Signature s = (Signature) obj;
        return _type == s._type && Arrays.equals(_data, s._data);
    }
}
