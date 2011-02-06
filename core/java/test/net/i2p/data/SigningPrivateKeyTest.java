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
 * Test harness for loading / storing SigningPrivateKey objects
 *
 * @author jrandom
 */
public class SigningPrivateKeyTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        byte data[] = new byte[SigningPrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        signingPrivateKey.setData(data);
        return signingPrivateKey; 
    }
    public DataStructure createStructureToRead() { return new SigningPrivateKey(); }
    
    public void testBase64Constructor() throws Exception{
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        byte data[] = new byte[SigningPrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        signingPrivateKey.setData(data);
        
        SigningPrivateKey key2 = new SigningPrivateKey(signingPrivateKey.toBase64());
        assertEquals(signingPrivateKey, key2);
    }
    
    public void testNullEquals(){
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        byte data[] = new byte[SigningPrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        signingPrivateKey.setData(data);
        
        assertFalse(signingPrivateKey.equals(null));
    }
    
    public void testNullData() throws Exception{
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        signingPrivateKey.toString();
        
        boolean error = false;
        try{
            signingPrivateKey.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }
    
    public void testShortData() throws Exception{
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        byte data[] = new byte[56];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i);
        
        boolean error = false;
        try{
            signingPrivateKey.setData(data);
            signingPrivateKey.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }catch(IllegalArgumentException exc) {
            error = true;
        }
        assertTrue(error);
    }
    
    public void testShortRead() throws Exception{
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        ByteArrayInputStream in = new ByteArrayInputStream("short".getBytes());
        boolean error = false;
        try{
            signingPrivateKey.readBytes(in);
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }
    
    
}
