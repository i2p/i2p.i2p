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
}
