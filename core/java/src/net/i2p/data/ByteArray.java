package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.Serializable;

/**
 * Wrap up an array of bytes so that they can be compared and placed in hashes,
 * maps, and the like.
 *
 * @author jrandom
 */
public class ByteArray implements Serializable {
    private byte[] _data;

    public ByteArray() {
        this(null);
    }

    public ByteArray(byte[] data) {
        _data = data;
    }

    public byte[] getData() {
        return _data;
    }

    public void setData(byte[] data) {
        _data = data;
    }

    public boolean equals(Object o) {
        if (o == null) return false;
        if (o instanceof ByteArray) {
            return compare(getData(), ((ByteArray) o).getData());
        }

        try {
            byte val[] = (byte[]) o;
            return compare(getData(), val);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean compare(byte[] lhs, byte[] rhs) {
        return DataHelper.eq(lhs, rhs);
    }

    public int hashCode() {
        return DataHelper.hashCode(getData());
    }

    public String toString() {
        return DataHelper.toString(getData(), 32);
    }
}