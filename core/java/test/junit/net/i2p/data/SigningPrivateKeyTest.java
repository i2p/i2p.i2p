package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */
 
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 * Test harness for loading / storing SigningPrivateKey objects
 *
 * @author jrandom
 */
public class SigningPrivateKeyTest extends StructureTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

    public DataStructure createDataStructure() throws DataFormatException {
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        byte data[] = new byte[SigningPrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        signingPrivateKey.setData(data);
        return signingPrivateKey; 
    }
    public DataStructure createStructureToRead() { return new SigningPrivateKey(); }

    @Test
    public void testBase64Constructor() throws Exception{
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        byte data[] = new byte[SigningPrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        signingPrivateKey.setData(data);

        SigningPrivateKey key2 = new SigningPrivateKey(signingPrivateKey.toBase64());
        assertEquals(signingPrivateKey, key2);
    }

    @Test
    public void testNullEquals(){
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        byte data[] = new byte[SigningPrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        signingPrivateKey.setData(data);

        assertFalse(signingPrivateKey.equals(null));
    }

    @Test
    public void testNullData() throws Exception{
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        signingPrivateKey.toString();

        exception.expect(DataFormatException.class);
        exception.expectMessage("No data to write out");
        signingPrivateKey.writeBytes(new ByteArrayOutputStream());
    }

    @Test
    public void testShortData() throws Exception{
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        byte data[] = new byte[56];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i);

        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("Bad data length: 56; required: " + SigningPrivateKey.KEYSIZE_BYTES);
        signingPrivateKey.setData(data);
        signingPrivateKey.writeBytes(new ByteArrayOutputStream());
    }

    @Test
    public void testShortRead() throws Exception{
        SigningPrivateKey signingPrivateKey = new SigningPrivateKey();
        ByteArrayInputStream in = new ByteArrayInputStream(DataHelper.getASCII("short"));

        exception.expect(EOFException.class);
        exception.expectMessage("EOF after reading 5 bytes of " + SigningPrivateKey.KEYSIZE_BYTES + " byte value");
        signingPrivateKey.readBytes(in);
    }
}
