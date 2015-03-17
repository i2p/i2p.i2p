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
 * A session key is 32byte Integer. 
 *
 * @author jrandom
 */
public class SessionKey extends SimpleDataStructure {
    private Object _preparedKey;

    public final static int KEYSIZE_BYTES = 32;
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
     * caveat: this method isn't synchronized with the preparedKey, so don't
     * try to *change* the key data after already doing some 
     * encryption/decryption (or if you do change it, be sure this object isn't
     * mid decrypt)
     */
    @Override
    public void setData(byte[] data) {
        super.setData(data);
        _preparedKey = null;
    }
    
    /** 
     * retrieve an internal representation of the session key, as known
     * by the AES engine used.  this can be reused safely
     */
    public Object getPreparedKey() { return _preparedKey; }
    public void setPreparedKey(Object obj) { _preparedKey = obj; }
}
