/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel.localServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.crypto.Blinding;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.BlindData;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.i2ptunnel.GunzipOutputStream;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;
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

    private final static String ERR_B32 =
         "HTTP/1.1 400 Bad\r\n"+
         "Content-Type: text/plain\r\n"+
         "Connection: close\r\n"+
         "Proxy-Connection: close\r\n"+
         "\r\n"+
         "B32 update failed - bad parameters";

    private final static String OK =
         "HTTP/1.1 200 OK\r\n" +
         "Content-Type: text/plain\r\n" +
         "Cache-Control: max-age=86400\r\n" +
         "Connection: close\r\n"+
         "Proxy-Connection: close\r\n"+
         "\r\n"+
         "I2P HTTP proxy OK";

    private final static String NEWKEY =
         "HTTP/1.1 200 OK\r\n" +
         "Content-Type: text/html; charset=UTF-8\r\n" +
         "Referrer-Policy: no-referrer\r\n"+
         "Cache-Control: no-cache\r\n" +
         "Connection: close\r\n"+
         "Proxy-Connection: close\r\n"+
         "\r\n";

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
     *  @param sockMgr only for /b32, otherwise ignored
     *  @param targetRequest decoded path only, non-null
     *  @param query raw (encoded), may be null
     *  @param allowGzip may we send a gzipped response?
     */
    public static void serveLocalFile(I2PAppContext context, I2PSocketManager sockMgr,
                                      OutputStream out, String method, String targetRequest,
                                      String query, String proxyNonce, boolean allowGzip) throws IOException {
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
            String filename = targetRequest.substring(1);
            // theme hack
            if (filename.startsWith("themes/console/default/"))
                filename = filename.replaceFirst("default", context.getProperty("routerconsole.theme", "light"));
            if (filename.endsWith(".css"))
                filename = filename + ".gz";
            InputStream in = getResource(filename);
            if (in != null) {
                try {
                    String type;
                    if (filename.endsWith(".css.gz"))
                        type = "text/css; charset=UTF-8";
                    else if (filename.endsWith(".ico"))
                        type = "image/x-icon";
                    else if (filename.endsWith(".png"))
                        type = "image/png";
                    else if (filename.endsWith(".jpg"))
                        type = "image/jpeg";
                    else type = "text/html; charset=UTF-8";
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: ".getBytes("UTF-8"));
                    out.write(type.getBytes("UTF-8"));
                    if (allowGzip && filename.endsWith(".gz"))
                        out.write("\r\nContent-Encoding: gzip".getBytes("UTF-8"));
                    out.write("\r\nCache-Control: max-age=86400\r\nConnection: close\r\nProxy-Connection: close\r\n\r\n".getBytes("UTF-8"));
                    if (!allowGzip && filename.endsWith(".gz")) {
                        // gunzip on the fly. should be very rare, all browsers should support gzip
                        OutputStream out2 = new GunzipOutputStream(out);
                        DataHelper.copy(in, out2);
                        out2.flush();
                    } else {
                        DataHelper.copy(in, out);
                    }
                } finally {
                    try { in.close(); } catch (IOException ioe) {}
                }
                return;
            }
        }

        // Add to addressbook (form submit)
        // Parameters are url, host, dest, nonce, and local | router | private.
        // Do the add and redirect.
        if (targetRequest.equals("/add")) {
            if (query == null) {
                out.write(ERR_ADD.getBytes("UTF-8"));
                return;
            }
            Map<String, String> opts = decodeQuery(query);

            String url = opts.get("url");
            String host = opts.get("host");
            String b64Dest = opts.get("dest");
            String nonce = opts.get("nonce");
            String referer = opts.get("referer");
            String book = "privatehosts.txt";
            if (opts.get("local") != null)
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
                NamingService ns = context.namingService();
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

        } else if (targetRequest.equals("/b32")) {
            // Send a blinding info message (form submit)
            // Parameters are url, host, nonce, code, privkey, secret, action.
            // Store the results and either display them or redirect.
            if (query == null) {
                out.write(ERR_ADD.getBytes("UTF-8"));
                return;
            }
            Map<String, String> opts = decodeQuery(query);

            String err = null;
            String url = opts.get("url");
            String host = opts.get("host");
            String nonce = opts.get("nonce");
            String code = opts.get("code");
            String privkey = opts.get("privkey");
            if (privkey != null)
                privkey = privkey.trim();
            String secret = opts.get("secret");
            if (secret != null)
                secret = secret.trim();
            String action = opts.get("action");
            if (proxyNonce.equals(nonce) && url != null && host != null && code != null) {
                boolean success = true;
                PrivateKey privateKey = null;
                PublicKey publicKey = null;
                if (!code.equals("2") && !code.equals("4")) {
                    secret = null;
                } else if (secret == null || secret.length() == 0) {
                    err = _t("Missing lookup password");
                    success = false;
                }

                int authType = BlindData.AUTH_NONE;
                if (!code.equals("3") && !code.equals("4")) {
                    privkey = null;
                } else if ("newdh".equals(action) || "newpsk".equals(action)) {
                    // newpsk probably not required
                    KeyPair kp = context.keyGenerator().generatePKIKeys(EncType.ECIES_X25519);
                    privateKey = kp.getPrivate();
                    publicKey = kp.getPublic();
                    authType = action.equals("newdh") ? BlindData.AUTH_DH : BlindData.AUTH_PSK;
                } else if (privkey == null || privkey.length() == 0) {
                    err = _t("Missing private key");
                    success = false;
                } else {
                    byte[] data = Base64.decode(privkey);
                    if (data == null || data.length != 32) {
                        err = _t("Invalid private key");
                        success = false;
                    } else {
                        privateKey = new PrivateKey(EncType.ECIES_X25519, data);
                        authType = BlindData.AUTH_PSK;
                    }
                }

                if (success) {
                    try {
                        // get spk and blind type
                        BlindData bd = Blinding.decode(context, host);
                        SigningPublicKey spk = bd.getUnblindedPubKey();
                        SigType bt = bd.getBlindedSigType();
                        bd = new BlindData(context, spk, bt, secret, authType, privateKey);
                        long now = context.clock().now();
                        bd.setDate(now);
                        long exp = now + ((bd.getAuthRequired() || bd.getSecretRequired()) ? 365*24*60*60*1000L
                                                                                           :  90*24*68*60*1000L);
                        bd.setExpiration(exp);
                        I2PSession sess = sockMgr.getSession();
                        sess.sendBlindingInfo(bd);
                        if ("newdh".equals(action) || "newpsk".equals(action)) {
                            String key;
                            if ("newdh".equals(action))
                                key = publicKey.toBase64();
                            else
                                key = privateKey.toBase64();
                            StringBuilder buf = new StringBuilder(1024);
                            PortMapper pm = context.portMapper();
                            String conURL = pm.getConsoleURL();
                            buf.append(NEWKEY)
                               .append("<html><head><title>")
                               .append(_t("Your new encryption key"))
                               .append("</title>\n" +
                                       "<link rel=\"shortcut icon\" href=\"http://proxy.i2p/themes/console/images/favicon.ico\" >\n" +
                                       "<link href=\"http://proxy.i2p/themes/console/default/console.css\" rel=\"stylesheet\" type=\"text/css\" >\n" +
                                       "</head><body>\n" +
                                       "<div class=logo>\n" +
                                       "<a href=\"")
                               .append(conURL).append("\" title=\"").append(_t("Router Console"))
                               .append("\"><img src=\"http://proxy.i2p/themes/console/images/i2plogo.png\" alt=\"I2P Router Console\" border=\"0\"></a><hr>\n" +
                                       "<a href=\"")
                               .append(conURL).append("config\">").append(_t("Configuration")).append("</a> <a href=\"")
                               .append(conURL).append("help.jsp\">").append(_t("Help")).append("</a>");
                            if (pm.isRegistered(PortMapper.SVC_SUSIDNS)) {
                                buf.append(" <a href=\"").append(conURL).append("susidns/index\">")
                                   .append(_t("Address book")).append("</a>\n");
                            }
                            buf.append("</div>" +
                                       "<div class=warning id=warning>\n" +
                                       "<h3>")
                               .append(_t("Your new encryption key"))
                               .append("</h3>\n<p>" +
                                       "<textarea rows=\"1\" style=\"min-width: 0; min-height: 0;\" cols=\"70\" wrap=\"off\" readonly=\"readonly\" >")
                               .append(key)
                               .append("</textarea><p>")
                               .append(_t("Copy the key and send it to the server operator."))
                               .append(' ')
                               .append(_t("After you are granted permission, you may proceed to the website."))
                               .append("<p><a href=\"")
                               .append(url)
                               .append("\">")
                               .append(url)
                               .append("</a></div>");
                            out.write(buf.toString().getBytes("UTF-8"));
                            I2PTunnelHTTPClientBase.writeFooter(out);
                        } else {
                            writeB32RedirectPage(out, host, url);
                        }
                        return;
                    } catch (IllegalArgumentException iae) {
                        err = iae.toString();
                    } catch (I2PSessionException ise) {
                        err = ise.toString();
                    }
                }
            }
            out.write(ERR_B32.getBytes("UTF-8"));
            if (err != null)
                out.write(("\n\n" + err + "\n\n" + _t("Go back and fix the error")).getBytes("UTF-8"));
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
            tbook = _t("local");
        else if ("privatehosts.txt".equals(book))
            tbook = _t("private");
        else
            tbook = book;

        PortMapper pm = I2PAppContext.getGlobalContext().portMapper();
        String conURL = pm.getConsoleURL();
        String idn = I2PTunnelHTTPClientBase.decodeIDNHost(host);
        out.write(("HTTP/1.1 200 OK\r\n"+
                  "Content-Type: text/html; charset=UTF-8\r\n"+
                  "Referrer-Policy: no-referrer\r\n"+
                  "Connection: close\r\n"+
                  "Proxy-Connection: close\r\n"+
                  "\r\n"+
                  "<html><head>"+
                  "<title>" + _t("Redirecting to {0}", idn) + "</title>\n" +
                  "<link rel=\"shortcut icon\" href=\"http://proxy.i2p/themes/console/images/favicon.ico\" >\n" +
                  "<link href=\"http://proxy.i2p/themes/console/default/console.css\" rel=\"stylesheet\" type=\"text/css\" >\n" +
                  "<meta http-equiv=\"Refresh\" content=\"1; url=" + url + "\">\n" +
                  "</head><body>\n" +
                  "<div class=logo>\n" +
                  "<a href=\"" + conURL + "\" title=\"" + _t("Router Console") + "\"><img src=\"http://proxy.i2p/themes/console/images/i2plogo.png\" alt=\"I2P Router Console\" border=\"0\"></a><hr>\n" +
                  "<a href=\"" + conURL + "config\">" + _t("Configuration") + "</a> <a href=\"" + conURL + "help.jsp\">" + _t("Help") + "</a>").getBytes("UTF-8"));
        if (pm.isRegistered(PortMapper.SVC_SUSIDNS))
            out.write((" <a href=\"" + conURL + "susidns/index\">" + _t("Address Book") + "</a>\n").getBytes("UTF-8"));
        out.write(("</div>" +
                  "<div class=warning id=warning>\n" +
                  "<h3>" +
                  (success ?
                           _t("Saved {0} to the {1} address book, redirecting now.", idn, tbook) :
                           _t("Failed to save {0} to the {1} address book, redirecting now.", idn, tbook)) +
                  "</h3>\n<p><a href=\"" + url + "\">" +
                  _t("Click here if you are not redirected automatically.") +
                  "</a></p></div>").getBytes("UTF-8"));
        I2PTunnelHTTPClientBase.writeFooter(out);
        out.flush();
    }

    /** @since 0.9.43 */
    private static void writeB32RedirectPage(OutputStream out, String host, String url) throws IOException {
        PortMapper pm = I2PAppContext.getGlobalContext().portMapper();
        String conURL = pm.getConsoleURL();
        String idn = I2PTunnelHTTPClientBase.decodeIDNHost(host);
        out.write(("HTTP/1.1 200 OK\r\n"+
                  "Content-Type: text/html; charset=UTF-8\r\n"+
                  "Referrer-Policy: no-referrer\r\n"+
                  "Connection: close\r\n"+
                  "Proxy-Connection: close\r\n"+
                  "\r\n"+
                  "<html><head>"+
                  "<title>" + _t("Redirecting to {0}", idn) + "</title>\n" +
                  "<link rel=\"shortcut icon\" href=\"http://proxy.i2p/themes/console/images/favicon.ico\" >\n" +
                  "<link href=\"http://proxy.i2p/themes/console/default/console.css\" rel=\"stylesheet\" type=\"text/css\" >\n" +
                  "<meta http-equiv=\"Refresh\" content=\"1; url=" + url + "\">\n" +
                  "</head><body>\n" +
                  "<div class=logo>\n" +
                  "<a href=\"" + conURL + "\" title=\"" + _t("Router Console") + "\"><img src=\"http://proxy.i2p/themes/console/images/i2plogo.png\" alt=\"I2P Router Console\" border=\"0\"></a><hr>\n" +
                  "<a href=\"" + conURL + "config\">" + _t("Configuration") + "</a> <a href=\"" + conURL + "help.jsp\">" + _t("Help") + "</a>").getBytes("UTF-8"));
        if (pm.isRegistered(PortMapper.SVC_SUSIDNS))
            out.write((" <a href=\"" + conURL + "susidns/index\">" + _t("Address Book") + "</a>\n").getBytes("UTF-8"));
        out.write(("</div>" +
                  "<div class=warning id=warning>\n" +
                  "<h3>" +
                  _t("Saved the authentication for {0}, redirecting now.", idn) +
                  "</h3>\n<p><a href=\"" + url + "\">" +
                  _t("Click here if you are not redirected automatically.") +
                  "</a></p></div>").getBytes("UTF-8"));
        I2PTunnelHTTPClientBase.writeFooter(out);
        out.flush();
    }

    /**
     *  Parse an encoded query.
     *  Only supports ONE value per key.
     *
     *  @param query an ENCODED query, non-null
     *  @return map of DECODED keys to DECODED values, non-null. Values may be empty.
     *  @since 0.9.43 adapted from I2PTunnelHTTPClient.removeHelper()
     */
    private static Map<String, String> decodeQuery(String query) {
        Map<String, String> rv = new HashMap<String, String>(8);
        int keystart = 0;
        int valstart = -1;
        String key = null;
        for (int i = 0; i <= query.length(); i++) {
            char c = i < query.length() ? query.charAt(i) : '&';
            if (c == ';' || c == '&') {
                // end of key or value
                if (valstart < 0)
                    key = query.substring(keystart, i);
                if (key.length() > 0) {
                    String decodedKey = decode(key);
                    String newQuery = keystart > 0 ? query.substring(0, keystart - 1) : "";
                    if (i < query.length() - 1) {
                        if (keystart > 0)
                            newQuery += query.substring(i);
                        else
                            newQuery += query.substring(i + 1);
                    }
                    String value = valstart >= 0 ? query.substring(valstart, i) : "";
                    String decodedValue = decode(value);
                    rv.put(decodedKey, decodedValue);
                }
                keystart = i + 1;
                valstart = -1;
            } else if (c == '=' && valstart < 0) {
                // end of key
                key = query.substring(keystart, i);
                valstart = i + 1;
            }
        }
        return rv;
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

    /**
     *  @param resource relative path
     *  @return stream or null if not found
     *  @since 0.9.49
     */
    public static InputStream getResource(String resource) {
            return LocalHTTPServer.class.getResourceAsStream("/net/i2p/i2ptunnel/resources/" + resource);
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

/****
    private static String[] tests = {
        "", "foo", "foo=bar", "&", "&=&", "===", "&&",
        "a&b&c&d",
        "a&b&c&",
        "i2paddresshelper=foo",
        "i2paddresshelper=foo===",
        "i2paddresshelper=%66oo",
        "%692paddresshelper=foo",
        "i2paddresshelper=foo&a=b",
        "a=b&i2paddresshelper=foo",
        "a=b&i2paddresshelper&c=d",
        "a=b&i2paddresshelper=foo&c=d",
        "a=b;i2paddresshelper=foo;c=d",
        "a=b&i2paddresshelper=foo&c",
        "a=b&i2paddresshelper=foo==&c",
        "a=b&i2paddresshelper=foo%3d%3d&c",
        "a=b&i2paddresshelper=f%6f%6F==&c",
        "a=b&i2paddresshelper=foo&i2paddresshelper=bar&c",
        "a=b&i2paddresshelper=foo&c%3F%3f%26%3b%3B%3d%3Dc=x%3F%3f%26%3b%3B%3d%3Dx"
    };

    public static void main(String[] args) {
        for (int i = 0; i < tests.length; i++) {
            Map<String, String> m = decodeQuery(tests[i]);
            System.out.println("\nTest \"" + tests[i] + '"');
            for (Map.Entry<String, String> e : m.entrySet()) {
                System.out.println("    \"" + e.getKey() + "\" = \"" + e.getValue() + '"');
            }
        }
    }
****/
}
