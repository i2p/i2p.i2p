package net.i2p.crypto;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

/**
 * Calculate the HMAC-SHA256 of a key+message.  Currently FAKE - returns a stupid
 * kludgy hash: H(H(key) XOR H(data)).  Fix me!
 *
 */
public class HMACSHA256Generator {
    public HMACSHA256Generator(I2PAppContext context) { // nop
    }
    
    public static HMACSHA256Generator getInstance() {
        return I2PAppContext.getGlobalContext().hmac();
    }
    
    /**
     * This should calculate the HMAC/SHA256, but it DOESNT.  Its just a kludge.
     * Fix me.
     */
    public Hash calculate(SessionKey key, byte data[]) {
        if ((key == null) || (key.getData() == null) || (data == null))
            throw new NullPointerException("Null arguments for HMAC");

        Hash hkey = SHA256Generator.getInstance().calculateHash(key.getData());
        Hash hdata = SHA256Generator.getInstance().calculateHash(data);
        return SHA256Generator.getInstance().calculateHash(DataHelper.xor(hkey.getData(), hdata.getData()));
    }
}