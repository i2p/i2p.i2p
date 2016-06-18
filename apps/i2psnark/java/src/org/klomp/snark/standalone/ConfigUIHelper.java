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
     *  Note: we don't currently _x the language strings,
     *  we'll just rely on the JVM's translations for now.
     *  Country flag unused.
     */
    private static final String langs[][] = {
        { "ar", "lang_ar", "Arabic", null },
        { "cs", "cz", "Czech", null },
        //{ "da", "dk", "Danish", null },
        { "de", "de", "German", null },
        //{ "et", "ee", "Estonian", null },
        //{ "el", "gr", "Greek", null },
        { "en", "us", "English", null },
        { "es", "es", "Spanish", null },
        { "fi", "fi", "Finnish", null },
        { "fr", "fr", "French", null },
        { "hu", "hu", "Hungarian", null },
        { "it", "it", "Italian", null },
        { "ja", "jp", "Japanese", null },
        //{ "mg", "mg", "Malagasy", null },
        { "nl", "nl", "Dutch", null },
        { "nb", "no", "Norwegian Bokmaal", null },
        { "pl", "pl", "Polish", null },
        { "pt", "pt", "Portuguese", null },
        { "pt_BR", "br", "Portuguese", "Brazil" },
        { "ro", "ro", "Romanian", null },
        { "ru", "ru", "Russian", null },
        { "sk", "sk", "Slovak", null },
        { "sv", "se", "Swedish", null },
        { "tr", "tr", "Turkish", null },
        { "uk", "ua", "Ukrainian", null },
        { "vi", "vn", "Vietnamese", null },
        { "zh", "cn", "Chinese", null },
        //{ "zh_TW", "tw", "Chinese", "Taiwan" },
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
            // we don't actually have translations for these, see above
            buf.append(Translate.getDisplayLanguage(slang, langs[i][2], ctx, BUNDLE_NAME));
            String name = langs[i][3];
            if (name != null) {
                String cou = (under > 0) ? lang.substring(under + 1) : lang;
                Locale cur = new Locale(current);
                Locale loc = new Locale(slang, cou);
                buf.append(" (")
                   //.append(Translate.getString(name, ctx, COUNTRY_BUNDLE_NAME))
                   .append(loc.getDisplayCountry(cur))
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
