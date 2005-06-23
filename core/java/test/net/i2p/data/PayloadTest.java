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
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;

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
 
 public class PayloadTest extends StructureTest{
    
    public DataStructure createDataStructure() throws DataFormatException {
        Payload payload = new Payload();
        SessionKey key = (SessionKey)(new SessionKeyTest()).createDataStructure();
        
        byte data[] = "Hello, I2P".getBytes();
        payload.setUnencryptedData(data);
        Hash hash = (Hash)(new HashTest()).createDataStructure();
        
        Destination target = (Destination)(new DestinationTest()).createDataStructure();
    payload.setEncryptedData(data);
    
        return payload; 
    }
    public DataStructure createStructureToRead() { return new Payload(); }
    
    public void testStructure() throws Exception{
        byte[] temp = null;
        
        DataStructure orig;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        orig = createDataStructure();
        orig.writeBytes(baos);
        
        
        temp = baos.toByteArray();
        
        DataStructure ds;
        ByteArrayInputStream bais = new ByteArrayInputStream(temp);
        
        ds = createStructureToRead();
        ds.readBytes(bais);
        Payload payload = (Payload)ds;
        payload.setUnencryptedData(payload.getEncryptedData());
        
        assertEquals(orig, ds);
    }
    
}