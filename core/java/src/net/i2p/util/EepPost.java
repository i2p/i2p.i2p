package net.i2p.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;

import net.i2p.I2PAppContext;

/**
 * Simple helper for uploading files and such via HTTP POST (rfc 1867)
 *
 * @deprecated unused
 */
public class EepPost {
    private Log _log;
    private static final String CRLF = "\r\n";
    
    public EepPost() {
        this(I2PAppContext.getGlobalContext());
    }
    public EepPost(I2PAppContext ctx) {
        _log = ctx.logManager().getLog(EepPost.class);
    }
    
/*****
    public static void main(String args[]) {
        EepPost e = new EepPost();
        Map fields = new HashMap();
        fields.put("key", "value");
        fields.put("key1", "value1");
        fields.put("key2", "value2");
        fields.put("blogpost0", new File("/home/i2p/1.snd"));
        fields.put("blogpost1", new File("/home/i2p/2.snd"));
        fields.put("blogpost2", new File("/home/i2p/2.snd"));
        fields.put("blogpost3", new File("/home/i2p/2.snd"));
        fields.put("blogpost4", new File("/home/i2p/2.snd"));
        fields.put("blogpost5", new File("/home/i2p/2.snd"));
        e.postFiles("http://localhost:7653/import.jsp", null, -1, fields, null);
        //e.postFiles("http://localhost/cgi-bin/read.pl", null, -1, fields, null);
        //e.postFiles("http://localhost:2001/import.jsp", null, -1, fields, null);
    }
*****/

    /**
     * Submit an HTTP POST to the given URL (using the proxy if specified),
     * uploading the given fields.  If the field's value is a File object, then
     * that file is uploaded, and if the field's value is a String object, the
     * value is posted for that particular field.  Multiple values for one 
     * field name is not currently supported.
     *
     */
    public void postFiles(String url, String proxyHost, int proxyPort, Map fields, Runnable onCompletion) {
        I2PThread postThread = new I2PThread(new Runner(url, proxyHost, proxyPort, fields, onCompletion));
        postThread.start();
    }
    
