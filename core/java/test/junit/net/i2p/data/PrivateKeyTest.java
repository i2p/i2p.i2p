package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the Private domain 
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
 * Test harness for loading / storing PrivateKey objects
 *
 * @author jrandom
 */
public class PrivateKeyTest extends StructureTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

    public DataStructure createDataStructure() throws DataFormatException {
        PrivateKey privateKey = new PrivateKey();
        byte data[] = new byte[PrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        privateKey.setData(data);
        return privateKey; 
    }
    public DataStructure createStructureToRead() { return new PrivateKey(); }

    @Test
    public void testBase64Constructor() throws Exception{
        PrivateKey privateKey = new PrivateKey();
        byte data[] = new byte[PrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        privateKey.setData(data);

        PrivateKey key2 = new PrivateKey(privateKey.toBase64());
        assertEquals(privateKey, key2);
    }

    @Test
    public void testNullEquals(){
        PrivateKey privateKey = new PrivateKey();
        byte data[] = new byte[PrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        privateKey.setData(data);

        assertFalse(privateKey.equals(null));
    }

    @Test
    public void testNullData() throws Exception{
        PrivateKey privateKey = new PrivateKey();
        privateKey.toString();

        exception.expect(DataFormatException.class);
        exception.expectMessage("No data to write out");
        privateKey.writeBytes(new ByteArrayOutputStream());
    }

    @Test
    public void testShortData() throws Exception{
        PrivateKey privateKey = new PrivateKey();
        byte data[] = new byte[56];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i);

        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("Bad data length: 56; required: " + PrivateKey.KEYSIZE_BYTES);
        privateKey.setData(data);
    }

    @Test
    public void testShortRead() throws Exception{
        PrivateKey privateKey = new PrivateKey();
        ByteArrayInputStream in = new ByteArrayInputStream(DataHelper.getASCII("six times nine equals forty-two"));

        exception.expect(EOFException.class);
        exception.expectMessage("EOF after reading 31 bytes of " + PrivateKey.KEYSIZE_BYTES + " byte value");
        privateKey.readBytes(in);
    }
}
