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
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
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
    private Log _log;
    private final static CryptixRijndael_Algorithm _algo = new CryptixRijndael_Algorithm();
    private final static boolean USE_FAKE_CRYPTO = false;
    private final static byte FAKE_KEY = 0x2A;
    
    public CryptixAESEngine(I2PAppContext context) {
        super(context);
        _log = context.logManager().getLog(CryptixAESEngine.class);
    }
    
    public void encrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int length) {
        if ( (payload == null) || (out == null) || (sessionKey == null) || (iv == null) || (iv.length != 16) ) 
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
        
        DataHelper.xor(iv, 0, payload, payloadIndex, out, outIndex, 16);
        encryptBlock(out, outIndex, sessionKey, out, outIndex);
        for (int x = 1; x < numblock; x++) {
            DataHelper.xor(out, outIndex + (x-1) * 16, payload, payloadIndex + x * 16, out, outIndex + x * 16, 16);
            encryptBlock(out, outIndex + x * 16, sessionKey, out, outIndex + x * 16);
        }
    }

    public void decrypt(byte payload[], int payloadIndex, byte out[], int outIndex, SessionKey sessionKey, byte iv[], int length) {
        if ((iv== null) || (payload == null) || (payload.length <= 0) || (sessionKey == null)
            || (iv.length != 16)) 
            throw new IllegalArgumentException("bad setup");

        if (USE_FAKE_CRYPTO) {
            _log.warn("AES Crypto disabled!  Using trivial XOR");
            System.arraycopy(payload, payloadIndex, out, outIndex, length);
            return ;
        }

        int numblock = payload.length / 16;
        if (payload.length % 16 != 0) numblock++;

        decryptBlock(payload, 0, sessionKey, out, 0);
		DataHelper.xor(out, 0, iv, 0, out, 0, 16);
        for (int x = 1; x < numblock; x++) {
            decryptBlock(payload, x * 16, sessionKey, out, x * 16);
            DataHelper.xor(out, x * 16, payload, (x - 1) * 16, out, x * 16, 16);
        }
    }

    final void encryptBlock(byte payload[], int inIndex, SessionKey sessionKey, byte out[], int outIndex) {
        try {
            Object key = CryptixRijndael_Algorithm.makeKey(sessionKey.getData(), 16);
            CryptixRijndael_Algorithm.blockEncrypt(payload, out, inIndex, outIndex, key, 16);
        } catch (InvalidKeyException ike) {
            _log.error("Invalid key", ike);
        }
    }

    /** decrypt the data with the session key provided
     * @param payload encrypted data
     * @param sessionKey private session key
     * @return unencrypted data
     */
    final void decryptBlock(byte payload[], int inIndex, SessionKey sessionKey, byte rv[], int outIndex) {
        try {
            Object key = CryptixRijndael_Algorithm.makeKey(sessionKey.getData(), 16);
            CryptixRijndael_Algorithm.blockDecrypt(payload, rv, inIndex, outIndex, key, 16);
        } catch (InvalidKeyException ike) {
            _log.error("Invalid key", ike);
        }
    }
    
    public static void main(String args[]) {
        I2PAppContext ctx = new I2PAppContext();
        try {
            testEDBlock(ctx);
            testED(ctx);
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
}