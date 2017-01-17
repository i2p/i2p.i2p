package net.i2p.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** 
 * Moved from CryptixAESEngine and net.i2p.router.tasks.CryptoChecker.
 * This class does not do any logging. See CryptoChecker for the logging.
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
        } catch (ExceptionInInitializerError eiie) {
            // Java 9 b134 bug
            // > java -jar build/i2p.jar cryptocheck
            // Exception in thread "main" java.lang.ExceptionInInitializerError
            // 	at javax.crypto.JceSecurityManager.<clinit>(java.base@9-Ubuntu/JceSecurityManager.java:65)
            // 	at javax.crypto.Cipher.getConfiguredPermission(java.base@9-Ubuntu/Cipher.java:2595)
            // 	at javax.crypto.Cipher.getMaxAllowedKeyLength(java.base@9-Ubuntu/Cipher.java:2619)
            // 	at net.i2p.crypto.CryptoCheck.<clinit>(CryptoCheck.java:19)
            // 	at java.lang.Class.forName0(java.base@9-Ubuntu/Native Method)
            // 	at java.lang.Class.forName(java.base@9-Ubuntu/Class.java:374)
            // 	at net.i2p.util.CommandLine.exec(CommandLine.java:66)
            // 	at net.i2p.util.CommandLine.main(CommandLine.java:51)
            // Caused by: java.lang.SecurityException: Can not initialize cryptographic mechanism
            // 	at javax.crypto.JceSecurity.<clinit>(java.base@9-Ubuntu/JceSecurity.java:91)
            // 	... 8 more
            // Caused by: java.lang.NullPointerException
            // 	at sun.nio.fs.UnixPath.normalizeAndCheck(java.base@9-Ubuntu/UnixPath.java:75)
            // 	at sun.nio.fs.UnixPath.<init>(java.base@9-Ubuntu/UnixPath.java:69)
            // 	at sun.nio.fs.UnixFileSystem.getPath(java.base@9-Ubuntu/UnixFileSystem.java:280)
            // 	at java.nio.file.Paths.get(java.base@9-Ubuntu/Paths.java:84)
            // 	at javax.crypto.JceSecurity.setupJurisdictionPolicies(java.base@9-Ubuntu/JceSecurity.java:254)
            // 	at javax.crypto.JceSecurity.access$000(java.base@9-Ubuntu/JceSecurity.java:49)
            // 	at javax.crypto.JceSecurity$1.run(java.base@9-Ubuntu/JceSecurity.java:82)
            // 	at javax.crypto.JceSecurity$1.run(java.base@9-Ubuntu/JceSecurity.java:79)
            // 	at java.security.AccessController.doPrivileged(java.base@9-Ubuntu/Native Method)
            // 	at javax.crypto.JceSecurity.<clinit>(java.base@9-Ubuntu/JceSecurity.java:78)
            // 	... 8 more
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
