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

import net.i2p.data.Base64;
import net.i2p.util.RandomSource;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SipHash;

/**
 *  32 bytes, usually of random data.
 *
 *  Not recommended for external use, subject to change.
 *
 *  As of 0.9.44, does NOT extend SimpleDataStructure, to save space
 */
public class SessionTag {
    public final static int BYTE_LENGTH = 32;
    private final int _cachedHashCode;
    private final byte[] _data;

    /**
     *  Instantiate the data array and fill it with random data.
     */
    public SessionTag() {
        _data = SimpleByteCache.acquire(BYTE_LENGTH);
        RandomSource.getInstance().nextBytes(_data);
        _cachedHashCode = SipHash.hashCode(_data);
    }

    /**
     *  Instantiate the data array and fill it with random data.
     *  @param create ignored as of 0.9.44, assumed true
     */
    public SessionTag(boolean create) {
        this();
    }

    /**
     *  @param val as of 0.9.44, non-null
     */
    public SessionTag(byte val[]) {
        if (val.length != BYTE_LENGTH)
            throw new IllegalArgumentException();
        _data = val;
        _cachedHashCode = SipHash.hashCode(val);
    }
    
    public byte[] getData() {
        return _data;
    }
    
    public int length() {
        return BYTE_LENGTH;
    }

    /**
     *  SessionTags are generated both locally and by peers, in quantity,
     *  and are used as keys in several datastructures (see TransientSessionKeyManager),
     *  so we use a secure hashCode function.
     */
    @Override
    public int hashCode() {
        return _cachedHashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ((obj == null) || !(obj instanceof SessionTag)) return false;
        return Arrays.equals(_data, ((SessionTag) obj)._data);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[SessionTag: ");
        if (_data == null) {
            buf.append("null");
        } else {
            buf.append(Base64.encode(_data));
        }
        buf.append(']');
        return buf.toString();
    }
}
