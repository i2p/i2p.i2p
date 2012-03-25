package net.i2p.router.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLContext;

import net.i2p.client.I2PClient;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.ShellCommand;

/**
 * SSL version of ClientListenerRunner
 *
 * @since 0.8.3
 * @author zzz
 */
class SSLClientListenerRunner extends ClientListenerRunner {

    private SSLServerSocketFactory _factory;

    private static final String PROP_KEYSTORE_PASSWORD = "i2cp.keystorePassword";
    private static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";
    private static final String PROP_KEY_PASSWORD = "i2cp.keyPassword";
    private static final String KEY_ALIAS = "i2cp";
    private static final String ASCII_KEYFILE = "i2cp.local.crt";

    public SSLClientListenerRunner(RouterContext context, ClientManager manager, int port) {
        super(context, manager, port);
    }
    
    /**
     * @return success if it exists and we have a password, or it was created successfully.
     */
    private boolean verifyKeyStore(File ks) {
        if (ks.exists()) {
            boolean rv = _context.getProperty(PROP_KEY_PASSWORD) != null;
            if (!rv)
                _log.error("I2CP SSL error, must set " + PROP_KEY_PASSWORD + " in " +
                           (new File(_context.getConfigDir(), "router.config")).getAbsolutePath());
            return rv;
        }
        File dir = ks.getParentFile();
        if (!dir.exists()) {
            File sdir = new SecureDirectory(dir.getAbsolutePath());
            if (!sdir.mkdir())
                return false;
        }
        boolean rv = createKeyStore(ks);

        // Now read it back out of the new keystore and save it in ascii form
        // where the clients can get to it.
        // Failure of this part is not fatal.
        if (rv)
            exportCert(ks);
        return rv;
    }


    /**
     * Call out to keytool to create a new keystore with a keypair in it.
     * Trying to do this programatically is a nightmare, requiring either BouncyCastle
     * libs or using proprietary Sun libs, and it's a huge mess.
     * If successful, stores the keystore password and key password in router.config.
     *
     * @return success
     */
    private boolean createKeyStore(File ks) {
        // make a random 48 character password (30 * 8 / 5)
        byte[] rand = new byte[30];
        _context.random().nextBytes(rand);
        String keyPassword = Base32.encode(rand);
        // and one for the cname
        _context.random().nextBytes(rand);
        String cname = Base32.encode(rand) + ".i2cp.i2p.net";

        String keytool = (new File(System.getProperty("java.home"), "bin/keytool")).getAbsolutePath();
        String[] args = new String[] {
                   keytool,
                   "-genkey",            // -genkeypair preferred in newer keytools, but this works with more
                   "-storetype", KeyStore.getDefaultType(),
                   "-keystore", ks.getAbsolutePath(),
                   "-storepass", DEFAULT_KEYSTORE_PASSWORD,
                   "-alias", KEY_ALIAS,
                   "-dname", "CN=" + cname + ",OU=I2CP,O=I2P Anonymous Network,L=XX,ST=XX,C=XX",
                   "-validity", "3652",  // 10 years
                   "-keyalg", "DSA",
                   "-keysize", "1024",
                   "-keypass", keyPassword};
        boolean success = (new ShellCommand()).executeSilentAndWaitTimed(args, 30);  // 30 secs
        if (success) {
            success = ks.exists();
            if (success) {
                SecureFileOutputStream.setPerms(ks);
                Map<String, String> changes = new HashMap();
                changes.put(PROP_KEYSTORE_PASSWORD, DEFAULT_KEYSTORE_PASSWORD);
                changes.put(PROP_KEY_PASSWORD, keyPassword);
                _context.router().saveConfig(changes, null);
            }
        }
        if (success) {
            _log.logAlways(Log.INFO, "Created self-signed certificate for " + cname + " in keystore: " + ks.getAbsolutePath() + "\n" +
                           "The certificate name was generated randomly, and is not associated with your " +
                           "IP address, host name, router identity, or destination keys.");
        } else {
            _log.error("Failed to create I2CP SSL keystore using command line:");
            StringBuilder buf = new StringBuilder(256);
            for (int i = 0;  i < args.length; i++) {
                buf.append('"').append(args[i]).append("\" ");
            }
            _log.error(buf.toString());
            _log.error("This is for the Sun/Oracle keytool, others may be incompatible.\n" +
                       "If you create the keystore manually, you must add " + PROP_KEYSTORE_PASSWORD + " and " + PROP_KEY_PASSWORD +
                       " to " + (new File(_context.getConfigDir(), "router.config")).getAbsolutePath());
        }
        return success;
    }

