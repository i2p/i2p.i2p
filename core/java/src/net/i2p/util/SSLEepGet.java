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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

/**
 * HTTPS only, non-proxied only, no retries, no min and max size options, no timeout option
 * Fails on 301 or 302 (doesn't follow redirect)
 * Fails on bad certs (must have a valid cert chain)
 * Self-signed certs or CAs not in the JVM key store must be loaded to be trusted.
 *
 * Since 0.8.2, loads additional trusted CA certs from $I2P/certificates/ and ~/.i2p/certificates/
 *
 * @author zzz
 * @since 0.7.10
 */
public class SSLEepGet extends EepGet {
    /** if true, save cert chain on cert error */
    private boolean _saveCerts;
    /** true if called from main(), used for logging */
    private boolean _commandLine;
    /** may be null if init failed */
    private final SSLContext _sslContext;
    /** may be null if init failed */
    private SavingTrustManager _stm;

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
        // we're using this constructor:
        // public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize, long maxSize, String outputFile, OutputStream outputStream, String url, boolean allowCaching, String etag, String postData) {
        super(ctx, false, null, -1, 0, -1, -1, null, outputStream, url, true, null, null);
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
        String url = null;
        boolean saveCerts = false;
        try {
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-s")) {
                    saveCerts = true;
                } else if (args[i].startsWith("-")) {
                    usage();
                    return;
                } else {
                    url = args[i];
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            usage();
            return;
        }
        
        if (url == null) {
            usage();
            return;
        }

        String saveAs = suggestName(url);
        OutputStream out;
        try {
            // resume from a previous eepget won't work right doing it this way
            out = new FileOutputStream(saveAs);
        } catch (IOException ioe) {
            System.err.println("Failed to create output file " + saveAs);
            return;
        }

