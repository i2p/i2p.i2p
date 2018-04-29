/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel.localServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.NamingService;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnelHTTPClient;
import net.i2p.util.FileUtil;
import net.i2p.util.PortMapper;
import net.i2p.util.Translate;

/**
 *  Very simple web server.
 *
 *  Serve local files in the docs/ directory, for CSS and images in
 *  error pages, using the reserved address proxy.i2p
 *  (similar to p.p in privoxy).
 *  This solves the problems with including links to the router console,
 *  as assuming the router console is at 127.0.0.1 leads to broken
 *  links if it isn't.
 *
 *  @since 0.7.6, moved from I2PTunnelHTTPClient in 0.9
 */
public abstract class LocalHTTPServer {

    private final static String ERR_404 =
         "HTTP/1.1 404 Not Found\r\n"+
         "Content-Type: text/plain\r\n"+
         "Connection: close\r\n"+
         "Proxy-Connection: close\r\n"+
         "\r\n"+
         "HTTP Proxy local file not found";

    private final static String ERR_ADD =
         "HTTP/1.1 409 Bad\r\n"+
         "Content-Type: text/plain\r\n"+
         "Connection: close\r\n"+
         "Proxy-Connection: close\r\n"+
         "\r\n"+
         "Add to addressbook failed - bad parameters";

    private final static String OK =
         "HTTP/1.1 200 OK\r\n" +
         "Content-Type: text/plain\r\n" +
         "Cache-Control: max-age=86400\r\n" +
         "Connection: close\r\n"+
         "Proxy-Connection: close\r\n"+
         "\r\n"+
         "I2P HTTP proxy OK";

    /**
     *  Very simple web server.
     *
     *  Serve local files in the docs/ directory, for CSS and images in
     *  error pages, using the reserved address proxy.i2p
     *  (similar to p.p in privoxy).
     *  This solves the problems with including links to the router console,
     *  as assuming the router console is at 127.0.0.1 leads to broken
     *  links if it isn't.
     *
     *  Ignore all request headers (If-Modified-Since, etc.)
     *
     *  There is basic protection here -
     *  FileUtil.readFile() prevents traversal above the base directory -
     *  but inproxy/gateway ops would be wise to block proxy.i2p to prevent
     *  exposing the docs/ directory or perhaps other issues through
     *  uncaught vulnerabilities.
     *  Restrict to the /themes/ directory for now.
     *
     *  @param targetRequest decoded path only, non-null
     *  @param query raw (encoded), may be null
     */
    public static void serveLocalFile(OutputStream out, String method, String targetRequest,
                                      String query, String proxyNonce) throws IOException {
        //System.err.println("targetRequest: \"" + targetRequest + "\"");
        // a home page message for the curious...
        if (targetRequest.equals("/")) {
            out.write(OK.getBytes("UTF-8"));
            out.flush();
            return;
        }
        if ((method.equals("GET") || method.equals("HEAD")) &&
            targetRequest.startsWith("/themes/") &&
            !targetRequest.contains("..")) {
            String filename = null;
            try {
                filename = targetRequest.substring(8); // "/themes/".length
            } catch (IndexOutOfBoundsException ioobe) {
                 return;
            }
            // theme hack
            if (filename.startsWith("console/default/"))
                filename = filename.replaceFirst("default", I2PAppContext.getGlobalContext().getProperty("routerconsole.theme", "light"));
            File themesDir = new File(I2PAppContext.getGlobalContext().getBaseDir(), "docs/themes");
            File file = new File(themesDir, filename);
            if (file.exists() && !file.isDirectory()) {
                String type;
                if (filename.endsWith(".css"))
                    type = "text/css";
                else if (filename.endsWith(".ico"))
                    type = "image/x-icon";
                else if (filename.endsWith(".png"))
                    type = "image/png";
                else if (filename.endsWith(".jpg"))
                    type = "image/jpeg";
                else type = "text/html";
                out.write("HTTP/1.1 200 OK\r\nContent-Type: ".getBytes("UTF-8"));
                out.write(type.getBytes("UTF-8"));
                out.write("\r\nCache-Control: max-age=86400\r\nConnection: close\r\nProxy-Connection: close\r\n\r\n".getBytes("UTF-8"));
                FileUtil.readFile(filename, themesDir.getAbsolutePath(), out);
                return;
            }
        }

        // Add to addressbook (form submit)
        // Parameters are url, host, dest, nonce, and master | router | private.
        // Do the add and redirect.
        if (targetRequest.equals("/add")) {
            if (query == null) {
                out.write(ERR_ADD.getBytes("UTF-8"));
                return;
            }
            Map<String, String> opts = new HashMap<String, String>(8);
            // this only works if all keys are followed by =value
            StringTokenizer tok = new StringTokenizer(query, "=&;");
            while (tok.hasMoreTokens()) {
                String k = tok.nextToken();
                if (!tok.hasMoreTokens())
                    break;
                String v = tok.nextToken();
                opts.put(decode(k), decode(v));
            }

            String url = opts.get("url");
            String host = opts.get("host");
            String b64Dest = opts.get("dest");
            String nonce = opts.get("nonce");
            String referer = opts.get("referer");
            String book = "privatehosts.txt";
            if (opts.get("master") != null)
                book = "userhosts.txt";
            else if (opts.get("router") != null)
                book = "hosts.txt";
            Destination dest = null;
            if (b64Dest != null) {
                try {
                    dest = new Destination(b64Dest);
                } catch (DataFormatException dfe) {
                    System.err.println("Bad dest to save?" + b64Dest);
                }
            }
            //System.err.println("url          : \"" + url           + "\"");
            //System.err.println("host         : \"" + host          + "\"");
            //System.err.println("b64dest      : \"" + b64Dest       + "\"");
            //System.err.println("book         : \"" + book          + "\"");
            //System.err.println("nonce        : \"" + nonce         + "\"");
            if (proxyNonce.equals(nonce) && url != null && host != null && dest != null) {
                NamingService ns = I2PAppContext.getGlobalContext().namingService();
                Properties nsOptions = new Properties();
                nsOptions.setProperty("list", book);
                if (referer != null && referer.startsWith("http")) {
                    String ref = DataHelper.escapeHTML(referer);
                    String from = "<a href=\"" + ref + "\">" + ref + "</a>";
                    nsOptions.setProperty("s", _t("Added via address helper from {0}", from));
                } else {
                    nsOptions.setProperty("s", _t("Added via address helper"));
                }
                boolean success = ns.put(host, dest, nsOptions);
                writeRedirectPage(out, success, host, book, url);
                return;
            }
            out.write(ERR_ADD.getBytes("UTF-8"));
        } else {
            out.write(ERR_404.getBytes("UTF-8"));
        }
        out.flush();
    }

