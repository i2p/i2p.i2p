package net.i2p.i2ptunnel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLContext;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyStoreUtil;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;

/**
 * Utilities for I2PTunnel client SSL server sockets.
 *
 * @since 0.9.15 adopted from net.i2p.router.client.SSLClientListenerRunner
 */
public class SSLClientUtil {

    private static final String PROP_KEYSTORE_PASSWORD = "keystorePassword";
    private static final String PROP_KEY_PASSWORD = "keyPassword";
    private static final String PROP_KEY_ALIAS = "keyAlias";
    private static final String ASCII_KEYFILE_SUFFIX = ".local.crt";
    private static final String PROP_KS_NAME = "keystoreFile";
    private static final String KS_DIR = "keystore";
    private static final String PREFIX = "i2ptunnel-";
    private static final String KS_SUFFIX = ".ks";
    private static final String CERT_DIR = "certificates/i2ptunnel";

    /**
     *  Create a new selfsigned cert and keystore and pubkey cert if they don't exist.
     *  May take a while.
     *
     *  @param opts in/out, updated if rv is true
     *  @return false if it already exists; if true, caller must save opts
     *  @throws IOException on creation fail
     */
    public static boolean verifyKeyStore(Properties opts) throws IOException {
        return verifyKeyStore(opts, "");
    }

    /**
     *  Create a new selfsigned cert and keystore and pubkey cert if they don't exist.
     *  May take a while.
     *
     *  @param opts in/out, updated if rv is true
     *  @param optPfx add this prefix when getting/setting options
     *  @return false if it already exists; if true, caller must save opts
     *  @throws IOException on creation fail
     */
    public static boolean verifyKeyStore(Properties opts, String optPfx) throws IOException {
        return verifyKeyStore(opts, optPfx, null);
    }

    /**
     *  Create a new selfsigned cert and keystore and pubkey cert if they don't exist.
     *  May take a while.
     *
     *  @param opts in/out, updated if rv is true
     *  @param optPfx add this prefix when getting/setting options
     *  @param altNames the Subject Alternative Names. May be null. May contain hostnames and/or IP addresses.
     *                  cname, localhost, 127.0.0.1, and ::1 will be automatically added.
     *  @return false if it already exists; if true, caller must save opts
     *  @throws IOException on creation fail
     *  @since 0.9.34 added altNames param
     */
    public static boolean verifyKeyStore(Properties opts, String optPfx, Set<String> altNames) throws IOException {
        String name = opts.getProperty(optPfx + PROP_KEY_ALIAS);
        if (name == null) {
            name = KeyStoreUtil.randomString();
            opts.setProperty(optPfx + PROP_KEY_ALIAS, name);
        }
        String ksname = opts.getProperty(optPfx + PROP_KS_NAME);
        if (ksname == null) {
            ksname = PREFIX + name + KS_SUFFIX;
            opts.setProperty(optPfx + PROP_KS_NAME, ksname);
        }
        File ks = new File(ksname);
        if (!ks.isAbsolute()) {
            ks = new File(I2PAppContext.getGlobalContext().getConfigDir(), KS_DIR);
            ks = new File(ks, ksname);
        }
        if (ks.exists())
            return false;
        File dir = ks.getParentFile();
        if (!dir.exists()) {
            File sdir = new SecureDirectory(dir.getAbsolutePath());
            if (!sdir.mkdirs())
                throw new IOException("Unable to create keystore " + ks);
        }
        boolean rv = createKeyStore(ks, name, opts, optPfx, altNames);
        if (!rv)
            throw new IOException("Unable to create keystore " + ks);

        // Now read it back out of the new keystore and save it in ascii form
        // where the clients can get to it.
        // Failure of this part is not fatal.
        exportCert(ks, name, opts, optPfx);
        return true;
    }


