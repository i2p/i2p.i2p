package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.util.Log;

/**
 * Defines the SessionKey as defined by the I2P data structure spec.
 * A session key is 32byte Integer. 
 *
 * @author jrandom
 */
public class SessionKey extends DataStructureImpl {
    private final static Log _log = new Log(SessionKey.class);
    private byte[] _data;
    private Object _preparedKey;

    public final static int KEYSIZE_BYTES = 32;
    public static final SessionKey INVALID_KEY = new SessionKey(new byte[KEYSIZE_BYTES]);

    public SessionKey() {
        this(null);
    } 
    public SessionKey(byte data[]) {
        setData(data);
    }

    public byte[] getData() {
        return _data;
    }

    /**
     * caveat: this method isn't synchronized with the preparedKey, so don't
     * try to *change* the key data after already doing some 
     * encryption/decryption (or if you do change it, be sure this object isn't
     * mid decrypt)
     */
    public void setData(byte[] data) {
        _data = data;
        _preparedKey = null;
    }
    
    /** 
     * retrieve an internal representation of the session key, as known
     * by the AES engine used.  this can be reused safely
     */
    public Object getPreparedKey() { return _preparedKey; }
    public void setPreparedKey(Object obj) { _preparedKey = obj; }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _data = new byte[KEYSIZE_BYTES];
        int read = read(in, _data);
        if (read != KEYSIZE_BYTES) throw new DataFormatException("Not enough bytes to read the session key");
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_data == null) throw new DataFormatException("No data in the session key to write out");
        if (_data.length != KEYSIZE_BYTES) throw new DataFormatException("Invalid size of data in the private key");
        out.write(_data);
    }
    
    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof SessionKey)) return false;
        return DataHelper.eq(_data, ((SessionKey) obj)._data);
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_data);
    }

    @Override
    public String toString() { 
        if (true) return super.toString(); 
        StringBuilder buf = new StringBuilder(64);
        buf.append("[SessionKey: ");
        if (_data == null) {
            buf.append("null key");
        } else {
            buf.append("size: ").append(_data.length);
            //int len = 32;
            //if (len > _data.length) len = _data.length;
            //buf.append(" first ").append(len).append(" bytes: ");
            //buf.append(DataHelper.toString(_data, len));
        }
        buf.append("]");
        return buf.toString();
    }
}