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
import net.i2p.data.PrivateKey;

/**
 * Test harness for loading / storing PrivateKey objects
 *
 * @author jrandom
 */
class PrivateKeyTest extends StructureTest {
    static {
        TestData.registerTest(new PrivateKeyTest(), "PrivateKey");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        PrivateKey privateKey = new PrivateKey();
        byte data[] = new byte[PrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        privateKey.setData(data);
        return privateKey; 
    }
    public DataStructure createStructureToRead() { return new PrivateKey(); }
}
