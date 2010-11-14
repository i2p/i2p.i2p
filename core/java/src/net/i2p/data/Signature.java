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
 * Defines the signature as defined by the I2P data structure spec.
 * A signature is a 40byte Integer verifying the authenticity of some data 
 * using the algorithm defined in the crypto spec.
 *
 * @author jrandom
 */
public class Signature extends SimpleDataStructure {
    public final static int SIGNATURE_BYTES = 40;
    public final static byte[] FAKE_SIGNATURE = new byte[SIGNATURE_BYTES];
    static {
        for (int i = 0; i < SIGNATURE_BYTES; i++)
            FAKE_SIGNATURE[i] = 0x00;
    }

    public Signature() {
        super();
    }

    public Signature(byte data[]) {
        super(data);
    }

    public int length() {
        return SIGNATURE_BYTES;
    }
}
