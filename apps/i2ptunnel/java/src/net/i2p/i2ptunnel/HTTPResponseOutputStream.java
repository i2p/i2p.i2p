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
    private final I2PAppContext _context;
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

    private static final int CACHE_SIZE = 8*1024;
    private static final ByteCache _cache = ByteCache.getInstance(8, CACHE_SIZE);
    // OOM DOS prevention
    private static final int MAX_HEADER_SIZE = 64*1024;
    
    public HTTPResponseOutputStream(OutputStream raw) {
        super(raw);
        _context = I2PAppContext.getGlobalContext();
        // all createRateStat in I2PTunnelHTTPClient.startRunning()
        _log = _context.logManager().getLog(getClass());
        _headerBuffer = _cache.acquire();
        _buf1 = new byte[1];
    }

    @Override
    public void write(int c) throws IOException {
        _buf1[0] = (byte)c;
        write(_buf1, 0, 1);
    }

    @Override
    public void write(byte buf[]) throws IOException { 
        write(buf, 0, buf.length); 
    }

    @Override
    public void write(byte buf[], int off, int len) throws IOException {
        if (_headerWritten) {
            out.write(buf, off, len);
            //out.flush();
            return;
        }

        for (int i = 0; i < len; i++) {
            ensureCapacity();
            _headerBuffer.getData()[_headerBuffer.getValid()] = buf[off+i];
            _headerBuffer.setValid(_headerBuffer.getValid()+1);

            if (headerReceived()) {
                writeHeader();
                _headerWritten = true;
                if (i + 1 < len) {
                    // write out the remaining
                    out.write(buf, off+i+1, len-i-1);
                    //out.flush();
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
        if (_headerBuffer.getValid() >= MAX_HEADER_SIZE)
            throw new IOException("Max header size exceeded: " + MAX_HEADER_SIZE);
        if (_headerBuffer.getValid() + 1 >= _headerBuffer.getData().length) {
            int newSize = (int)(_headerBuffer.getData().length * 1.5);
            ByteArray newBuf = new ByteArray(new byte[newSize]);
            System.arraycopy(_headerBuffer.getData(), 0, newBuf.getData(), 0, _headerBuffer.getValid());
            newBuf.setValid(_headerBuffer.getValid());
            newBuf.setOffset(0);
            // if we changed the ByteArray size, don't put it back in the cache
            if (_headerBuffer.getData().length == CACHE_SIZE)
                _cache.release(_headerBuffer);
            _headerBuffer = newBuf;
        }
    }
    
    /** are the headers finished? */
    private boolean headerReceived() {
        if (_headerBuffer.getValid() < 3) return false;
        byte first = _headerBuffer.getData()[_headerBuffer.getValid()-3];
        byte second = _headerBuffer.getData()[_headerBuffer.getValid()-2];
        byte third = _headerBuffer.getData()[_headerBuffer.getValid()-1];
        return (isNL(second) && isNL(third)) || //   \n\n
               (isNL(first) && isNL(third));    // \n\r\n
    }
    
    /**
     * Possibly tweak that first HTTP response line (HTTP/1.0 200 OK, etc).
     * Overridden on server side.
     *
     */
    protected String filterResponseLine(String line) {
        return line;
    }
    
    /** we ignore any potential \r, since we trim it on write anyway */
    private static final byte NL = '\n';
    private static boolean isNL(byte b) { return (b == NL); }
    
    /** ok, received, now munge & write it */
    private void writeHeader() throws IOException {
        String responseLine = null;

        boolean connectionSent = false;
        boolean proxyConnectionSent = false;
        
        int lastEnd = -1;
        for (int i = 0; i < _headerBuffer.getValid(); i++) {
            if (isNL(_headerBuffer.getData()[i])) {
                if (lastEnd == -1) {
                    responseLine = DataHelper.getUTF8(_headerBuffer.getData(), 0, i+1); // includes NL
                    responseLine = filterResponseLine(responseLine);
                    responseLine = (responseLine.trim() + "\r\n");
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Response: " + responseLine.trim());
                    out.write(DataHelper.getUTF8(responseLine));
                } else {
                    for (int j = lastEnd+1; j < i; j++) {
                        if (_headerBuffer.getData()[j] == ':') {
                            int keyLen = j-(lastEnd+1);
                            int valLen = i-(j+1);
                            if ( (keyLen <= 0) || (valLen < 0) )
                                throw new IOException("Invalid header @ " + j);
                            String key = DataHelper.getUTF8(_headerBuffer.getData(), lastEnd+1, keyLen);
                            String val;
                            if (valLen == 0)
                                val = "";
                            else
                                val = DataHelper.getUTF8(_headerBuffer.getData(), j+2, valLen).trim();
                            
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Response header [" + key + "] = [" + val + "]");
                            
                            String lcKey = key.toLowerCase(Locale.US);
                            if ("connection".equals(lcKey)) {
                                if (val.toLowerCase(Locale.US).contains("upgrade")) {
                                    // pass through for websocket
                                    out.write(DataHelper.getASCII("Connection: " + val + "\r\n"));
                                    proxyConnectionSent = true;
                                } else {
                                    out.write(DataHelper.getASCII("Connection: close\r\n"));
                                }
                                connectionSent = true;
                            } else if ("proxy-connection".equals(lcKey)) {
                                out.write(DataHelper.getASCII("Proxy-Connection: close\r\n"));
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
                                        if (_log.shouldLog(Log.INFO))
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
            out.write(DataHelper.getASCII("Connection: close\r\n"));
        if (!proxyConnectionSent)
            out.write(DataHelper.getASCII("Proxy-Connection: close\r\n"));
            
        finishHeaders();

        boolean shouldCompress = shouldCompress();
        if (_log.shouldLog(Log.INFO))
            _log.info("After headers: gzip? " + _gzip + " compress? " + shouldCompress);
        
        // done, shove off
        if (_headerBuffer.getData().length == CACHE_SIZE)
            _cache.release(_headerBuffer);
        else
            _headerBuffer = null;
        if (shouldCompress) {
            beginProcessing();
        }
    }
    
    protected boolean shouldCompress() { return _gzip; }
    
    protected void finishHeaders() throws IOException {
        out.write(DataHelper.getASCII("\r\n")); // end of the headers
    }
    
    @Override
    public void close() throws IOException {
        if (_log.shouldLog(Log.INFO))
            _log.info("Closing " + out + " threaded?? " + shouldCompress(), new Exception("I did it"));
        synchronized(this) {
            // synch with changing out field below
            super.close();
        }
    }
    
    protected void beginProcessing() throws IOException {
        //out.flush();
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
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
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
