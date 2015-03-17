package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Test harness for loading / storing PublicKey objects
 *
 * @author jrandom
 */
public class PublicKeyTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        PublicKey publicKey = new PublicKey();
        byte data[] = new byte[PublicKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        publicKey.setData(data);
        return publicKey; 
    }
    public DataStructure createStructureToRead() { return new PublicKey(); }
    
    public void testBase64Constructor() throws Exception{
        PublicKey publicKey = new PublicKey();
        byte data[] = new byte[PublicKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        publicKey.setData(data);
        
        PublicKey key2 = new PublicKey(publicKey.toBase64());
        assertEquals(publicKey, key2);
    }
    
    public void testNullEquals(){
        PublicKey publicKey = new PublicKey();
        byte data[] = new byte[PublicKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        publicKey.setData(data);
        
        assertFalse(publicKey.equals(null));
    }
    
    public void testNullData() throws Exception{
        PublicKey publicKey = new PublicKey();
        publicKey.toString();
        
        boolean error = false;
        try{
            publicKey.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }
    
    public void testShortData() throws Exception{
        PublicKey publicKey = new PublicKey();
        byte data[] = new byte[56];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i);
        
        boolean error = false;
        try{
            publicKey.setData(data);
            publicKey.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }catch(IllegalArgumentException exc) {
            error = true;
        }
        assertTrue(error);
    }
    
    public void testShortRead() throws Exception{
        PublicKey publicKey = new PublicKey();
        ByteArrayInputStream in = new ByteArrayInputStream("six times nine equals forty-two".getBytes());
        boolean error = false;
        try{
            publicKey.readBytes(in);
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }
}
