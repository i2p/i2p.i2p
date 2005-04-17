package net.i2p.crypto;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;

/**
 * Calculate the HMAC-SHA256 of a key+message.  All the good stuff occurs
 * in {@link org.bouncycastle.crypto.macs.HMac} and 
 * {@link org.bouncycastle.crypto.digests.SHA256Digest}.
 *
 */
public class HMACSHA256Generator {
    private I2PAppContext _context;
    private List _available;
    public HMACSHA256Generator(I2PAppContext context) {
        _context = context;
        _available = new ArrayList(32);
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
        
        HMac mac = acquire();
        mac.init(key.getData());
        mac.update(data, offset, length);
        byte rv[] = new byte[Hash.HASH_LENGTH];
        mac.doFinal(rv, 0);
        release(mac);
        return new Hash(rv);
    }
    
    private HMac acquire() {
        synchronized (_available) {
            if (_available.size() > 0)
                return (HMac)_available.remove(0);
        }
        return new HMac(new SHA256Digest());
    }
    private void release(HMac mac) {
        synchronized (_available) {
            if (_available.size() < 64)
                _available.add(mac);
        }
    }
}