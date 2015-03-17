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
 * Defines the SigningPrivateKey as defined by the I2P data structure spec.
 * A signing private key is 20 byte Integer. The private key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 * This key varies from the PrivateKey in its usage (signing, not decrypting)
 *
 * @author jrandom
 */
public class SigningPrivateKey extends SimpleDataStructure {
    public final static int KEYSIZE_BYTES = 20;

    public SigningPrivateKey() {
        super();
    }

    public SigningPrivateKey(byte data[]) {
        super(data);
    }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of SigningPrivateKey
     */
    public SigningPrivateKey(String base64Data)  throws DataFormatException {
        super();
        fromBase64(base64Data);
    }

    public int length() {
        return KEYSIZE_BYTES;
    }

    /** converts this signing private key to its public equivalent
     * @return a SigningPublicKey object derived from this private key
     */
    public SigningPublicKey toPublic() {
        return KeyGenerator.getSigningPublicKey(this);
    }
}
