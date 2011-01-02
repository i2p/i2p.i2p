package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.util.RandomSource;
import net.i2p.util.SimpleByteCache;

/**
 *  32 bytes, usually of random data.
 *  Changed from ByteArray to SimpleDataStructure in 0.8.2.
 */
public class SessionTag extends SimpleDataStructure {
    public final static int BYTE_LENGTH = 32;

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
        }
    }

    public SessionTag(byte val[]) {
        super(val);
    }
    
    public int length() {
        return BYTE_LENGTH;
    }
}
