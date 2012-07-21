package net.i2p.router.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copied and modded from I2PTunnel IndexBean (GPL)
 * @author zzz
 */
public class CSSHelper extends HelperBase {

    private static final Map<String, Boolean> _UACache = new ConcurrentHashMap();

    public CSSHelper() {}

    public static final String THEME_CONFIG_FILE = "themes.config";
    public static final String PROP_THEME_NAME = "routerconsole.theme";
    public static final String DEFAULT_THEME = "light";
    public static final String BASE_THEME_PATH = "/themes/console/";
    private static final String FORCE = "classic";
    public static final String PROP_REFRESH = "routerconsole.summaryRefresh";
    public static final String DEFAULT_REFRESH = "60";
    public static final int MIN_REFRESH = 3;
    public static final String PROP_DISABLE_REFRESH = "routerconsole.summaryDisableRefresh";
    private static final String PROP_XFRAME = "routerconsole.disableXFrame";

    public String getTheme(String userAgent) {
        String url = BASE_THEME_PATH;
        if (userAgent != null && userAgent.contains("MSIE")) {
            url += FORCE + "/";
        } else {
            // This is the first thing to use _context on most pages
            if (_context == null)
                throw new IllegalStateException("No contexts. This is usually because the router is either starting up or shutting down.");
            String theme = _context.readConfigFile(THEME_CONFIG_FILE).getProperty(PROP_THEME_NAME, DEFAULT_THEME);
            url += theme + "/";
        }
        return url;
    }

    /** change default language for the router AND save it */
    public void setLang(String lang) {
        // Protected with nonce in css.jsi
        if (lang != null && lang.length() == 2 && !lang.equals(_context.getProperty(Messages.PROP_LANG))) {
            _context.router().saveConfig(Messages.PROP_LANG, lang);
        }
    }

    /** needed for conditional css loads for zh */
    public String getLang() {
        return Messages.getLanguage(_context);
    }

    /**
     *  Show / hide news on home page
     *  @param val if non-null, "1" to show, else hide
     *  @since 0.8.12
     */
    public void setNews(String val) {
        // Protected with nonce in css.jsi
        if (val != null)
            NewsFetcher.getInstance(_context).showNews(val.equals("1"));
    }

    /**
     *  Should we send X_Frame_Options=SAMEORIGIN
     *  Default true
     *  @since 0.9.1
     */
    public boolean shouldSendXFrame() {
        return !_context.getBooleanProperty(PROP_XFRAME);
    }

    /** change refresh and save it */
    public void setRefresh(String r) {
        try {
            if (Integer.parseInt(r) < MIN_REFRESH)
                r = "" + MIN_REFRESH;
        } catch (Exception e) {
        }
        _context.router().saveConfig(PROP_REFRESH, r);
    }

    /** @return refresh time in seconds, as a string */
    public String getRefresh() {
        String r = _context.getProperty(PROP_REFRESH, DEFAULT_REFRESH);
        try {
            if (Integer.parseInt(r) < MIN_REFRESH)
                r = "" + MIN_REFRESH;
        } catch (Exception e) {
        }
        return r;
    }

    /**
     * change disable refresh boolean and save it
     * @since 0.9.1
     */
    public void setDisableRefresh(String r) {
        String disableRefresh = "false";
        if ("0".equals(r))
            disableRefresh = "true";
        _context.router().saveConfig(PROP_DISABLE_REFRESH, disableRefresh);
    }

    /**
     * @return true if refresh is disabled
     * @since 0.9.1
     */
    public boolean getDisableRefresh() {
        return _context.getBooleanProperty(PROP_DISABLE_REFRESH);
    }

    /** translate the title and display consistently */
    public String title(String s) {
         StringBuilder buf = new StringBuilder(128);
         buf.append("<title>")
            .append(_("I2P Router Console"))
            .append(" - ")
            .append(_(s))
            .append("</title>");
         return buf.toString();
    }

    /**
     *  Should we allow a refreshing IFrame?
     *  @since 0.8.5
     */
    public boolean allowIFrame(String ua) {
        if (ua == null)
            return true;
        Boolean brv = _UACache.get(ua);
        if (brv != null)
            return brv.booleanValue();
        boolean rv = shouldAllowIFrame(ua);
        _UACache.put(ua, Boolean.valueOf(rv));
        return rv;
    }

    private static boolean shouldAllowIFrame(String ua) {
        return
                               // text
                             !(ua.startsWith("Lynx") || ua.startsWith("w3m") ||
                               ua.startsWith("ELinks") || ua.startsWith("Links") ||
                               ua.startsWith("Dillo") ||
                               // mobile
                               // http://www.zytrax.com/tech/web/mobile_ids.html
                               ua.contains("Android") || ua.contains("iPhone") ||
                               ua.contains("iPod") || ua.contains("iPad") ||
                               ua.contains("Kindle") || ua.contains("Mobile") ||
                               ua.contains("Nintendo Wii") || ua.contains("Opera Mini") ||
                               ua.contains("Palm") ||
                               ua.contains("PLAYSTATION") || ua.contains("Playstation") ||
                               ua.contains("Profile/MIDP-") || ua.contains("SymbianOS") ||
                               ua.contains("Windows CE") || ua.contains("Windows Phone") ||
                               ua.startsWith("BlackBerry") || ua.startsWith("DoCoMo") ||
                               ua.startsWith("Nokia") || ua.startsWith("OPWV-SDK") ||
                               ua.startsWith("MOT-") || ua.startsWith("SAMSUNG-") ||
                               ua.startsWith("nook") || ua.startsWith("SCH-") ||
                               ua.startsWith("SEC-") || ua.startsWith("SonyEricsson") ||
                               ua.startsWith("Vodafone"));
    }
}
