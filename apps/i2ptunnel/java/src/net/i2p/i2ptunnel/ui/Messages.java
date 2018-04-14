package net.i2p.i2ptunnel.ui;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

/**
 * Translate strings for this package.
 * This is for the strings in the UI. Bundles are in the war.
 * Note that there are separate bundles for the proxy error messages
 * in the jar, which are not accessed by this class.
 * @since 0.7.9
 */
public class Messages {
    private static final String BUNDLE_NAME = "net.i2p.i2ptunnel.web.messages";
    private final I2PAppContext _context;

    public Messages() {
        _context = I2PAppContext.getGlobalContext();
    }

    /** lang in routerconsole.lang property, else current locale */
    public String _t(String key) {
        return Translate.getString(key, _context, BUNDLE_NAME);
    }

    public static String _t(String key, I2PAppContext ctx) {
        return Translate.getString(key, ctx, BUNDLE_NAME);
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than getString(s, ctx), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -&gt; '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    public String _t(String s, Object o) {
        return Translate.getString(s, o, _context, BUNDLE_NAME);
    }

    /** @since 0.9.26 */
    public String _t(String s, Object o1, Object o2) {
        return Translate.getString(s, o1, o2, _context, BUNDLE_NAME);
    }

    /** translate (ngettext)
     *  @since 0.9.7
     */
    public static String ngettext(String s, String p, int n, I2PAppContext ctx) {
        return Translate.getString(n, s, p, ctx, BUNDLE_NAME);
    }
}
