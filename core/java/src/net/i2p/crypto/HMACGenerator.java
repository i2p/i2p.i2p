package net.i2p.crypto;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.SimpleByteCache;

/**
 * Calculate the HMAC of a key+message.
 *
 * As of 0.9.42, this is just a stub.
 * See net.i2p.router.transport.udp.SSUHMACGenerator for
 * the HMAC used in SSU (what was originally this class),
 * and SHA256Generator for the HMAC used in Syndie.
 *
 */
public abstract class HMACGenerator {
    
    public HMACGenerator() {}
    
    /**
     * Calculate the HMAC of the data with the given key
     *
     * @param target out parameter the first 16 bytes contain the HMAC, the last 16 bytes are zero
     * @param targetOffset offset into target to put the hmac
     * @throws IllegalArgumentException for bad key or target too small
     */
    public abstract void calculate(SessionKey key, byte data[], int offset, int length, byte target[], int targetOffset);
    
    /**
     * Verify the MAC inline, reducing some unnecessary memory churn.
     *
     * @param key session key to verify the MAC with
     * @param curData MAC to verify
     * @param curOffset index into curData to MAC
     * @param curLength how much data in curData do we want to run the HMAC over
     * @param origMAC what do we expect the MAC of curData to equal
     * @param origMACOffset index into origMAC
     * @param origMACLength how much of the MAC do we want to verify
     * @throws IllegalArgumentException for bad key
     */
    public abstract boolean verify(SessionKey key, byte curData[], int curOffset, int curLength,
                                   byte origMAC[], int origMACOffset, int origMACLength);
    

    /**
     * 32 bytes from the byte array cache.
     * Does NOT zero.
     */
    protected byte[] acquireTmp() {
        byte rv[] = SimpleByteCache.acquire(Hash.HASH_LENGTH);
        return rv;
    }

    protected void releaseTmp(byte tmp[]) {
        SimpleByteCache.release(tmp);
    }
}
