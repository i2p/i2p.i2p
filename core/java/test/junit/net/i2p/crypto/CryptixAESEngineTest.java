package net.i2p.crypto;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;

public class CryptixAESEngineTest extends TestCase{
    public void testED() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[128];
        byte encrypted[] = new byte[128];
        byte decrypted[] = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, encrypted, 0, key, iv, orig.length);
        aes.decrypt(encrypted, 0, decrypted, 0, key, iv, encrypted.length);
        assertTrue(DataHelper.eq(decrypted,orig));
    }
    
    public static void testED2() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[128];
        byte data[] = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, data, 0, key, iv, data.length);
        aes.decrypt(data, 0, data, 0, key, iv, data.length);
        assertTrue(DataHelper.eq(data,orig));
    }
    
    public static void testFake() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        SessionKey wrongKey = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[128];
        byte encrypted[] = new byte[128];
        byte decrypted[] = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, encrypted, 0, key, iv, orig.length);
        aes.decrypt(encrypted, 0, decrypted, 0, wrongKey, iv, encrypted.length);
        assertFalse(DataHelper.eq(decrypted,orig));
    }
    
    public static void testNull() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        SessionKey wrongKey = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[128];
        byte encrypted[] = new byte[128];
        byte decrypted[] = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, encrypted, 0, key, iv, orig.length);
        
        boolean error = false;
        try { 
            aes.decrypt(null, 0, null, 0, wrongKey, iv, encrypted.length);
        } catch (IllegalArgumentException iae) {
            error = true;
        } 
        assertTrue(error);
    }
    
    public static void testEDBlock() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[16];
        byte encrypted[] = new byte[16];
        byte decrypted[] = new byte[16];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encryptBlock(orig, 0, key, encrypted, 0);
        aes.decryptBlock(encrypted, 0, key, decrypted, 0);
        assertTrue(DataHelper.eq(decrypted,orig));
    }
    
    public static void testEDBlock2() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[16];
        byte data[] = new byte[16];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encryptBlock(orig, 0, key, data, 0);
        aes.decryptBlock(data, 0, key, data, 0);
        assertTrue(DataHelper.eq(data,orig));
    }
}