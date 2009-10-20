package net.i2p.router.web;

/**
 * Copied and modded from I2PTunnel IndexBean (GPL)
 * @author zzz
 */
public class CSSHelper extends HelperBase {
    public CSSHelper() {}
    
    public static final String PROP_THEME_NAME = "routerconsole.theme";
    public static final String DEFAULT_THEME = "light";
    private static final String BASE = "/themes/console/";
    private static final String FORCE = "classic";

    public String getTheme(String userAgent) {
        String url = BASE;
        if (userAgent != null && userAgent.contains("MSIE")) {
            url += FORCE + "/";
        } else {
            String theme = _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);
            url += theme + "/";
        }
        return url;
    }

    /** change default language for the router but don't save it */
    public void setLang(String lang) {
        if (lang != null && lang.length() > 0)
            _context.router().setConfigSetting(Messages.PROP_LANG, lang);
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
