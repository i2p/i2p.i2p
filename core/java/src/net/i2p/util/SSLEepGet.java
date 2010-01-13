package net.i2p.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import javax.net.ssl.SSLSocketFactory;

// all part of the CA experiment below
//import java.io.FileInputStream;
//import java.io.InputStream;
//import java.util.Enumeration;
//import java.security.KeyStore;
//import java.security.GeneralSecurityException;
//import java.security.cert.CertificateExpiredException;
//import java.security.cert.CertificateNotYetValidException;
//import java.security.cert.CertificateFactory;
//import java.security.cert.X509Certificate;
//import javax.net.ssl.KeyManagerFactory;
//import javax.net.ssl.SSLContext;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 * HTTPS only, non-proxied only, no retries, no min and max size options, no timeout option
 * Fails on 301 or 302 (doesn't follow redirect)
 * Fails on self-signed certs (must have a valid cert chain)
 *
 * @author zzz
 * @since 0.7.10
 */
public class SSLEepGet extends EepGet {
    //private static SSLContext _sslContext;

    public SSLEepGet(I2PAppContext ctx, OutputStream outputStream, String url) {
        // we're using this constructor:
        // public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize, long maxSize, String outputFile, OutputStream outputStream, String url, boolean allowCaching, String etag, String postData) {
        super(ctx, false, null, -1, 0, -1, -1, null, outputStream, url, true, null, null);
    }
   
    /**
     * SSLEepGet url
     * no command line options supported
     */ 
    public static void main(String args[]) {
        String url = null;
        try {
            for (int i = 0; i < args.length; i++) {
                if (args[i].startsWith("-")) {
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

        /******
         *  This is all an experiment to add a CA cert loaded from a file so we can use
         *  selfsigned certs on our servers.
         *  But it's failing.
         *  Run as java -Djava.security.debug=certpath -Djavax.net.debug=trustmanager -cp $I2P/lib/i2p.jar net.i2p.util.SSLEepGet "$@"
         *  to see the problems. It isn't including the added cert in the Trust Anchor list.
         ******/

        /******
        String foo = System.getProperty("javax.net.ssl.keyStore");
        if (foo == null) {
            File cacerts = new File(System.getProperty("java.home"), "lib/security/cacerts");
            foo = cacerts.getAbsolutePath();
        }
        System.err.println("Location is: " + foo);
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            try {
                InputStream fis = new FileInputStream(foo);
                ks.load(fis, "changeit".toCharArray());
                fis.close();
            } catch (GeneralSecurityException gse) {
                System.err.println("KS error, no default keys: " + gse);
                ks.load(null, "changeit".toCharArray());
            } catch (IOException ioe) {
                System.err.println("IO error, no default keys: " + ioe);
                ks.load(null, "changeit".toCharArray());
            }

            addCert(ks, "cacert");

            for(Enumeration<String> e = ks.aliases(); e.hasMoreElements();) {
                String alias = e.nextElement();
                System.err.println("Aliases: " + alias + " isCert? " + ks.isCertificateEntry(alias));
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "".toCharArray());
            SSLContext sslc = SSLContext.getInstance("SSL");
            sslc.init(kmf.getKeyManagers(), null, null);
            _sslContext = sslc;
        } catch (GeneralSecurityException gse) {
            System.err.println("KS error: " + gse);
            return;
        } catch (IOException ioe) {
            System.err.println("IO error: " + ioe);
            return;
        }
        *******/

        EepGet get = new SSLEepGet(I2PAppContext.getGlobalContext(), out, url);
        get.addStatusListener(get.new CLIStatusListener(1024, 40));
        get.fetch(45*1000, -1, 60*1000);
    }
    
    private static void usage() {
        System.err.println("SSLEepGet url");
    }

/******
    private static boolean addCert(KeyStore ks, String file) {

            try {
                InputStream fis = new FileInputStream(file);
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate)cf.generateCertificate(fis);
                fis.close();
                System.err.println("Adding cert, Issuer: " + cert.getIssuerX500Principal());
                try {
                    cert.checkValidity();
                } catch (CertificateExpiredException cee) {
                    System.err.println("Warning - expired cert: " + cee);
                } catch (CertificateNotYetValidException cnyve) {
                    System.err.println("Warning - not yet valid cert: " + cnyve);
                }
                // use file name as alias
                ks.setCertificateEntry(file, cert);
            } catch (GeneralSecurityException gse) {
                System.err.println("Read cert error: " + gse);
                return false;
            } catch (IOException ioe) {
                System.err.println("Read cert error: " + ioe);
                return false;
            }
        return true;
    }
*******/
    
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
        
        timeout.resetTimer();
        if (_fetchInactivityTimeout > 0)
            timeout.setInactivityTimeout(_fetchInactivityTimeout);
        else
            timeout.setInactivityTimeout(60*1000);
        
        if (_redirectLocation != null) {
            throw new IOException("Server redirect to " + _redirectLocation + " not allowed");
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Headers read completely, reading " + _bytesRemaining);
        
        boolean strictSize = (_bytesRemaining >= 0);

        int remaining = (int)_bytesRemaining;
        byte buf[] = new byte[1024];
        while (_keepFetching && ( (remaining > 0) || !strictSize ) && !_aborted) {
            int toRead = buf.length;
            if (strictSize && toRead > remaining)
                toRead = remaining;
            int read = _proxyIn.read(buf, 0, toRead);
            if (read == -1)
                break;
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
        
        if (_aborted)
            throw new IOException("Timed out reading the HTTP data");
        
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

        try {
            URL url = new URL(_actualURL);
            if ("https".equals(url.getProtocol())) {
                String host = url.getHost();
                int port = url.getPort();
                if (port == -1)
                    port = 443;
                // part of the experiment above
                //if (_sslContext != null)
                //    _proxy = _sslContext.getSocketFactory().createSocket(host, port);
                //else
                    _proxy = SSLSocketFactory.getDefault().createSocket(host, port);
            } else {
                throw new IOException("Only https supported: " + _actualURL);
            }
        } catch (MalformedURLException mue) {
            throw new IOException("Request URL is invalid");
        }

        _proxyIn = _proxy.getInputStream();
        _proxyOut = _proxy.getOutputStream();
        
        _proxyOut.write(DataHelper.getUTF8(req));
        _proxyOut.flush();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Request flushed");
    }
}
