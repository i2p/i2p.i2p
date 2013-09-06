package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.crypto.SigType;

/**
 * Defines the signature as defined by the I2P data structure spec.
 * A signature is a 40-byte array verifying the authenticity of some data 
 * using the DSA-SHA1 algorithm.
 *
 * The signature is the 20-byte R followed by the 20-byte S,
 * both are unsigned integers.
 *
 * @author jrandom
 */
public class Signature extends SimpleDataStructure {
    private static final SigType DEF_TYPE = SigType.DSA_SHA1;
    /** 40 */
    public final static int SIGNATURE_BYTES = DEF_TYPE.getSigLen();
    /** all zeros */
    public final static byte[] FAKE_SIGNATURE = new byte[SIGNATURE_BYTES];

    private final SigType _type;

    public Signature() {
        this(DEF_TYPE);
    }

    /**
     *  @since 0.9.8
     */
    public Signature(SigType type) {
        super();
        _type = type;
    }

    public Signature(byte data[]) {
        this(DEF_TYPE, data);
    }

    /**
     *  @since 0.9.8
     */
    public Signature(SigType type, byte data[]) {
        super();
        _type = type;
        setData(data);
    }

    public int length() {
        return _type.getSigLen();
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
}
