package net.i2p.router.tunnel;

import java.util.HashSet;
import net.i2p.data.ByteArray;

/** 
 * waste lots of RAM 
 */
class HashSetIVValidator implements IVValidator {
    private HashSet _received;
    
    public HashSetIVValidator() {
        _received = new HashSet();
    }
    public boolean receiveIV(byte[] iv) {
        //if (true) // foo!
        //    return true; 
        ByteArray ba = new ByteArray(iv);
        boolean isNew = false;
        synchronized (_received) {
            isNew = _received.add(ba);
        }
        return isNew;
    }
}