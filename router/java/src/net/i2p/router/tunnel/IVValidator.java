package net.i2p.router.tunnel;

import java.util.HashSet;
import net.i2p.data.ByteArray;

/**
 * Provide a generic interface for IV validation which may be implemented
 * through something as simple as a hashtable or more a complicated 
 * bloom filter.
 *
 */
public interface IVValidator {
    /** 
     * receive the IV for the tunnel, returning true if it is valid,
     * or false if it has already been used (or is otherwise invalid).
     *
     */
    public boolean receiveIV(byte iv[]);
}

/** accept everything */
class DummyValidator implements IVValidator {
    private static final DummyValidator _instance = new DummyValidator();
    public static DummyValidator getInstance() { return _instance; }
    private DummyValidator() {}
    
    public boolean receiveIV(byte[] iv) { return true; }
}

/** waste lots of RAM */
class HashSetIVValidator implements IVValidator {
    private HashSet _received;
    
    public HashSetIVValidator() {
        _received = new HashSet();
    }
    public boolean receiveIV(byte[] iv) {
        ByteArray ba = new ByteArray(iv);
        boolean isNew = false;
        synchronized (_received) {
            isNew = _received.add(ba);
        }
        return isNew;
    }
}