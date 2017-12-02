package net.i2p.data;

/*
 * Public domain
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import net.i2p.crypto.SHA256Generator;

/**
 * A SimpleDataStructure contains only a single fixed-length byte array.
 * The main reason to do this is to override
 * toByteArray() and fromByteArray(), which are used by toBase64(), fromBase64(),
 * and calculateHash() in DataStructureImpl - otherwise these would go through
 * a wasteful array-to-stream-to-array pass.
 * It also centralizes a lot of common code.
 *
 * Implemented in 0.8.2 and retrofitted over several of the classes in this package.
 *
 * As of 0.8.3, SDS objects may be cached. An SDS may be instantiated with null data,
 * and setData(null) is also OK. However,
 * once non-null data is set, the data reference is immutable;
 * subsequent attempts to set the data via setData(), readBytes(),
 * fromByteArray(), or fromBase64() will throw a RuntimeException.
 *
 * @since 0.8.2
 * @author zzz
 */
public abstract class SimpleDataStructure extends DataStructureImpl {
    protected byte[] _data;
    
    /** A new instance with the data set to null. Call readBytes(), setData(), or fromByteArray() after this to set the data */
    public SimpleDataStructure() {
    }
    
    /** @throws IllegalArgumentException if data is not the legal number of bytes (but null is ok) */
    public SimpleDataStructure(byte data[]) {
        setData(data);
    }

    /**
     * The legal length of the byte array in this data structure
     * @since 0.8.2
     */
    abstract public int length();

    /**
     * Get the data reference (not a copy)
     * @return the byte array, or null if unset
     */
    public byte[] getData() {
        return _data;
    }

    /**
     * Sets the data.
     * @param data of correct length, or null
     * @throws IllegalArgumentException if data is not the legal number of bytes (but null is ok)
     * @throws RuntimeException if data already set.
     */
    public void setData(byte[] data) {
        if (_data != null)
            throw new RuntimeException("Data already set");
        if (data != null && data.length != length())
            throw new IllegalArgumentException("Bad data length: " + data.length + "; required: " + length());
        _data = data;
    }

    /**
     * Sets the data.
     * @param in the stream to read
     * @throws RuntimeException if data already set.
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_data != null)
            throw new RuntimeException("Data already set");
        int length = length();
        _data = new byte[length];
        // Throws on incomplete read
        read(in, _data);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_data == null) throw new DataFormatException("No data to write out");
        out.write(_data);
    }
    
    @Override
    public String toBase64() {
        if (_data == null)
            return null;
        return Base64.encode(_data);
    }

    /**
     * Sets the data.
     * @throws DataFormatException if decoded data is not the legal number of bytes or on decoding error
     * @throws RuntimeException if data already set.
     */
    @Override
    public void fromBase64(String data) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        byte[] d = Base64.decode(data);
        if (d == null)
            throw new DataFormatException("Bad Base64 encoded data");
        if (d.length != length())
            throw new DataFormatException("Bad decoded data length, expected " + length() + " got " + d.length);
        // call setData() instead of _data = data in case overridden
        setData(d);
    }

    /** @return the SHA256 hash of the byte array, or null if the data is null */
    @Override
    public Hash calculateHash() {
        if (_data != null) return SHA256Generator.getInstance().calculateHash(_data);
        return null;
    }

    /**
     * Overridden for efficiency.
     * @return same thing as getData()
     */
    @Override
    public byte[] toByteArray() {
        return _data;
    }

    /**
     * Overridden for efficiency.
     * Does the same thing as setData() but null not allowed.
     * @param data non-null
     * @throws DataFormatException if null or wrong length
     * @throws RuntimeException if data already set.
     */
    @Override
    public void fromByteArray(byte data[]) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        if (data.length != length())
            throw new DataFormatException("Bad data length: " + data.length + "; required: " + length());
        // call setData() instead of _data = data in case overridden
        setData(data);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append('[').append(getClass().getSimpleName()).append(": ");
        int length = length();
        if (_data == null) {
            buf.append("null");
        } else if (length <= 32) {
            buf.append(toBase64());
        } else {
            buf.append("size: ").append(Integer.toString(length));
        }
        buf.append(']');
        return buf.toString();
    }

    /**
     * We assume the data has enough randomness in it, so use the first 4 bytes for speed.
     * If this is not the case, override in the extending class.
     */
    @Override
    public int hashCode() {
        if (_data == null)
            return 0;
        int rv = _data[0];
        for (int i = 1; i < 4; i++)
            rv ^= (_data[i] << (i*8));
        return rv;
    }

    /**
     * Warning - this returns true for two different classes with the same size
     * and same data, e.g. SessionKey and SessionTag, but you wouldn't
     * put them in the same Set, would you?
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ((obj == null) || !(obj instanceof SimpleDataStructure)) return false;
        return Arrays.equals(_data, ((SimpleDataStructure) obj)._data);
    }
}