    /** @since 0.8.7 */
    private static void writeRedirectPage(OutputStream out, boolean success, String host, String book, String url) throws IOException {
        String tbook;
        if ("hosts.txt".equals(book))
            tbook = _t("router");
        else if ("userhosts.txt".equals(book))
            tbook = _t("master");
        else if ("privatehosts.txt".equals(book))
            tbook = _t("private");
        else
            tbook = book;

        PortMapper pm = I2PAppContext.getGlobalContext().portMapper();
        String conURL = pm.getConsoleURL();
        out.write(("HTTP/1.1 200 OK\r\n"+
                  "Content-Type: text/html; charset=UTF-8\r\n"+
                  "Referrer-Policy: no-referrer\r\n"+
                  "Connection: close\r\n"+
                  "Proxy-Connection: close\r\n"+
                  "\r\n"+
                  "<html><head>"+
                  "<title>" + _t("Redirecting to {0}", host) + "</title>\n" +
                  "<link rel=\"shortcut icon\" href=\"http://proxy.i2p/themes/console/images/favicon.ico\" >\n" +
                  "<link href=\"http://proxy.i2p/themes/console/default/console.css\" rel=\"stylesheet\" type=\"text/css\" >\n" +
                  "<meta http-equiv=\"Refresh\" content=\"1; url=" + url + "\">\n" +
                  "</head><body>\n" +
                  "<div class=logo>\n" +
                  "<a href=\"" + conURL + "\" title=\"" + _t("Router Console") + "\"><img src=\"http://proxy.i2p/themes/console/images/i2plogo.png\" alt=\"I2P Router Console\" border=\"0\"></a><hr>\n" +
                  "<a href=\"" + conURL + "config\">" + _t("Configuration") + "</a> <a href=\"" + conURL + "help.jsp\">" + _t("Help") + "</a>").getBytes("UTF-8"));
        if (pm.isRegistered(PortMapper.SVC_SUSIDNS))
            out.write((" <a href=\"" + conURL + "susidns/index\">" + _t("Addressbook") + "</a>\n").getBytes("UTF-8"));
        out.write(("</div>" +
                  "<div class=warning id=warning>\n" +
                  "<h3>" +
                  (success ?
                           _t("Saved {0} to the {1} addressbook, redirecting now.", host, tbook) :
                           _t("Failed to save {0} to the {1} addressbook, redirecting now.", host, tbook)) +
                  "</h3>\n<p><a href=\"" + url + "\">" +
                  _t("Click here if you are not redirected automatically.") +
                  "</a></p></div>").getBytes("UTF-8"));
        I2PTunnelHTTPClient.writeFooter(out);
        out.flush();
    }

    /**
     *  Decode %xx encoding
     *  @since 0.8.7
     */
    public static String decode(String s) {
        if (!s.contains("%"))
            return s;
        StringBuilder buf = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '%') {
                buf.append(c);
            } else {
                try {
                    buf.append((char) Integer.parseInt(s.substring(++i, (++i) + 1), 16));
                } catch (IndexOutOfBoundsException ioobe) {
                    break;
                } catch (NumberFormatException nfe) {
                    break;
                }
            }
        }
        return buf.toString();
    }

    /** these strings go in the jar, not the war */
    private static final String BUNDLE_NAME = "net.i2p.i2ptunnel.proxy.messages";

    /** lang in routerconsole.lang property, else current locale */
    protected static String _t(String key) {
        return Translate.getString(key, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /** {0} */
    protected static String _t(String key, Object o) {
        return Translate.getString(key, o, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /** {0} and {1} */
    protected static String _t(String key, Object o, Object o2) {
        return Translate.getString(key, o, o2, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

}
