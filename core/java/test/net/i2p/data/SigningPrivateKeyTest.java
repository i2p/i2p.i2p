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
import net.i2p.data.SigningPrivateKey;

/**
 * Test harness for loading / storing SigningPrivateKey objects
 *
 * @author jrandom
 */
class SigningPrivateKeyTest extends StructureTest {
    static {
        TestData.registerTest(new SigningPrivateKeyTest(), "SigningPrivateKey");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        SigningPrivateKey privateKey = new SigningPrivateKey();
        byte data[] = new byte[SigningPrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        privateKey.setData(data);
        return privateKey; 
    }
    public DataStructure createStructureToRead() { return new SigningPrivateKey(); }
}
