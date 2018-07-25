package net.i2p.servlet.util;

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
}
