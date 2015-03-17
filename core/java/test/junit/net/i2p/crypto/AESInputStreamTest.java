package net.i2p.crypto;
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

import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.RandomSource;
/**
 * @author Comwiz
 */
public class AESInputStreamTest extends TestCase {
    public void testMultiple() throws Exception{
        SessionKey key = KeyGenerator.getInstance().generateSessionKey();
        byte iv[] = "there once was a".getBytes();
        
        int[] sizes = {1024 * 32, 20, 3, 0};
        
        for(int j = 0; j < sizes.length; j++){
            byte orig[] = new byte[sizes[j]];
            for (int i = 0; i < 20; i++) {
                RandomSource.getInstance().nextBytes(orig);
                runTest(orig, key, iv);
            }
        }
        
    }
    
    private static void runTest(byte orig[], SessionKey key, byte[] iv) throws Exception{
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        
        ByteArrayOutputStream origStream = new ByteArrayOutputStream(512);
        AESOutputStream out = new AESOutputStream(ctx, origStream, key, iv);
        out.write(orig);
        out.close();

        byte encrypted[] = origStream.toByteArray();

        ByteArrayInputStream encryptedStream = new ByteArrayInputStream(encrypted);
        AESInputStream sin = new AESInputStream(ctx, encryptedStream, key, iv);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        byte buf[] = new byte[1024 * 32];
        int read = DataHelper.read(sin, buf);
        if (read > 0) baos.write(buf, 0, read);
            sin.close();
        byte fin[] = baos.toByteArray();
        
        Hash origHash = SHA256Generator.getInstance().calculateHash(orig);
        Hash newHash = SHA256Generator.getInstance().calculateHash(fin);
        
        assertEquals(origHash, newHash);
        assertTrue(DataHelper.eq(orig, fin));
    }

    public static void testOffset() throws Exception{
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        
        byte[] orig = new byte[32];
        RandomSource.getInstance().nextBytes(orig);
        
        SessionKey key = KeyGenerator.getInstance().generateSessionKey();
        byte iv[] = "there once was a".getBytes();
        
        ByteArrayOutputStream origStream = new ByteArrayOutputStream(512);
        AESOutputStream out = new AESOutputStream(ctx, origStream, key, iv);
        out.write(orig);
        out.close();

        byte encrypted[] = origStream.toByteArray();

        byte encryptedSegment[] = new byte[40];
        System.arraycopy(encrypted, 0, encryptedSegment, 0, 40);

        ByteArrayInputStream encryptedStream = new ByteArrayInputStream(encryptedSegment);
        AESInputStream sin = new AESInputStream(ctx, encryptedStream, key, iv);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        byte buf[] = new byte[1024 * 32];
        int read = DataHelper.read(sin, buf);
        int remaining = sin.remainingBytes();
        int readyBytes = sin.readyBytes();
        
        if (read > 0) 
            baos.write(buf, 0, read);
        sin.close();
        byte fin[] = baos.toByteArray();
        
        Hash origHash = SHA256Generator.getInstance().calculateHash(orig);
        Hash newHash = SHA256Generator.getInstance().calculateHash(fin);
        
        assertFalse(origHash.equals(newHash));
        assertFalse(DataHelper.eq(orig, fin));
    }
}
