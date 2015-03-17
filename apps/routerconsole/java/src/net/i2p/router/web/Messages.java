package net.i2p.router.web;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

/**
 * Translate strings for this package.
 */
public class Messages extends Translate {
    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /** lang in routerconsole.lang property, else current locale */
    public static String getString(String key, I2PAppContext ctx) {
        return Translate.getString(key, ctx, BUNDLE_NAME);
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than getString(s, ctx), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To tranlslate parameter also, use _("foo {0} bar", _("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    public static String getString(String s, Object o, I2PAppContext ctx) {
        return Translate.getString(s, o, ctx, BUNDLE_NAME);
    }

    /** two params @since 0.7.14 */
    public static String getString(String s, Object o, Object o2, I2PAppContext ctx) {
        return Translate.getString(s, o, o2, ctx, BUNDLE_NAME);
    }

    /** translate (ngettext) @since 0.7.14 */
    public static String getString(int n, String s, String p, I2PAppContext ctx) {
        return Translate.getString(n, s, p, ctx, BUNDLE_NAME);
    }
}
