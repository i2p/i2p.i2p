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
 */
public class ByteArray implements Serializable, Comparable {
    private byte[] _data;

    public ByteArray() {
        this(null);
    }

    public ByteArray(byte[] data) {
        _data = data;
    }

    public final byte[] getData() {
        return _data;
    }

    public void setData(byte[] data) {
        _data = data;
    }

    public final boolean equals(Object o) {
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

    private static final boolean compare(byte[] lhs, byte[] rhs) {
        return DataHelper.eq(lhs, rhs);
    }
    
    public final int compareTo(Object obj) {
        if (obj.getClass() != getClass()) throw new ClassCastException("invalid object: " + obj);
        return DataHelper.compareTo(_data, ((ByteArray)obj).getData());
    }

    public final int hashCode() {
        return DataHelper.hashCode(getData());
    }

    public final String toString() {
        return DataHelper.toString(getData(), 32);
    }
}