package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.SigningPublicKey;

/**
 * Test harness for loading / storing PublicKey objects
 *
 * @author jrandom
 */
class SigningPublicKeyTest extends StructureTest {
    static {
        TestData.registerTest(new SigningPublicKeyTest(), "SigningPublicKey");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        SigningPublicKey publicKey = new SigningPublicKey();
        byte data[] = new byte[SigningPublicKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        publicKey.setData(data);
        return publicKey; 
    }
    public DataStructure createStructureToRead() { return new SigningPublicKey(); }
}
