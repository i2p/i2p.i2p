package net.i2p.router.tunnel;

import java.util.Set;

import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.util.ConcurrentHashSet;

/** 
 * waste lots of RAM 
 *
 * @deprecated unused
 */
class HashSetIVValidator implements IVValidator {
    private final Set<ByteArray> _received;
    
    public HashSetIVValidator() {
        _received = new ConcurrentHashSet();
    }
    
    public boolean receiveIV(byte ivData[], int ivOffset, byte payload[], int payloadOffset) {
        //if (true) // foo!
        //    return true; 
        byte iv[] = new byte[HopProcessor.IV_LENGTH];
        DataHelper.xor(ivData, ivOffset, payload, payloadOffset, iv, 0, HopProcessor.IV_LENGTH);
        ByteArray ba = new ByteArray(iv);
        boolean isNew = _received.add(ba);
        return isNew;
    }
}
