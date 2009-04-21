package net.i2p.router.tunnel;

import java.util.HashSet;

import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;

/** 
 * waste lots of RAM 
 */
class HashSetIVValidator implements IVValidator {
    private final HashSet _received;
    
    public HashSetIVValidator() {
        _received = new HashSet();
    }
    
    public boolean receiveIV(byte ivData[], int ivOffset, byte payload[], int payloadOffset) {
        //if (true) // foo!
        //    return true; 
        byte iv[] = new byte[HopProcessor.IV_LENGTH];
        DataHelper.xor(ivData, ivOffset, payload, payloadOffset, iv, 0, HopProcessor.IV_LENGTH);
        ByteArray ba = new ByteArray(iv);
        boolean isNew = false;
        synchronized (_received) {
            isNew = _received.add(ba);
        }
        return isNew;
    }
}