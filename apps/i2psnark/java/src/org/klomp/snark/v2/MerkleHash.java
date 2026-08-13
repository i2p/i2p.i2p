package org.klomp.snark.v2;

import java.util.Arrays;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

/**
 * Same as net.i2p.data.Hash, but without all the superclasses
 * and other legacy stuff, to save space.
 *
 * @since 0.9.71
 */
public class MerkleHash {

    public final static int HASH_LENGTH = 32;
    public final static MerkleHash FAKE_HASH = new MerkleHash(new byte[HASH_LENGTH]);

    private final byte[] _data;

    /**
     * @throws IllegalArgumentException if data is not 32 bytes
     */
    public MerkleHash(byte data[]) {
        if (data == null || data.length != HASH_LENGTH)
            throw new IllegalArgumentException();
        _data = data;
    }

    public byte[] getData() {
        return _data;
    }

    public int length() {
        return HASH_LENGTH;
    }

    public String toBase64() {
        return Base64.encode(_data);
    }

    @Override
    public String toString() {
        return "MerkleHash: " + toBase64();
    }

    @Override
    public int hashCode() {
        return (int) DataHelper.fromLong(_data, 0, 4);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ((obj == null) || !(obj instanceof MerkleHash)) return false;
        return Arrays.equals(_data, ((MerkleHash) obj)._data);
    }
}
