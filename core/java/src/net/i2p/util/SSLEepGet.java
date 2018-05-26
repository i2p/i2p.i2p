package net.i2p.util;

/*
 * Contains code from:
 * http://blogs.sun.com/andreas/resource/InstallCert.java
 * http://blogs.sun.com/andreas/entry/no_more_unable_to_find
 *
 * ===============
 *
 * Copyright 2006 Sun Microsystems, Inc.  All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   - Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *
 *   - Neither the name of Sun Microsystems nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Locale;
import java.net.Socket;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;
import net.i2p.crypto.CertUtil;
import net.i2p.crypto.KeyStoreUtil;
import net.i2p.data.DataHelper;
import net.i2p.socks.SOCKS4Client;
import net.i2p.socks.SOCKS5Client;

/**
 * HTTPS only, no retries, no min and max size options, no timeout option
 * Fails on 301 or 302 (doesn't follow redirect)
 * Fails on bad certs (must have a valid cert chain)
 * Self-signed certs or CAs not in the JVM key store must be loaded to be trusted.
 *
 * Since 0.8.2, loads additional trusted CA certs from $I2P/certificates/ssl/ and ~/.i2p/certificates/ssl/
 *
 * Since 0.9.33, HTTP proxies (CONNECT) supported. Proxy auth not supported.
 *
 * @author zzz
 * @since 0.7.10
 */
public class SSLEepGet extends EepGet {
    /** if true, save cert chain on cert error */
    private int _saveCerts;
    /** if true, don't do hostname verification */
    private boolean _bypassVerification;
    /** true if called from main(), used for logging */
    private boolean _commandLine;
    /** may be null if init failed */
    private final SSLContext _sslContext;
    /** may be null if init failed */
    private SavingTrustManager _stm;
    private final ProxyType _proxyType;

    private static final String CERT_DIR = "certificates/ssl";
    private static final String PROP_USE_DNS_OVER_HTTPS = "eepget.useDNSOverHTTPS";
    private static final boolean DEFAULT_USE_DNS_OVER_HTTPS = false;

    /**
     *  Not all may be supported.
     *  @since 0.9.33
     */
    public enum ProxyType { NONE, HTTP, HTTPS, INTERNAL, SOCKS4, SOCKS5, TRANSPARENT }


    /**
     *  A new SSLEepGet with a new SSLState
     */
    public SSLEepGet(I2PAppContext ctx, OutputStream outputStream, String url) {
        this(ctx, outputStream, url, null);
    }

    /**
     *  @param state an SSLState retrieved from a previous SSLEepGet with getSSLState(), or null.
     *               This makes repeated fetches from the same host MUCH faster,
     *               and prevents repeated key store loads even for different hosts.
     *  @since 0.8.2
     */
    public SSLEepGet(I2PAppContext ctx, OutputStream outputStream, String url, SSLState state) {
        this(ctx, null, outputStream, url, null);
    }

    /**
     *  A new SSLEepGet with a new SSLState
     *  @since 0.9.9
     */
    public SSLEepGet(I2PAppContext ctx, String outputFile, String url) {
        this(ctx, outputFile, url, null);
    }

    /**
     *  @param state an SSLState retrieved from a previous SSLEepGet with getSSLState(), or null.
     *               This makes repeated fetches from the same host MUCH faster,
     *               and prevents repeated key store loads even for different hosts.
     *  @since 0.9.9
     */
    public SSLEepGet(I2PAppContext ctx, String outputFile, String url, SSLState state) {
        this(ctx, outputFile, null, url, null);
    }

    /**
     *  Use a proxy.
     *
     *  @param proxyHost Must be valid hostname or literal IPv4/v6.
     *                   If type is INTERNAL, set to "localhost".
     *  @param proxyPort Must be valid, -1 disallowed, no default.
     *                   If type is INTERNAL, set to 4444.
     *  @since 0.9.33
     */
    public SSLEepGet(I2PAppContext ctx, ProxyType type, String proxyHost, int proxyPort,
                     OutputStream outputStream, String url) {
        this(ctx, type, proxyHost, proxyPort, outputStream, url, null);
    }

