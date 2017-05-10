package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Defines the SessionKey as defined by the I2P data structure spec.
 * A session key is a 32 byte Integer. 
 *
 * To create one with random data, use I2PAppContext.keyGenerator().generateSessionKey().
 *
 * @author jrandom
 */
public class SessionKey extends SimpleDataStructure {
    private Object _preparedKey;

    public final static int KEYSIZE_BYTES = 32;
    /** A key with all zeroes in the data */
    public static final SessionKey INVALID_KEY = new SessionKey(new byte[KEYSIZE_BYTES]);

    public SessionKey() {
        super();
    } 

    public SessionKey(byte data[]) {
        super(data);
    }

    public int length() {
        return KEYSIZE_BYTES;
    }

    /**
     * Sets the data.
     * @param data 32 bytes, or null
     * @throws IllegalArgumentException if data is not the legal number of bytes (but null is ok)
     * @throws RuntimeException if data already set.
     */
    @Override
    public void setData(byte[] data) {
        super.setData(data);
    }
    
    /** 
     * retrieve an internal representation of the session key, as known
     * by the AES engine used.  this can be reused safely
     */
    public Object getPreparedKey() { return _preparedKey; }
    public void setPreparedKey(Object obj) { _preparedKey = obj; }
}
