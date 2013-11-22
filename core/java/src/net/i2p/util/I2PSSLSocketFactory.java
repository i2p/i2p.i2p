package net.i2p.util;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.GeneralSecurityException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyStoreUtil;

/**
 * Loads trusted ASCII certs from ~/.i2p/certificates/ and $I2P/certificates/.
 *
 * @author zzz
 * @since 0.9.9 moved from ../client, original since 0.8.3
 */
public class I2PSSLSocketFactory {

    private final SSLSocketFactory _factory;

    /**
     * @param relativeCertPath e.g. "certificates/i2cp"
     * @since 0.9.9 was static
     */
    public I2PSSLSocketFactory(I2PAppContext context, boolean loadSystemCerts, String relativeCertPath)
                               throws GeneralSecurityException {
        _factory = initSSLContext(context, loadSystemCerts, relativeCertPath);
    }

    /**
     * Returns a socket to the host.
     */
    public Socket createSocket(String host, int port) throws IOException {
        return _factory.createSocket(host, port);
    }

    /**
     * Returns a socket to the host.
     * @since 0.9.9
     */
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return _factory.createSocket(host, port);
    }

    /**
     *  Loads certs from
     *  the ~/.i2p/certificates/ and $I2P/certificates/ directories.
     */
    private static SSLSocketFactory initSSLContext(I2PAppContext context, boolean loadSystemCerts, String relativeCertPath)
                               throws GeneralSecurityException {
        Log log = context.logManager().getLog(I2PSSLSocketFactory.class);
        KeyStore ks;
        if (loadSystemCerts) {
            ks = KeyStoreUtil.loadSystemKeyStore();
            if (ks == null)
                throw new GeneralSecurityException("Key Store init error");
        } else {
            try {
                ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, "".toCharArray());
            } catch (IOException ioe) {
                throw new GeneralSecurityException("Key Store init error", ioe);
            }
        }

        File dir = new File(context.getConfigDir(), relativeCertPath);
        int adds = KeyStoreUtil.addCerts(dir, ks);
        int totalAdds = adds;
        if (adds > 0) {
            if (log.shouldLog(Log.INFO))
                log.info("Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());
        }

        File dir2 = new File(context.getBaseDir(), relativeCertPath);
        if (!dir.getAbsolutePath().equals(dir2.getAbsolutePath())) {
            adds = KeyStoreUtil.addCerts(dir2, ks);
            totalAdds += adds;
            if (adds > 0) {
                if (log.shouldLog(Log.INFO))
                    log.info("Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());
            }
        }
        if (totalAdds > 0 || loadSystemCerts) {
            if (log.shouldLog(Log.INFO))
                log.info("Loaded total of " + totalAdds + " new trusted certificates");
        } else {
            String msg = "No trusted certificates loaded (looked in " +
                       dir.getAbsolutePath() + (dir.getAbsolutePath().equals(dir2.getAbsolutePath()) ? "" : (" and " + dir2.getAbsolutePath())) +
                       ", SSL connections will fail. " +
                       "Copy the cert in " + relativeCertPath + " from the router to the directory.";
            // don't continue, since we didn't load the system keystore, we have nothing.
            throw new GeneralSecurityException(msg);
        }

            SSLContext sslc = SSLContext.getInstance("TLS");
            TrustManagerFactory tmf =   TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            sslc.init(null, tmf.getTrustManagers(), context.random());
            return sslc.getSocketFactory();
    }
}
