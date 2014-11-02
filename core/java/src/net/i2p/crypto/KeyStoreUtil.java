package net.i2p.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Locale;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.ShellCommand;
import net.i2p.util.SystemVersion;

/**
 *  Keystore utilities, consolidated from various places.
 *
 *  @since 0.9.9
 */
public class KeyStoreUtil {
        
    public static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";
    private static final String DEFAULT_KEY_ALGORITHM = "RSA";
    private static final int DEFAULT_KEY_SIZE = 2048;
    private static final int DEFAULT_KEY_VALID_DAYS = 3652;  // 10 years

    /**
     *  Create a new KeyStore object, and load it from ksFile if it is
     *  non-null and it exists.
     *  If ksFile is non-null and it does not exist, create a new empty
     *  keystore file.
     *
     *  @param ksFile may be null
     *  @param password may be null
     *  @return success
     */
    public static KeyStore createKeyStore(File ksFile, String password)
                              throws GeneralSecurityException, IOException {
        boolean exists = ksFile != null && ksFile.exists();
        char[] pwchars = password != null ? password.toCharArray() : null;
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        if (exists) {
            InputStream fis = null;
            try {
                fis = new FileInputStream(ksFile);
                ks.load(fis, pwchars);
            } finally {
                if (fis != null) try { fis.close(); } catch (IOException ioe) {}
            }
        }
        if (ksFile != null && !exists) {
            OutputStream fos = null;
            try {
                fos = new SecureFileOutputStream(ksFile);
                ks.store(fos, pwchars);
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
        return ks;
    }

    /**
     *  Loads certs from location of javax.net.ssl.keyStore property,
     *  else from $JAVA_HOME/lib/security/jssacacerts,
     *  else from $JAVA_HOME/lib/security/cacerts.
     *
     *  @return null on catastrophic failure, returns empty KeyStore if can't load system file
     *  @since 0.8.2, moved from SSLEepGet.initSSLContext() in 0.9.9
     */
    public static KeyStore loadSystemKeyStore() {
        KeyStore ks;
        try {
            ks = KeyStore.getInstance(KeyStore.getDefaultType());
        } catch (GeneralSecurityException gse) {
            error("Key Store init error", gse);
            return null;
        }
        boolean success = false;
        String override = System.getProperty("javax.net.ssl.keyStore");
        if (override != null)
            success = loadCerts(new File(override), ks);
        if (!success) {
            if (SystemVersion.isAndroid()) {
                if (SystemVersion.getAndroidVersion() >= 14) {
                    try {
                        ks.load(null, DEFAULT_KEYSTORE_PASSWORD.toCharArray());
                        success = addCerts(new File(System.getProperty("java.home"), "etc/security/cacerts"), ks) > 0;
                    } catch (Exception e) {}
                } else {
                    success = loadCerts(new File(System.getProperty("java.home"), "etc/security/cacerts.bks"), ks);
                }
            } else {
                success = loadCerts(new File(System.getProperty("java.home"), "lib/security/jssecacerts"), ks);
                if (!success)
                    success = loadCerts(new File(System.getProperty("java.home"), "lib/security/cacerts"), ks);
            }
        }

        if (!success) {
            try {
                // must be initted
                ks.load(null, DEFAULT_KEYSTORE_PASSWORD.toCharArray());
            } catch (Exception e) {}
            error("All key store loads failed, will only load local certificates", null);
        }
        return ks;
    }

    /**
     *  Load all X509 Certs from a key store File into a KeyStore
     *  Note that each call reinitializes the KeyStore
     *
     *  @return success
     *  @since 0.8.2, moved from SSLEepGet in 0.9.9
     */
    private static boolean loadCerts(File file, KeyStore ks) {
        if (!file.exists())
            return false;
        InputStream fis = null;
        try {
            fis = new FileInputStream(file);
            // "changeit" is the default password
            ks.load(fis, DEFAULT_KEYSTORE_PASSWORD.toCharArray());
            info("Certs loaded from " + file);
        } catch (GeneralSecurityException gse) {
            error("KeyStore load error, no default keys: " + file.getAbsolutePath(), gse);
            try {
                // not clear if null is allowed for password
                ks.load(null, DEFAULT_KEYSTORE_PASSWORD.toCharArray());
            } catch (Exception foo) {}
            return false;
        } catch (IOException ioe) {
            error("KeyStore load error, no default keys: " + file.getAbsolutePath(), ioe);
            try {
                ks.load(null, DEFAULT_KEYSTORE_PASSWORD.toCharArray());
            } catch (Exception foo) {}
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (IOException foo) {}
        }
        return true;
    }


    /**
     *  Count all X509 Certs in a key store
     *
     *  @return number successfully added
     *  @since 0.8.2, moved from SSLEepGet in 0.9.9
     */
    public static int countCerts(KeyStore ks) {
        int count = 0;
        try {
            for(Enumeration<String> e = ks.aliases(); e.hasMoreElements();) {
                String alias = e.nextElement();
                if (ks.isCertificateEntry(alias)) {
                    info("Found cert " + alias);
                    count++;
                }
            }
        } catch (Exception foo) {}
        return count;
    }

    /**
     *  Load all X509 Certs from a directory and add them to the
     *  trusted set of certificates in the key store
     *
     *  @return number successfully added
     *  @since 0.8.2, moved from SSLEepGet in 0.9.9
     */
    public static int addCerts(File dir, KeyStore ks) {
        info("Looking for X509 Certificates in " + dir.getAbsolutePath());
        int added = 0;
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File f = files[i];
                    if (!f.isFile())
                        continue;
                    // use file name as alias
                    // https://www.sslshopper.com/ssl-converter.html
                    // No idea if all these formats can actually be read by CertificateFactory
                    String alias = f.getName().toLowerCase(Locale.US);
                    if (alias.endsWith(".crt") || alias.endsWith(".pem") || alias.endsWith(".key") ||
                        alias.endsWith(".der") || alias.endsWith(".key") || alias.endsWith(".p7b") ||
                        alias.endsWith(".p7c") || alias.endsWith(".pfx") || alias.endsWith(".p12"))
                        alias = alias.substring(0, alias.length() - 4);
                    boolean success = addCert(f, alias, ks);
                    if (success)
                        added++;
                }
            }
        }
        return added;
    }

    /**
     *  Load an X509 Cert from a file and add it to the
     *  trusted set of certificates in the key store
     *
     *  @return success
     *  @since 0.8.2, moved from SSLEepGet in 0.9.9
     */
    public static boolean addCert(File file, String alias, KeyStore ks) {
        InputStream fis = null;
        try {
            fis = new FileInputStream(file);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate)cf.generateCertificate(fis);
            info("Read X509 Certificate from " + file.getAbsolutePath() +
                          " Issuer: " + cert.getIssuerX500Principal() +
                          "; Valid From: " + cert.getNotBefore() +
                          " To: " + cert.getNotAfter());
            try {
                cert.checkValidity();
            } catch (CertificateExpiredException cee) {
                String s = "Rejecting expired X509 Certificate: " + file.getAbsolutePath();
                // Android often has old system certs
                if (SystemVersion.isAndroid())
                    warn(s, cee);
                else
                    error(s, cee);
                return false;
            } catch (CertificateNotYetValidException cnyve) {
                error("Rejecting X509 Certificate not yet valid: " + file.getAbsolutePath(), cnyve);
                return false;
            }
            ks.setCertificateEntry(alias, cert);
            info("Now trusting X509 Certificate, Issuer: " + cert.getIssuerX500Principal());
        } catch (GeneralSecurityException gse) {
            error("Error reading X509 Certificate: " + file.getAbsolutePath(), gse);
            return false;
        } catch (IOException ioe) {
            error("Error reading X509 Certificate: " + file.getAbsolutePath(), ioe);
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (IOException foo) {}
        }
        return true;
    }

    /** 48 char b32 string (30 bytes of entropy) */
    public static String randomString() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        // make a random 48 character password (30 * 8 / 5)
        byte[] rand = new byte[30];
        ctx.random().nextBytes(rand);
        return Base32.encode(rand);
    }

    /**
     *  Create a keypair and store it in the keystore at ks, creating it if necessary.
     *  Use default keystore password, valid days, algorithm, and key size.
     *
     *  Warning, may take a long time.
     *
     *  @param ks path to the keystore
     *  @param alias the name of the key
     *  @param cname e.g. randomstuff.console.i2p.net
     *  @param ou e.g. console
     *  @param keyPW the key password, must be at least 6 characters
     *
     *  @return success
     *  @since 0.8.3, consolidated from RouterConsoleRunner and SSLClientListenerRunner in 0.9.9
     */
    public static boolean createKeys(File ks, String alias, String cname, String ou,
                                     String keyPW) {
        return createKeys(ks, DEFAULT_KEYSTORE_PASSWORD, alias, cname, ou,
                          DEFAULT_KEY_VALID_DAYS, DEFAULT_KEY_ALGORITHM, DEFAULT_KEY_SIZE, keyPW);
    }

    /**
     *  Create a keypair and store it in the keystore at ks, creating it if necessary.
     *
     *  Warning, may take a long time.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password
     *  @param alias the name of the key
     *  @param cname e.g. randomstuff.console.i2p.net
     *  @param ou e.g. console
     *  @param validDays e.g. 3652 (10 years)
     *  @param keyAlg e.g. DSA , RSA, EC
     *  @param keySize e.g. 1024
     *  @param keyPW the key password, must be at least 6 characters
     *
     *  @return success
     *  @since 0.8.3, consolidated from RouterConsoleRunner and SSLClientListenerRunner in 0.9.9
     */
    public static boolean createKeys(File ks, String ksPW, String alias, String cname, String ou,
                                     int validDays, String keyAlg, int keySize, String keyPW) {
        if (ks.exists()) {
            try {
                if (getCert(ks, ksPW, alias) != null) {
                    error("Not overwriting key " + alias + ", already exists in " + ks, null);
                    return false;
                }
            } catch (Exception e) {
                error("Not overwriting key \"" + alias + "\", already exists in " + ks, e);
                return false;
            }
        } else {
            File dir = ks.getParentFile();
            if (dir != null && !dir.exists()) {
                File sdir = new SecureDirectory(dir.getAbsolutePath());
                if (!sdir.mkdir()) {
                    error("Can't create directory " + dir, null);
                    return false;
                }
            }
        }
        String keytool = (new File(System.getProperty("java.home"), "bin/keytool")).getAbsolutePath();
        String[] args = new String[] {
                   keytool,
                   "-genkey",            // -genkeypair preferred in newer keytools, but this works with more
                   "-storetype", KeyStore.getDefaultType(),
                   "-keystore", ks.getAbsolutePath(),
                   "-storepass", ksPW,
                   "-alias", alias,
                   "-dname", "CN=" + cname + ",OU=" + ou + ",O=I2P Anonymous Network,L=XX,ST=XX,C=XX",
                   "-validity", Integer.toString(validDays),  // 10 years
                   "-keyalg", keyAlg,
                   "-sigalg", getSigAlg(keySize, keyAlg),
                   "-keysize", Integer.toString(keySize),
                   "-keypass", keyPW
        };
        // TODO pipe key password to process; requires ShellCommand enhancements
        boolean success = (new ShellCommand()).executeSilentAndWaitTimed(args, 240);
        if (success) {
            success = ks.exists();
            if (success) {
                try {
                    success = getPrivateKey(ks, ksPW, alias, keyPW) != null;
                    if (!success)
                        error("Key gen failed to get private key", null);
                } catch (Exception e) {
                    error("Key gen failed to get private key", e);
                    success = false;
                }
            }
            if (!success)
                error("Key gen failed for unknown reasons", null);
        }
        if (success) {
            SecureFileOutputStream.setPerms(ks);
            info("Created self-signed certificate for " + cname + " in keystore: " + ks.getAbsolutePath());
        } else {
            StringBuilder buf = new StringBuilder(256);
            for (int i = 0;  i < args.length; i++) {
                buf.append('"').append(args[i]).append("\" ");
            }
            error("Failed to generate keys using command line: " + buf, null);
        }
        return success;
    }

    private static String getSigAlg(int size, String keyalg) {
        if (keyalg.equals("EC"))
            keyalg = "ECDSA";
        String hash;
        if (keyalg.equals("ECDSA")) {
            if (size <= 256)
                hash = "SHA256";
            else if (size <= 384)
                hash = "SHA384";
            else
                hash = "SHA512";
        } else {
            if (size <= 1024)
                hash = "SHA1";
            else if (size <= 2048)
                hash = "SHA256";
            else if (size <= 3072)
                hash = "SHA384";
            else
                hash = "SHA512";
        }
        return hash + "with" + keyalg;
    }

    /** 
     *  Get a private key out of a keystore
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password, may be null
     *  @param alias the name of the key
     *  @param keyPW the key password, must be at least 6 characters
     *  @return the key or null if not found
     */
    public static PrivateKey getPrivateKey(File ks, String ksPW, String alias, String keyPW)
                              throws GeneralSecurityException, IOException {
        InputStream fis = null;
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            fis = new FileInputStream(ks);
            char[] pwchars = ksPW != null ? ksPW.toCharArray() : null;
            keyStore.load(fis, pwchars);
            char[] keypwchars = keyPW.toCharArray();
            return (PrivateKey) keyStore.getKey(alias, keypwchars);
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
    }

    /** 
     *  Get a cert out of a keystore
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password, may be null
     *  @param alias the name of the key
     *  @return the certificate or null if not found
     */
    public static Certificate getCert(File ks, String ksPW, String alias)
                              throws GeneralSecurityException, IOException {
        InputStream fis = null;
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            fis = new FileInputStream(ks);
            char[] pwchars = ksPW != null ? ksPW.toCharArray() : null;
            keyStore.load(fis, pwchars);
            return keyStore.getCertificate(alias);
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
    }

    /** 
     *  Pull the cert back OUT of the keystore and save it in Base64-encoded X.509 format
     *  so the clients can get to it.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password, may be null
     *  @param alias the name of the key
     *  @param certFile output
     *  @return success
     *  @since 0.8.3 moved from SSLClientListenerRunner in 0.9.9
     */
    public static boolean exportCert(File ks, String ksPW, String alias, File certFile) {
        InputStream fis = null;
        try {
            Certificate cert = getCert(ks, ksPW, alias);
            if (cert != null)
                return CertUtil.saveCert(cert, certFile);
        } catch (GeneralSecurityException gse) {
            error("Error saving ASCII SSL keys", gse);
        } catch (IOException ioe) {
            error("Error saving ASCII SSL keys", ioe);
        }
        return false;
    }

    private static void info(String msg) {
        log(I2PAppContext.getGlobalContext(), Log.INFO, msg, null);
    }

    /** @since 0.9.17 */
    private static void warn(String msg, Throwable t) {
        log(I2PAppContext.getGlobalContext(), Log.WARN, msg, t);
    }

    private static void error(String msg, Throwable t) {
        log(I2PAppContext.getGlobalContext(), Log.ERROR, msg, t);
    }

    //private static void info(I2PAppContext ctx, String msg) {
    //    log(ctx, Log.INFO, msg, null);
    //}

    //private static void error(I2PAppContext ctx, String msg, Throwable t) {
    //    log(ctx, Log.ERROR, msg, t);
    //}

    private static void log(I2PAppContext ctx, int level, String msg, Throwable t) {
        if (level >= Log.WARN && !ctx.isRouterContext()) {
            System.out.println(msg);
            if (t != null)
                t.printStackTrace();
        }
        Log l = ctx.logManager().getLog(KeyStoreUtil.class);
        l.log(level, msg, t);
    }

    public static void main(String[] args) {
        try {
            if (args.length > 0) {
                File ksf = new File(args[0]);
                createKeyStore(ksf, DEFAULT_KEYSTORE_PASSWORD);
                System.out.println("Created empty keystore " + ksf);
            } else {
                KeyStore ks = loadSystemKeyStore();
                if (ks != null) {
                    System.out.println("Loaded system keystore");
                    int count = countCerts(ks);
                    System.out.println("Found " + count + " certs");
                } else {
                    System.out.println("FAIL");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
