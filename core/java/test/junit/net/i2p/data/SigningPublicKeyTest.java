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

import org.junit.Test;

/**
 * Test harness for loading / storing PublicKey objects
 *
 * @author jrandom
 */
public class SigningPublicKeyTest extends StructureTest {

    public DataStructure createDataStructure() throws DataFormatException {
        SigningPublicKey publicKey = new SigningPublicKey();
        byte data[] = new byte[SigningPublicKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        publicKey.setData(data);
        return publicKey;
    }
    public DataStructure createStructureToRead() { return new SigningPublicKey(); }

    @Test
    public void testBase64Constructor() throws Exception{
        SigningPublicKey publicKey = new SigningPublicKey();
        byte data[] = new byte[SigningPublicKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        publicKey.setData(data);

        SigningPublicKey key2 = new SigningPublicKey(publicKey.toBase64());
        assertEquals(publicKey, key2);
    }

    @Test
    public void testNullEquals(){
        SigningPublicKey publicKey = new SigningPublicKey();
        byte data[] = new byte[SigningPublicKey.KEYSIZE_BYTES];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%56);
        publicKey.setData(data);

        assertFalse(publicKey.equals(null));
    }

    @Test
    public void testNullData() throws Exception{
        SigningPublicKey publicKey = new SigningPublicKey();
        publicKey.toString();

        try {
            publicKey.writeBytes(new ByteArrayOutputStream());
            fail("no exception thrown");
        } catch (DataFormatException expected) {
            assertEquals("No data to write out", expected.getMessage());
        }

    }

    @Test
    public void testShortData() throws Exception{
        SigningPublicKey publicKey = new SigningPublicKey();
        byte data[] = new byte[56];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i);

        try {
            publicKey.setData(data);
            publicKey.writeBytes(new ByteArrayOutputStream());
            fail("no exception thrown");
        } catch (IllegalArgumentException expected) {
            assertEquals("Bad data length: 56; required: " + SigningPublicKey.KEYSIZE_BYTES, expected.getMessage());
        }
    }

    @Test
    public void testShortRead() throws Exception{
        SigningPublicKey publicKey = new SigningPublicKey();
        ByteArrayInputStream in = new ByteArrayInputStream(DataHelper.getASCII("six times nine equals forty-two"));

        try {
            publicKey.readBytes(in);
            fail("exception not thrown");
        } catch (EOFException expected) {
            assertEquals("EOF after reading 31 bytes of " + SigningPublicKey.KEYSIZE_BYTES + " byte value", expected.getMessage());
        }
    }
}
