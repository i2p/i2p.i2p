package net.i2p.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertStore;
import java.security.cert.X509Certificate;
import java.security.cert.X509CRL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.crypto.provider.I2PProvider;
import net.i2p.data.Base32;
import net.i2p.data.DataHelper;
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
public final class KeyStoreUtil {
        
    public static boolean _blacklistLogged;

    public static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";
    private static final String DEFAULT_KEY_ALGORITHM = "RSA";
    private static final int DEFAULT_KEY_SIZE = 2048;
    private static final String DEFAULT_CA_KEY_ALGORITHM = "EC";
    private static final int DEFAULT_CA_KEY_SIZE = 256;
    private static final int DEFAULT_KEY_VALID_DAYS = 3652;  // 10 years

    static {
        I2PProvider.addProvider();
    }

    /**
     *  SHA1 hashes
     *
     *  No reports of some of these in a Java keystore but just to be safe...
     *  CNNIC ones are in Ubuntu keystore.
     *
     *  In comments below are the serial numer, CN, and OU
     */
    private static final String[] BLACKLIST_SHA1 = new String[] {
        // CNNIC https://googleonlinesecurity.blogspot.com/2015/03/maintaining-digital-certificate-security.html
        //new BigInteger("49:33:00:01".replace(":", ""), 16),
        //"CNNIC ROOT",
        //null,
        "8b:af:4c:9b:1d:f0:2a:92:f7:da:12:8e:b9:1b:ac:f4:98:60:4b:6f",

        // CNNIC EV root https://bugzilla.mozilla.org/show_bug.cgi?id=607208
        //new BigInteger("48:9f:00:01".replace(":", ""), 16),
        //"China Internet Network Information Center EV Certificates Root",
        //null,
        "4f:99:aa:93:fb:2b:d1:37:26:a1:99:4a:ce:7f:f0:05:f2:93:5d:1e",

        // Superfish http://blog.erratasec.com/2015/02/extracting-superfish-certificate.html
        //new BigInteger("d2:fc:13:87:a9:44:dc:e7".replace(":", ""), 16),
        //"Superfish, Inc.",
        //null,
        "c8:64:48:48:69:d4:1d:2b:0d:32:31:9c:5a:62:f9:31:5a:af:2c:bd",

        // eDellRoot https://www.reddit.com/r/technology/comments/3twmfv/dell_ships_laptops_with_rogue_root_ca_exactly/
        //new BigInteger("6b:c5:7b:95:18:93:aa:97:4b:62:4a:c0:88:fc:3b:b6".replace(":", ""), 16),
        //"eDellRoot",
        //null,
        "98:a0:4e:41:63:35:77:90:c4:a7:9e:6d:71:3f:f0:af:51:fe:69:27",

        // DSDTestProvider https://blog.hboeck.de/archives/876-Superfish-2.0-Dangerous-Certificate-on-Dell-Laptops-breaks-encrypted-HTTPS-Connections.html
        // serial number is actually negative; hex string as reported by certtool below
        //new BigInteger("a4:4c:38:47:f8:ee:71:80:43:4d:b1:80:b9:a7:e9:62".replace(":", ""), 16)
        //new BigInteger("-5b:b3:c7:b8:07:11:8e:7f:bc:b2:4e:7f:46:58:16:9e".replace(":", ""), 16),
        //"DSDTestProvider",
        //null,
        "02:c2:d9:31:06:2d:7b:1d:c2:a5:c7:f5:f0:68:50:64:08:1f:b2:21",

        // Verisign G1 Roots
        // https://googleonlinesecurity.blogspot.com/2015/12/proactive-measures-in-digital.html
        // https://knowledge.symantec.com/support/ssl-certificates-support/index?page=content&id=ALERT1941
        // SHA-1
        //new BigInteger("3c:91:31:cb:1f:f6:d0:1b:0e:9a:b8:d0:44:bf:12:be".replace(":", ""), 16),
        //null,
        //"Class 3 Public Primary Certification Authority",
        "a1:db:63:93:91:6f:17:e4:18:55:09:40:04:15:c7:02:40:b0:ae:6b",

        // MD2
        //new BigInteger("70:ba:e4:1d:10:d9:29:34:b6:38:ca:7b:03:cc:ba:bf".replace(":", ""), 16),
        //null,
        //"Class 3 Public Primary Certification Authority",
        "74:2c:31:92:e6:07:e4:24:eb:45:49:54:2b:e1:bb:c5:3e:61:74:e2",

        // Comodo SHA1 https://cabforum.org/pipermail/public/2015-December/006500.html
        // https://bugzilla.mozilla.org/show_bug.cgi?id=1208461
        //new BigInteger("44:be:0c:8b:50:00:21:b4:11:d3:2a:68:06:a9:ad:69".replace(":", ""), 16)
	//"UTN - DATACorp SGC"
        //null
        "58:11:9f:0e:12:82:87:ea:50:fd:d9:87:45:6f:4f:78:dc:fa:d6:d4"
    };

    private static final Set<SHA1Hash> _blacklist = new HashSet<SHA1Hash>(16);

