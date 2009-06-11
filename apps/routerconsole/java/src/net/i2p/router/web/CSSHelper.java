package net.i2p.router.web;

/**
 * Copied and modded from I2PTunnel IndexBean (GPL)
 * @author zzz
 */
public class CSSHelper extends HelperBase {
    public CSSHelper() {}
    
    public static final String PROP_THEME_NAME = "routerconsole.theme";
    private static final String BASE = "/themes/console/";

    public String getTheme() {
        String url = BASE;
        String theme = _context.getProperty(PROP_THEME_NAME);
        if (theme != null)
            url += theme + "/";
        return url;
    }
}
