package net.i2p.data.i2np;

/*
 * free (adj.): unencumbered; not under the control of others
 * No warranty of any kind, either expressed or implied.  
 */

import net.i2p.data.SimpleDataStructure;

/**
 *  ElGamal-encrypted request or response.
 *  528 bytes. Previously stored in a ByteArray.
 *  May or may not be AES layer-encrypted.
 *
 *  Note that these are layer-encrypted and layer-decrypted in-place.
 *  Do not cache.
 *
 *  @since 0.9.18
 */
public class EncryptedBuildRecord extends SimpleDataStructure {

    public final static int LENGTH = TunnelBuildMessageBase.RECORD_SIZE;

    /** @throws IllegalArgumentException if data is not correct length (null is ok) */
    public EncryptedBuildRecord(byte data[]) {
        super(data);
    }

    public int length() {
        return LENGTH;
    }
}