    /**
     *  Use a proxy.
     *
     *  @param proxyHost Must be valid hostname or literal IPv4/v6.
     *                   If type is INTERNAL, set to "localhost".
     *  @param proxyPort Must be valid, -1 disallowed, no default.
     *                   If type is INTERNAL, set to 4444.
     *  @param state an SSLState retrieved from a previous SSLEepGet with getSSLState(), or null.
     *               This makes repeated fetches from the same host MUCH faster,
     *               and prevents repeated key store loads even for different hosts.
     *  @since 0.9.33
     */
    public SSLEepGet(I2PAppContext ctx, ProxyType type, String proxyHost, int proxyPort,
                     OutputStream outputStream, String url, SSLState state) {
        // we're using this constructor:
        // public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize, long maxSize, String outputFile, OutputStream outputStream, String url, boolean allowCaching, String etag, String postData) {
        super(ctx, type != ProxyType.NONE, proxyHost, proxyPort, 0, -1, -1, null, outputStream, url, true, null, null);
        if (type != ProxyType.NONE && !_shouldProxy)
            throw new IllegalArgumentException("Bad proxy params");
        _proxyType = type;
        if (state != null && state.context != null)
            _sslContext = state.context;
        else
            _sslContext = initSSLContext();
        if (_sslContext == null)
            _log.error("Failed to initialize custom SSL context, using default context");
    }

    /**
     *  Use a proxy.
     *
     *  @param proxyHost Must be valid hostname or literal IPv4/v6.
     *                   If type is INTERNAL, set to "localhost".
     *  @param proxyPort Must be valid, -1 disallowed, no default.
     *                   If type is INTERNAL, set to 4444.
     *  @since 0.9.33
     */
    public SSLEepGet(I2PAppContext ctx, ProxyType type, String proxyHost, int proxyPort,
                     String outputFile, String url) {
        this(ctx, type, proxyHost, proxyPort, outputFile, url, null);
    }

    /**
     *  Use a proxy.
     *
     *  @param proxyHost Must be valid hostname or literal IPv4/v6.
     *                   If type is INTERNAL, set to "localhost".
     *  @param proxyPort Must be valid, -1 disallowed, no default.
     *                   If type is INTERNAL, set to 4444.
     *  @param state an SSLState retrieved from a previous SSLEepGet with getSSLState(), or null.
     *               This makes repeated fetches from the same host MUCH faster,
     *               and prevents repeated key store loads even for different hosts.
     *  @since 0.9.33
     */
    public SSLEepGet(I2PAppContext ctx, ProxyType type, String proxyHost, int proxyPort,
                     String outputFile, String url, SSLState state) {
        // we're using this constructor:
        // public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize, long maxSize, String outputFile, OutputStream outputStream, String url, boolean allowCaching, String etag, String postData) {
        super(ctx, type != ProxyType.NONE, proxyHost, proxyPort, 0, -1, -1, outputFile, null, url, true, null, null);
        if (type != ProxyType.NONE && !_shouldProxy)
            throw new IllegalArgumentException("Bad proxy params");
        _proxyType = type;
        if (state != null && state.context != null)
            _sslContext = state.context;
        else
            _sslContext = initSSLContext();
        if (_sslContext == null)
            _log.error("Failed to initialize custom SSL context, using default context");
    }

    /**
     *  outputFile, outputStream: One null, one non-null
     *
     *  @param state an SSLState retrieved from a previous SSLEepGet with getSSLState(), or null.
     *               This makes repeated fetches from the same host MUCH faster,
     *               and prevents repeated key store loads even for different hosts.
     *  @since 0.9.9
     */
    private SSLEepGet(I2PAppContext ctx, String outputFile, OutputStream outputStream, String url, SSLState state) {
        // we're using this constructor:
        // public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize, long maxSize, String outputFile, OutputStream outputStream, String url, boolean allowCaching, String etag, String postData) {
        super(ctx, false, null, -1, 0, -1, -1, outputFile, outputStream, url, true, null, null);
        _proxyType = ProxyType.NONE;
        if (state != null && state.context != null)
            _sslContext = state.context;
        else
            _sslContext = initSSLContext();
        if (_sslContext == null)
            _log.error("Failed to initialize custom SSL context, using default context");
    }
   
