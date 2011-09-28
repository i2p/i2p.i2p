package net.i2p.i2ptunnel;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.concurrent.RejectedExecutionException;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.BigPipedInputStream;
import net.i2p.util.ByteCache;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.ReusableGZIPInputStream;

/**
 * This does the transparent gzip decompression on the client side.
 * Extended in I2PTunnelHTTPServer to do the compression on the server side.
 *
 * Simple stream for delivering an HTTP response to
 * the client, trivially filtered to make sure "Connection: close"
 * is always in the response.  Perhaps add transparent handling of the
 * Content-encoding: x-i2p-gzip, adjusting the headers to say Content-encoding: identity?
 * Content-encoding: gzip is trivial as well, but Transfer-encoding: chunked makes it
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
    private long _dataWritten;
    protected long _dataExpected;
    protected String _contentType;

    private static final int CACHE_SIZE = 8*1024;
    private static final ByteCache _cache = ByteCache.getInstance(8, CACHE_SIZE);
    // OOM DOS prevention
    private static final int MAX_HEADER_SIZE = 64*1024;
    
    public HTTPResponseOutputStream(OutputStream raw) {
        super(raw);
        _context = I2PAppContext.getGlobalContext();
        _context.statManager().createRateStat("i2ptunnel.httpCompressionRatio", "ratio of compressed size to decompressed size after transfer", "I2PTunnel", new long[] { 60*60*1000 });
        _context.statManager().createRateStat("i2ptunnel.httpCompressed", "compressed size transferred", "I2PTunnel", new long[] { 60*60*1000 });
        _context.statManager().createRateStat("i2ptunnel.httpExpanded", "size transferred after expansion", "I2PTunnel", new long[] { 60*60*1000 });
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
            _dataWritten += len;
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
                    _dataWritten += len-i-1;
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
                    responseLine = new String(_headerBuffer.getData(), 0, i+1); // includes NL
                    responseLine = filterResponseLine(responseLine);
                    responseLine = (responseLine.trim() + "\r\n");
                    out.write(responseLine.getBytes());
                } else {
                    for (int j = lastEnd+1; j < i; j++) {
                        if (_headerBuffer.getData()[j] == ':') {
                            int keyLen = j-(lastEnd+1);
                            int valLen = i-(j+1);
                            if ( (keyLen <= 0) || (valLen < 0) )
                                throw new IOException("Invalid header @ " + j);
                            String key = new String(_headerBuffer.getData(), lastEnd+1, keyLen);
                            String val = null;
                            if (valLen == 0)
                                val = "";
                            else
                                val = new String(_headerBuffer.getData(), j+2, valLen).trim();
                            
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Response header [" + key + "] = [" + val + "]");
                            
                            if ("Connection".equalsIgnoreCase(key)) {
                                out.write("Connection: close\r\n".getBytes());
                                connectionSent = true;
                            } else if ("Proxy-Connection".equalsIgnoreCase(key)) {
                                out.write("Proxy-Connection: close\r\n".getBytes());
                                proxyConnectionSent = true;
                            } else if ( ("Content-encoding".equalsIgnoreCase(key)) && ("x-i2p-gzip".equalsIgnoreCase(val)) ) {
                                _gzip = true;
                            } else if ("Proxy-Authenticate".equalsIgnoreCase(key)) {
                                // filter this hop-by-hop header; outproxy authentication must be configured in I2PTunnelHTTPClient
                            } else {
                                if ("Content-Length".equalsIgnoreCase(key)) {
                                    // save for compress decision on server side
                                    try {
                                        _dataExpected = Long.parseLong(val);
                                    } catch (NumberFormatException nfe) {}
                                } else if ("Content-Type".equalsIgnoreCase(key)) {
                                    // save for compress decision on server side
                                    _contentType = val;
                                }
                                out.write((key.trim() + ": " + val.trim() + "\r\n").getBytes());
                            }
                            break;
                        }
                    }
                }
                lastEnd = i;
            }
        }
        
        if (!connectionSent)
            out.write("Connection: close\r\n".getBytes());
        if (!proxyConnectionSent)
            out.write("Proxy-Connection: close\r\n".getBytes());
            
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
        out.write("\r\n".getBytes()); // end of the headers
    }
    
    @Override
    public void close() throws IOException {
        out.close();
    }
    
    protected void beginProcessing() throws IOException {
        //out.flush();
        PipedInputStream pi = BigPipedInputStream.getInstance();
        PipedOutputStream po = new PipedOutputStream(pi);
        // Run in the client thread pool, as there should be an unused thread
        // there after the accept().
        // Overridden in I2PTunnelHTTPServer, where it does not use the client pool.
        try {
            I2PTunnelClientBase.getClientExecutor().execute(new Pusher(pi, out));
        } catch (RejectedExecutionException ree) {
            // shouldn't happen
            throw ree;
        }
        out = po;
    }
    
    private class Pusher implements Runnable {
        private final InputStream _inRaw;
        private final OutputStream _out;

        public Pusher(InputStream in, OutputStream out) {
            _inRaw = in;
            _out = out;
        }

        public void run() {
            ReusableGZIPInputStream _in = null;
            long written = 0;
            ByteArray ba = null;
            try {
                _in = ReusableGZIPInputStream.acquire();
                // blocking
                _in.initialize(_inRaw);
                ba = _cache.acquire();
                byte buf[] = ba.getData();
                int read = -1;
                while ( (read = _in.read(buf)) != -1) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Read " + read + " and writing it to the browser/streams");
;                   _out.write(buf, 0, read);
                    _out.flush();
                    written += read;
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info("Decompressed: " + written + ", " + _in.getTotalRead() + "/" + _in.getTotalExpanded());
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error decompressing: " + written + ", " + (_in != null ? _in.getTotalRead() + "/" + _in.getTotalExpanded() : ""), ioe);
            } catch (OutOfMemoryError oom) {
                _log.error("OOM in HTTP Decompressor", oom);
            } finally {
                if (_log.shouldLog(Log.INFO) && (_in != null))
                    _log.info("After decompression, written=" + written + 
                                " read=" + _in.getTotalRead() 
                                + ", expanded=" + _in.getTotalExpanded() + ", remaining=" + _in.getRemaining() 
                                + ", finished=" + _in.getFinished());
                if (ba != null)
                    _cache.release(ba);
                if (_out != null) try { 
                    _out.close(); 
                } catch (IOException ioe) {}
            }

            if (_in != null) {
                double compressed = _in.getTotalRead();
                double expanded = _in.getTotalExpanded();
                ReusableGZIPInputStream.release(_in);
                if (compressed > 0 && expanded > 0) {
                    // only update the stats if we did something
                    double ratio = compressed/expanded;
                    _context.statManager().addRateData("i2ptunnel.httpCompressionRatio", (int)(100d*ratio), 0);
                    _context.statManager().addRateData("i2ptunnel.httpCompressed", (long)compressed, 0);
                    _context.statManager().addRateData("i2ptunnel.httpExpanded", (long)expanded, 0);
                }
            }
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