    /** 
     * Pull the cert back OUT of the keystore and save it as ascii
     * so the clients can get to it.
     */
    private void exportCert(File ks) {
        File sdir = new SecureDirectory(_context.getConfigDir(), "certificates");
        if (sdir.exists() || sdir.mkdir()) {
            InputStream fis = null;
            try {
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                fis = new FileInputStream(ks);
                String ksPass = _context.getProperty(PROP_KEYSTORE_PASSWORD, DEFAULT_KEYSTORE_PASSWORD);
                keyStore.load(fis, ksPass.toCharArray());
                Certificate cert = keyStore.getCertificate(KEY_ALIAS);
                if (cert != null) {
                    File certFile = new File(sdir, ASCII_KEYFILE);
                    saveCert(cert, certFile);
                } else {
                    _log.error("Error getting SSL cert to save as ASCII");
                }
            } catch (GeneralSecurityException gse) {
                _log.error("Error saving ASCII SSL keys", gse);
            } catch (IOException ioe) {
                _log.error("Error saving ASCII SSL keys", ioe);
            } finally {
                if (fis != null) try { fis.close(); } catch (IOException ioe) {}
            }
        } else {
            _log.error("Error saving ASCII SSL keys");
        }
    }

    private static final int LINE_LENGTH = 64;

    /**
     *  Modified from:
     *  http://www.exampledepot.com/egs/java.security.cert/ExportCert.html
     *
     *  Write a certificate to a file in base64 format.
     */
    private void saveCert(Certificate cert, File file) {
        OutputStream os = null;
        try {
           // Get the encoded form which is suitable for exporting
           byte[] buf = cert.getEncoded();
           os = new SecureFileOutputStream(file);
           PrintWriter wr = new PrintWriter(os);
           wr.println("-----BEGIN CERTIFICATE-----");
           String b64 = Base64.encode(buf, true);     // true = use standard alphabet
           for (int i = 0; i < b64.length(); i += LINE_LENGTH) {
               wr.println(b64.substring(i, Math.min(i + LINE_LENGTH, b64.length())));
           }
           wr.println("-----END CERTIFICATE-----");
           wr.flush();
        } catch (CertificateEncodingException cee) {
           _log.error("Error writing X509 Certificate " + file.getAbsolutePath(), cee);
        } catch (IOException ioe) {
            _log.error("Error writing X509 Certificate " + file.getAbsolutePath(), ioe);
        } finally {
            try { if (os != null) os.close(); } catch (IOException foo) {}
        }
    }

    /** 
     * Sets up the SSLContext and sets the socket factory.
     * @return success
     */
    private boolean initializeFactory(File ks) {
        String ksPass = _context.getProperty(PROP_KEYSTORE_PASSWORD, DEFAULT_KEYSTORE_PASSWORD);
        String keyPass = _context.getProperty(PROP_KEY_PASSWORD);
        if (keyPass == null) {
            _log.error("No key password, set " + PROP_KEY_PASSWORD +
                       " in " + (new File(_context.getConfigDir(), "router.config")).getAbsolutePath());
            return false;
        }
        InputStream fis = null;
        try {
            SSLContext sslc = SSLContext.getInstance("TLS");
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            fis = new FileInputStream(ks);
            keyStore.load(fis, ksPass.toCharArray());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keyPass.toCharArray());
            sslc.init(kmf.getKeyManagers(), null, _context.random());
            _factory = sslc.getServerSocketFactory();
            return true;
        } catch (GeneralSecurityException gse) {
            _log.error("Error loading SSL keys", gse);
        } catch (IOException ioe) {
            _log.error("Error loading SSL keys", ioe);
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
        return false;
    }

    /** 
     * Get a SSLServerSocket.
     */
    @Override
    protected ServerSocket getServerSocket() throws IOException {
        ServerSocket rv;
        if (_bindAllInterfaces) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Listening on port " + _port + " on all interfaces");
            rv = _factory.createServerSocket(_port);
        } else {
            String listenInterface = _context.getProperty(ClientManagerFacadeImpl.PROP_CLIENT_HOST, 
                                                          ClientManagerFacadeImpl.DEFAULT_HOST);
            if (_log.shouldLog(Log.INFO))
                _log.info("Listening on port " + _port + " of the specific interface: " + listenInterface);
            rv = _factory.createServerSocket(_port, 0, InetAddress.getByName(listenInterface));
        }
        return rv;
    }

    /** 
     * Create (if necessary) and load the key store, then run.
     */
    @Override
    public void runServer() {
        File keyStore = new File(_context.getConfigDir(), "keystore/i2cp.ks");
        if (verifyKeyStore(keyStore) && initializeFactory(keyStore)) {
            super.runServer();
        } else {
            _log.error("SSL I2CP server error - Failed to create or open key store");
        }
    }

    /**
     *  Overridden because SSL handshake may need more time,
     *  and available() in super doesn't work.
     *  The handshake doesn't start until a read().
     */
    @Override
    protected boolean validate(Socket socket) {
        try {
            InputStream is = socket.getInputStream();
            int oldTimeout = socket.getSoTimeout();
            socket.setSoTimeout(4 * CONNECT_TIMEOUT);
            boolean rv = is.read() == I2PClient.PROTOCOL_BYTE;
            socket.setSoTimeout(oldTimeout);
            return rv;
        } catch (IOException ioe) {}
        if (_log.shouldLog(Log.WARN))
             _log.warn("Peer did not authenticate themselves as I2CP quickly enough, dropping");
        return false;
    }
}