        SSLEepGet get = new SSLEepGet(I2PAppContext.getGlobalContext(), out, url);
        if (saveCerts)
            get._saveCerts = true;
        get._commandLine = true;
        get.addStatusListener(get.new CLIStatusListener(1024, 40));
        get.fetch(45*1000, -1, 60*1000);
    }
    
    private static void usage() {
        System.err.println("Usage: SSLEepGet https://url");
        System.err.println("To save unknown certs, use: SSLEepGet -s https://url");
    }

    /**
     *  Loads certs from location of javax.net.ssl.keyStore property,
     *  else from $JAVA_HOME/lib/security/jssacacerts,
     *  else from $JAVA_HOME/lib/security/cacerts.
     *
     *  Then adds certs found in the $I2P/certificates/ directory
     *  and in the ~/.i2p/certificates/ directory.
     *
     *  @return null on failure
     *  @since 0.8.2
     */
    private SSLContext initSSLContext() {
        KeyStore ks;
        try {
            ks = KeyStore.getInstance(KeyStore.getDefaultType());
        } catch (GeneralSecurityException gse) {
            _log.error("Key Store init error", gse);
            return null;
        }
        boolean success = false;
        String override = System.getProperty("javax.net.ssl.keyStore");
        if (override != null)
            success = loadCerts(new File(override), ks);
        if (!success)
            success = loadCerts(new File(System.getProperty("java.home"), "lib/security/jssecacerts"), ks);
        if (!success)
            success = loadCerts(new File(System.getProperty("java.home"), "lib/security/cacerts"), ks);

        if (!success) {
            _log.error("All key store loads failed, will only load local certificates");
        } else if (_log.shouldLog(Log.INFO)) {
            int count = 0;
            try {
                for(Enumeration<String> e = ks.aliases(); e.hasMoreElements();) {
                    String alias = e.nextElement();
                    if (ks.isCertificateEntry(alias))
                        count++;
                }
            } catch (Exception foo) {}
            _log.info("Loaded " + count + " default trusted certificates");
        }

        File dir = new File(_context.getBaseDir(), "certificates");
        int adds = addCerts(dir, ks);
        int totalAdds = adds;
        if (adds > 0 && _log.shouldLog(Log.INFO))
            _log.info("Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());
        if (!_context.getBaseDir().getAbsolutePath().equals(_context.getConfigDir().getAbsolutePath())) {
            dir = new File(_context.getConfigDir(), "certificates");
            adds = addCerts(dir, ks);
            totalAdds += adds;
            if (adds > 0 && _log.shouldLog(Log.INFO))
                _log.info("Loaded " + adds + " trusted certificates from " + dir.getAbsolutePath());
        }
        dir = new File(System.getProperty("user.dir"));
        if (!_context.getBaseDir().getAbsolutePath().equals(dir.getAbsolutePath())) {
            dir = new File(_context.getConfigDir(), "certificates");
            adds = addCerts(dir, ks);
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
            return sslc;
        } catch (GeneralSecurityException gse) {
            _log.error("Key Store update error", gse);
        }
        return null;
    }

    /**
     *  Load all X509 Certs from a key store File into a KeyStore
     *  Note that each call reinitializes the KeyStore
     *
     *  @return success
     *  @since 0.8.2
     */
    private boolean loadCerts(File file, KeyStore ks) {
        if (!file.exists())
            return false;
        InputStream fis = null;
        try {
            fis = new FileInputStream(file);
            // "changeit" is the default password
            ks.load(fis, "changeit".toCharArray());
        } catch (GeneralSecurityException gse) {
            _log.error("KeyStore load error, no default keys: " + file.getAbsolutePath(), gse);
            try {
                // not clear if null is allowed for password
                ks.load(null, "changeit".toCharArray());
            } catch (Exception foo) {}
            return false;
        } catch (IOException ioe) {
            _log.error("KeyStore load error, no default keys: " + file.getAbsolutePath(), ioe);
            try {
                ks.load(null, "changeit".toCharArray());
            } catch (Exception foo) {}
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (IOException foo) {}
        }
        return true;
    }

    /**
     *  Load all X509 Certs from a directory and add them to the
     *  trusted set of certificates in the key store
     *
     *  @return number successfully added
     *  @since 0.8.2
     */
    private int addCerts(File dir, KeyStore ks) {
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
     *  @since 0.8.2
     */
    private boolean addCert(File file, String alias, KeyStore ks) {
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
	    throw new UnsupportedOperationException();
	}

	public void checkClientTrusted(X509Certificate[] chain, String authType)
		throws CertificateException {
	    throw new UnsupportedOperationException();
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
            System.out.println("NOTE: Saving untrusted X509 certificate as " + name);
            System.out.println("      Issuer:     " + cert.getIssuerX500Principal());
            System.out.println("      Valid From: " + cert.getNotBefore());
            System.out.println("      Valid To:   " + cert.getNotAfter());
            try {
                cert.checkValidity();
            } catch (Exception e) {
                System.out.println("      WARNING: Certificate is not currently valid, it cannot be used");
            }
            saveCert(cert, new File(name));
        }
        System.out.println("NOTE: To trust them, copy the certificate file(s) to the certificates directory and rerun without the -s option");
        System.out.println("NOTE: EepGet failed, certificate error follows:");
    }

    private static final int LINE_LENGTH = 64;

    /**
     *  Modified from:
     *  http://www.exampledepot.com/egs/java.security.cert/ExportCert.html
     *
     *  This method writes a certificate to a file in base64 format.
     *  @since 0.8.2
     */
    private static void saveCert(Certificate cert, File file) {
        OutputStream os = null;
        try {
           // Get the encoded form which is suitable for exporting
           byte[] buf = cert.getEncoded();
           os = new FileOutputStream(file);
           PrintWriter wr = new PrintWriter(os);
           wr.println("-----BEGIN CERTIFICATE-----");
           String b64 = Base64.encode(buf, true);     // true = use standard alphabet
           for (int i = 0; i < b64.length(); i += LINE_LENGTH) {
               wr.println(b64.substring(i, Math.min(i + LINE_LENGTH, b64.length())));
           }
           wr.println("-----END CERTIFICATE-----");
           wr.flush();
        } catch (CertificateEncodingException cee) {
            System.out.println("Error writing X509 Certificate " + file.getAbsolutePath() + ' ' + cee);
        } catch (IOException ioe) {
            System.out.println("Error writing X509 Certificate " + file.getAbsolutePath() + ' ' + ioe);
        } finally {
            try { if (os != null) os.close(); } catch (IOException foo) {}
        }
    }

    /**
     *  An opaque class for the caller to pass to repeated instantiations of SSLEepGet.
     *  @since 0.8.2
     */
    public static class SSLState {
        private SSLContext context;

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
        _headersRead = false;
        _aborted = false;
        try {
            readHeaders();
        } finally {
            _headersRead = true;
        }
        if (_aborted)
            throw new IOException("Timed out reading the HTTP headers");
        
        if (timeout != null) {
            timeout.resetTimer();
            if (_fetchInactivityTimeout > 0)
                timeout.setInactivityTimeout(_fetchInactivityTimeout);
            else
                timeout.setInactivityTimeout(60*1000);
        }        

        if (_redirectLocation != null) {
            throw new IOException("Server redirect to " + _redirectLocation + " not allowed");
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Headers read completely, reading " + _bytesRemaining);
        
        boolean strictSize = (_bytesRemaining >= 0);

        Thread pusher = null;
        _decompressException = null;
        if (_isGzippedResponse) {
            PipedInputStream pi = BigPipedInputStream.getInstance();
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

        //try {
            URL url = new URL(_actualURL);
            String host = null;
            int port = 0;
            if ("https".equals(url.getProtocol())) {
                host = url.getHost();
                port = url.getPort();
                if (port == -1)
                    port = 443;
                if (_sslContext != null)
                    _proxy = _sslContext.getSocketFactory().createSocket(host, port);
                else
                    _proxy = SSLSocketFactory.getDefault().createSocket(host, port);
            } else {
                throw new IOException("Only https supported: " + _actualURL);
            }
        // an MUE is an IOE
        //} catch (MalformedURLException mue) {
        //    throw new IOException("Request URL is invalid");
        //}

        _proxyIn = _proxy.getInputStream();
        _proxyOut = _proxy.getOutputStream();
        
        // This is where the cert errors happen
        try {
            _proxyOut.write(DataHelper.getUTF8(req));
            _proxyOut.flush();
        } catch (SSLHandshakeException sslhe) {
            // this maybe would be better done in the catch in super.fetch(), but
            // then we'd have to copy it all over here.
            _log.error("SSL negotiation error with " + host + ':' + port +
                       " - self-signed certificate or untrusted certificate authority?", sslhe);
            if (_saveCerts && _stm != null)
                saveCerts(host, _stm);
            else if (_commandLine) {
                System.out.println("FAILED (probably due to untrusted certificates) - Run with -s option to save certificates");
            }
            // this is an IOE
            throw sslhe;
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Request flushed");
    }
}
