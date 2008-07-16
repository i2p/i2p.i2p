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
 * Test harness for loading / storing Signature objects
 *
 * @author jrandom
 */
public class SignatureTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        Signature sig = new Signature();
        byte data[] = new byte[Signature.SIGNATURE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        sig.setData(data);
        return sig; 
    }
    public DataStructure createStructureToRead() { return new Signature(); }
}
