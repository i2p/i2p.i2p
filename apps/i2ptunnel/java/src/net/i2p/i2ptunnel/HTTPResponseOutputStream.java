package net.i2p.i2ptunnel;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * This does the transparent gzip decompression on the client side.
 * Extended in I2PTunnelHTTPServer to do the compression on the server side.
 *
 * Simple stream for delivering an HTTP response to
 * the client, trivially filtered to make sure "Connection: close"
 * is always in the response.  Perhaps add transparent handling of the
 * Content-Encoding: x-i2p-gzip, adjusting the headers to say Content-Encoding: identity?
 * Content-Encoding: gzip is trivial as well, but Transfer-Encoding: chunked makes it
 * more work than is worthwhile at the moment.
 *
 */
class HTTPResponseOutputStream extends FilterOutputStream {
    private final Log _log;
    protected ByteArray _headerBuffer;
    private boolean _headerWritten;
    private final byte _buf1[];
    protected boolean _gzip;
    protected long _dataExpected;
    /** lower-case, trimmed */
    protected String _contentType;
    /** lower-case, trimmed */
    protected String _contentEncoding;

    private static final int CACHE_SIZE = 4*1024;
    private static final ByteCache _cache = ByteCache.getInstance(8, CACHE_SIZE);
    // OOM DOS prevention
    private static final int MAX_HEADER_SIZE = 64*1024;
    /** we ignore any potential \r, since we trim it on write anyway */
    private static final byte NL = '\n';
    private static final byte[] CONNECTION_CLOSE = DataHelper.getASCII("Connection: close\r\n");
    private static final byte[] PROXY_CONNECTION_CLOSE = DataHelper.getASCII("Proxy-Connection: close\r\n");
    private static final byte[] CRLF = DataHelper.getASCII("\r\n");
    
    public HTTPResponseOutputStream(OutputStream raw) {
        super(raw);
        I2PAppContext context = I2PAppContext.getGlobalContext();
        _log = context.logManager().getLog(getClass());
        _headerBuffer = _cache.acquire();
        _buf1 = new byte[1];
    }

    @Override
    public void write(int c) throws IOException {
        _buf1[0] = (byte)c;
        write(_buf1, 0, 1);
    }

    @Override
    public void write(byte buf[], int off, int len) throws IOException {
        if (_headerWritten) {
            out.write(buf, off, len);
            return;
        }

        for (int i = 0; i < len; i++) {
            ensureCapacity();
            int valid = _headerBuffer.getValid();
            _headerBuffer.getData()[valid] = buf[off+i];
            _headerBuffer.setValid(valid + 1);

            if (headerReceived()) {
                writeHeader();
                _headerWritten = true;
                if (i + 1 < len) {
                    // write out the remaining
                    out.write(buf, off+i+1, len-i-1);
                }
                return;
            }
        }
    }
    
    /**
     *  grow (and free) the buffer as necessary
     *  @throws IOException if the headers are too big
     */
    private void ensureCapacity() throws IOException {
        int valid = _headerBuffer.getValid();
        if (valid >= MAX_HEADER_SIZE)
            throw new IOException("Max header size exceeded: " + MAX_HEADER_SIZE);
        byte[] data = _headerBuffer.getData();
        int len = data.length;
        if (valid + 1 >= len) {
            int newSize = len * 2;
            ByteArray newBuf = new ByteArray(new byte[newSize]);
            System.arraycopy(data, 0, newBuf.getData(), 0, valid);
            newBuf.setValid(valid);
            newBuf.setOffset(0);
            // if we changed the ByteArray size, don't put it back in the cache
            if (len == CACHE_SIZE)
                _cache.release(_headerBuffer);
            _headerBuffer = newBuf;
        }
    }
    
    /** are the headers finished? */
    private boolean headerReceived() {
        int valid = _headerBuffer.getValid();
        if (valid < 3)
            return false;
        byte[] data = _headerBuffer.getData();
        byte third = data[valid - 1];
        if (third != NL)
            return false;
        byte first = data[valid - 3];
        if (first == NL)     // \n\r\n
            return true;
        byte second = data[valid - 2];
        return second == NL; //   \n\n
    }
    
    /**
     * Possibly tweak that first HTTP response line (HTTP/1.0 200 OK, etc).
     * Overridden on server side.
     *
     */
    protected String filterResponseLine(String line) {
        return line;
    }
    
