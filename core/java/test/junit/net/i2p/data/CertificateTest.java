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
public class CertificateTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        Certificate cert = new Certificate();
        byte data[] = new byte[32];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        cert.setPayload(data);
        cert.setCertificateType(Certificate.CERTIFICATE_TYPE_NULL);
        return cert; 
    }
    public DataStructure createStructureToRead() { return new Certificate(); }
}
