package net.i2p.servlet.util;

import java.util.Locale;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Request;

/**
 * Simple utilities for servlets.
 * Consolidated from i2psnark, susimail, and routerconsole
 * @since 0.9.33
 */
public class ServletUtil {

    private ServletUtil() {};

    /**
     *  @param ua User-Agent string, non-null
     *  @return true if a text-mode or mobile browser
     */
    public static boolean isSmallBrowser(String ua) {
        return isTextBrowser(ua) || isMobileBrowser(ua);
    }

    /**
     *  @param ua User-Agent string, non-null
     *  @return true if a text-mode browser
     */
    public static boolean isTextBrowser(String ua) {
        return
            ua.startsWith("Lynx") || ua.startsWith("w3m") ||
            ua.startsWith("ELinks") || ua.startsWith("Links") ||
            ua.startsWith("Dillo") || ua.startsWith("Emacs-w3m");
    }


    /**
     *  The intent here is to return true for phones but
     *  false for big tablets? But not consistent.
     *
     *  @param ua User-Agent string, non-null
     *  @return true if a mobile browser
     */
    public static boolean isMobileBrowser(String ua) {
        return
            // http://www.zytrax.com/tech/web/mobile_ids.html
            // Android tablet UAs don't have "Mobile" in them
            (ua.contains("Android") && ua.contains("Mobile")) ||
            ua.contains("BlackBerry") ||
            ua.contains("iPhone") ||
            ua.contains("iPod") || ua.contains("iPad") ||
            ua.contains("Kindle") || ua.contains("Mobile") ||
            ua.contains("Nintendo") ||
            ua.contains("Opera Mini") || ua.contains("Opera Mobi") ||
            ua.contains("Palm") ||
            ua.contains("PLAYSTATION") || ua.contains("Playstation") ||
            ua.contains("Profile/MIDP-") || ua.contains("SymbianOS") ||
            ua.contains("Windows CE") || ua.contains("Windows Phone") ||
            ua.startsWith("DoCoMo") ||
            ua.startsWith("Cricket") || ua.startsWith("HTC") ||
            ua.startsWith("J-PHONE") || ua.startsWith("KDDI-") ||
            ua.startsWith("LG-") || ua.startsWith("LGE-") ||
            ua.startsWith("Nokia") || ua.startsWith("OPWV-SDK") ||
            ua.startsWith("MOT-") || ua.startsWith("SAMSUNG-") ||
            ua.startsWith("nook") || ua.startsWith("SCH-") ||
            ua.startsWith("SEC-") || ua.startsWith("SonyEricsson") ||
            ua.startsWith("Vodafone");
    }

    /**
      * Truncate a String.
      * Same as s.substring(0, len) except that
      * it won't split a surrogate pair or at a ZWJ.
      *
      * @param s non-null
      * @param len greater than zero
      * @return s if shorter; s.substring(0, len) if
      *           the char at len-1 is not a high surrogate
      *           or the char at len-1 or len is not a zero-width joiner;
      *           s.substring(0, len+1 or len+2) if it is
      * @since 0.9.33
      */
    public static String truncate(String s, int len) {
        if (s.length() <= len)
            return s;
        char c = s.charAt(len - 1);
        // https://en.wikipedia.org/wiki/Zero-width_joiner
        if (Character.isHighSurrogate(c) || c == 0x200D)
            return s.substring(0, len + 1);
        if (s.charAt(len) == 0x200D)
            return s.substring(0, len + 2);
        return s.substring(0, len);
    }

