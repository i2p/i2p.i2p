package net.i2p.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** 
 * Moved from CryptixAESEngine and net.i2p.router.tasks.CryptoChecker
 *
 * @since 0.9.23
 */
public class CryptoCheck {

    private static final boolean _isUnlimited;

    static {
        boolean unlimited = false;
        try {
            unlimited = Cipher.getMaxAllowedKeyLength("AES") >= 256;
        } catch (GeneralSecurityException gse) {
            // a NoSuchAlgorithmException
        } catch (NoSuchMethodError nsme) {
            // JamVM, gij
            try {
                Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
                cipher.init(Cipher.ENCRYPT_MODE, key);
                unlimited = true;
            } catch (GeneralSecurityException gse) {
            }
        }
        _isUnlimited = unlimited;
    }

    private CryptoCheck() {}

    /**
     *  Do we have unlimited crypto?
     */
    public static boolean isUnlimited() {
        return _isUnlimited;
    }

    public static void main(String args[]) {
        System.out.println("Unlimited? " + isUnlimited());
    }
}
