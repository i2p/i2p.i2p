package net.i2p.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Loads trusted ASCII certs from ~/.i2p/certificates/ and $CWD/certificates/.
 * Keeps a single static SSLContext for the whole JVM.
 *
 * @author zzz
 * @since 0.8.3
 */
class I2CPSSLSocketFactory {

    private static final Object _initLock = new Object();
    private static SSLSocketFactory _factory;
    private static Log _log;

    private static final String CERT_DIR = "certificates";

    /**
     * Initializes the static SSL Context if required, then returns a socket
     * to the host.
     *
     * @param ctx just for logging
     * @throws IOException on init error or usual socket errors
     */
    public static Socket createSocket(I2PAppContext ctx, String host, int port) throws IOException {
        synchronized(_initLock) {
            if (_factory == null) {
                _log = ctx.logManager().getLog(I2CPSSLSocketFactory.class);
                initSSLContext(ctx);
                if (_factory == null)
                    throw new IOException("Unable to create SSL Context for I2CP Client");
                _log.info("I2CP Client-side SSL Context initialized");
            }
        }
        return _factory.createSocket(host, port);
    }

    /**
     *  Loads certs from
     *  the ~/.i2p/certificates/ and $CWD/certificates/ directories.
     */
    private static void initSSLContext(I2PAppContext context) {
        KeyStore ks;
        try {
            ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, "".toCharArray());
        } catch (GeneralSecurityException gse) {
            _log.error("Key Store init error", gse);
            return;
        } catch (IOException ioe) {
            _log.error("Key Store init error", ioe);
            return;
        }

        File dir = new File(context.getConfigDir(), CERT_DIR);
        int adds = addCerts(dir, ks);
        int totalAdds = adds;
        if (adds > 0 && _log.shouldLog(Log.INFO))
            _log.info("Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());

        File dir2 = new File(System.getProperty("user.dir"), CERT_DIR);
        if (!dir.getAbsolutePath().equals(dir2.getAbsolutePath())) {
            adds = addCerts(dir2, ks);
            totalAdds += adds;
            if (adds > 0 && _log.shouldLog(Log.INFO))
                _log.info("Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());
        }
        if (totalAdds > 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Loaded total of " + totalAdds + " new trusted certificates");
        } else {
            _log.error("No trusted certificates loaded (looked in " +
                       dir.getAbsolutePath() + (dir.getAbsolutePath().equals(dir2.getAbsolutePath()) ? "" : (" and " + dir2.getAbsolutePath())) +
                       ", I2CP SSL client connections will fail. " +
                       "Copy the file certificates/i2cp.local.crt from the router to the directory.");
            // don't continue, since we didn't load the system keystore, we have nothing.
            return;
        }

        try {
            SSLContext sslc = SSLContext.getInstance("TLS");
            TrustManagerFactory tmf =   TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            sslc.init(null, tmf.getTrustManagers(), context.random());
            _factory = sslc.getSocketFactory();
        } catch (GeneralSecurityException gse) {
            _log.error("SSL context init error", gse);
        }
    }

    /**
     *  Load all X509 Certs from a directory and add them to the
     *  trusted set of certificates in the key store
     *
     *  @return number successfully added
     */
    private static int addCerts(File dir, KeyStore ks) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Looking for X509 Certificates in " + dir.getAbsolutePath());
        int added = 0;
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File f = files[i];
                    if (!f.isFile())
                        continue;
                    // use file name as alias
                    String alias = f.getName().toLowerCase(Locale.US);
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
     */
    private static boolean addCert(File file, String alias, KeyStore ks) {
        InputStream fis = null;
        try {
            fis = new FileInputStream(file);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate)cf.generateCertificate(fis);
            if (_log.shouldLog(Log.INFO)) {
                _log.info("Read X509 Certificate from " + file.getAbsolutePath() +
                          " Issuer: " + cert.getIssuerX500Principal() +
                          "; Valid From: " + cert.getNotBefore() +
                          " To: " + cert.getNotAfter());
            }
            try {
                cert.checkValidity();
            } catch (CertificateExpiredException cee) {
                _log.error("Rejecting expired X509 Certificate: " + file.getAbsolutePath(), cee);
                return false;
            } catch (CertificateNotYetValidException cnyve) {
                _log.error("Rejecting X509 Certificate not yet valid: " + file.getAbsolutePath(), cnyve);
                return false;
            }
            ks.setCertificateEntry(alias, cert);
            if (_log.shouldLog(Log.INFO))
                _log.info("Now trusting X509 Certificate, Issuer: " + cert.getIssuerX500Principal());
        } catch (GeneralSecurityException gse) {
            _log.error("Error reading X509 Certificate: " + file.getAbsolutePath(), gse);
            return false;
        } catch (IOException ioe) {
            _log.error("Error reading X509 Certificate: " + file.getAbsolutePath(), ioe);
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (IOException foo) {}
        }
        return true;
    }
}
