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

    public byte[] encrypt(byte payload[], SessionKey sessionKey, byte initializationVector[]) {
        if ((initializationVector == null) || (payload == null) || (payload.length <= 0) || (sessionKey == null)
            || (initializationVector.length != 16)) return null;

        if (USE_FAKE_CRYPTO) {
            _log.warn("AES Crypto disabled!  Using trivial XOR");
            byte rv[] = new byte[payload.length];
            for (int i = 0; i < rv.length; i++)
                rv[i] = (byte) (payload[i] ^ FAKE_KEY);
            return rv;
        }

        int numblock = payload.length / 16;
        if (payload.length % 16 != 0) numblock++;
        byte[][] plain = new byte[numblock][16];
        for (int x = 0; x < numblock; x++) {
            for (int y = 0; y < 16; y++) {
                plain[x][y] = payload[x * 16 + y];
            }
        }

        byte[][] cipher = new byte[numblock][16];
        cipher[0] = encrypt(xor(initializationVector, plain[0]), sessionKey);
        for (int x = 1; x < numblock; x++) {
            cipher[x] = encrypt(xor(cipher[x - 1], plain[x]), sessionKey);
        }

        byte[] ret = new byte[numblock * 16];
        for (int x = 0; x < numblock; x++) {
            for (int y = 0; y < 16; y++) {
                ret[x * 16 + y] = cipher[x][y];
            }
        }

        return ret;
    }

    public byte[] decrypt(byte payload[], SessionKey sessionKey, byte initializationVector[]) {
        if ((initializationVector == null) || (payload == null) || (payload.length <= 0) || (sessionKey == null)
            || (initializationVector.length != 16)) return null;

        if (USE_FAKE_CRYPTO) {
            _log.warn("AES Crypto disabled!  Using trivial XOR");
            byte rv[] = new byte[payload.length];
            for (int i = 0; i < rv.length; i++)
                rv[i] = (byte) (payload[i] ^ FAKE_KEY);
            return rv;
        }

        int numblock = payload.length / 16;
        if (payload.length % 16 != 0) numblock++;
        byte[][] cipher = new byte[numblock][16];
        for (int x = 0; x < numblock; x++) {
            for (int y = 0; y < 16; y++) {
                cipher[x][y] = payload[x * 16 + y];
            }
        }

        byte[][] plain = new byte[numblock][16];
        plain[0] = xor(decrypt(cipher[0], sessionKey), initializationVector);
        for (int x = 1; x < numblock; x++) {
            plain[x] = xor(decrypt(cipher[x], sessionKey), cipher[x - 1]);
        }

        byte[] ret = new byte[numblock * 16];
        for (int x = 0; x < numblock; x++) {
            for (int y = 0; y < 16; y++) {
                ret[x * 16 + y] = plain[x][y];
            }
        }

        return ret;
    }

    final static byte[] xor(byte[] a, byte[] b) {
        if ((a == null) || (b == null) || (a.length != b.length)) return null;
        byte[] ret = new byte[a.length];
        for (int x = 0; x < a.length; x++) {
            ret[x] = (byte) (a[x] ^ b[x]);
        }
        return ret;
    }

    /** Encrypt the payload with the session key
     * @param payload data to be encrypted
     * @param sessionKey private esession key to encrypt to
     * @return encrypted data
     */
    final byte[] encrypt(byte payload[], SessionKey sessionKey) {
        try {
            Object key = CryptixRijndael_Algorithm.makeKey(sessionKey.getData(), 16);
            byte rv[] = new byte[payload.length];
            CryptixRijndael_Algorithm.blockEncrypt(payload, rv, 0, key, 16);
            return rv;
        } catch (InvalidKeyException ike) {
            _log.error("Invalid key", ike);
            return null;
        }
    }

    /** decrypt the data with the session key provided
     * @param payload encrypted data
     * @param sessionKey private session key
     * @return unencrypted data
     */
    final byte[] decrypt(byte payload[], SessionKey sessionKey) {
        try {
            Object key = CryptixRijndael_Algorithm.makeKey(sessionKey.getData(), 16);
            byte rv[] = new byte[payload.length];
            CryptixRijndael_Algorithm.blockDecrypt(payload, rv, 0, key, 16);
            return rv;
        } catch (InvalidKeyException ike) {
            _log.error("Invalid key", ike);
            return null;
        }
    }
}