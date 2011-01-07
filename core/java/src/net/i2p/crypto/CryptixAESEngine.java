package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.security.InvalidKeyException;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/** 
 * Wrapper for AES cypher operation using Cryptix's Rijndael implementation.  Implements
 * CBC with a 16 byte IV.
 * Problems: 
 * Only supports data of size mod 16 bytes - no inherent padding.
 *
 * @author jrandom, thecrypto
 */
public class CryptixAESEngine extends AESEngine {
    private final static CryptixRijndael_Algorithm _algo = new CryptixRijndael_Algorithm();
    private final static boolean USE_FAKE_CRYPTO = false;
    // keys are now cached in the SessionKey objects
    //private CryptixAESKeyCache _cache;
    
    private static final ByteCache _prevCache = ByteCache.getInstance(16, 16);
    
    public CryptixAESEngine(I2PAppContext context) {
        super(context);
        //_cache = new CryptixAESKeyCache();
    }
    
    /** @param length must be a multiple of 16 */
    @Override
    public void encrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int length) {
        encrypt(payload, payloadIndex, out, outIndex, sessionKey, iv, 0, length);
    }
    
    /** @param length must be a multiple of 16 */
    @Override
    public void encrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int ivOffset, int length) {
        if ( (payload == null) || (out == null) || (sessionKey == null) || (iv == null) ) 
            throw new NullPointerException("invalid args to aes");
        if (payload.length < payloadIndex + length)
            throw new IllegalArgumentException("Payload is too short");
        if (out.length < outIndex + length)
            throw new IllegalArgumentException("Output is too short");
        if (length <= 0) 
            throw new IllegalArgumentException("Length is too small");
        if (length % 16 != 0) 
            throw new IllegalArgumentException("Only lengths mod 16 are supported here");

        if (USE_FAKE_CRYPTO) {
            _log.warn("AES Crypto disabled!  Using trivial XOR");
            System.arraycopy(payload, payloadIndex, out, outIndex, length);
            return;
        }

        int numblock = length / 16;
        
        DataHelper.xor(iv, ivOffset, payload, payloadIndex, out, outIndex, 16);
        encryptBlock(out, outIndex, sessionKey, out, outIndex);
        for (int x = 1; x < numblock; x++) {
            DataHelper.xor(out, outIndex + (x-1) * 16, payload, payloadIndex + x * 16, out, outIndex + x * 16, 16);
            encryptBlock(out, outIndex + x * 16, sessionKey, out, outIndex + x * 16);
        }
    }
    
    @Override
    public void decrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int length) {
        decrypt(payload, payloadIndex, out, outIndex, sessionKey, iv, 0, length);
    }
    @Override
    public void decrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int ivOffset, int length) {
        if ((iv== null) || (payload == null) || (payload.length <= 0) || (sessionKey == null) ) 
            throw new IllegalArgumentException("bad setup");
        else if (out == null)
            throw new IllegalArgumentException("out is null");
        else if (out.length - outIndex < length)
            throw new IllegalArgumentException("out is too small (out.length=" + out.length 
                                               + " outIndex=" + outIndex + " length=" + length);

        if (USE_FAKE_CRYPTO) {
            _log.warn("AES Crypto disabled!  Using trivial XOR");
            System.arraycopy(payload, payloadIndex, out, outIndex, length);
            return ;
        }

        int numblock = length / 16;
        if (length % 16 != 0) numblock++;

        ByteArray prevA = _prevCache.acquire();
        byte prev[] = prevA.getData();
        ByteArray curA = _prevCache.acquire();
        byte cur[] = curA.getData();
        System.arraycopy(iv, ivOffset, prev, 0, 16);
        
        for (int x = 0; x < numblock; x++) {
            System.arraycopy(payload, payloadIndex + (x * 16), cur, 0, 16);
            decryptBlock(payload, payloadIndex + (x * 16), sessionKey, out, outIndex + (x * 16));
            DataHelper.xor(out, outIndex + x * 16, prev, 0, out, outIndex + x * 16, 16);
            iv = prev; // just use IV to switch 'em around
            prev = cur;
            cur = iv;
        }
        
        /*
        decryptBlock(payload, payloadIndex, sessionKey, out, outIndex);
        DataHelper.xor(out, outIndex, iv, 0, out, outIndex, 16);
        for (int x = 1; x < numblock; x++) {
            decryptBlock(payload, payloadIndex + (x * 16), sessionKey, out, outIndex + (x * 16));
            DataHelper.xor(out, outIndex + x * 16, payload, payloadIndex + (x - 1) * 16, out, outIndex + x * 16, 16);
        }
         */
        
        _prevCache.release(prevA);
        _prevCache.release(curA);
    }
    
    /** encrypt exactly 16 bytes using the session key */
    @Override
    public final void encryptBlock(byte payload[], int inIndex, SessionKey sessionKey, byte out[], int outIndex) {
        if (sessionKey.getPreparedKey() == null) {
            try {
                Object key = CryptixRijndael_Algorithm.makeKey(sessionKey.getData(), 16);
                sessionKey.setPreparedKey(key);
            } catch (InvalidKeyException ike) {
                _log.log(Log.CRIT, "Invalid key", ike);
                throw new IllegalArgumentException("wtf, invalid key?  " + ike.getMessage());
            }
        }
        
        CryptixRijndael_Algorithm.blockEncrypt(payload, out, inIndex, outIndex, sessionKey.getPreparedKey(), 16);
    }

    /** decrypt exactly 16 bytes of data with the session key provided
     * @param payload encrypted data
     * @param sessionKey private session key
     */
    @Override
    public final void decryptBlock(byte payload[], int inIndex, SessionKey sessionKey, byte rv[], int outIndex) {
        if ( (payload == null) || (rv == null) )
            throw new IllegalArgumentException("null block args");
        if (payload.length - inIndex > rv.length - outIndex)
            throw new IllegalArgumentException("bad block args [payload.len=" + payload.length 
                                               + " inIndex=" + inIndex + " rv.len=" + rv.length 
                                               + " outIndex="+outIndex);
        if (sessionKey.getPreparedKey() == null) {
            try {
                Object key = CryptixRijndael_Algorithm.makeKey(sessionKey.getData(), 16);
                sessionKey.setPreparedKey(key);
            } catch (InvalidKeyException ike) {
                _log.log(Log.CRIT, "Invalid key", ike);
                throw new IllegalArgumentException("wtf, invalid key?  " + ike.getMessage());
            }
        }

        CryptixRijndael_Algorithm.blockDecrypt(payload, rv, inIndex, outIndex, sessionKey.getPreparedKey(), 16);
    }
    
