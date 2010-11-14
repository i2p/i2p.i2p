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

}
