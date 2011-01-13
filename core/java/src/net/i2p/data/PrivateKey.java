package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.crypto.KeyGenerator;

/**
 * Defines the PrivateKey as defined by the I2P data structure spec.
 * A private key is 256byte Integer. The private key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 *
 * Note that we use short exponents, so all but the last 28.25 bytes are zero.
 * See http://www.i2p2.i2p/how_cryptography for details.
 *
 * @author jrandom
 */
public class PrivateKey extends SimpleDataStructure {
    public final static int KEYSIZE_BYTES = 256;

    public PrivateKey() {
        super();
    }

    public PrivateKey(byte data[]) {
        super(data);
    }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of PrivateKey
     */
    public PrivateKey(String base64Data) throws DataFormatException {
        super();
        fromBase64(base64Data);
    }

    public int length() {
        return KEYSIZE_BYTES;
    }

    /** derives a new PublicKey object derived from the secret contents
     * of this PrivateKey
     * @return a PublicKey object
     */
    public PublicKey toPublic() {
        return KeyGenerator.getPublicKey(this);
    }

    /**
     * We assume the data has enough randomness in it, so use the last 4 bytes for speed.
     * Overridden since we use short exponents, so the first 227 bytes are all zero.
     * Not that we are storing PrivateKeys in any Sets or Maps anywhere.
     */
    @Override
    public int hashCode() {
        if (_data == null)
            return 0;
        int rv = _data[KEYSIZE_BYTES - 4];
        for (int i = 1; i < 4; i++)
            rv ^= (_data[i + (KEYSIZE_BYTES - 4)] << (i*8));
        return rv;
    }

    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof PrivateKey)) return false;
        return DataHelper.eq(_data, ((PrivateKey) obj)._data);
    }
}