    static {
        for (int i = 0; i < BLACKLIST_SHA1.length; i++) {
            String s = BLACKLIST_SHA1[i].replace(":", "");
            BigInteger bi = new BigInteger(s, 16);
            byte[] b = bi.toByteArray();
            if (b.length == 21) {
                byte[] b2 = new byte[20];
                System.arraycopy(b, 1, b2, 0, 20);
                b = b2;
            }
            SHA1Hash h = new SHA1Hash(b);
            _blacklist.add(h);
        }
    }

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
                // must be initted
                ks.load(null, DEFAULT_KEYSTORE_PASSWORD.toCharArray());
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
     *  else from $JAVA_HOME/lib/security/jssecacerts,
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
                    } catch (IOException e) {
                    } catch (GeneralSecurityException e) {}
                } else {
                    success = loadCerts(new File(System.getProperty("java.home"), "etc/security/cacerts.bks"), ks);
                }
            } else {
                success = loadCerts(new File(System.getProperty("java.home"), "lib/security/jssecacerts"), ks);
                if (!success)
                    success = loadCerts(new File(System.getProperty("java.home"), "lib/security/cacerts"), ks);
            }
        }

        if (success) {
            removeBlacklistedCerts(ks);
        } else {
            try {
                // must be initted
                ks.load(null, DEFAULT_KEYSTORE_PASSWORD.toCharArray());
            } catch (IOException e) {
            } catch (GeneralSecurityException e) {}
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
            } catch (IOException foo) {
            } catch (GeneralSecurityException e) {}
            return false;
        } catch (IOException ioe) {
            error("KeyStore load error, no default keys: " + file.getAbsolutePath(), ioe);
            try {
                ks.load(null, DEFAULT_KEYSTORE_PASSWORD.toCharArray());
            } catch (IOException foo) {
            } catch (GeneralSecurityException e) {}
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
                    //info("Found cert " + alias);
                    count++;
                }
            }
        } catch (GeneralSecurityException e) {}
        return count;
    }

    /**
     *  Validate expiration for all private key certs in a key store.
     *  Use this for keystores containing selfsigned certs where the
     *  user will be expected to renew an expiring cert.
     *  Use this for Jetty keystores, where we aren't doing the loading ourselves.
     *
     *  If a cert isn't valid, it will probably cause bigger problems later when it's used.
     *
     *  @param f keystore file
     *  @param ksPW keystore password
     *  @param expiresWithin ms if cert expires within this long, we will log a warning, e.g. 180*24*60*60*1000L
     *  @return true if all are good, false if we logged something
     *  @since 0.9.34
     */
    public static boolean logCertExpiration(File f, String ksPW, long expiresWithin) {
        String location = f.getAbsolutePath();
        InputStream fis = null;
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            fis = new FileInputStream(f);
            ks.load(fis, ksPW.toCharArray());
            return logCertExpiration(ks, location, expiresWithin);
        } catch (IOException ioe) {
            error("Unable to check certificates in key store " + location, ioe);
            return false;
        } catch (GeneralSecurityException gse) {
            error("Unable to check certificates in key store " + location, gse);
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (IOException foo) {}
        }
    }

    /**
     *  Validate expiration for all private key certs in a key store.
     *  Use this for keystores containing selfsigned certs where the
     *  user will be expected to renew an expiring cert.
     *  Use this for keystores we are feeding to an SSLContext and ServerSocketFactory.
     *
     *  We added support for self-signed certs in 0.8.3 2011-01, with a 10-year expiration.
     *  We still don't generate them by default. We don't expect anybody's
     *  certs to expire until 2021.
     *
     *  @param location the path or other identifying info, for logging only
     *  @param expiresWithin ms if cert expires within this long, we will log a warning, e.g. 180*24*60*60*1000L
     *  @return true if all are good, false if we logged something
     *  @since 0.9.34
     */
    public static boolean logCertExpiration(KeyStore ks, String location, long expiresWithin) {
        boolean rv = true;
        try {
            int count = 0;
            for(Enumeration<String> e = ks.aliases(); e.hasMoreElements();) {
                String alias = e.nextElement();
                if (ks.isKeyEntry(alias)) {
                    Certificate[] cs;
                    try {
                        cs = ks.getCertificateChain(alias);
                    } catch (KeyStoreException kse) {
                        error("Unable to check certificates for \"" + alias + "\" in key store " + location, kse);
                        rv = false;
                        continue;
                    }
                    for (Certificate c : cs) {
                        if (c != null && (c instanceof X509Certificate)) {
                            count++;
                            X509Certificate cert = (X509Certificate) c;
                            try {
                                //System.out.println("checking " + alias + " in " + location);
                                cert.checkValidity();
                                long expiresIn = cert.getNotAfter().getTime() - System.currentTimeMillis();
                                //System.out.println("expiration of " + alias + " is in " + DataHelper.formatDuration(expiresIn));
                                if (expiresIn < expiresWithin) {
                                    Log l = I2PAppContext.getGlobalContext().logManager().getLog(KeyStoreUtil.class);
                                    String subj = cert.getIssuerX500Principal().toString();
                                    l.logAlways(Log.WARN, "Certificate \"" + subj + "\" in key store " + location +
                                                          " will expire in " + DataHelper.formatDuration2(expiresIn).replace("&nbsp;", " ") +
                                                          "\nYou should renew the certificate soon." +
                                                          // TODO better help or tools, or autorenew
                                                          "\nFor a local self-signed certificate, you may delete the keystore and restart," +
                                                          " or ask for help on how to renew.");
                                }
                            } catch (CertificateExpiredException cee) {
                                String subj = cert.getIssuerX500Principal().toString();
                                error("Expired certificate \"" + subj + "\" in key store " + location +
                                      "\nYou must renew the certificate." +
                                      // TODO better help or tools, or autorenew
                                      "\nFor a local self-signed certificate, you may simply delete the keystore and restart," +
                                      "\nor ask for help on how to renew.",
                                      null);
                                rv = false;
                            } catch (CertificateNotYetValidException cnyve) {
                                String subj = cert.getIssuerX500Principal().toString();
                                error("Not yet valid certificate \"" + subj + "\" in key store " + location, null);
                                rv = false;
                            }
                        }
                    }
                }
            }
            if (count == 0)
                error("No certificates found in key store " + location, null);
        } catch (GeneralSecurityException e) {
            error("Unable to check certificates in key store " + location, e);
            rv = false;
        }
        return rv;
    }

    /**
     *  Remove all blacklisted X509 Certs in a key store.
     *
     *  @return number successfully removed
     *  @since 0.9.24
     */
    private synchronized static int removeBlacklistedCerts(KeyStore ks) {
        if (SystemVersion.isAndroid())
            return 0;
        List<String> toRemove = new ArrayList<String>(4);
        try {
            MessageDigest md = SHA1.getInstance();
            for(Enumeration<String> e = ks.aliases(); e.hasMoreElements();) {
                String alias = e.nextElement();
                if (ks.isCertificateEntry(alias)) {
                    Certificate c = ks.getCertificate(alias);
                    if (c != null && (c instanceof X509Certificate)) {
                        //X509Certificate xc = (X509Certificate) c;
                        //BigInteger serial = xc.getSerialNumber();
                        // debug:
                        //String xname = CertUtil.getIssuerValue(xc, "CN");
                        //info("Found \"" + xname + "\" s/n: " + serial.toString(16));
                        //if (xname == null)
                        //    info("name is null, full issuer: " + xc.getIssuerX500Principal().getName());
                        byte[] enc = c.getEncoded();
                        if (enc != null) {
                            byte[] h = md.digest(enc);
                            //StringBuilder buf = new StringBuilder(60);
                            //String hex = DataHelper.toString(h);
                            //for (int i = 0; i < hex.length(); i += 2) {
                            //    buf.append(hex.charAt(i));
                            //    buf.append(hex.charAt(i+1));
                            //    if (i < hex.length() - 2)
                            //        buf.append(':');
                            //}
                            //info("hex is: " + buf);
                            if (_blacklist.contains(new SHA1Hash(h))) {
                                toRemove.add(alias);
                                if (!_blacklistLogged) {
                                    // should this be a logAlways?
                                    X509Certificate xc = (X509Certificate) c;
                                    BigInteger serial = xc.getSerialNumber();
                                    String cn = CertUtil.getIssuerValue(xc, "CN");
                                    String ou = CertUtil.getIssuerValue(xc, "OU");
                                    warn("Ignoring blacklisted certificate \"" + alias +
                                         "\" CN: \"" + cn +
                                         "\" OU: \"" + ou +
                                         "\" s/n: " + serial.toString(16), null);
                                }
                            }
                        } else {
                            info("null encoding!!!");
                        }
                    }
                }
            }
        } catch (GeneralSecurityException e) {}
        if (!toRemove.isEmpty()) {
            _blacklistLogged = true;
            for (String alias : toRemove) {
                try {
                    ks.deleteEntry(alias);
                } catch (GeneralSecurityException e) {}
            }
        }
        return toRemove.size();
    }

    /**
     *  Load all X509 Certs from a directory and add them to the
     *  trusted set of certificates in the key store
     *
     *  This DOES check for revocation.
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
                CertStore cs = CertUtil.loadCRLs();
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
                        alias.endsWith(".p7c") || alias.endsWith(".pfx") || alias.endsWith(".p12") ||
                        alias.endsWith(".cer"))
                        alias = alias.substring(0, alias.length() - 4);
                    boolean success = addCert(f, alias, ks, cs);
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
     *  This does NOT check for revocation.
     *
     *  @return success
     *  @since 0.8.2, moved from SSLEepGet in 0.9.9
     */
    public static boolean addCert(File file, String alias, KeyStore ks) {
        return addCert(file, alias, ks, null);
    }

    /**
     *  Load an X509 Cert from a file and add it to the
     *  trusted set of certificates in the key store
     *
     *  This DOES check for revocation, IF cs is non-null.
     *
     *  @param cs may be null; if non-null, check for revocation
     *  @return success
     *  @since 0.9.25
     */
    public static boolean addCert(File file, String alias, KeyStore ks, CertStore cs) {
        try {
            X509Certificate cert = CertUtil.loadCert(file);
            info("Read X509 Certificate from " + file.getAbsolutePath() +
                          " Issuer: " + cert.getIssuerX500Principal() +
                          " Serial: " + cert.getSerialNumber().toString(16) +
                          "; Valid From: " + cert.getNotBefore() +
                          " To: " + cert.getNotAfter());
            if (cs != null && CertUtil.isRevoked(cs, cert)) {
                error("Certificate is revoked: " + file, new Exception());
                return false;
            }
            ks.setCertificateEntry(alias, cert);
            info("Now trusting X509 Certificate, Issuer: " + cert.getIssuerX500Principal());
        } catch (CertificateExpiredException cee) {
            String s = "Rejecting expired X509 Certificate: " + file.getAbsolutePath();
            // Android often has old system certs
            // our SSL certs may be old also
            //if (SystemVersion.isAndroid())
                warn(s, cee);
            //else
            //    error(s, cee);
            return false;
        } catch (CertificateNotYetValidException cnyve) {
            error("Rejecting X509 Certificate not yet valid: " + file.getAbsolutePath(), cnyve);
            return false;
        } catch (GeneralSecurityException gse) {
            error("Error reading X509 Certificate: " + file.getAbsolutePath(), gse);
            return false;
        } catch (IOException ioe) {
            error("Error reading X509 Certificate: " + file.getAbsolutePath(), ioe);
            return false;
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
     *  As of 0.9.35, default algorithm and size depends on cname. If it appears to be
     *  a CA, it will use EC/256. Otherwise, it will use RSA/2048.
     *
     *  Warning, may take a long time.
     *
     *  @param ks path to the keystore
     *  @param alias the name of the key
     *  @param cname e.g. localhost. Must be a hostname or email address. IP addresses will not be correctly encoded.
     *  @param ou e.g. console
     *  @param keyPW the key password, must be at least 6 characters
     *
     *  @return success
     *  @since 0.8.3, consolidated from RouterConsoleRunner and SSLClientListenerRunner in 0.9.9
     */
    public static boolean createKeys(File ks, String alias, String cname, String ou,
                                     String keyPW) {
        final boolean isCA = !cname.contains("@") && !cname.endsWith(".family.i2p.net") &&
                             SigType.ECDSA_SHA256_P256.isAvailable();
        final String alg = isCA ? DEFAULT_CA_KEY_ALGORITHM : DEFAULT_KEY_ALGORITHM;
        final int sz = isCA ? DEFAULT_CA_KEY_SIZE : DEFAULT_KEY_SIZE;
        return createKeys(ks, DEFAULT_KEYSTORE_PASSWORD, alias, cname, null, ou,
                          DEFAULT_KEY_VALID_DAYS, alg, sz, keyPW);
    }

    /**
     *  Create a keypair and store it in the keystore at ks, creating it if necessary.
     *  Use default keystore password, valid days, algorithm, and key size.
     *
     *  As of 0.9.35, default algorithm and size depends on cname. If it appears to be
     *  a CA, it will use EC/256. Otherwise, it will use RSA/2048.
     *
     *  Warning, may take a long time.
     *
     *  @param ks path to the keystore
     *  @param alias the name of the key
     *  @param cname e.g. localhost. Must be a hostname or email address. IP addresses will not be correctly encoded.
     *  @param altNames the Subject Alternative Names. May be null. May contain hostnames and/or IP addresses.
     *                  cname, localhost, 127.0.0.1, and ::1 will be automatically added.
     *  @param ou e.g. console
     *  @param keyPW the key password, must be at least 6 characters
     *
     *  @return success
     *  @since 0.9.34 added altNames param
     */
    public static boolean createKeys(File ks, String alias, String cname, Set<String> altNames, String ou,
                                     String keyPW) {
        final boolean isCA = !cname.contains("@") && !cname.endsWith(".family.i2p.net") &&
                             SigType.ECDSA_SHA256_P256.isAvailable();
        final String alg = isCA ? DEFAULT_CA_KEY_ALGORITHM : DEFAULT_KEY_ALGORITHM;
        final int sz = isCA ? DEFAULT_CA_KEY_SIZE : DEFAULT_KEY_SIZE;
        return createKeys(ks, DEFAULT_KEYSTORE_PASSWORD, alias, cname, altNames, ou,
                          DEFAULT_KEY_VALID_DAYS, alg, sz, keyPW);
    }

    /**
     *  Create a keypair and store it in the keystore at ks, creating it if necessary.
     *
     *  For new code, the createKeysAndCRL() with the SigType argument is recommended over this one,
     *  as it throws exceptions, and returns the certificate and CRL.
     *
     *  Warning, may take a long time.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password
     *  @param alias the name of the key
     *  @param cname e.g. localhost. Must be a hostname or email address. IP addresses will not be correctly encoded.
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
        return createKeys(ks, ksPW, alias, cname, null, ou, validDays, keyAlg, keySize, keyPW);
    }

    /**
     *  Create a keypair and store it in the keystore at ks, creating it if necessary.
     *
     *  For new code, the createKeysAndCRL() with the SigType argument is recommended over this one,
     *  as it throws exceptions, and returns the certificate and CRL.
     *
     *  Warning, may take a long time.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password
     *  @param alias the name of the key
     *  @param cname e.g. localhost. Must be a hostname or email address. IP addresses will not be correctly encoded.
     *  @param altNames the Subject Alternative Names. May be null. May contain hostnames and/or IP addresses.
     *                  cname, localhost, 127.0.0.1, and ::1 will be automatically added.
     *  @param ou e.g. console
     *  @param validDays e.g. 3652 (10 years)
     *  @param keyAlg e.g. DSA , RSA, EC
     *  @param keySize e.g. 1024
     *  @param keyPW the key password, must be at least 6 characters
     *
     *  @return success
     *  @since 0.9.34 added altNames param
     */
    public static boolean createKeys(File ks, String ksPW, String alias, String cname, Set<String> altNames, String ou,
                                     int validDays, String keyAlg, int keySize, String keyPW) {
        boolean useKeytool = I2PAppContext.getGlobalContext().getBooleanProperty("crypto.useExternalKeytool");
        if (useKeytool) {
            if (altNames != null)
                throw new IllegalArgumentException("can't do SAN in keytool");
            return createKeysCLI(ks, ksPW, alias, cname, ou, validDays, keyAlg, keySize, keyPW);
        } else {
            try {
                createKeysAndCRL(ks, ksPW, alias, cname, altNames, ou, validDays, keyAlg, keySize, keyPW);
                return true;
            } catch (GeneralSecurityException gse) {
                error("Create keys error", gse);
                return false;
            } catch (IOException ioe) {
                error("Create keys error", ioe);
                return false;
            }
        }
    }

    /**
     *  New way - Native Java, does not call out to keytool.
     *  Create a keypair and store it in the keystore at ks, creating it if necessary.
     *
     *  This returns the public key, private key, certificate, and CRL in an array.
     *  All of these are Java classes. Keys may be converted to I2P classes with SigUtil.
     *  The private key and selfsigned cert are stored in the keystore.
     *  The public key may be derived from the private key with KeyGenerator.getSigningPublicKey().
     *  The public key certificate may be stored separately with
     *  CertUtil.saveCert() if desired.
     *  The CRL is not stored by this method, store it with
     *  CertUtil.saveCRL() or CertUtil.exportCRL() if desired.
     *
     *  Throws on all errors.
     *  Warning, may take a long time.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password
     *  @param alias the name of the key
     *  @param cname e.g. localhost. Must be a hostname or email address. IP addresses will not be correctly encoded.
     *  @param ou e.g. console
     *  @param validDays e.g. 3652 (10 years)
     *  @param keyAlg e.g. DSA , RSA, EC
     *  @param keySize e.g. 1024
     *  @param keyPW the key password, must be at least 6 characters
     *  @return all you need:
     *      rv[0] is a Java PublicKey
     *      rv[1] is a Java PrivateKey
     *      rv[2] is a Java X509Certificate
     *      rv[3] is a Java X509CRL
     *  @since 0.9.25
     */
    public static Object[] createKeysAndCRL(File ks, String ksPW, String alias, String cname, String ou,
                                            int validDays, String keyAlg, int keySize, String keyPW)
                                                throws GeneralSecurityException, IOException {
        return createKeysAndCRL(ks, ksPW, alias, cname, null, ou, validDays, keyAlg, keySize, keyPW);
    }

    /**
     *  New way - Native Java, does not call out to keytool.
     *  Create a keypair and store it in the keystore at ks, creating it if necessary.
     *
     *  This returns the public key, private key, certificate, and CRL in an array.
     *  All of these are Java classes. Keys may be converted to I2P classes with SigUtil.
     *  The private key and selfsigned cert are stored in the keystore.
     *  The public key may be derived from the private key with KeyGenerator.getSigningPublicKey().
     *  The public key certificate may be stored separately with
     *  CertUtil.saveCert() if desired.
     *  The CRL is not stored by this method, store it with
     *  CertUtil.saveCRL() or CertUtil.exportCRL() if desired.
     *
     *  Throws on all errors.
     *  Warning, may take a long time.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password
     *  @param alias the name of the key
     *  @param cname e.g. localhost. Must be a hostname or email address. IP addresses will not be correctly encoded.
     *  @param altNames the Subject Alternative Names. May be null. May contain hostnames and/or IP addresses.
     *                  cname, localhost, 127.0.0.1, and ::1 will be automatically added.
     *  @param ou e.g. console
     *  @param validDays e.g. 3652 (10 years)
     *  @param keyAlg e.g. DSA , RSA, EC
     *  @param keySize e.g. 1024
     *  @param keyPW the key password, must be at least 6 characters
     *  @return all you need:
     *      rv[0] is a Java PublicKey
     *      rv[1] is a Java PrivateKey
     *      rv[2] is a Java X509Certificate
     *      rv[3] is a Java X509CRL
     *  @since 0.9.34 added altNames param
     */
    public static Object[] createKeysAndCRL(File ks, String ksPW, String alias, String cname, Set<String> altNames, String ou,
                                            int validDays, String keyAlg, int keySize, String keyPW)
                                                throws GeneralSecurityException, IOException {
        String algoName = getSigAlg(keySize, keyAlg);
        SigType type = null;
        for (SigType t : EnumSet.allOf(SigType.class)) {
            if (t.getAlgorithmName().equals(algoName)) {
                type = t;
                break;
            }
        }
        if (type == null)
            throw new GeneralSecurityException("Unsupported algorithm/size: " + keyAlg + '/' + keySize);
        return createKeysAndCRL(ks, ksPW, alias, cname, altNames, ou, validDays, type, keyPW);
    }

    /**
     *  New way - Native Java, does not call out to keytool.
     *  Create a keypair and store it in the keystore at ks, creating it if necessary.
     *
     *  This returns the public key, private key, certificate, and CRL in an array.
     *  All of these are Java classes. Keys may be converted to I2P classes with SigUtil.
     *  The private key and selfsigned cert are stored in the keystore.
     *  The public key may be derived from the private key with KeyGenerator.getSigningPublicKey().
     *  The public key certificate may be stored separately with
     *  CertUtil.saveCert() if desired.
     *  The CRL is not stored by this method, store it with
     *  CertUtil.saveCRL() or CertUtil.exportCRL() if desired.
     *
     *  Throws on all errors.
     *  Warning, may take a long time.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password
     *  @param alias the name of the key
     *  @param cname e.g. localhost. Must be a hostname or email address. IP addresses will not be correctly encoded.
     *  @param ou e.g. console
     *  @param validDays e.g. 3652 (10 years)
     *  @param keyPW the key password, must be at least 6 characters
     *  @return all you need:
     *      rv[0] is a Java PublicKey
     *      rv[1] is a Java PrivateKey
     *      rv[2] is a Java X509Certificate
     *      rv[3] is a Java X509CRL
     *  @since 0.9.25
     */
    public static Object[] createKeysAndCRL(File ks, String ksPW, String alias, String cname, String ou,
                                            int validDays, SigType type, String keyPW)
                                                throws GeneralSecurityException, IOException {
        return createKeysAndCRL(ks, ksPW, alias, cname, null, ou, validDays, type, keyPW);
    }

    /**
     *  New way - Native Java, does not call out to keytool.
     *  Create a keypair and store it in the keystore at ks, creating it if necessary.
     *
     *  This returns the public key, private key, certificate, and CRL in an array.
     *  All of these are Java classes. Keys may be converted to I2P classes with SigUtil.
     *  The private key and selfsigned cert are stored in the keystore.
     *  The public key may be derived from the private key with KeyGenerator.getSigningPublicKey().
     *  The public key certificate may be stored separately with
     *  CertUtil.saveCert() if desired.
     *  The CRL is not stored by this method, store it with
     *  CertUtil.saveCRL() or CertUtil.exportCRL() if desired.
     *
     *  Throws on all errors.
     *  Warning, may take a long time.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password
     *  @param alias the name of the key
     *  @param cname e.g. localhost. Must be a hostname or email address. IP addresses will not be correctly encoded.
     *  @param altNames the Subject Alternative Names. May be null. May contain hostnames and/or IP addresses.
     *                  cname, localhost, 127.0.0.1, and ::1 will be automatically added.
     *  @param ou e.g. console
     *  @param validDays e.g. 3652 (10 years)
     *  @param keyPW the key password, must be at least 6 characters
     *  @return all you need:
     *      rv[0] is a Java PublicKey
     *      rv[1] is a Java PrivateKey
     *      rv[2] is a Java X509Certificate
     *      rv[3] is a Java X509CRL
     *  @since 0.9.34 added altNames param
     */
    public static Object[] createKeysAndCRL(File ks, String ksPW, String alias, String cname, Set<String> altNames, String ou,
                                            int validDays, SigType type, String keyPW)
                                                throws GeneralSecurityException, IOException {
        File dir = ks.getParentFile();
        if (dir != null && !dir.exists()) {
            File sdir = new SecureDirectory(dir.getAbsolutePath());
            if (!sdir.mkdirs())
                throw new IOException("Can't create directory " + dir);
        }
        Object[] rv = SelfSignedGenerator.generate(cname, altNames, ou, "I2P", "I2P Anonymous Network", null, null, validDays, type);
        //PublicKey jpub = (PublicKey) rv[0];
        PrivateKey jpriv = (PrivateKey) rv[1];
        X509Certificate cert = (X509Certificate) rv[2];
        //X509CRL crl = (X509CRL) rv[3];
        List<X509Certificate> certs = Collections.singletonList(cert);
        storePrivateKey(ks, ksPW, alias, keyPW, jpriv, certs);
        return rv;
    }

    /**
     *  OLD way - keytool
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
    private static boolean createKeysCLI(File ks, String ksPW, String alias, String cname, String ou,
                                     int validDays, String keyAlg, int keySize, String keyPW) {
        if (ks.exists()) {
            try {
                if (getCert(ks, ksPW, alias) != null) {
                    error("Not overwriting key " + alias + ", already exists in " + ks, null);
                    return false;
                }
            } catch (IOException e) {
                error("Not overwriting key \"" + alias + "\", already exists in " + ks, e);
                return false;
            } catch (GeneralSecurityException e) {
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
        List<String> a = new ArrayList<String>(32);
        a.add(keytool);
        a.add("-genkey");    // -genkeypair preferred in newer keytools, but this works with more
        //a.add("-v");         // verbose, gives you a stack trace on exception
        a.add("-storetype"); a.add(KeyStore.getDefaultType());
        a.add("-keystore");  a.add(ks.getAbsolutePath());
        a.add("-storepass"); a.add(ksPW);
        a.add("-alias");     a.add(alias);
        a.add("-dname");     a.add("CN=" + cname + ",OU=" + ou + ",O=I2P Anonymous Network,L=XX,ST=XX,C=XX");
        a.add("-validity");  a.add(Integer.toString(validDays));  // 10 years
        a.add("-keyalg");    a.add(keyAlg);
        a.add("-sigalg");    a.add(getSigAlg(keySize, keyAlg));
        a.add("-keysize");   a.add(Integer.toString(keySize));
        a.add("-keypass");   a.add(keyPW);
        if (keyAlg.equals("Ed") || keyAlg.equals("EdDSA") || keyAlg.equals("ElGamal")) {
            File f = I2PAppContext.getGlobalContext().getBaseDir();
            f = new File(f, "lib");
            f = new File(f, "i2p.jar");
            // providerpath is not in the man page; see keytool -genkey -help
            a.add("-providerpath");  a.add(f.getAbsolutePath());
            a.add("-providerclass"); a.add("net.i2p.crypto.provider.I2PProvider");
        }
        String[] args = a.toArray(new String[a.size()]);
        // TODO pipe key password to process; requires ShellCommand enhancements
        boolean success = (new ShellCommand()).executeSilentAndWaitTimed(args, 240);
        if (success) {
            success = ks.exists();
            if (success) {
                try {
                    success = getPrivateKey(ks, ksPW, alias, keyPW) != null;
                    if (!success)
                        error("Key gen failed to get private key", null);
                } catch (IOException e) {
                    error("Key gen failed to get private key", e);
                    success = false;
                } catch (GeneralSecurityException e) {
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
        else if (keyalg.equals("Ed"))
            keyalg = "EdDSA";
        String hash;
        if (keyalg.equals("ECDSA")) {
            if (size <= 256)
                hash = "SHA256";
            else if (size <= 384)
                hash = "SHA384";
            else
                hash = "SHA512";
        } else if (keyalg.equals("EdDSA")) {
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
     *  Export the private key and certificate chain (if any) out of a keystore.
     *  Does NOT close the output stream. Throws on all errors.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password, may be null
     *  @param alias the name of the key
     *  @param keyPW the key password, must be at least 6 characters
     *  @since 0.9.25
     */
    public static void exportPrivateKey(File ks, String ksPW, String alias, String keyPW,
                                        OutputStream out)
                              throws GeneralSecurityException, IOException {
        InputStream fis = null;
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            fis = new FileInputStream(ks);
            char[] pwchars = ksPW != null ? ksPW.toCharArray() : null;
            keyStore.load(fis, pwchars);
            char[] keypwchars = keyPW.toCharArray();
            PrivateKey pk = (PrivateKey) keyStore.getKey(alias, keypwchars);
            if (pk == null)
                throw new GeneralSecurityException("private key not found: " + alias);
            Certificate[] certs = keyStore.getCertificateChain(alias);
            CertUtil.exportPrivateKey(pk, certs, out);
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
    }

    /** 
     *  Renew the the private key certificate in a keystore.
     *  Closes the input and output streams. Throws on all errors.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password, may be null
     *  @param alias the name of the key, or null to get the first one in keystore
     *  @param keyPW the key password, must be at least 6 characters
     *  @param validDays new cert to expire this many days from now
     *  @return the new certificate
     *  @since 0.9.34
     */
    public static X509Certificate renewPrivateKeyCertificate(File ks, String ksPW, String alias,
                                                             String keyPW, int validDays)
                                                             throws GeneralSecurityException, IOException {
        InputStream fis = null;
        OutputStream fos = null;
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            fis = new FileInputStream(ks);
            char[] pwchars = ksPW != null ? ksPW.toCharArray() : null;
            keyStore.load(fis, pwchars);
            try { fis.close(); } catch (IOException ioe) {}
            fis = null;
            char[] keypwchars = keyPW.toCharArray();
            if (alias == null) {
                for (Enumeration<String> e = keyStore.aliases(); e.hasMoreElements();) {
                    alias = e.nextElement();
                    break;
                }
                if (alias == null)
                    throw new GeneralSecurityException("no private keys found");
            }
            PrivateKey pk = (PrivateKey) keyStore.getKey(alias, keypwchars);
            if (pk == null)
                throw new GeneralSecurityException("private key not found: " + alias);
            Certificate[] certs = keyStore.getCertificateChain(alias);
            if (certs.length != 1)
                throw new GeneralSecurityException("Bad cert chain length");
            X509Certificate cert = (X509Certificate) certs[0];
            Object[] rv = SelfSignedGenerator.renew(cert, pk, validDays);
            cert = (X509Certificate) rv[2];
            certs[0] = cert;
            keyStore.setKeyEntry(alias, pk, keypwchars, certs);
            fos = new SecureFileOutputStream(ks);
            keyStore.store(fos, pwchars);
            return cert;
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }

    /** 
     *  Import the private key and certificate chain to a keystore.
     *  Keystore will be created if it does not exist.
     *  Private key MUST be first in the stream.
     *  Closes the stream. Throws on all errors.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password, may be null
     *  @param alias the name of the key. If null, will be taken from the Subject CN
     *               of the first certificate in the chain.
     *  @param keyPW the key password, must be at least 6 characters
     *  @return the alias as specified or extracted
     *  @since 0.9.25
     */
    public static String importPrivateKey(File ks, String ksPW, String alias, String keyPW,
                                          InputStream in)
                              throws GeneralSecurityException, IOException {
        OutputStream fos = null;
        try {
            KeyStore keyStore = createKeyStore(ks, ksPW);
            PrivateKey pk = CertUtil.loadPrivateKey(in);
            List<X509Certificate> certs = CertUtil.loadCerts(in);
            if (alias == null) {
                alias = CertUtil.getSubjectValue(certs.get(0), "CN");
                if (alias == null)
                    throw new GeneralSecurityException("no alias specified and no Subject CN in cert");
                if (alias.endsWith(".family.i2p.net") && alias.length() > ".family.i2p.net".length())
                    alias = alias.substring(0, ".family.i2p.net".length());
            }
            keyStore.setKeyEntry(alias, pk, keyPW.toCharArray(), certs.toArray(new Certificate[certs.size()]));
            char[] pwchars = ksPW != null ? ksPW.toCharArray() : null;
            fos = new SecureFileOutputStream(ks);
            keyStore.store(fos, pwchars);
            return alias;
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            try { in.close(); } catch (IOException ioe) {}
        }
    }

    /** 
     *  Import the private key and certificate chain to a keystore.
     *  Keystore will be created if it does not exist.
     *  Private key MUST be first in the stream.
     *  Closes the stream. Throws on all errors.
     *
     *  @param ks path to the keystore
     *  @param ksPW the keystore password, may be null
     *  @param alias the name of the key, non-null.
     *  @param keyPW the key password, must be at least 6 characters
     *  @since 0.9.25
     */
    public static void storePrivateKey(File ks, String ksPW, String alias, String keyPW,
                                       PrivateKey pk, List<X509Certificate> certs)
                              throws GeneralSecurityException, IOException {
        OutputStream fos = null;
        try {
            KeyStore keyStore = createKeyStore(ks, ksPW);
            keyStore.setKeyEntry(alias, pk, keyPW.toCharArray(), certs.toArray(new Certificate[certs.size()]));
            char[] pwchars = ksPW != null ? ksPW.toCharArray() : null;
            fos = new SecureFileOutputStream(ks);
            keyStore.store(fos, pwchars);
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
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

    /**
     *   Usage: KeyStoreUtil (loads from system keystore)
     *          KeyStoreUtil foo.ks (loads from system keystore, and from foo.ks keystore if exists, else creates empty)
     *          KeyStoreUtil certDir (loads from system keystore and all certs in certDir if exists)
     *          KeyStoreUtil import file.ks file.key alias keypw (imports private key from file to keystore)
     *          KeyStoreUtil export file.ks alias keypw (exports private key from keystore)
     *          KeyStoreUtil keygen file.ks alias keypw (create keypair in keystore)
     *          KeyStoreUtil keygen2 file.ks alias keypw (create keypair using I2PProvider)
     */
/****
    public static void main(String[] args) {
        try {
            if (args.length > 0 && "import".equals(args[0])) {
                testImport(args);
                return;
            }
            if (args.length > 0 && "export".equals(args[0])) {
                testExport(args);
                return;
            }
            if (args.length > 0 && "keygen".equals(args[0])) {
                testKeygen(args);
                return;
            }
            if (args.length > 0 && "keygen2".equals(args[0])) {
                testKeygen2(args);
                return;
            }
            File ksf = (args.length > 0) ? new File(args[0]) : null;
            if (ksf != null && !ksf.exists()) {
                createKeyStore(ksf, DEFAULT_KEYSTORE_PASSWORD);
                System.out.println("Created empty keystore " + ksf);
            } else {
                KeyStore ks = loadSystemKeyStore();
                if (ks != null) {
                    System.out.println("Loaded system keystore");
                    int count = countCerts(ks);
                    System.out.println("Found " + count + " certs");
                    if (ksf != null && ksf.isDirectory()) {
                        count = addCerts(ksf, ks);
                        System.out.println("Found " + count + " certs in " + ksf);
                        if (count > 0) {
                            // rerun blacklist as a test
                            _blacklistLogged = false;
                            count = removeBlacklistedCerts(ks);
                            if (count > 0)
                                System.out.println("Found " + count + " blacklisted certs in " + ksf);
                        }
                    }
                } else {
                    System.out.println("FAIL");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void testImport(String[] args) throws Exception {
        File ksf = new File(args[1]);
        InputStream in = new FileInputStream(args[2]);
        String alias = args[3];
        String pw = args[4];
        importPrivateKey(ksf, DEFAULT_KEYSTORE_PASSWORD, alias, pw, in);
    }


    private static void testExport(String[] args) throws Exception {
        File ksf = new File(args[1]);
        String alias = args[2];
        String pw = args[3];
        exportPrivateKey(ksf, DEFAULT_KEYSTORE_PASSWORD, alias, pw, System.out);
    }

    private static void testKeygen(String[] args) throws Exception {
        File ksf = new File(args[1]);
        String alias = args[2];
        String pw = args[3];
        boolean ok = createKeys(ksf, DEFAULT_KEYSTORE_PASSWORD, alias, "test cname", "test ou",
                                //DEFAULT_KEY_VALID_DAYS, "EdDSA", 256, pw);
                                DEFAULT_KEY_VALID_DAYS, "ElGamal", 2048, pw);
        System.out.println("genkey ok? " + ok);
    }

    private static void testKeygen2(String[] args) throws Exception {
        // keygen test using the I2PProvider
        SigType type = SigType.EdDSA_SHA512_Ed25519;
        //SigType type = SigType.ElGamal_SHA256_MODP2048;
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance(type.getBaseAlgorithm().getName());
        kpg.initialize(type.getParams());
        java.security.KeyPair kp = kpg.generateKeyPair();
        java.security.PublicKey jpub = kp.getPublic();
        java.security.PrivateKey jpriv = kp.getPrivate();

        System.out.println("Encoded private key:");
        System.out.println(net.i2p.util.HexDump.dump(jpriv.getEncoded()));
        System.out.println("Encoded public key:");
        System.out.println(net.i2p.util.HexDump.dump(jpub.getEncoded()));

        java.security.Signature jsig = java.security.Signature.getInstance(type.getAlgorithmName());
        jsig.initSign(jpriv);
        byte[] data = new byte[111];
        net.i2p.util.RandomSource.getInstance().nextBytes(data);
        jsig.update(data);
        byte[] bsig = jsig.sign();
        System.out.println("Encoded signature:");
        System.out.println(net.i2p.util.HexDump.dump(bsig));
        jsig.initVerify(jpub);
        jsig.update(data);
        boolean ok = jsig.verify(bsig);
        System.out.println("verify passed? " + ok);

        net.i2p.data.Signature sig = SigUtil.fromJavaSig(bsig, type);
        System.out.println("Signature test: " + sig);
    }
****/
}
