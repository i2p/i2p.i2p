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
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class HashTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        Hash hash = new Hash();
        byte data[] = new byte[32];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        hash.setData(data);
        return hash; 
    }
    public DataStructure createStructureToRead() { return new Hash(); }
}
