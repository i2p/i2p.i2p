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
import java.io.IOException;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.Iterator;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Simple stream for delivering an HTTP response to
 * the client, trivially filtered to make sure "Connection: close"
 * is always in the response.
 *
 */
class HTTPResponseOutputStream extends FilterOutputStream {
    private static final Log _log = new Log(HTTPResponseOutputStream.class);
    private ByteCache _cache;
    protected ByteArray _headerBuffer;
    private boolean _headerWritten;
    private byte _buf1[];
    private static final int CACHE_SIZE = 8*1024;
    
    public HTTPResponseOutputStream(OutputStream raw) {
        super(raw);
        _cache = ByteCache.getInstance(8, CACHE_SIZE);
        _headerBuffer = _cache.acquire();
        _headerWritten = false;
        _buf1 = new byte[1];
    }

    public void write(int c) throws IOException {
        _buf1[0] = (byte)c;
        write(_buf1, 0, 1);
    }
    public void write(byte buf[]) throws IOException { 
        write(buf, 0, buf.length); 
    }
    public void write(byte buf[], int off, int len) throws IOException {
        if (_headerWritten) {
            out.write(buf, off, len);
            return;
        }

        for (int i = 0; i < len; i++) {
            ensureCapacity();
            _headerBuffer.getData()[_headerBuffer.getValid()] = buf[off+i];
            _headerBuffer.setValid(_headerBuffer.getValid()+1);

            if (headerReceived()) {
                writeHeader();
                _headerWritten = true;
                if (i + 1 < len) // write out the remaining
                    out.write(buf, off+i+1, len-i-1);
                return;
            }
        }
    }
    
    /**
     * filter any headers (adding or removing as necessary), and tweak
     * the first response line as necessary.
     *
     * @return response line ("200 OK", etc)
     */
    protected String filterHeaders(String responseLine, Properties props) {
        props.setProperty("Connection", "close");
        props.setProperty("Proxy-Connection", "close");
        return responseLine;
    }
    
    
    /** grow (and free) the buffer as necessary */
    private void ensureCapacity() {
        if (_headerBuffer.getValid() + 1 >= _headerBuffer.getData().length) {
            int newSize = (int)(_headerBuffer.getData().length * 1.5);
            ByteArray newBuf = new ByteArray(new byte[newSize]);
            System.arraycopy(_headerBuffer.getData(), 0, newBuf.getData(), 0, _headerBuffer.getValid());
            newBuf.setValid(_headerBuffer.getValid());
            newBuf.setOffset(0);
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
    
    /** we ignore any potential \r, since we trim it on write anyway */
    private static final byte NL = '\n';
    private boolean isNL(byte b) { return (b == NL); }
    
    /** ok, received, now munge & write it */
    private void writeHeader() throws IOException {
        Properties props = new Properties();
        String responseLine = null;

        int lastEnd = -1;
        for (int i = 0; i < _headerBuffer.getValid(); i++) {
            if (isNL(_headerBuffer.getData()[i])) {
                if (lastEnd == -1) {
                    responseLine = new String(_headerBuffer.getData(), 0, i+1); // includes NL
                } else {
                    for (int j = lastEnd+1; j < i; j++) {
                        if (_headerBuffer.getData()[j] == ':') {
                            String key = new String(_headerBuffer.getData(), lastEnd+1, j-(lastEnd+1));
                            String val = new String(_headerBuffer.getData(), j+2, i-(j+2));
                            props.setProperty(key, val);
                            break;
                        }
                    }
                }
                lastEnd = i;
            }
        }
        
        if (responseLine == null)
            throw new IOException("No HTTP response line, with props=" + props);

        responseLine = filterHeaders(responseLine, props);
        responseLine = (responseLine.trim() + "\n");
        
        if (_log.shouldLog(Log.DEBUG)) {
            StringBuffer msg = new StringBuffer(responseLine.length() + props.size() * 64);
            msg.append("HTTP response: first line [").append(responseLine.trim());
            msg.append("] options: \n");
            
            for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
                String key = (String)iter.next();
                String val = props.getProperty(key);
                msg.append('[').append(key.trim()).append("]=[").append(val.trim()).append("]\n");
            }

            _log.debug(msg.toString());
        }
        
        out.write(responseLine.getBytes());

        for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
            String key = (String)iter.next();
            String val = props.getProperty(key);
            String line = key.trim() + ": " + val.trim() + "\n";
            out.write(line.getBytes());
        }
        out.write("\n".getBytes()); // end of the headers
        
        // done, shove off
        if (_headerBuffer.getData().length == CACHE_SIZE)
            _cache.release(_headerBuffer);
        else
            _headerBuffer = null;
    }
    
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
        String large    = "HTTP/1.1 200 OK\n" +
                          "Last-modified: Tue, 25 Nov 2003 12:05:38 GMT\n" +
                          "Expires: Tue, 25 Nov 2003 12:05:38 GMT\n" +
                          "Content-length: 32\n" +
                          "\n" +
                          "hi ho, this is the body";
        /* */
        test("Simple", simple);
        test("Filtered", filtered);
        test("Filtered windows", winfilter);
        test("Minimal", minimal);
        test("Windows", winmin);
        test("Large", large);
        test("Invalid (short headers)", invalid1);
        test("Invalid (no headers)", invalid2);
        test("Invalid (windows with short headers)", invalid3);
        test("Invalid (windows no headers)", invalid4);
        /* */
    }
    
    private static void test(String name, String orig) {
        System.out.println("====Testing: " + name + "\n" + orig + "\n------------");
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            HTTPResponseOutputStream resp = new HTTPResponseOutputStream(baos);
            resp.write(orig.getBytes());
            resp.flush();
            String received = new String(baos.toByteArray());
            System.out.println(received);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}