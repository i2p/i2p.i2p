package net.i2p.data.i2np;

import java.security.GeneralSecurityException;
import java.util.Properties;

import com.southernstorm.noise.protocol.ChaChaPolyCipherState;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

/**
 * Class that creates an encrypted tunnel build message record.
 *
 * The reply record is the same size as the request record (528 bytes).
 *
 * When decrypted:
 *
 *<pre>
 * Bytes 0-31 contain the hash of bytes 32-527
 * Bytes 32-526 contain random data.
 * Byte 527 contains the reply.
 *</pre>
 */
public class BuildResponseRecord {

    /**
     * Create a new encrypted response.
     * AES only for ElGamal routers.
     *
     * @param status the response 0-255
     * @param replyIV 16 bytes
     * @param responseMessageId unused except for debugging
     * @return a 528-byte response record
     */
    public static EncryptedBuildRecord create(I2PAppContext ctx, int status, SessionKey replyKey,
                                              byte replyIV[], long responseMessageId) {
        byte rv[] = new byte[TunnelBuildReplyMessage.RECORD_SIZE];
        ctx.random().nextBytes(rv, Hash.HASH_LENGTH, TunnelBuildReplyMessage.RECORD_SIZE - Hash.HASH_LENGTH - 1);
        rv[TunnelBuildMessage.RECORD_SIZE-1] = (byte) status;
        // rv = AES(SHA256(padding+status) + padding + status, replyKey, replyIV)
        ctx.sha().calculateHash(rv, Hash.HASH_LENGTH, rv.length - Hash.HASH_LENGTH, rv, 0);
        ctx.aes().encrypt(rv, 0, rv, 0, replyKey, replyIV, rv.length);
        return new EncryptedBuildRecord(rv);
    }

    /**
     * Create a new encrypted response (long record).
     * ChaCha/Poly only for ECIES routers.
     *
     * @param status the response 0-255
     * @param replyAD 32 bytes
     * @param options 511 bytes max when serialized
     * @return a 528-byte response record
     * @throws IllegalArgumentException if options too big or on encryption failure
     * @since 0.9.48
     */
    public static EncryptedBuildRecord create(I2PAppContext ctx, int status, SessionKey replyKey,
                                              byte replyAD[], Properties options) {
        byte rv[] = new byte[TunnelBuildReplyMessage.RECORD_SIZE];
        int off;
        try {
            off = DataHelper.toProperties(rv, 0, options);
        } catch (Exception e) {
            throw new IllegalArgumentException("options", e);
        }
        int sz = TunnelBuildReplyMessage.RECORD_SIZE - off - 1;
        if (sz > 0)
            ctx.random().nextBytes(rv, off, sz);
        else if (sz < 0)
            throw new IllegalArgumentException("options");
        rv[TunnelBuildMessage.RECORD_SIZE - 17] = (byte) status;
        boolean ok = encryptAEADBlock(replyAD, rv, replyKey);
        if (!ok)
            throw new IllegalArgumentException("encrypt fail");
        return new EncryptedBuildRecord(rv);
    }

    /**
     * Create a new encrypted response (short record).
     * ChaCha/Poly only for ECIES routers.
     *
     * @param status the response 0-255
     * @param replyAD 32 bytes
     * @param options 116 bytes max when serialized
     * @param slot the slot number, 0-7
     * @return a 218-byte response record
     * @throws IllegalArgumentException if options too big or on encryption failure
     * @since 0.9.51
     */
    public static ShortEncryptedBuildRecord createShort(I2PAppContext ctx, int status, SessionKey replyKey,
                                                        byte replyAD[], Properties options, int slot) {
        byte rv[] = new byte[ShortTunnelBuildMessage.SHORT_RECORD_SIZE];
        int off;
        try {
            off = DataHelper.toProperties(rv, 0, options);
        } catch (Exception e) {
            throw new IllegalArgumentException("options", e);
        }
        int sz = ShortTunnelBuildMessage.SHORT_RECORD_SIZE - off - 1;
        if (sz > 0)
            ctx.random().nextBytes(rv, off, sz);
        else if (sz < 0)
            throw new IllegalArgumentException("options");
        rv[ShortTunnelBuildMessage.SHORT_RECORD_SIZE - 17] = (byte) status;
        boolean ok = encryptAEADBlock(replyAD, rv, replyKey, slot);
        if (!ok)
            throw new IllegalArgumentException("encrypt fail");
        return new ShortEncryptedBuildRecord(rv);
    }