    /**
     * SSLEepGet https://foo/bar
     *   or to save cert chain:
     * SSLEepGet -s https://foo/bar
     */ 
    public static void main(String args[]) {
        int saveCerts = 0;
        boolean noVerify = false;
        String proxyHost = "127.0.0.1";
        int proxyPort = 0;
        ProxyType ptype = ProxyType.NONE;
        boolean error = false;
        Getopt g = new Getopt("ssleepget", args, "p:y:sz");
        try {
            int c;
            while ((c = g.getopt()) != -1) {
              switch (c) {
                case 'p':
                    String s = g.getOptarg();
                    int colon = s.indexOf(':');
                    if (colon >= 0) {
                        // Todo IPv6 [a:b:c]:4444
                        proxyHost = s.substring(0, colon);
                        String port = s.substring(colon + 1);
                        proxyPort = Integer.parseInt(port);
                    } else {
                        proxyHost = s;
                        // proxyPort remains default
                    }
                    break;

                case 'y':
                    String y = g.getOptarg().toUpperCase(Locale.US);
                    if (y.equals("HTTP") || y.equals("HTTPS")) {
                        ptype = ProxyType.HTTP;
                    } else if (y.equals("SOCKS4")) {
                        ptype = ProxyType.SOCKS4;
                    } else if (y.equals("SOCKS5")) {
                        ptype = ProxyType.SOCKS5;
                    } else if (y.equals("I2P")) {
                        ptype = ProxyType.INTERNAL;
                        proxyHost = "localhost";
                        proxyPort = 4444;
                    } else {
                        error = true;
                    }
                    break;

                case 's':
                    saveCerts++;
                    break;

                case 'z':
                    noVerify = true;
                    break;

                case '?':
                case ':':
                default:
                    error = true;
                    break;
              }  // switch
            } // while
        } catch (RuntimeException e) {
            e.printStackTrace();
            error = true;
        }

        if (error || args.length - g.getOptind() != 1) {
            usage();
            System.exit(1);
        }
        String url = args[g.getOptind()];

        String saveAs = suggestName(url);

        SSLEepGet get;
        if (proxyHost != null) {
            if (proxyPort == 0) {
                if (ptype == ProxyType.HTTP)
                    proxyPort = 8080;
                else
                    proxyPort = 1080;
            }
            get = new SSLEepGet(I2PAppContext.getGlobalContext(), ptype, proxyHost, proxyPort, saveAs, url);
        } else {
            get = new SSLEepGet(I2PAppContext.getGlobalContext(), saveAs, url);
        }
        if (saveCerts > 0)
            get._saveCerts = saveCerts;
        if (noVerify)
            get._bypassVerification = true;
        get._commandLine = true;
        get.addStatusListener(get.new CLIStatusListener(1024, 40));
        if(!get.fetch(45*1000, -1, 60*1000))
            System.exit(1);
    }
    
    private static void usage() {
        System.err.println("Usage: SSLEepGet [-psyz] https://url\n" +
                           "  -p proxyHost[:proxyPort]    // default port 8080 for HTTPS and 1080 for SOCKS; default localhost:4444 for I2P\n" +
                           "  -y HTTPS|SOCKS4|SOCKS5|I2P  // proxy type, default HTTPS if proxyHost is set\n" +
                           "  -s save unknown certs\n" +
                           "  -s -s save all certs\n" +
                           "  -z bypass hostname verification");
    }

