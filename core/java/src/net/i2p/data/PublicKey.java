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
 * Defines the PublicKey as defined by the I2P data structure spec.
 * A public key is 256byte Integer. The public key represents only the 
 * exponent, not the primes, which are constant and defined in the crypto spec.
 *
 * @author jrandom
 */
public class PublicKey extends SimpleDataStructure {
    public final static int KEYSIZE_BYTES = 256;

    public PublicKey() {
        super();
    }

    /** @param data must be non-null */
    public PublicKey(byte data[]) {
        super();
        if (data == null)
            throw new IllegalArgumentException("Data must be specified");
        _data = data;
    }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of PublicKey
     */
    public PublicKey(String base64Data)  throws DataFormatException {
        super();
        fromBase64(base64Data);
    }
    
    public int length() {
        return KEYSIZE_BYTES;
    }
}
