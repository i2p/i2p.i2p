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
 * Defines the hash as defined by the I2P data structure spec.
 * AA hash is the SHA-256 of some data, taking up 32 bytes.
 *
 * @author jrandom
 */
public class Hash extends DataStructureImpl {
    private final static Log _log = new Log(Hash.class);
    private byte[] _data;
    private volatile String _stringified;

    public final static int HASH_LENGTH = 32;
    public final static Hash FAKE_HASH = new Hash(new byte[HASH_LENGTH]);

    public Hash() {
        setData(null);
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
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _data = new byte[HASH_LENGTH];
        _stringified = null;
        int read = read(in, _data);
        if (read != HASH_LENGTH) throw new DataFormatException("Not enough bytes to read the hash");
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_data == null) throw new DataFormatException("No data in the hash to write out");
        if (_data.length != HASH_LENGTH) throw new DataFormatException("Invalid size of data in the private key");
        out.write(_data);
    }

    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof Hash)) return false;
        return DataHelper.eq(_data, ((Hash) obj)._data);
    }

    public int hashCode() {
        return DataHelper.hashCode(_data);
    }

    public String toString() {
        if (_stringified == null) {
            StringBuffer buf = new StringBuffer(64);
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
}