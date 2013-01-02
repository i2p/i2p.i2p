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

import net.i2p.util.RandomSource;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SipHash;

/**
 *  32 bytes, usually of random data.
 *  Changed from ByteArray to SimpleDataStructure in 0.8.2.
 */
public class SessionTag extends SimpleDataStructure {
    public final static int BYTE_LENGTH = 32;
    private int _cachedHashCode;

    public SessionTag() {
        super();
    }

    /**
     *  @param create if true, instantiate the data array and fill it with random data.
     */
    public SessionTag(boolean create) {
        super();
        if (create) {
            _data = SimpleByteCache.acquire(BYTE_LENGTH);
            RandomSource.getInstance().nextBytes(_data);
            _cachedHashCode = SipHash.hashCode(_data);
        }
    }

    public SessionTag(byte val[]) {
        super(val);
    }
    
    public int length() {
        return BYTE_LENGTH;
    }

    @Override
    public void setData(byte[] data) {
        super.setData(data);
        _cachedHashCode = SipHash.hashCode(data);
    }

    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        super.readBytes(in);
        _cachedHashCode = SipHash.hashCode(_data);
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

}