    /**
     * Encrypts in place.
     * Handles standard (528) byte records only.
     *
     * @param ad non-null
     * @param data 528 bytes, data will be encrypted in place.
     * @return success
     * @since 0.9.48
     */
    private static final boolean encryptAEADBlock(byte[] ad, byte data[], SessionKey key) {
        if (data.length != EncryptedBuildRecord.LENGTH)
            throw new IllegalArgumentException();
        ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
        chacha.initializeKey(key.getData(), 0);
        try {
            chacha.encryptWithAd(ad, data, 0, data, 0, data.length - 16);
        } catch (GeneralSecurityException e) {
            return false;
        } finally {
            chacha.destroy();
        }
        return true;
    }

    /*
     * ChaCha/Poly only for ECIES routers.
     * Handles standard (528) byte records only.
     * Decrypts in place.
     * Status will be rec.getData()[511].
     * Properties will be at rec.getData()[0].
     *
     * @param rec 528 bytes, data will be decrypted in place.
     * @param ad non-null
     * @return success
     * @since 0.9.48
     */
    public static boolean decrypt(EncryptedBuildRecord rec, SessionKey key, byte[] ad) {
        if (rec.length() != EncryptedBuildRecord.LENGTH)
            throw new IllegalArgumentException();
        ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
        chacha.initializeKey(key.getData(), 0);
        try {
            // this is safe to do in-place, it checks the mac before starting decryption
            byte[] data = rec.getData();
            chacha.decryptWithAd(ad, data, 0, data, 0, rec.length());
        } catch (GeneralSecurityException e) {
            return false;
        } finally {
            chacha.destroy();
        }
        return true;
    }

    /**
     * Encrypts in place.
     * Handles short (218) byte records only.
     *
     * @param ad non-null
     * @param data 218 bytes, data will be encrypted in place.
     * @param nonce the slot number, 0-7
     * @return success
     * @since 0.9.51
     */
    private static final boolean encryptAEADBlock(byte[] ad, byte data[], SessionKey key, int nonce) {
        if (data.length != ShortEncryptedBuildRecord.LENGTH || nonce < 0 || nonce > 7)
            throw new IllegalArgumentException();
        ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
        chacha.initializeKey(key.getData(), 0);
        chacha.setNonce(nonce);
        try {
            chacha.encryptWithAd(ad, data, 0, data, 0, data.length - 16);
        } catch (GeneralSecurityException e) {
            return false;
        } finally {
            chacha.destroy();
        }
        return true;
    }

    /*
     * ChaCha/Poly only for ECIES routers.
     * Handles short (218) byte records only.
     * Decrypts in place.
     * Status will be rec.getData()[201].
     * Properties will be at rec.getData()[0].
     *
     * @param rec 218 bytes, data will be decrypted in place.
     * @param ad non-null
     * @param nonce the slot number, 0-7
     * @return success
     * @since 0.9.51
     */
    public static boolean decrypt(EncryptedBuildRecord rec, SessionKey key, byte[] ad, int nonce) {
        if (rec.length() != ShortEncryptedBuildRecord.LENGTH || nonce < 0 || nonce > 7)
            throw new IllegalArgumentException();
        ChaChaPolyCipherState chacha = new ChaChaPolyCipherState();
        chacha.initializeKey(key.getData(), 0);
        chacha.setNonce(nonce);
        try {
            // this is safe to do in-place, it checks the mac before starting decryption
            byte[] data = rec.getData();
            chacha.decryptWithAd(ad, data, 0, data, 0, rec.length());
        } catch (GeneralSecurityException e) {
            return false;
        } finally {
            chacha.destroy();
        }
        return true;
    }
}
