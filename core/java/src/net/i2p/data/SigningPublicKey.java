package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Defines the SigningPublicKey as defined by the I2P data structure spec.
 * A public key is 256byte Integer. The public key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 * This key varies from the PrivateKey in its usage (verifying signatures, not encrypting)
 *
 * @author jrandom
 */
public class SigningPublicKey extends SimpleDataStructure {
    public final static int KEYSIZE_BYTES = 128;

    public SigningPublicKey() {
        super();
    }

    public SigningPublicKey(byte data[]) {
        super(data);
    }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of SigningPublicKey
     */
    public SigningPublicKey(String base64Data)  throws DataFormatException {
        super();
        fromBase64(base64Data);
    }

    public int length() {
        return KEYSIZE_BYTES;
    }
}
