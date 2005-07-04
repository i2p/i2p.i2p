package net.i2p.crypto;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

/**
 * Calculate the HMAC-SHA256 of a key+message.  All the good stuff occurs
 * in {@link org.bouncycastle.crypto.macs.HMac} and 
 * {@link org.bouncycastle.crypto.digests.SHA256Digest}.
 *
 */
public class DummyHMACSHA256Generator extends HMACSHA256Generator {
    private I2PAppContext _context;
    public DummyHMACSHA256Generator(I2PAppContext context) {
        super(context);
        _context = context;
    }
    
    public static HMACSHA256Generator getInstance() {
        return I2PAppContext.getGlobalContext().hmac();
    }
    
    /**
     * Calculate the HMAC of the data with the given key
     */
    public Hash calculate(SessionKey key, byte data[]) {
        if ((key == null) || (key.getData() == null) || (data == null))
            throw new NullPointerException("Null arguments for HMAC");
        return calculate(key, data, 0, data.length);
    }
    
    /**
     * Calculate the HMAC of the data with the given key
     */
    public Hash calculate(SessionKey key, byte data[], int offset, int length) {
        if ((key == null) || (key.getData() == null) || (data == null))
            throw new NullPointerException("Null arguments for HMAC");
        
        byte rv[] = new byte[Hash.HASH_LENGTH];
        System.arraycopy(key.getData(), 0, rv, 0, Hash.HASH_LENGTH);
        if (Hash.HASH_LENGTH >= length)
            DataHelper.xor(data, offset, rv, 0, rv, 0, length);
        else
            DataHelper.xor(data, offset, rv, 0, rv, 0, Hash.HASH_LENGTH);
        return new Hash(rv);
    }
}