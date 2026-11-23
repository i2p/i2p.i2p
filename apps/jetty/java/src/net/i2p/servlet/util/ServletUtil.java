package net.i2p.servlet.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.http.HttpVersion;

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
     *  Validate Origin for methods other than GET, HEAD, OPTIONS and TRACE.
     *  Allows requests with matching Origin (same-origin), or no Origin header.
     *  Rejects opaque, malformed, multiple and cross-origin values.
     *
     *  Ref: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Origin
     *
     *  @param request the HTTP request
     *  @return true if allowed
     *  @since 0.9.70
     */
    public static boolean allowOrigin(Request request) {
        if (isSafeMethod(request.getMethod()))
            return true;
        if ("cross-site".equals(request.getHeaders().get("Sec-Fetch-Site")))
            return false;
        List<String> origins = request.getHeaders().getValuesList("Origin");
        if (origins.isEmpty())
            return true;
        String authority = request.getHttpURI().getAuthority();
        return origins.size() == 1 && allowOrigin(origins.get(0),
               authority != null ? authority : request.getHeaders().get("Host"), request.isSecure());
    }

    /**
     *  Validate Origin for methods other than GET, HEAD, OPTIONS and TRACE.
     *  Allows requests with matching Origin (same-origin), or no Origin header.
     *  Rejects opaque, malformed, multiple and cross-origin values.
     *
     *  Ref: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Origin
     *
     *  @param request the HTTP request
     *  @return true if allowed
     *  @since 0.9.70
     */
    public static boolean allowOrigin(HttpServletRequest request) {
        if (isSafeMethod(request.getMethod()))
            return true;
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site")))
            return false;
        Enumeration<String> origins = request.getHeaders("Origin");
        if (origins == null || !origins.hasMoreElements())
            return true;
        String origin = origins.nextElement();
        return !origins.hasMoreElements() &&
               allowOrigin(origin, servletAuthority(request), request.isSecure());
    }

    private static String servletAuthority(HttpServletRequest request) {
        String protocol = request.getProtocol();
        if (!HttpVersion.HTTP_2.asString().equals(protocol) && !HttpVersion.HTTP_3.asString().equals(protocol))
            return request.getHeader("Host");
        // HTTP/2 and HTTP/3 carry :authority; a Host field is optional.
        String name = request.getServerName();
        if (name == null) return null;
        if (name.indexOf(':') >= 0 && !name.startsWith("[")) name = '[' + name + ']';
        return name + ':' + request.getServerPort();
    }

    private static boolean isSafeMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) ||
               "OPTIONS".equals(method) || "TRACE".equals(method);
    }

    /**
     *  Compare parsed scheme, host and effective port, without DNS lookups.
     *  An Origin is an origin only, without credentials, path, query or fragment.
     *
     *  Ref: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Origin
     *
     *  @return true if allowed
     *  @since 0.9.70 adapted from I2P+
     */
    private static boolean allowOrigin(String origin, String host, boolean isSecure) {
        if (origin == null || host == null)
            return false;
        try {
            URI source = new URI(origin);
            URI target = new URI((isSecure ? "https://" : "http://") + host);
            return isOrigin(source) && isOrigin(target) &&
                   source.getScheme().equalsIgnoreCase(target.getScheme()) &&
                   source.getHost().equalsIgnoreCase(target.getHost()) &&
                   originPort(source) == originPort(target);
        } catch (URISyntaxException invalid) {
            return false;
        }
    }

    private static boolean isOrigin(URI uri) {
        int port = uri.getPort();
        return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) &&
               uri.getHost() != null && uri.getRawUserInfo() == null &&
               "".equals(uri.getRawPath()) && uri.getRawQuery() == null && uri.getRawFragment() == null &&
               !uri.getRawAuthority().endsWith(":") &&
               (port == -1 || (port > 0 && port <= 65535));
    }

    private static int originPort(URI uri) {
        int port = uri.getPort();
        return port >= 0 ? port : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }
}