    /**
      * Sanitize an encoded String from getQueryString().
      * Does not decode.
      *
      * @param s may be null
      * @return s or null if s was null or s contained any problematic characters
      * @since 0.9.70
      */
    public static String sanitizeQuery(String s) {
        if (s == null)
            return null;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            switch (s.charAt(i)) {
                case '\r':
                case '\n':
                case '"':
                case '\'':
                case '<':
                case '>':
                    return null;
            }
        }
        return s;
    }

    /**
     *  Validate Origin header for POST requests.
     *  Allows requests with matching Origin (same-origin), or no Origin header.
     *  Rejects cross-origin POST requests to prevent CSRF attacks.
     *
     *  Ref: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Origin
     *
     *  @param request the HTTP request
     *  @return true if allowed
     *  @since 0.9.70
     */
    public static boolean allowOrigin(Request request) {
        if (!request.getMethod().toUpperCase(Locale.US).equals("POST"))
            return true;
        return allowOrigin(request.getHeaders().get("Origin"), request.getHeaders().get("Host"), request.isSecure());
    }

    /**
     *  Validate Origin header for POST requests.
     *  Allows requests with matching Origin (same-origin), or no Origin header.
     *  Rejects cross-origin POST requests to prevent CSRF attacks.
     *
     *  Ref: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Origin
     *
     *  @param request the HTTP request
     *  @return true if allowed
     *  @since 0.9.70
     */
    public static boolean allowOrigin(HttpServletRequest request) {
        if (!request.getMethod().toUpperCase(Locale.US).equals("POST"))
            return true;
        return allowOrigin(request.getHeader("Origin"), request.getHeader("Host"), request.isSecure());
    }

    /**
     *  Validate Origin header for POST requests.
     *  Allows requests with matching Origin (same-origin), or no Origin header.
     *  Rejects cross-origin POST requests to prevent CSRF attacks.
     *
     *  Ref: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Origin
     *
     *  @return true if allowed
     *  @since 0.9.70 adapted from I2P+
     */
    private static boolean allowOrigin(String origin, String host, boolean isSecure) {
        if (origin == null || host == null)
            return true;
        origin = origin.toLowerCase(Locale.US);
        host = host.toLowerCase(Locale.US);

        // Origin format: "https://host:port" or "http://host:port"
        // Or "https://host" or "http://host"
        // Or "null"
        if (origin.equals("null"))
            return true;

        // Host format: "host" or "host:port"
        // normalize host
        StringBuilder buf = new StringBuilder(origin.length());
        buf.append(isSecure ? "https://" : "http://");
        buf.append(host);
        // shortcut
        if (origin.equals(buf.toString()))
            return true;
        if (host.contains("]:")) {
            // ipv6 has port
        } else if (host.contains("]")) {
            buf.append(':');
            buf.append(isSecure ? "443" : "80");
        } else if (host.contains(":")) {
            // ipv4 has port
        } else {
            buf.append(':');
            buf.append(isSecure ? "443" : "80");
        }

        // normalize origin
        if (origin.contains("]:")) {
            // ipv6 has port
        } else if (origin.contains("]")) {
            origin += ':';
            origin += (origin.startsWith("https:")) ? "443" : "80";
        } else if (origin.lastIndexOf(':') > 5) {
            // ipv4 has port
        } else {
            origin += ':';
            origin += (origin.startsWith("https:")) ? "443" : "80";
        }

        return origin.equals(buf.toString());
    }

/*
    public static void main(String[] args) {
        test("http://localhost", "localhost");
        test("https://localhost", "localhost");
        test("http://localhost:80", "localhost");
        test("https://localhost:443", "localhost");
        test("http://localhost:80", "localhost:80");
        test("https://localhost:443", "localhost:443");
        test("http://1.2.3.4", "localhost");
        test("https://1.2.3.4", "localhost");
        test("http://localhost", "localhost:80");
        test("https://localhost", "localhost:443");
        test("http://1.2.3.4:99", "localhost");
        test("https://1.2.3.4:99", "localhost");
        test("http://[1::2]", "localhost");
        test("http://[1::2]:99", "localhost");
        test("http://[1::2]", "[1::2]");
        test("http://[1::2]:99", "[1::2]");
        test("http://[1::2]", "[1::2]:80");
        test("http://[1::2]:99", "[1::2]:99");
        test("https://[1::2]", "[1::2]:443");
        test("https://[1::2]:99", "[1::2]:99");
    }

    private static void test(String o, String h) {
        boolean a = allowOrigin(o, h, false);
        System.out.println("Origin: " + o + " Host: " + h + " Secure? false Allow? " + a);
        a = allowOrigin(o, h, true);
        System.out.println("Origin: " + o + " Host: " + h + " Secure? true  Allow? " + a);
    }
*/
}
