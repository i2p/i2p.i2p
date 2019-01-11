package net.i2p.crypto;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import net.i2p.I2PAppContext;
import net.i2p.crypto.HMAC256Generator.HMACKey;

/**
 * Various flavors of HKDF using HMAC-SHA256.
 * See RFC 5869.
 * One or two outputs, with or without "info".
 * All keys and outputs are exactly 32 bytes.
 *
 * @since 0.9.38
 */
public final class HKDF {

    private final I2PAppContext _context;
    private static final byte[] ONE = new byte[] { 1 };
    
    /**
     * Thread safe, no state, can be reused
     */
    public HKDF(I2PAppContext context) {
        _context = context;
    }

    /**
     * One output, no info.
     *
     * @param key first 32 bytes used as the key
     * @param out must be exactly 32 bytes
     */
    public void calculate(byte[] key, byte[] data, byte[] out) {
        HMAC256Generator hmac = _context.hmac256();
        // Extract using out as a temp buffer
        hmac.calculate(key, data, 0, data.length, out, 0);
        // Expand
        // HMAC with 0x01
        // output to out
        hmac.calculate(out, ONE, 0, 1, out, 0);
    }

    /**
     * One output with info.
     *
     * @param key first 32 bytes used as the key
     * @param info non-null ASCII, "" if none
     * @param out must be exactly 32 bytes
     */
    public void calculate(byte[] key, byte[] data, String info, byte[] out) {
        HMAC256Generator hmac = _context.hmac256();
        int ilen = info.length();
        // Extract using out as a temp buffer
        hmac.calculate(key, data, 0, data.length, out, 0);
        byte[] tmp = new byte[ilen + 1];
        for (int i = 0; i < ilen; i++) {
            tmp[i] = (byte)info.charAt(i);
        }
        tmp[ilen] = 1;
        // Expand
        // HMAC with info and 0x01
        // output to out
        hmac.calculate(out, tmp, 0, tmp.length, out, 0);
    }

    /**
     * Two outputs, no info.
     *
     * @param key first 32 bytes used as the key
     * @param out 32 bytes will be copied to here
     * @param out2 32 bytes will be copied to here, may be the same as out
     * @param off2 offset for copy to out2
     */
    public void calculate(byte[] key, byte[] data, byte[] out,
                          byte[] out2, int off2) {
        calculate(key, data, "", out, out2, off2);
    }

    /**
     * Two outputs with info.
     *
     * @param key first 32 bytes used as the key
     * @param info non-null ASCII, "" if none
     * @param out 32 bytes will be copied to here
     * @param out2 32 bytes will be copied to here, may be the same as out
     * @param off2 offset for copy to out2
     */
    public void calculate(byte[] key, byte[] data, String info, byte[] out,
                          byte[] out2, int off2) {
        int ilen = info.length();
        byte[] tmp = new byte[ilen + 1];
        for (int i = 0; i < ilen; i++) {
            tmp[i] = (byte)info.charAt(i);
        }
        tmp[ilen] = 1;
        try {
            // here we use Java Mac directly rather than
            // HMAC256Generator for efficiency.
            Mac mac = Mac.getInstance("HmacSHA256");
            // SecretKeySpec copies the data, HMACKey doesn't
            SecretKey keyObj = new HMACKey(key);
            mac.init(keyObj);
            mac.update(data);
            // Extract to out as a tmp
            mac.doFinal(out, 0);
            // PRK
            // SecretKeySpec copies the data, HMACKey doesn't
            keyObj = new SecretKeySpec(out, 0, 32, "HmacSHA256");
            mac.init(keyObj);
            // Expand
            // HMAC 1 with info and 0x01
            // output 1 to out
            mac.update(tmp, 0, ilen + 1);
            mac.doFinal(out, 0);
            tmp[ilen] = 2;
            // HMAC 2 with output 1, info, and 0x02
            // output 2 to out2
            mac.reset();
            mac.update(out, 0, 32);
            mac.update(tmp, 0, ilen + 1);
            mac.doFinal(out2, off2);
        } catch (NoSuchAlgorithmException e) {
            throw new UnsupportedOperationException("HmacSHA256", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("HmacSHA256", e);
        } finally {
            // we could re-init the mac with a zero key
            // no way to zero out the SecretKeySpec though
        }
    }
}
