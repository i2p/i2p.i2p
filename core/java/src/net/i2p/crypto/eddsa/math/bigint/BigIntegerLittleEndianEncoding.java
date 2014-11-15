package net.i2p.crypto.eddsa.math.bigint;

import java.io.Serializable;
import java.math.BigInteger;

import net.i2p.crypto.eddsa.math.Encoding;
import net.i2p.crypto.eddsa.math.Field;
import net.i2p.crypto.eddsa.math.FieldElement;

public class BigIntegerLittleEndianEncoding extends Encoding implements Serializable {
    private static final long serialVersionUID = 3984579843759837L;
    /**
     * Mask where only the first b-1 bits are set.
     */
    private BigInteger mask;

    @Override
    public void setField(Field f) {
        super.setField(f);
        mask = BigInteger.ONE.shiftLeft(f.getb()-1).subtract(BigInteger.ONE);
    }

    public byte[] encode(FieldElement x) {
        return encode(((BigIntegerFieldElement)x).bi.and(mask));
    }

    /**
     *  Convert x to little endian.
     *  Constant time.
     *
     *  @return array of length b/8
     */
    public byte[] encode(BigInteger x) {
        byte[] in = x.toByteArray();
        byte[] out = new byte[f.getb()/8];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[in.length-1-i];
        }
        for (int i = in.length; i < out.length; i++) {
            out[i] = 0;
        }
        return out;
    }

    public FieldElement decode(byte[] in) {
        if (in.length != f.getb()/8)
            throw new IllegalArgumentException("Not a valid encoding");
        return new BigIntegerFieldElement(f, toBigInteger(in).and(mask));
    }

    /**
     *  Convert in to big endian
     */
    public BigInteger toBigInteger(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[in.length-1-i];
        }
        return new BigInteger(1, out);
    }

    /**
     * From the Ed25519 paper:<br>
     * x is negative if the (b-1)-bit encoding of x is lexicographically larger
     * than the (b-1)-bit encoding of -x. If q is an odd prime and the encoding
     * is the little-endian representation of {0, 1,..., q-1} then the negative
     * elements of F_q are {1, 3, 5,..., q-2}.
     * @return true if negative
     */
    public boolean isNegative(FieldElement x) {
        return ((BigIntegerFieldElement)x).bi.testBit(0);
    }
}
