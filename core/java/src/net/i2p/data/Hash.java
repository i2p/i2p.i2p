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

/**
 * Defines the hash as defined by the I2P data structure spec.
 * A hash is the SHA-256 of some data, taking up 32 bytes.
 *
 * @author jrandom
 */
public class Hash extends DataStructureImpl {
    private byte[] _data;
    private volatile String _stringified;
    private volatile String _base64ed;
    private int _cachedHashCode;

    public final static int HASH_LENGTH = 32;
    public final static Hash FAKE_HASH = new Hash(new byte[HASH_LENGTH]);
    
    public Hash() {
    }

    public Hash(byte data[]) {
        setData(data);
    }

    public byte[] getData() {
        return _data;
    }

    public void setData(byte[] data) {
        _data = data;
        _stringified = null;
        _base64ed = null;
        _cachedHashCode = calcHashCode();
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _data = new byte[HASH_LENGTH];
        _stringified = null;
        _base64ed = null;
        int read = read(in, _data);
        if (read != HASH_LENGTH) throw new DataFormatException("Not enough bytes to read the hash");
        _cachedHashCode = calcHashCode();
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_data == null) throw new DataFormatException("No data in the hash to write out");
        if (_data.length != HASH_LENGTH) throw new DataFormatException("Invalid size of data in the hash");
        out.write(_data);
    }
    
    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof Hash)) return false;
        return DataHelper.eq(_data, ((Hash) obj)._data);
    }
    
    /** a Hash is a hash, so just use the first 4 bytes for speed */
    @Override
    public int hashCode() {
        return _cachedHashCode;
    }

    /** a Hash is a hash, so just use the first 4 bytes for speed */
    private int calcHashCode() {
        int rv = 0;
        if (_data != null) {
            for (int i = 0; i < 4; i++)
                rv ^= (_data[i] << (i*8));
        }
        return rv;
    }
    
    @Override
    public String toString() {
        if (_stringified == null) {
            StringBuilder buf = new StringBuilder(64);
            buf.append("[Hash: ");
            if (_data == null) {
                buf.append("null hash");
            } else {
                buf.append(toBase64());
            }
            buf.append("]");
            _stringified = buf.toString();
        }
        return _stringified;
    }
    
    @Override
    public String toBase64() {
        if (_base64ed == null) {
            _base64ed = super.toBase64();
        }
        return _base64ed;
    }
}