    /**
     *  Loads certs from location of javax.net.ssl.keyStore property,
     *  else from $JAVA_HOME/lib/security/jssacacerts,
     *  else from $JAVA_HOME/lib/security/cacerts.
     *
     *  Then adds certs found in the $I2P/certificates/ssl/ directory
     *  and in the ~/.i2p/certificates/ssl/ directory.
     *
     *  @return null on failure
     *  @since 0.8.2
     */
    private SSLContext initSSLContext() {
        KeyStore ks = KeyStoreUtil.loadSystemKeyStore();
        if (ks == null) {
            _log.error("Key Store init error");
            return null;
        }
        if (_log.shouldLog(Log.INFO)) {
            int count = KeyStoreUtil.countCerts(ks);
            _log.info("Loaded " + count + " default trusted certificates");
        }

        File dir = new File(_context.getBaseDir(), CERT_DIR);
        int adds = KeyStoreUtil.addCerts(dir, ks);
        int totalAdds = adds;
        if (adds > 0 && _log.shouldLog(Log.INFO))
            _log.info("Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());
        if (!_context.getBaseDir().getAbsolutePath().equals(_context.getConfigDir().getAbsolutePath())) {
            dir = new File(_context.getConfigDir(), CERT_DIR);
            adds = KeyStoreUtil.addCerts(dir, ks);
            totalAdds += adds;
            if (adds > 0 && _log.shouldLog(Log.INFO))
                _log.info("Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());
        }
        dir = new File(System.getProperty("user.dir"));
        if (!_context.getBaseDir().getAbsolutePath().equals(dir.getAbsolutePath())) {
            dir = new File(_context.getConfigDir(), CERT_DIR);
            adds = KeyStoreUtil.addCerts(dir, ks);
            totalAdds += adds;
            if (adds > 0 && _log.shouldLog(Log.INFO))
                _log.info("Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Loaded total of " + totalAdds + " new trusted certificates");

        try {
            SSLContext sslc = SSLContext.getInstance("TLS");
            TrustManagerFactory tmf =   TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            X509TrustManager defaultTrustManager = (X509TrustManager)tmf.getTrustManagers()[0];
            _stm = new SavingTrustManager(defaultTrustManager);
            sslc.init(null, new TrustManager[] {_stm}, null);
        /****
            if (_log.shouldLog(Log.DEBUG)) {
                SSLEngine eng = sslc.createSSLEngine();
                SSLParameters params = sslc.getDefaultSSLParameters();
                String[] s = eng.getSupportedProtocols();
                Arrays.sort(s);
                _log.debug("Supported protocols: " + s.length);
                for (int i = 0; i < s.length; i++) {
                     _log.debug(s[i]);
                }
                s = eng.getEnabledProtocols();
                Arrays.sort(s);
                _log.debug("Enabled protocols: " + s.length);
                for (int i = 0; i < s.length; i++) {
                     _log.debug(s[i]);
                }
                s = params.getProtocols();
                if (s == null)
                    s = new String[0];
                _log.debug("Default protocols: " + s.length);
                Arrays.sort(s);
                for (int i = 0; i < s.length; i++) {
                     _log.debug(s[i]);
                }
                s = eng.getSupportedCipherSuites();
                Arrays.sort(s);
                _log.debug("Supported ciphers: " + s.length);
                for (int i = 0; i < s.length; i++) {
                     _log.debug(s[i]);
                }
                s = eng.getEnabledCipherSuites();
                Arrays.sort(s);
                _log.debug("Enabled ciphers: " + s.length);
                for (int i = 0; i < s.length; i++) {
                     _log.debug(s[i]);
                }
                s = params.getCipherSuites();
                if (s == null)
                    s = new String[0];
                _log.debug("Default ciphers: " + s.length);
                Arrays.sort(s);
                for (int i = 0; i < s.length; i++) {
                     _log.debug(s[i]);
                }
            }
          ****/
            return sslc;
        } catch (GeneralSecurityException gse) {
            _log.error("Key Store update error", gse);
        } catch (ExceptionInInitializerError eiie) {
            // java 9 b134 see ../crypto/CryptoCheck for example
            // Catching this may be pointless, fetch still fails
            _log.error("SSL context error - Java 9 bug?", eiie);
        }
        return null;
    }
    
    /**
     *  From http://blogs.sun.com/andreas/resource/InstallCert.java
     *  This just saves the certificate chain for later inspection.
     *  @since 0.8.2
     */
    private static class SavingTrustManager implements X509TrustManager {
        private final X509TrustManager tm;
        private X509Certificate[] chain;

        SavingTrustManager(X509TrustManager tm) {
            this.tm = tm;
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException();
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            this.chain = chain;
            tm.checkServerTrusted(chain, authType);
        }
    }

    /**
     *  Modified from http://blogs.sun.com/andreas/resource/InstallCert.java
     *  @since 0.8.2
     */
    private static void saveCerts(String host, SavingTrustManager stm) {
        X509Certificate[] chain = stm.chain;
        if (chain == null) {
            System.out.println("Could not obtain server certificate chain");
            return;
        }
        for (int k = 0; k < chain.length; k++) {
            X509Certificate cert = chain[k];
            String name = host + '-' + (k + 1) + ".crt";
            System.out.println("NOTE: Saving X509 certificate as " + name);
            System.out.println("      Issuer:     " + cert.getIssuerX500Principal());
            System.out.println("      Valid From: " + cert.getNotBefore());
            System.out.println("      Valid To:   " + cert.getNotAfter());
            try {
                cert.checkValidity();
            } catch (GeneralSecurityException e) {
                System.out.println("      WARNING: Certificate is not currently valid, it cannot be used");
            }
            CertUtil.saveCert(cert, new File(name));
        }
        System.out.println("NOTE: To trust them, copy the certificate file(s) to the certificates directory and rerun without the -s option");
    }

    /**
     *  An opaque class for the caller to pass to repeated instantiations of SSLEepGet.
     *  @since 0.8.2
     */
    public static class SSLState {
        private final SSLContext context;

        private SSLState(SSLContext ctx) {
             context = ctx;
        }
    }

    /**
     *  Pass this back to the next SSLEepGet constructor for faster fetches.
     *  This may be called either after the constructor or after the fetch.
     *  @since 0.8.2
     */
    public SSLState getSSLState() {
        return new SSLState(_sslContext);
    }

    ///// end of all the SSL stuff
    ///// start of overrides

    @Override
    protected void doFetch(SocketTimeout timeout) throws IOException {
        _aborted = false;
        readHeaders();
        if (_aborted)
            throw new IOException("Timed out reading the HTTP headers");
        
        if (timeout != null) {
            timeout.resetTimer();
            if (_fetchInactivityTimeout > 0)
                timeout.setInactivityTimeout(_fetchInactivityTimeout);
            else
                timeout.setInactivityTimeout(60*1000);
        }        
        if (_fetchInactivityTimeout > 0)
            _proxy.setSoTimeout(_fetchInactivityTimeout);
        else
            _proxy.setSoTimeout(INACTIVITY_TIMEOUT);

        if (_redirectLocation != null) {
            throw new IOException("Server redirect to " + _redirectLocation + " not allowed");
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Headers read completely, reading " + _bytesRemaining);
        
        boolean strictSize = (_bytesRemaining >= 0);

        Thread pusher = null;
        _decompressException = null;
        if (_isGzippedResponse) {
            PipedInputStream pi = new PipedInputStream(64*1024);
            PipedOutputStream po = new PipedOutputStream(pi);
            pusher = new I2PAppThread(new Gunzipper(pi, _out), "EepGet Decompressor");
            _out = po;
            pusher.start();
        }

        int remaining = (int)_bytesRemaining;
        byte buf[] = new byte[16*1024];
        while (_keepFetching && ( (remaining > 0) || !strictSize ) && !_aborted) {
            int toRead = buf.length;
            if (strictSize && toRead > remaining)
                toRead = remaining;
            int read = _proxyIn.read(buf, 0, toRead);
            if (read == -1)
                break;
            if (timeout != null)
                timeout.resetTimer();
            _out.write(buf, 0, read);
            _bytesTransferred += read;

            remaining -= read;
            if (remaining==0 && _encodingChunked) {
                int char1 = _proxyIn.read();
                if (char1 == '\r') {
                    int char2 = _proxyIn.read();
                    if (char2 == '\n') {
                        remaining = (int) readChunkLength();
                    } else {
                        _out.write(char1);
                        _out.write(char2);
                        _bytesTransferred += 2;
                        remaining -= 2;
                        read += 2;
                    }
                } else {
                    _out.write(char1);
                    _bytesTransferred++;
                    remaining--;
                    read++;
                }
            }
            if (timeout != null)
                timeout.resetTimer();
            if (_bytesRemaining >= read) // else chunked?
                _bytesRemaining -= read;
            if (read > 0) {
                for (int i = 0; i < _listeners.size(); i++) 
                    _listeners.get(i).bytesTransferred(
                            _alreadyTransferred, 
                            read, 
                            _bytesTransferred, 
                            _encodingChunked?-1:_bytesRemaining, 
                            _url);
                // This seems necessary to properly resume a partial download into a stream,
                // as nothing else increments _alreadyTransferred, and there's no file length to check.
                // Do this after calling the listeners to keep the total correct
                _alreadyTransferred += read;
            }
        }
            
        if (_out != null)
            _out.close();
        _out = null;
        
        if (_isGzippedResponse) {
            try {
                pusher.join();
            } catch (InterruptedException ie) {}
            pusher = null;
            if (_decompressException != null) {
                // we can't resume from here
                _keepFetching = false;
                throw _decompressException;
            }
        }

        if (_aborted)
            throw new IOException("Timed out reading the HTTP data");
        
        if (timeout != null)
            timeout.cancel();
        
        if (_transferFailed) {
            // 404, etc - transferFailed is called after all attempts fail, by fetch() above
            for (int i = 0; i < _listeners.size(); i++) 
                _listeners.get(i).attemptFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt, _numRetries, new Exception("Attempt failed"));
        } else if ( (_bytesRemaining == -1) || (remaining == 0) ) {
            for (int i = 0; i < _listeners.size(); i++) 
                _listeners.get(i).transferComplete(
                        _alreadyTransferred, 
                        _bytesTransferred, 
                        _encodingChunked?-1:_bytesRemaining, 
                        _url, 
                        _outputFile, 
                        _notModified);
        } else {
            throw new IOException("Disconnection on attempt " + _currentAttempt + " after " + _bytesTransferred);
        }
    }

    @Override
    protected void sendRequest(SocketTimeout timeout) throws IOException {
        if (_outputStream != null) {
            // We are reading into a stream supplied by a caller,
            // for which we cannot easily determine how much we've written.
            // Assume that _alreadyTransferred holds the right value
            // (we should never be restarted to work on an old stream).
        } else {
            File outFile = new File(_outputFile);
            if (outFile.exists())
                _alreadyTransferred = outFile.length();
        }

        String req = getRequest();

        String host;
        int port;
        try {
            URI url = new URI(_actualURL);
            if ("https".equals(url.getScheme())) {
                host = url.getHost();
                if (host == null)
                    throw new MalformedURLException("Bad URL");
                if (host.toLowerCase(Locale.US).endsWith(".i2p"))
                    throw new MalformedURLException("I2P addresses unsupported");
                port = url.getPort();
                if (port == -1)
                    port = 443;

                String originalHost = host;
                boolean useDNSOverHTTPS = _context.getProperty(PROP_USE_DNS_OVER_HTTPS, DEFAULT_USE_DNS_OVER_HTTPS);
                // This duplicates checks in DNSOverHTTPS.lookup() but do it here too so
                // we don't even construct it if we don't need it
                if (useDNSOverHTTPS && !host.equals("dns.google.com") && !Addresses.isIPAddress(host)) {
                    DNSOverHTTPS doh = new DNSOverHTTPS(_context, getSSLState());
                    String ip = doh.lookup(host);
                    if (ip != null)
                        host = ip;
                }

                if (_shouldProxy) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Connecting to " + _proxyType + " proxy");
                    switch (_proxyType) {
                      case HTTP:
                        httpProxyConnect(host, port);
                        break;

                      case INTERNAL:
                        internalHttpProxyConnect(host, port);
                        break;

                      case SOCKS4:
                        socksProxyConnect(false, host, port);
                        break;

                      case SOCKS5:
                        socksProxyConnect(true, host, port);
                        break;

                      case HTTPS:
                      case TRANSPARENT:
                      default:
                        throw new IOException("Unsupported proxy type " + _proxyType);
                    }

                    // wrap the socket in an SSLSocket
                    if (_sslContext != null)
                        _proxy = _sslContext.getSocketFactory().createSocket(_proxy, host, port, true);
                    else
                        _proxy = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(_proxy, host, port, true);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(_proxyType + " proxy headers read completely");
                } else {
                    // Warning, createSocket() followed by connect(InetSocketAddress)
                    // disables SNI, at least on Java 7.
                    // So we must do createSocket(host, port) and then setSoTimeout;
                    // we can't create a disconnected socket and then call setSoTimeout, sadly.
                    if (_sslContext != null)
                        _proxy = _sslContext.getSocketFactory().createSocket(host, port);
                    else
                        _proxy = SSLSocketFactory.getDefault().createSocket(host, port);
                    if (_fetchHeaderTimeout > 0) {
                        _proxy.setSoTimeout(_fetchHeaderTimeout);
                    }
                }

                SSLSocket socket = (SSLSocket) _proxy;
                I2PSSLSocketFactory.setProtocolsAndCiphers(socket);
                if (!_bypassVerification) {
                    try {
                        I2PSSLSocketFactory.verifyHostname(_context, socket, originalHost);
                    } catch (SSLException ssle) {
                        if (_saveCerts > 0 && _stm != null)
                            saveCerts(host, _stm);
                        throw ssle;
                    }
                }
            } else {
                throw new MalformedURLException("Only https supported: " + _actualURL);
            }
        } catch (URISyntaxException use) {
            IOException ioe = new MalformedURLException("Redirected to invalid URL");
            ioe.initCause(use);
            throw ioe;
        }

        _proxyIn = _proxy.getInputStream();
        _proxyOut = _proxy.getOutputStream();
        
        // This is where the cert errors happen
        try {
            _proxyOut.write(DataHelper.getUTF8(req));
            _proxyOut.flush();
            if (_saveCerts > 1 && _stm != null)
                saveCerts(host, _stm);
        } catch (SSLException sslhe) {
            // this maybe would be better done in the catch in super.fetch(), but
            // then we'd have to copy it all over here.
            _log.error("SSL negotiation error with " + host + ':' + port +
                       " - self-signed certificate or untrusted certificate authority?", sslhe);
            if (_saveCerts > 0 && _stm != null)
                saveCerts(host, _stm);
            else if (_commandLine) {
                System.out.println("FAILED (probably due to untrusted certificates) - Run with -s option to save certificates");
            }
            // this is an IOE
            throw sslhe;
        }

        _proxyIn = new BufferedInputStream(_proxyIn);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Request flushed");
    }

    /**
     *  Connect to a HTTP proxy.
     *  Proxy address must be in _proxyHost and _proxyPort.
     *  Side effects: Sets _proxy, _proxyIn, _proxyOut,
     *  and other globals via readHeaders()
     *
     *  @param host what the proxy should connect to
     *  @param port what the proxy should connect to
     *  @since 0.9.33
     */
    private void httpProxyConnect(String host, int port) throws IOException {
        // connect to the proxy
        // _proxyPort validated in superconstrutor, no need to set default here
        if (_fetchHeaderTimeout > 0) {
            _proxy = new Socket();
            _proxy.setSoTimeout(_fetchHeaderTimeout);
            _proxy.connect(new InetSocketAddress(_proxyHost, _proxyPort), _fetchHeaderTimeout);
        } else {
            _proxy = new Socket(_proxyHost, _proxyPort);
        }
        httpProxyConnect(_proxy, host, port);
    }

    /**
     *  Connect to a HTTP proxy.
     *  Proxy address must be in _proxyHost and _proxyPort.
     *  Side effects: Sets _proxy, _proxyIn, _proxyOut,
     *  and other globals via readHeaders()
     *
     *  @param port what the proxy should connect to, probably 4444
     *  @since 0.9.33
     */
    private void internalHttpProxyConnect(String host, int port) throws IOException {
        // connect to the proxy
        _proxy = InternalSocket.getSocket(_proxyHost, _proxyPort);
        httpProxyConnect(_proxy, host, port);
    }

    /**
     *  Connect to a HTTP proxy.
     *  Proxy address must be in _proxyHost and _proxyPort.
     *  Side effects: Sets _proxyIn, _proxyOut,
     *  and other globals via readHeaders()
     *
     *  @param host what the proxy should connect to
     *  @param port what the proxy should connect to
     *  @since 0.9.33
     */
    private void httpProxyConnect(Socket proxy, String host, int port) throws IOException {
        _proxyIn = _proxy.getInputStream();
        _proxyOut = _proxy.getOutputStream();
        StringBuilder buf = new StringBuilder(64);
        buf.append("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n");
        // TODO if we need extra headers to the proxy, add a new method and list.
        // Standard extra headers go the server, not the proxy
        //if (_extraPHeaders != null) {
        //    for (String hdr : _extraPHeaders) {
        //        buf.append(hdr).append("\r\n");
        //}
        if (_authState != null && _authState.authMode != AUTH_MODE.NONE) {
            // TODO untested, is this right?
            buf.append("Proxy-Authorization: ");
            buf.append(_authState.getAuthHeader("CONNECT", host));
            buf.append("\r\n");
        }
        buf.append("\r\n");
        _proxyOut.write(DataHelper.getUTF8(buf.toString()));
        _proxyOut.flush();

        // read the proxy response
        _aborted = false;
        readHeaders();
        if (_aborted)
            throw new IOException("Timed out reading the proxy headers");
        if (_responseCode == 407) {
            // TODO
            throw new IOException("Authorization unsupported on HTTP Proxy");
        } else if (_responseCode != 200) {
            // readHeaders() will throw on most errors, but here we ensure it is 200
            throw new IOException("Invalid proxy response: " + _responseCode + ' ' + _responseText);
        }
        if (_redirectLocation != null)
            throw new IOException("Proxy redirect not allowed");
    }

    /**
     *  Connect to a SOCKS proxy.
     *  Proxy address must be in _proxyHost and _proxyPort.
     *  Side effects: Sets _proxy, _proxyIn, _proxyOut,
     *  and other globals via readHeaders()
     *
     *  @param host what the proxy should connect to
     *  @param port what the proxy should connect to
     *  @since 0.9.33
     */
    private void socksProxyConnect(boolean isSocks5, String host, int port) throws IOException {
        if (_fetchHeaderTimeout > 0) {
            _proxy = new Socket();
            _proxy.setSoTimeout(_fetchHeaderTimeout);
            _proxy.connect(new InetSocketAddress(_proxyHost, _proxyPort), _fetchHeaderTimeout);
        } else {
            _proxy = new Socket(_proxyHost, _proxyPort);
        }
        if (_authState != null) {
            if (!isSocks5)
                throw new IOException("Authorization unsupported on SOCKS 4");
            SOCKS5Client.connect(_proxy, host, port, _authState.getUsername(), _authState.getPassword());
        } else {
            if (isSocks5)
                SOCKS5Client.connect(_proxy, host, port);
            else
                SOCKS4Client.connect(_proxy, host, port);
        }
        _proxyIn = _proxy.getInputStream();
        _proxyOut = _proxy.getOutputStream();
    }
}