    /** ok, received, now munge & write it */
    private void writeHeader() throws IOException {
        String responseLine = null;

        boolean connectionSent = false;
        boolean proxyConnectionSent = false;
        
        int lastEnd = -1;
        byte[] data = _headerBuffer.getData();
        int valid = _headerBuffer.getValid();
        for (int i = 0; i < valid; i++) {
            if (data[i] == NL) {
                if (lastEnd == -1) {
                    responseLine = DataHelper.getUTF8(data, 0, i+1); // includes NL
                    responseLine = filterResponseLine(responseLine);
                    responseLine = (responseLine.trim() + "\r\n");
                    if (_log.shouldInfo())
                        _log.info("Response: " + responseLine.trim());
                    out.write(DataHelper.getUTF8(responseLine));
                } else {
                    for (int j = lastEnd+1; j < i; j++) {
                        if (data[j] == ':') {
                            int keyLen = j-(lastEnd+1);
                            int valLen = i-(j+1);
                            if ( (keyLen <= 0) || (valLen < 0) )
                                throw new IOException("Invalid header @ " + j);
                            String key = DataHelper.getUTF8(data, lastEnd+1, keyLen);
                            String val;
                            if (valLen == 0)
                                val = "";
                            else
                                val = DataHelper.getUTF8(data, j+2, valLen).trim();
                            
                            if (_log.shouldInfo())
                                _log.info("Response header [" + key + "] = [" + val + "]");
                            
                            String lcKey = key.toLowerCase(Locale.US);
                            if ("connection".equals(lcKey)) {
                                if (val.toLowerCase(Locale.US).contains("upgrade")) {
                                    // pass through for websocket
                                    out.write(DataHelper.getASCII("Connection: " + val + "\r\n"));
                                    proxyConnectionSent = true;
                                } else {
                                    out.write(CONNECTION_CLOSE);
                                }
                                connectionSent = true;
                            } else if ("proxy-connection".equals(lcKey)) {
                                out.write(PROXY_CONNECTION_CLOSE);
                                proxyConnectionSent = true;
                            } else if ("content-encoding".equals(lcKey) && "x-i2p-gzip".equals(val.toLowerCase(Locale.US))) {
                                _gzip = true;
                            } else if ("proxy-authenticate".equals(lcKey)) {
                                // filter this hop-by-hop header; outproxy authentication must be configured in I2PTunnelHTTPClient
                                // see e.g. http://blog.c22.cc/2013/03/11/privoxy-proxy-authentication-credential-exposure-cve-2013-2503/
                            } else {
                                if ("content-length".equals(lcKey)) {
                                    // save for compress decision on server side
                                    try {
                                        _dataExpected = Long.parseLong(val);
                                    } catch (NumberFormatException nfe) {}
                                } else if ("content-type".equals(lcKey)) {
                                    // save for compress decision on server side
                                    _contentType = val.toLowerCase(Locale.US);
                                } else if ("content-encoding".equals(lcKey)) {
                                    // save for compress decision on server side
                                    _contentEncoding = val.toLowerCase(Locale.US);
                                } else if ("set-cookie".equals(lcKey)) {
                                    String lcVal = val.toLowerCase(Locale.US);
                                    if (lcVal.contains("domain=b32.i2p") ||
                                        lcVal.contains("domain=.b32.i2p") ||
                                        lcVal.contains("domain=i2p") ||
                                        lcVal.contains("domain=.i2p")) {
                                        // Strip privacy-damaging "supercookies" for i2p and b32.i2p
                                        // See RFC 6265 and http://publicsuffix.org/
                                        if (_log.shouldInfo())
                                            _log.info("Stripping \"" + key + ": " + val + "\" from response ");
                                        break;
                                    }
                                }
                                out.write(DataHelper.getUTF8(key.trim() + ": " + val + "\r\n"));
                            }
                            break;
                        }
                    }
                }
                lastEnd = i;
            }
        }
        
        if (!connectionSent)
            out.write(CONNECTION_CLOSE);
        if (!proxyConnectionSent)
            out.write(PROXY_CONNECTION_CLOSE);
            
        finishHeaders();

        boolean shouldCompress = shouldCompress();
        if (_log.shouldInfo())
            _log.info("After headers: gzip? " + _gzip + " compress? " + shouldCompress);
        
        if (data.length == CACHE_SIZE)
            _cache.release(_headerBuffer);
        _headerBuffer = null;
        if (shouldCompress) {
            beginProcessing();
        }
    }
    
