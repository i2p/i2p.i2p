package net.i2p.router.crypto.ratchet;

import java.util.Arrays;

import net.i2p.data.Base64;

/**
 *  8 bytes, usually of random data.
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

    public RatchetSessionTag(byte val[]) {
        if (val.length != LENGTH)
            throw new IllegalArgumentException();
        _data = RatchetPayload.fromLong8(val, 0);
    }
    
    public byte[] getData() {
        byte[] rv = new byte[LENGTH];
        RatchetPayload.toLong8(rv, 0, _data);
        return rv;
    }
    
    public int length() {
        return LENGTH;
    }

    public String toBase64() {
        return Base64.encode(getData());
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
        StringBuilder buf = new StringBuilder(64);
        buf.append("[RatchetSessionTag: ");
        buf.append(toBase64());
        buf.append(']');
        return buf.toString();
    }
}
