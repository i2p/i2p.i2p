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

/**
 * Defines the hash as defined by the I2P data structure spec.
 * A hash is the SHA-256 of some data, taking up 32 bytes.
 *
 * @author jrandom
 */
public class Hash extends SimpleDataStructure {
    private volatile String _stringified;
    private volatile String _base64ed;
    private int _cachedHashCode;

    public final static int HASH_LENGTH = 32;
    public final static Hash FAKE_HASH = new Hash(new byte[HASH_LENGTH]);
    private static final int CACHE_SIZE = 2048;
    
    private static final SDSCache<Hash> _cache = new SDSCache(Hash.class, HASH_LENGTH, CACHE_SIZE);

    /**
     * Pull from cache or return new
     * @since 0.8.3
     */
    public static Hash create(byte[] data) {
        return _cache.get(data);
    }

    /**
     * Pull from cache or return new
     * @since 0.8.3
     */
    public static Hash create(byte[] data, int off) {
        return _cache.get(data, off);
    }

    /**
     * Pull from cache or return new
     * @since 0.8.3
     */
    public static Hash create(InputStream in) throws IOException {
        return _cache.get(in);
    }

    public Hash() {
        super();
    }

    /** @throws IllegalArgumentException if data is not 32 bytes (null is ok) */
    public Hash(byte data[]) {
        super();
        setData(data);
    }

    public int length() {
        return HASH_LENGTH;
    }

    /** @throws IllegalArgumentException if data is not 32 bytes (null is ok) */
    @Override
    public void setData(byte[] data) {
        super.setData(data);
        _stringified = null;
        _base64ed = null;
        _cachedHashCode = super.hashCode();
    }

    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        super.readBytes(in);
        _stringified = null;
        _base64ed = null;
        _cachedHashCode = super.hashCode();
    }
    
    /** a Hash is a hash, so just use the first 4 bytes for speed */
    @Override
    public int hashCode() {
        return _cachedHashCode;
    }

    @Override
    public String toBase64() {
        if (_base64ed == null) {
            _base64ed = super.toBase64();
        }
        return _base64ed;
    }
}
