package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/** 
 * Dummy wrapper for AES cipher operation.
 *
 */
public class AESEngine {
    private Log _log;
    private I2PAppContext _context;
    public AESEngine(I2PAppContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(AESEngine.class);
        if (getClass() == AESEngine.class)
            _log.warn("Warning: AES is disabled");
    }
    
    /** Encrypt the payload with the session key
     * @param payload data to be encrypted
     * @param sessionKey private esession key to encrypt to
     * @param initializationVector IV for CBC
     * @return encrypted data
     */
    public byte[] encrypt(byte payload[], SessionKey sessionKey, byte initializationVector[]) {
        if ((initializationVector == null) || (payload == null) || (sessionKey == null)
            || (initializationVector.length != 16)) return null;

        byte cyphertext[] = new byte[payload.length + (16 - (payload.length % 16))];
        System.arraycopy(payload, 0, cyphertext, 0, payload.length);
        return cyphertext;
    }

    public byte[] safeEncrypt(byte payload[], SessionKey sessionKey, byte iv[], int paddedSize) {
        if ((iv == null) || (payload == null) || (sessionKey == null) || (iv.length != 16)) return null;

        ByteArrayOutputStream baos = new ByteArrayOutputStream(paddedSize + 64);
        Hash h = _context.sha().calculateHash(sessionKey.getData());
        try {
            h.writeBytes(baos);
            DataHelper.writeLong(baos, 4, payload.length);
            baos.write(payload);
            byte tv[] = baos.toByteArray();
            baos.write(ElGamalAESEngine.getPadding(_context, tv.length, paddedSize));
        } catch (IOException ioe) {
            _log.error("Error writing data", ioe);
            return null;
        } catch (DataFormatException dfe) {
            _log.error("Error writing data", dfe);
            return null;
        }
        return encrypt(baos.toByteArray(), sessionKey, iv);
    }

    public byte[] safeDecrypt(byte payload[], SessionKey sessionKey, byte iv[]) {
        if ((iv == null) || (payload == null) || (sessionKey == null) || (iv.length != 16)) return null;

        byte decr[] = decrypt(payload, sessionKey, iv);
        if (decr == null) {
            _log.error("Error decrypting the data - payload " + payload.length + " decrypted to null");
            return null;
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(decr);
        Hash h = _context.sha().calculateHash(sessionKey.getData());
        try {
            Hash rh = new Hash();
            rh.readBytes(bais);
            if (!h.equals(rh)) {
                _log.error("Hash does not match [key=" + sessionKey + " / iv =" + DataHelper.toString(iv, iv.length)
                           + "]", new Exception("Hash error"));
                return null;
            }
            long len = DataHelper.readLong(bais, 4);
            byte data[] = new byte[(int) len];
            int read = bais.read(data);
            if (read != len) {
                _log.error("Not enough to read");
                return null;
            }
            return data;
        } catch (IOException ioe) {
            _log.error("Error writing data", ioe);
            return null;
        } catch (DataFormatException dfe) {
            _log.error("Error writing data", dfe);
            return null;
        }
    }

    /** decrypt the data with the session key provided
     * @param cyphertext encrypted data
     * @param sessionKey private session key
     * @param initializationVector IV for CBC
     * @return unencrypted data
     */
    public byte[] decrypt(byte cyphertext[], SessionKey sessionKey, byte initializationVector[]) {
        if ((initializationVector == null) || (cyphertext == null) || (sessionKey == null)
            || (initializationVector.length != 16)) return null;

        byte payload[] = new byte[cyphertext.length];
        _log.warn("Warning: AES is disabled");
        return cyphertext;
    }

    public static void main(String args[]) {
        I2PAppContext ctx = new I2PAppContext();
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte iv[] = new byte[16];
        RandomSource.getInstance().nextBytes(iv);

        byte sbuf[] = new byte[16];
        RandomSource.getInstance().nextBytes(sbuf);
        byte se[] = ctx.AESEngine().encrypt(sbuf, key, iv);
        byte sd[] = ctx.AESEngine().decrypt(se, key, iv);
        ctx.logManager().getLog(AESEngine.class).debug("Short test: " + DataHelper.eq(sd, sbuf));

        byte lbuf[] = new byte[1024];
        RandomSource.getInstance().nextBytes(sbuf);
        byte le[] = ctx.AESEngine().safeEncrypt(lbuf, key, iv, 2048);
        byte ld[] = ctx.AESEngine().safeDecrypt(le, key, iv);
        ctx.logManager().getLog(AESEngine.class).debug("Long test: " + DataHelper.eq(ld, lbuf));
    }
}