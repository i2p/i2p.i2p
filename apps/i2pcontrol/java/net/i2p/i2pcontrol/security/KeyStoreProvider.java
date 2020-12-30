package net.i2p.i2pcontrol.security;

import net.i2p.crypto.KeyStoreUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class KeyStoreProvider {
    public static final String DEFAULT_CERTIFICATE_ALGORITHM_STRING = "RSA";
    public static final int DEFAULT_CERTIFICATE_KEY_LENGTH = 4096;
    public static final int DEFAULT_CERTIFICATE_VALIDITY = 365 * 10;
    public final static String DEFAULT_CERTIFICATE_DOMAIN = "localhost";
    public final static String DEFAULT_CERTIFICATE_ALIAS = "I2PControl CA";
    public static final String DEFAULT_KEYSTORE_NAME = "i2pcontrol.ks";
    public static final String DEFAULT_KEYSTORE_PASSWORD = KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD;
    public static final String DEFAULT_CERTIFICATE_PASSWORD = "nut'nfancy";
    private final String _pluginDir;
    private KeyStore _keystore;

    public KeyStoreProvider(String pluginDir) {
        _pluginDir = pluginDir;
    }

    public void initialize() {
        KeyStoreUtil.createKeys(new File(getKeyStoreLocation()),
                                DEFAULT_KEYSTORE_PASSWORD,
                                DEFAULT_CERTIFICATE_ALIAS,
                                DEFAULT_CERTIFICATE_DOMAIN,
                                "i2pcontrol",
                                DEFAULT_CERTIFICATE_VALIDITY,
                                DEFAULT_CERTIFICATE_ALGORITHM_STRING,
                                DEFAULT_CERTIFICATE_KEY_LENGTH,
                                DEFAULT_CERTIFICATE_PASSWORD);
    }

    /**
     *  @param password unused
     *  @return null on failure
     */
    public static X509Certificate readCert(KeyStore ks, String certAlias, String password) {
        try {
            X509Certificate cert = (X509Certificate) ks.getCertificate(certAlias);

            if (cert == null) {
                throw new RuntimeException("Got null cert from keystore!");
            }

            try {
                cert.verify(cert.getPublicKey());
                return cert;
            } catch (Exception e) {
                System.err.println("Failed to verify caCert certificate against caCert");
                e.printStackTrace();
            }
        } catch (KeyStoreException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     *  @param password for the keystore
     *  @return null on failure
     */
/****
    public static X509Certificate readCert(File keyStoreFile, String certAlias, String password) {
        try {
            KeyStore ks = getDefaultKeyStore();
            ks.load(new FileInputStream(keyStoreFile), password.toCharArray());
            X509Certificate cert = (X509Certificate) ks.getCertificate(certAlias);

            if (cert == null) {
                throw new RuntimeException("Got null cert from keystore!");
            }

            try {
                cert.verify(cert.getPublicKey());
                return cert;
            } catch (Exception e) {
                System.err.println("Failed to verify caCert certificate against caCert");
                e.printStackTrace();
            }
        } catch (IOException e) {
            System.err.println("Couldn't read keystore from: " + keyStoreFile.toString());
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (CertificateException e) {
            e.printStackTrace();
        } catch (KeyStoreException e) {
            System.err.println("No certificate with alias: " + certAlias + "  found.");
            e.printStackTrace();
        }
        return null;
    }
****/

    /**
     *  @param password for the key
     *  @return null on failure, or throws RuntimeException...
     */
/****
    public static PrivateKey readPrivateKey(KeyStore ks, String alias, String password) {
        try {
            // load the key entry from the keystore
            Key key = ks.getKey(alias, password.toCharArray());

            if (key == null) {
                throw new RuntimeException("Got null key from keystore!");
            }

            PrivateKey privKey = (PrivateKey) key;
            return privKey;
        } catch (UnrecoverableKeyException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (KeyStoreException e) {
            e.printStackTrace();
        }
        return null;
    }
****/

    /**
     *  @return null on failure
     */
/****
    public static PrivateKey readPrivateKey(String alias, File keyStoreFile, String keyStorePassword, String keyPassword) {
        try {
            KeyStore ks = getDefaultKeyStore();
            ks.load(new FileInputStream(keyStoreFile), keyStorePassword.toCharArray());
            return readPrivateKey(ks, alias, keyStorePassword);
        } catch (CertificateException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("Couldn't read keystore from: " + keyStoreFile.toString());
            e.printStackTrace();
        }
        return null;
    }
****/

    /**
     *  @return null on failure
     */
/****
    public static KeyStore writeCACertToKeyStore(KeyStore keyStore, String keyPassword, String alias, PrivateKey caPrivKey, X509Certificate caCert) {
        try {
            X509Certificate[] chain = new X509Certificate[1];
            chain[0] = caCert;

            keyStore.setKeyEntry(alias, caPrivKey, keyPassword.toCharArray(), chain);
            File keyStoreFile = new File(I2PControlController.getPluginDir() + File.separator + DEFAULT_KEYSTORE_NAME);
            keyStore.store(new FileOutputStream(keyStoreFile), DEFAULT_KEYSTORE_PASSWORD.toCharArray());
            return keyStore;
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (CertificateException e) {
            e.printStackTrace();
        } catch (KeyStoreException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
****/

    /**
     *  @return null on failure
     */
    public synchronized KeyStore getDefaultKeyStore() {
        if (_keystore == null) {
            File keyStoreFile = new File(getKeyStoreLocation());

            InputStream is = null;
            try {
                _keystore = KeyStore.getInstance(KeyStore.getDefaultType());
                if (keyStoreFile.exists()) {
                    is = new FileInputStream(keyStoreFile);
                    _keystore.load(is, DEFAULT_KEYSTORE_PASSWORD.toCharArray());
                    return _keystore;
                }

                initialize();
                if (keyStoreFile.exists()) {
                    is = new FileInputStream(keyStoreFile);
                    _keystore.load(is, DEFAULT_KEYSTORE_PASSWORD.toCharArray());
                    return _keystore;
                } else {
                    throw new IOException("KeyStore file " + keyStoreFile.getAbsolutePath() + " wasn't readable");
                }
            } catch (Exception e) {
                // Ignore. Not an issue. Let's just create a new keystore instead.
            } finally {
                if (is != null) try { is.close(); } catch (IOException ioe) {}
            }
            return null;
        } else {
            return _keystore;
        }
    }

    public String getKeyStoreLocation() {
        File keyStoreFile = new File(_pluginDir, DEFAULT_KEYSTORE_NAME);
        return keyStoreFile.getAbsolutePath();
    }
}
