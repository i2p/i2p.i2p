package net.i2p.crypto;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

/**
 * Calculate the HMAC-SHA256 of a key+message.  Currently FAKE - returns a stupid
 * kludgy hash: H(H(key) XOR H(data)).  Fix me!
 *
 */
public abstract class HMACSHA256Generator {
    private static HMACSHA256Generator _generator = new DummyHMACSHA256Generator();

    public static HMACSHA256Generator getInstance() {
        return _generator;
    }

    public abstract Hash calculate(SessionKey key, byte data[]);
}

/**
 * jrandom smells.  
 *
 */

class DummyHMACSHA256Generator extends HMACSHA256Generator {
    public Hash calculate(SessionKey key, byte data[]) {
        if ((key == null) || (key.getData() == null) || (data == null))
            throw new NullPointerException("Null arguments for HMAC");

        Hash hkey = SHA256Generator.getInstance().calculateHash(key.getData());
        Hash hdata = SHA256Generator.getInstance().calculateHash(data);
        return SHA256Generator.getInstance().calculateHash(DataHelper.xor(hkey.getData(), hdata.getData()));
    }
}