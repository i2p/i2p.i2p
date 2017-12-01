package net.i2p.router.web;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

/**
 * Translate strings for this package.
 */
public class Messages extends Translate {
    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /** @since public since 0.9.33, was package private */
    public static final String COUNTRY_BUNDLE_NAME = "net.i2p.router.countries.messages";

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
     *    Single quotes must be doubled, i.e. ' -&gt; '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
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

    /**
     *  Return the "display language", e.g. "English" for the language specified
     *  by langCode, using the current language.
     *  Uses translation if available, then JVM Locale.getDisplayLanguage() if available, else default param.
     *
     *  @param langCode two-letter lower-case
     *  @param dflt e.g. "English"
     *  @since 0.9.5
     */
    public static String getDisplayLanguage(String langCode, String dflt, I2PAppContext ctx) {
        return Translate.getDisplayLanguage(langCode, dflt, ctx, BUNDLE_NAME);
    }
}