/********
    public static void main(String args[]) {
        I2PAppContext ctx = new I2PAppContext();
        try {
            testEDBlock(ctx);
            testEDBlock2(ctx);
            testED(ctx);
            testED2(ctx);
            //testFake(ctx);
            //testNull(ctx);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
    }
    private static void testED(I2PAppContext ctx) {
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
        if (!DataHelper.eq(decrypted,orig))
            throw new RuntimeException("full D(E(orig)) != orig");
        else
            System.out.println("full D(E(orig)) == orig");
    }
    private static void testED2(I2PAppContext ctx) {
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[128];
        byte data[] = new byte[128];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encrypt(orig, 0, data, 0, key, iv, data.length);
        aes.decrypt(data, 0, data, 0, key, iv, data.length);
        if (!DataHelper.eq(data,orig))
            throw new RuntimeException("full D(E(orig)) != orig");
        else
            System.out.println("full D(E(orig)) == orig");
    }
    private static void testFake(I2PAppContext ctx) {
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
        if (DataHelper.eq(decrypted,orig))
            throw new RuntimeException("full D(E(orig)) == orig when we used the wrong key!");
        else
            System.out.println("full D(E(orig)) != orig when we used the wrong key");
    }
    private static void testNull(I2PAppContext ctx) {
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
        try { 
            aes.decrypt(null, 0, null, 0, wrongKey, iv, encrypted.length);
        } catch (IllegalArgumentException iae) {
            return;
        } 
        
        throw new RuntimeException("full D(E(orig)) didn't fail when we used null!");
    }
    private static void testEDBlock(I2PAppContext ctx) {
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
        if (!DataHelper.eq(decrypted,orig))
            throw new RuntimeException("block D(E(orig)) != orig");
        else
            System.out.println("block D(E(orig)) == orig");
    }
    private static void testEDBlock2(I2PAppContext ctx) {
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        byte orig[] = new byte[16];
        byte data[] = new byte[16];
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(orig);
        CryptixAESEngine aes = new CryptixAESEngine(ctx);
        aes.encryptBlock(orig, 0, key, data, 0);
        aes.decryptBlock(data, 0, key, data, 0);
        if (!DataHelper.eq(data,orig))
            throw new RuntimeException("block D(E(orig)) != orig");
        else
            System.out.println("block D(E(orig)) == orig");
    }
*******/
}
