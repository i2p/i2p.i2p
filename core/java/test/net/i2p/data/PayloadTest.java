package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Payload;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;

/**
 * Test harness for loading / storing Payload objects
 *
 * @author jrandom
 */
class PayloadTest extends StructureTest {
    private final static Log _log = new Log(PayloadTest.class);

    static {
        TestData.registerTest(new PayloadTest(), "Payload");
    }
    public DataStructure createDataStructure() throws DataFormatException {
        Payload payload = new Payload();
        SessionKey key = (SessionKey)(new SessionKeyTest()).createDataStructure();
        //payload.setEncryptionKey(key);
        byte data[] = "Hello, I2P".getBytes();
        payload.setUnencryptedData(data);
        Hash hash = (Hash)(new HashTest()).createDataStructure();
        //payload.setHash(hash);
        Destination target = (Destination)(new DestinationTest()).createDataStructure();
	payload.setEncryptedData(data);
        //payload.encryptPayload(target, 128);
        return payload; 
    }
    public DataStructure createStructureToRead() { return new Payload(); }
    
    public String testData(InputStream inputStream) {
        try {
            DataStructure structure = createStructureToRead();
            structure.readBytes(inputStream);
	    Payload payload = (Payload)structure;
	    payload.setUnencryptedData(payload.getEncryptedData());
            //((Payload)structure).decryptPayload((PrivateKey)(new PrivateKeyTest()).createDataStructure());
            return structure.toString();
        } catch (DataFormatException dfe) {
            _log.error("Error reading the data structure", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error reading the data structure", ioe);
            return null;
        }
    }
    
}
