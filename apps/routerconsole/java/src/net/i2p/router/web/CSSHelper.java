package net.i2p.router.web;

/**
 * Copied and modded from I2PTunnel IndexBean (GPL)
 * @author zzz
 */
public class CSSHelper extends HelperBase {
    public CSSHelper() {}
    
    public static final String PROP_THEME_NAME = "routerconsole.theme";
    public static final String DEFAULT_THEME = "light";
    public static final String BASE_THEME_PATH = "/themes/console/";
    private static final String FORCE = "classic";
    public static final String PROP_REFRESH = "routerconsole.summaryRefresh";
    public static final String DEFAULT_REFRESH = "60";

    public String getTheme(String userAgent) {
        String url = BASE_THEME_PATH;
        if (userAgent != null && userAgent.contains("MSIE")) {
            url += FORCE + "/";
        } else {
            // This is the first thing to use _context on most pages
            if (_context == null)
                throw new IllegalStateException("No contexts. This is usually because the router is either starting up or shutting down.");
            String theme = _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);
            url += theme + "/";
        }
        return url;
    }

    /** change default language for the router but don't save it */
    public void setLang(String lang) {
        // TODO: Protect with nonce or require POST
        if (lang != null && lang.length() == 2)
            _context.router().setConfigSetting(Messages.PROP_LANG, lang);
    }

    /** needed for conditional css loads for zh */
    public String getLang() {
        return Messages.getLanguage(_context);
    }

    /** change refresh and save it */
    public void setRefresh(String r) {
        _context.router().setConfigSetting(PROP_REFRESH, r);
        _context.router().saveConfig();
    }

    /** @return refresh time in seconds, as a string */
    public String getRefresh() {
        return _context.getProperty(PROP_REFRESH, DEFAULT_REFRESH);
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
}
