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
public class DestinationTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        Destination dest = new Destination();
        StructureTest tst = new CertificateTest();
        dest.setCertificate((Certificate)tst.createDataStructure());
        tst = new PublicKeyTest();
        dest.setPublicKey((PublicKey)tst.createDataStructure());
        tst = new SigningPublicKeyTest();
        dest.setSigningPublicKey((SigningPublicKey)tst.createDataStructure());
        return dest; 
    }
    public DataStructure createStructureToRead() { return new Destination(); }
}