    /**
     *  Create a new keystore with a keypair in it.
     *
     *  @param name used in CNAME
     *  @param opts in/out, updated if rv is true, must contain PROP_KEY_ALIAS
     *  @param optPfx add this prefix when getting/setting options
     *  @param altNames the Subject Alternative Names. May be null. May contain hostnames and/or IP addresses.
     *                  cname, localhost, 127.0.0.1, and ::1 will be automatically added.
     *  @return success, if true, opts will have password properties added to be saved
     */
    private static boolean createKeyStore(File ks, String name, Properties opts, String optPfx, Set<String> altNames) {
        // make a random 48 character password (30 * 8 / 5)
        String keyPassword = KeyStoreUtil.randomString();
        String cname = "localhost";

        String keyName = opts.getProperty(optPfx + PROP_KEY_ALIAS);
        boolean success = KeyStoreUtil.createKeys(ks, keyName, cname, altNames, "I2PTUNNEL", keyPassword);
        if (success) {
            success = ks.exists();
            if (success) {
                opts.setProperty(optPfx + PROP_KEYSTORE_PASSWORD, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD);
                opts.setProperty(optPfx + PROP_KEY_PASSWORD, keyPassword);
            }
        }
        if (success) {
            logAlways("Created self-signed certificate for " + cname + " in keystore: " + ks.getAbsolutePath() + "\n" +
                      "The certificate was generated randomly.\n" +
                      "Unless you have changed the default settings, the certificate is not associated with your " +
                           "IP address, host name, router identity, or destination keys.");
        } else {
            error("Failed to create I2PTunnel SSL keystore.\n" +
                       "If you create the keystore manually, you must add " + optPfx + PROP_KEYSTORE_PASSWORD + " and " + optPfx + PROP_KEY_PASSWORD +
                       " to " + (new File(I2PAppContext.getGlobalContext().getConfigDir(), "i2ptunnel.config")).getAbsolutePath());
        }
        return success;
    }

    /** 
     *  Pull the cert back OUT of the keystore and save it as ascii
     *  so the clients can get to it.
     *
     *  @param name used to generate output file name
     *  @param opts must contain optPfx + PROP_KEY_ALIAS
     *  @param optPfx add this prefix when getting options
     */
    private static void exportCert(File ks, String name, Properties opts, String optPfx) {
        File sdir = new SecureDirectory(I2PAppContext.getGlobalContext().getConfigDir(), CERT_DIR);
        if (sdir.exists() || sdir.mkdirs()) {
            String keyAlias = opts.getProperty(optPfx + PROP_KEY_ALIAS);
            String ksPass = opts.getProperty(optPfx + PROP_KEYSTORE_PASSWORD, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD);
            File out = new File(sdir, PREFIX + name + ASCII_KEYFILE_SUFFIX);
            boolean success = KeyStoreUtil.exportCert(ks, ksPass, keyAlias, out);
            if (!success)
                error("Error getting SSL cert to save as ASCII");
        } else {
            error("Error saving ASCII SSL keys");
        }
    }

    /** 
     *  Sets up the SSLContext and sets the socket factory.
     *  No option prefix allowed.
     *
     * @throws IOException GeneralSecurityExceptions are wrapped in IOE for convenience
     * @return factory, throws on all errors
     */
    public static SSLServerSocketFactory initializeFactory(Properties opts) throws IOException {
        String ksPass = opts.getProperty(PROP_KEYSTORE_PASSWORD, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD);
        String keyPass = opts.getProperty(PROP_KEY_PASSWORD);
        if (keyPass == null) {
            throw new IOException("No key password, set " + PROP_KEY_PASSWORD + " in " +
                       (new File(I2PAppContext.getGlobalContext().getConfigDir(), "i2ptunnel.config")).getAbsolutePath());
        }
        String ksname = opts.getProperty(PROP_KS_NAME);
        if (ksname == null) {
            throw new IOException("No keystore, set " + PROP_KS_NAME + " in " +
                       (new File(I2PAppContext.getGlobalContext().getConfigDir(), "i2ptunnel.config")).getAbsolutePath());
        }
        File ks = new File(ksname);
        if (!ks.isAbsolute()) {
            ks = new File(I2PAppContext.getGlobalContext().getConfigDir(), KS_DIR);
            ks = new File(ks, ksname);
        }

        InputStream fis = null;
        try {
            SSLContext sslc = SSLContext.getInstance("TLS");
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            fis = new FileInputStream(ks);
            keyStore.load(fis, ksPass.toCharArray());
            KeyStoreUtil.logCertExpiration(keyStore, ks.getAbsolutePath(), 180*24*60*60*1000L);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keyPass.toCharArray());
            sslc.init(kmf.getKeyManagers(), null, I2PAppContext.getGlobalContext().random());
            return sslc.getServerSocketFactory();
        } catch (GeneralSecurityException gse) {
            IOException ioe = new IOException("keystore error");
            ioe.initCause(gse);
            throw ioe;
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
    }

    private static void error(String s) {
        I2PAppContext.getGlobalContext().logManager().getLog(SSLClientUtil.class).error(s);
    }

    private static void logAlways(String s) {
        I2PAppContext.getGlobalContext().logManager().getLog(SSLClientUtil.class).logAlways(Log.INFO, s);
    }
}
