package net.i2p.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.security.GeneralSecurityException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyStoreUtil;
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
                initSSLContext(ctx);
                if (_factory == null)
                    throw new IOException("Unable to create SSL Context for I2CP Client");
                info(ctx, "I2CP Client-side SSL Context initialized");
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
            error(context, "Key Store init error", gse);
            return;
        } catch (IOException ioe) {
            error(context, "Key Store init error", ioe);
            return;
        }

        File dir = new File(context.getConfigDir(), CERT_DIR);
        int adds = KeyStoreUtil.addCerts(dir, ks);
        int totalAdds = adds;
        if (adds > 0)
            info(context, "Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());

        File dir2 = new File(System.getProperty("user.dir"), CERT_DIR);
        if (!dir.getAbsolutePath().equals(dir2.getAbsolutePath())) {
            adds = KeyStoreUtil.addCerts(dir2, ks);
            totalAdds += adds;
            if (adds > 0)
                info(context, "Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());
        }
        if (totalAdds > 0) {
            info(context, "Loaded total of " + totalAdds + " new trusted certificates");
        } else {
            error(context, "No trusted certificates loaded (looked in " +
                       dir.getAbsolutePath() + (dir.getAbsolutePath().equals(dir2.getAbsolutePath()) ? "" : (" and " + dir2.getAbsolutePath())) +
                       ", I2CP SSL client connections will fail. " +
                       "Copy the file certificates/i2cp.local.crt from the router to the directory.", null);
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
            error(context, "SSL context init error", gse);
        }
    }

    /** @since 0.9.8 */
    private static void info(I2PAppContext ctx, String msg) {
        log(ctx, Log.INFO, msg, null);
    }

    /** @since 0.9.8 */
    private static void error(I2PAppContext ctx, String msg, Throwable t) {
        log(ctx, Log.ERROR, msg, t);
    }

    /** @since 0.9.8 */
    private static void log(I2PAppContext ctx, int level, String msg, Throwable t) {
        Log l = ctx.logManager().getLog(I2CPSSLSocketFactory.class);
        l.log(level, msg, t);
    }
}
