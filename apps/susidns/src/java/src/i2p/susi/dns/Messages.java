package i2p.susi.dns;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

/**
 * Translate strings for this package.
 * @since 0.7.9
 */
public class Messages {
    static final String BUNDLE_NAME = "i2p.susi.dns.messages";
    private final I2PAppContext _context;

    public Messages() {
        _context = I2PAppContext.getGlobalContext();
    }

    /** lang in routerconsole.lang property, else current locale */
    public String _t(String key) {
        return Translate.getString(key, _context, BUNDLE_NAME);
    }

    /** @since 0.9.11 */
    public String _t(String key, Object o) {
        return Translate.getString(key, o, _context, BUNDLE_NAME);
    }

    public static String getString(String s) {
        return Translate.getString(s, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    public static String getString(String s, Object o) {
        return Translate.getString(s, o, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    public static String getString(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /** translate (ngettext) @since 0.8.7 */
    public static String getString(int n, String s, String p) {
        return Translate.getString(n, s, p, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }
}
