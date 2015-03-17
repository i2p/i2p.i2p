package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the Private domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */
 
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Test harness for loading / storing PrivateKey objects
 *
 * @author jrandom
 */
public class PrivateKeyTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        PrivateKey privateKey = new PrivateKey();
        byte data[] = new byte[PrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        privateKey.setData(data);
        return privateKey; 
    }
    public DataStructure createStructureToRead() { return new PrivateKey(); }
    
    public void testBase64Constructor() throws Exception{
        PrivateKey privateKey = new PrivateKey();
        byte data[] = new byte[PrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        privateKey.setData(data);
        
        PrivateKey key2 = new PrivateKey(privateKey.toBase64());
        assertEquals(privateKey, key2);
    }
    
    public void testNullEquals(){
        PrivateKey privateKey = new PrivateKey();
        byte data[] = new byte[PrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        privateKey.setData(data);
        
        assertFalse(privateKey.equals(null));
    }
    
    public void testNullData() throws Exception{
        PrivateKey privateKey = new PrivateKey();
        privateKey.toString();
        
        boolean error = false;
        try{
            privateKey.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }
    
    public void testShortData() throws Exception{
        PrivateKey privateKey = new PrivateKey();
        byte data[] = new byte[56];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i);
        
        boolean error = false;
        try{
            privateKey.setData(data);
            privateKey.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }catch(IllegalArgumentException exc) {
            error = true;
        }
        assertTrue(error);
    }
    
    public void testShortRead() throws Exception{
        PrivateKey privateKey = new PrivateKey();
        ByteArrayInputStream in = new ByteArrayInputStream("six times nine equals forty-two".getBytes());
        boolean error = false;
        try{
            privateKey.readBytes(in);
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }
    
}
