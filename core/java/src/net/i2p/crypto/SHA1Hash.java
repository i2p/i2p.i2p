package net.i2p.crypto;

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

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;

/**
 * Because DSAEngine was abusing Hash for 20-byte hashes
 *
 * @since 0.8.1
 * @author zzz
 */
public class SHA1Hash extends DataStructureImpl {
    private byte[] _data;
    private int _cachedHashCode;

    public final static int HASH_LENGTH = SHA1.HASH_LENGTH;
    
    /** @throws IllegalArgumentException if data is not 20 bytes (null is ok) */
    public SHA1Hash(byte data[]) {
        setData(data);
    }

    public byte[] getData() {
        return _data;
    }

    /** @throws IllegalArgumentException if data is not 20 bytes (null is ok) */
    public void setData(byte[] data) {
        // FIXME DSAEngine uses a SHA-1 "Hash" as parameters and return values!
        if (data != null && data.length != HASH_LENGTH)
            throw new IllegalArgumentException("Hash must be 20 bytes");
        _data = data;
        _cachedHashCode = calcHashCode();
    }

    /** @throws IOException always */
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        throw new IOException("unimplemented");
    }
    
    /** @throws IOException always */
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        throw new IOException("unimplemented");
    }

    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof SHA1Hash)) return false;
        return DataHelper.eq(_data, ((SHA1Hash) obj)._data);
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
}