    protected boolean shouldCompress() { return _gzip; }
    
    protected void finishHeaders() throws IOException {
        out.write(CRLF); // end of the headers
    }
    
    @Override
    public void close() throws IOException {
        if (_log.shouldInfo())
            _log.info("Closing " + out + " compressed? " + shouldCompress(), new Exception("I did it"));
        synchronized(this) {
            // synch with changing out field below
            super.close();
        }
    }
    
    protected void beginProcessing() throws IOException {
        OutputStream po = new GunzipOutputStream(out);
        synchronized(this) {
            out = po;
        }
    }

/*******
    public static void main(String args[]) {
        String simple   = "HTTP/1.1 200 OK\n" +
                          "foo: bar\n" +
                          "baz: bat\n" +
                          "\n" +
                          "hi ho, this is the body";
        String filtered = "HTTP/1.1 200 OK\n" +
                          "Connection: keep-alive\n" +
                          "foo: bar\n" +
                          "baz: bat\n" +
                          "\n" +
                          "hi ho, this is the body";
        String winfilter= "HTTP/1.1 200 OK\r\n" +
                          "Connection: keep-alive\r\n" +
                          "foo: bar\r\n" +
                          "baz: bat\r\n" +
                          "\r\n" +
                          "hi ho, this is the body";
        String minimal  = "HTTP/1.1 200 OK\n" +
                          "\n" +
                          "hi ho, this is the body";
        String winmin   = "HTTP/1.1 200 OK\r\n" +
                          "\r\n" +
                          "hi ho, this is the body";
        String invalid1 = "HTTP/1.1 200 OK\n";
        String invalid2 = "HTTP/1.1 200 OK";
        String invalid3 = "HTTP 200 OK\r\n";
        String invalid4 = "HTTP 200 OK\r";
        String invalid5 = "HTTP/1.1 200 OK\r\n" +
                          "I am broken, and I smell\r\n" +
                          "\r\n";
        String invalid6 = "HTTP/1.1 200 OK\r\n" +
                          ":I am broken, and I smell\r\n" +
                          "\r\n";
        String invalid7 = "HTTP/1.1 200 OK\n" +
                          "I am broken, and I smell:\n" +
                          ":asdf\n" +
                          ":\n" +
                          "\n";
        String large    = "HTTP/1.1 200 OK\n" +
                          "Last-modified: Tue, 25 Nov 2003 12:05:38 GMT\n" +
                          "Expires: Tue, 25 Nov 2003 12:05:38 GMT\n" +
                          "Content-length: 32\n" +
                          "\n" +
                          "hi ho, this is the body";
        String blankval = "HTTP/1.0 200 OK\n" +
                          "A:\n" +
                          "\n";
        
        test("Simple", simple, true);
        test("Filtered", filtered, true);
        test("Filtered windows", winfilter, true);
        test("Minimal", minimal, true);
        test("Windows", winmin, true);
        test("Large", large, true);
        test("Blank whitespace", blankval, true);
        test("Invalid (short headers)", invalid1, true);
        test("Invalid (no headers)", invalid2, true);
        test("Invalid (windows with short headers)", invalid3, true);
        test("Invalid (windows no headers)", invalid4, true);
        test("Invalid (bad headers)", invalid5, true);
        test("Invalid (bad headers2)", invalid6, false);
        test("Invalid (bad headers3)", invalid7, false);
    }
    
    private static void test(String name, String orig, boolean shouldPass) {
        System.out.println("====Testing: " + name + "\n" + orig + "\n------------");
        try {
            OutputStream baos = new java.io.ByteArrayOutputStream(4096);
            HTTPResponseOutputStream resp = new HTTPResponseOutputStream(baos);
            resp.write(orig.getBytes());
            resp.flush();
            String received = new String(baos.toByteArray());
            System.out.println(received);
        } catch (Exception e) {
            if (shouldPass)
                e.printStackTrace();
            else
                System.out.println("Properly fails with " + e.getMessage());
        }
    }
******/
}
