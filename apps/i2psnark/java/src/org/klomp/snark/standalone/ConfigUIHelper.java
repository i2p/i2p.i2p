package org.klomp.snark.standalone;

import java.util.Locale;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

/**
 * Standalone (app context) only.
 * Copied from ConfigUIHelper.
 * @since 0.9.27
 */
public class ConfigUIHelper {

    private static final String CHECKED = " selected=\"selected\" ";
    private static final String BUNDLE_NAME = "org.klomp.snark.web.messages";
    //private static final String COUNTRY_BUNDLE_NAME = "net.i2p.router.countries.messages";

    /**
     *  Each language has the ISO code, the flag, the name, and the optional country name.
     *  Alphabetical by the ISO code please.
     *  See http://en.wikipedia.org/wiki/ISO_639-1 .
     *  Any language-specific flag added to the icon set must be
     *  added to the top-level build.xml for the updater.
     *  As of 0.9.12, ISO 639-2 three-letter codes are supported also.
     *
     *  Country flag unused.
     */
    private static final String langs[][] = {
        { "ar", "lang_ar", "Arabic ﻉﺮﺒﻳﺓ", null },
        { "cs", "cz", "Čeština", null },
        { "zh", "cn", "Chinese 中文", null },
        //{ "zh_TW", "tw", "Chinese 中文", "Taiwan" },
        //{ "da", "dk", "Dansk", null },
        { "de", "de", "Deutsch", null },
        //{ "et", "ee", "Eesti", null },
        { "en", "us", "English", null },
        { "es", "es", "Español", null },
        { "fr", "fr", "Français", null },
        //{ "gl", "lang_gl", "Galego", null },
        //{ "el", "gr", "Greek Ελληνικά", null },
        { "in", "id", "bahasa Indonesia", null },
        { "it", "it", "Italiano", null },
        { "ja", "jp", "Japanese 日本語", null },
        { "ko", "kr", "Korean 한국어", null },
        //{ "mg", "mg", "Malagasy", null },
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
        { "uk", "ua", "Ukrainian Українська", null },
        { "vi", "vn", "Vietnamese Tiếng Việt", null },
        { "xx", "a1", "Debug: Find untagged strings", null },
    };

    /**
     * Standalone (app context) only.
     * Copied from ConfigUIHelper.
     * @return HTML
     * @since 0.9.27
     */
    public static String getLangSettings(I2PAppContext ctx) {
        String clang = Translate.getLanguage(ctx);
        String current = clang;
        String country = Translate.getCountry(ctx);
        if (country != null && country.length() > 0)
            current += '_' + country;
        // find best match
        boolean found = false;
        for (int i = 0; i < langs.length; i++) {
            if (langs[i][0].equals(current)) {
                found = true;
                break;
            }
        }
        if (!found) {
            if (country != null && country.length() > 0) {
                current = clang;
                for (int i = 0; i < langs.length; i++) {
                    if (langs[i][0].equals(current)) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found)
                current = "en";
        }
        StringBuilder buf = new StringBuilder(512);
        buf.append("<select name=\"lang\">\n");
        for (int i = 0; i < langs.length; i++) {
            String lang = langs[i][0];
            if (lang.equals("xx") && !isAdvanced())
                continue;
            buf.append("<option ");
            if (lang.equals(current))
                buf.append(CHECKED);
            buf.append("value=\"").append(lang).append("\">");
            int under = lang.indexOf('_');
            String slang = (under > 0) ? lang.substring(0, under) : lang;
            buf.append(langs[i][2]);
            String name = langs[i][3];
            if (name != null) {
                buf.append(" (")
                   .append(name)
                   .append(')');
            }
            buf.append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }

    /** if necessary */
    private static boolean isAdvanced() { return false; }
}
