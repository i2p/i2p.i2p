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
    private int _valid;
    private int _offset;

    public ByteArray() {
    }

    /** Sets valid */
    public ByteArray(byte[] data) {
        _data = data;
        _valid = (data != null ? data.length : 0);
    }
    public ByteArray(byte[] data, int offset, int length) {
        _data = data;
        _offset = offset;
        _valid = length;
    }

    public byte[] getData() {
        return _data;
    }

    /** Warning, does not set valid */
    public void setData(byte[] data) {
        _data = data;
    }
    
    /** 
     * Count how many of the bytes in the array are 'valid'.  
     * this property does not necessarily have meaning for all byte 
     * arrays.
     */
    public int getValid() { return _valid; }
    public void setValid(int valid) { _valid = valid; }
    public int getOffset() { return _offset; }
    public void setOffset(int offset) { _offset = offset; }
    
    @Override
    public final boolean equals(Object o) {
        if (o == null) return false;
        if (o instanceof ByteArray) {
            ByteArray ba = (ByteArray)o;
            return compare(getData(), _offset, _valid, ba.getData(), ba.getOffset(), ba.getValid());
        }

        try {
            byte val[] = (byte[]) o;
            return compare(getData(), _offset, _valid, val, 0, val.length);
        } catch (Throwable t) {
            return false;
        }
    }

    private static final boolean compare(byte[] lhs, int loff, int llen, byte[] rhs, int roff, int rlen) {
        return (llen == rlen) && DataHelper.eq(lhs, loff, rhs, roff, llen);
    }
    
    public final int compareTo(Object obj) {
        if (obj.getClass() != getClass()) throw new ClassCastException("invalid object: " + obj);
        return DataHelper.compareTo(_data, ((ByteArray)obj).getData());
    }
    
    @Override
    public final int hashCode() {
        return DataHelper.hashCode(getData());
    }
    
    @Override
    public String toString() {
        return super.toString() + "/" + DataHelper.toString(getData(), 32) + "." + _valid;
    }
    
    public final String toBase64() {
        return Base64.encode(_data, _offset, _valid);
    }
}
