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
 * Test harness for loading / storing Payload objects
 *
 * @author jrandom
 */
 
 public class PayloadTest extends StructureTest{
    
    public DataStructure createDataStructure() throws DataFormatException {
        Payload payload = new Payload();
        SessionKey key = (SessionKey)(new SessionKeyTest()).createDataStructure();
        
        byte data[] = DataHelper.getASCII("Hello, I2P");
        // This causes equals() to fail unless we override the test
        // to set the unencrypted data after reading.
        // Unencrypted data is deprecated, just use encrypted data for the test.
        //payload.setUnencryptedData(data);
        Hash hash = (Hash)(new HashTest()).createDataStructure();
        
        Destination target = (Destination)(new DestinationTest()).createDataStructure();
        payload.setEncryptedData(data);
    
        return payload; 
    }
    public DataStructure createStructureToRead() { return new Payload(); }
    
}
