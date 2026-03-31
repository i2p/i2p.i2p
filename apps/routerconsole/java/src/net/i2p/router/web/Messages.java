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

    /**
     *  Each language has the ISO code, the flag, the name, and the optional country name.
     *  Alphabetical by the ISO code please.
     *  See http://en.wikipedia.org/wiki/ISO_639-1 .
     *  Any language-specific flag added to the icon set must be
     *  added to the top-level build.xml for the updater.
     *  As of 0.9.12, ISO 639-2 three-letter codes are supported also.
     *  Note: To avoid truncation, ensure language name is no longer than 17 chars.
     *
     *  @since 0.9.69 moved from ConfigUIHelper for TranslationStatus only, not for external use
     */
    public static final String LANGS[][] = {
        //
        // Note: any additions, also add to:
        // apps/i2psnark/java/src/org/klomp/snark/standalone/ConfigUIHelper.java
        // .tx/config
        // New lang_xx flags: Add to top-level build.xml
        // Names must be 18 chars or less (including country if specified)
        //
        // NOTE: flag field now unused, flags are not displayed
        //
        { "en", "us", "English", null },
        { "ar", "lang_ar", "Arabic عربية", null },
        { "az", "az", "Azerbaijani", null },
        { "cs", "cz", "Čeština", null },
        { "zh", "cn", "Chinese 中文", null },
        { "zh_TW", "tw", "Chinese 中文", "Taiwan" },
        { "gan", "cn", "Gan Chinese 赣语", null },
        { "da", "dk", "Dansk", null },
        { "de", "de", "Deutsch", null },
        { "et", "ee", "Eesti", null },
        { "es", "es", "Español", null },
        { "es_AR", "ar", "Español" ,"Argentina" },
        { "fa", "ir", "Persian فارسی", null },
        { "fr", "fr", "Français", null },
        { "gl", "lang_gl", "Galego", null },
        { "el", "gr", "Greek Ελληνικά", null },
        { "hi", "in", "Hindi हिन्दी", null },
        { "is", "is", "Icelandic", null },
        { "in", "id", "bahasa Indonesia", null },
        { "it", "it", "Italiano", null },
        { "ja", "jp", "Japanese 日本語", null },
        { "ko", "kr", "Korean 한국어", null },
        { "ku", "iq", "Kurdish", null },
        { "mg", "mg", "Malagasy", null },
        { "hu", "hu", "Magyar", null },
        { "nl", "nl", "Nederlands", null },
        { "nb", "no", "Norsk (bokmål)", null },
        { "pl", "pl", "Polski", null },
        { "pt", "pt", "Português", null },
        { "pt_BR", "br", "Português", "Brazil" },
        { "ro", "ro", "Română", null },
        { "ru", "ru", "Russian Русский", null },
        { "sk", "sk", "Slovenčina", null },
        { "fi", "fi", "Suomi", null },
        { "sv", "se", "Svenska", null },
        { "tr", "tr", "Türkçe", null },
        { "uk", "ua", "Ukraine Українська", null },
        { "vi", "vn", "Vietnam Tiếng Việt", null },
        { "xx", "a1", "Untagged strings", null },
    };

}
