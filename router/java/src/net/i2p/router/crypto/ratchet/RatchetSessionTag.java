package net.i2p.router.crypto.ratchet;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

/**
 *  8 bytes of random data.
 *  Does not extend SessionTag or DataStructure to save space
 *
 *  @since 0.9.44
 */
public class RatchetSessionTag {
    public final static int LENGTH = 8;

    private final long _data;

    public RatchetSessionTag(long val) {
        _data = val;
    }

    /**
     *  @param val will copy the first 8 bytes. Reference will not be kept.
     */
    public RatchetSessionTag(byte val[]) {
        if (val.length < LENGTH)
            throw new IllegalArgumentException();
        _data = DataHelper.fromLong8(val, 0);
    }
    
    /**
     *  @return data as a byte array
     */
    public byte[] getData() {
        byte[] rv = new byte[LENGTH];
        DataHelper.toLong8(rv, 0, _data);
        return rv;
    }
    
    /**
     *  @return data as a long value
     *  @since 0.9.46
     */
    public long getLong() {
        return _data;
    }
    
    public int length() {
        return LENGTH;
    }

    /** 12 chars */
    public String toBase64() {
        // for efficiency
        //return Base64.encode(getData());
        StringBuilder buf = new StringBuilder(12);
        for (int i = 58; i > 0; i -= 6) {
            buf.append(Base64.ALPHABET_I2P.charAt(((int) (_data >> i)) & 0x3f));
        }
        buf.append(Base64.ALPHABET_I2P.charAt(((int) (_data << 2)) & 0x3c));
        buf.append('=');
        return buf.toString();
    }

    /**
     * We assume the data has enough randomness in it, so use 4 bytes for speed.
     */
    @Override
    public int hashCode() {
        return (int) _data;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ((obj == null) || !(obj instanceof RatchetSessionTag)) return false;
        return _data == ((RatchetSessionTag) obj)._data;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(33);
        buf.append("[RatchetSessionTag: ");
        buf.append(toBase64());
        buf.append(']');
        return buf.toString();
    }

/****
    public static void main(String[] args) {
        // test toBase64()
        long l = net.i2p.util.RandomSource.getInstance().nextLong();
        RatchetSessionTag tag = new RatchetSessionTag(l);
        System.out.println(tag.toBase64());
        System.out.println(Base64.encode(tag.getData()));
    }
****/
}