    private class Runner implements Runnable {
        private String _url;
        private String _proxyHost;
        private int _proxyPort;
        private Map _fields;
        private Runnable _onCompletion;
        public Runner(String url, String proxy, int port, Map fields, Runnable onCompletion) {
            _url = url;
            _proxyHost = proxy;
            _proxyPort = port;
            _fields = fields;
            _onCompletion = onCompletion;
        }
        public void run() {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Running the post task");
            Socket s = null;
            try {
                URL u = new URL(_url);
                String h = u.getHost();
                int p = u.getPort();
                if (p <= 0)
                    p = 80;
                String path = u.getPath();

                boolean isProxy = true;
                if ( (_proxyHost == null) || (_proxyPort <= 0) ) {
                    isProxy = false;
                    _proxyHost = h;
                    _proxyPort = p;
                }

                if (_log.shouldLog(Log.DEBUG)) _log.debug("Connecting to the server/proxy...");
                s = new Socket(_proxyHost, _proxyPort);
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Connected");
                OutputStream out = s.getOutputStream();
                String sep = getSeparator();
                long length = calcContentLength(sep, _fields);
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Separator: " + sep + " content length: " + length);
                String header = getHeader(isProxy, path, h, p, sep, length);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Header: \n" + header);
                out.write(header.getBytes());
                out.flush();
                if (false) {
                    out.write(("--" + sep + CRLF + "content-disposition: form-data; name=\"field1\"" + CRLF + CRLF + "Stuff goes here" + CRLF + "--" + sep + "--" + CRLF).getBytes()); 
                } else {
                    sendFields(out, sep, _fields);
                }
                out.flush();
                if (_log.shouldLog(Log.DEBUG)) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    String line = null;
                    while ( (line = in.readLine()) != null) {
                        _log.debug("recv: [" + line + "]");
                    }
                }
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (s != null) try { s.close(); } catch (IOException ioe) {}
                if (_onCompletion != null)
                    _onCompletion.run();
            }
        }
    }
    
    private long calcContentLength(String sep, Map fields) {
        long len = 0;
        for (Iterator iter = fields.keySet().iterator(); iter.hasNext(); ) {
            String key = (String)iter.next();
            Object val = fields.get(key);
            if (val instanceof File) {
                File f = (File)val;
                len += ("--" + sep + CRLF + "Content-Disposition: form-data; name=\"" + key + "\"; filename=\"" + f.getName() + "\"" + CRLF).length();
                //len += ("Content-length: " + f.length() + "\n").length();
                len += ("Content-Type: application/octet-stream" + CRLF + CRLF).length();
                len += f.length();
                len += CRLF.length(); // nl
            } else {
                len += ("--" + sep + CRLF + "Content-Disposition: form-data; name=\"" + key + "\"" + CRLF + CRLF).length();
                len += val.toString().length();
                len += CRLF.length(); // nl
            }
        }
        len += 2 + sep.length() + 2 + CRLF.length(); //2 + sep.length() + 2;
        //len += 2;
        return len;
    }
    private void sendFields(OutputStream out, String separator, Map fields) throws IOException {
        for (Iterator iter = fields.keySet().iterator(); iter.hasNext(); ) {
            String field = (String)iter.next();
            Object val = fields.get(field);
            if (val instanceof File)
                sendFile(out, separator, field, (File)val);
            else
                sendField(out, separator, field, val.toString());
        }
        out.write(("--" + separator + "--" + CRLF).getBytes());
    }
    
    private void sendFile(OutputStream out, String separator, String field, File file) throws IOException {
        //long len = file.length();
        out.write(("--" + separator + CRLF).getBytes());
        out.write(("Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + file.getName() + "\"" + CRLF).getBytes());
        //out.write(("Content-length: " + len + "\n").getBytes());
        out.write(("Content-Type: application/octet-stream" + CRLF + CRLF).getBytes());
        FileInputStream in = new FileInputStream(file);
        byte buf[] = new byte[1024];
        int read = -1;
        while ( (read = in.read(buf)) != -1)
            out.write(buf, 0, read);
        out.write(CRLF.getBytes());
        in.close();
    }
    
    private void sendField(OutputStream out, String separator, String field, String val) throws IOException {
        out.write(("--" + separator + CRLF).getBytes());
        out.write(("Content-Disposition: form-data; name=\"" + field + "\"" + CRLF + CRLF).getBytes());
        out.write(val.getBytes());
        out.write(CRLF.getBytes());
    }
    
    private String getHeader(boolean isProxy, String path, String host, int port, String separator, long length) {
        StringBuilder buf = new StringBuilder(512);
        buf.append("POST ");
        if (isProxy) {
            buf.append("http://").append(host);
            if (port != 80)
                buf.append(":").append(port);
        }
        buf.append(path);
        buf.append(" HTTP/1.1" + CRLF);
        buf.append("Host: ").append(host);
        if (port != 80)
            buf.append(":").append(port);
        buf.append(CRLF);
        buf.append("Connection: close" + CRLF);
        buf.append("Content-length: ").append(length).append(CRLF);
        buf.append("Content-type: multipart/form-data, boundary=").append(separator);
        buf.append(CRLF);
        buf.append(CRLF);
        return buf.toString();
    }
    
    private String getSeparator() {
        //if (false)
        //    return "ABCDEFG"; 
        //if (false)
        //    return "------------------------" + new java.util.Random().nextLong();
        byte separator[] = new byte[32];  // 2^-128 chance of this being a problem
        I2PAppContext.getGlobalContext().random().nextBytes(separator);
        StringBuilder sep = new StringBuilder(48);
        for (int i = 0; i < separator.length; i++)
            sep.append((char)('a' + (separator[i]&0x0F))).append((char)('a' + ((separator[i] >>> 4) & 0x0F)));
        return sep.toString();
    }
}
