package net.i2p.router.web;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class ConfigUIHelper extends HelperBase {

    public String getSettings() {
        StringBuilder buf = new StringBuilder(512);
        buf.append("<div id=\"availablethemes\">");
        String current = _context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        Set<String> themes = themeSet();
        for (String theme : themes) {
            buf.append("<div class=\"themechoice\">")
               .append("<input type=\"radio\" class=\"optbox\" name=\"theme\" ");
            if (theme.equals(current))
                buf.append(CHECKED);
            buf.append("value=\"").append(theme).append("\">")
               .append("<object height=\"48\" width=\"48\" data=\"/themes/console/").append(theme).append("/images/thumbnail.png\">")
               .append("<img height=\"48\" width=\"48\" alt=\"\" src=\"/themes/console/images/thumbnail.png\">")
               .append("</object><br>")
               .append("<div class=\"themelabel\">").append(_t(theme)).append("</div>")
               .append("</div>\n");
        }
        boolean universalTheming = _context.getBooleanProperty(CSSHelper.PROP_UNIVERSAL_THEMING);
        buf.append("</div><div id=\"themeoptions\">");
        buf.append("<input type=\"checkbox\" name=\"universalTheming\" ");
        if (universalTheming)
            buf.append(CHECKED);
        buf.append("value=\"1\">")
           .append(_t("Set theme universally across all apps"))
           .append("<br>\n");
        return buf.toString();
    }

    public String getForceMobileConsole() {
        StringBuilder buf = new StringBuilder(256);
        boolean forceMobileConsole = _context.getBooleanProperty(CSSHelper.PROP_FORCE_MOBILE_CONSOLE);
        buf.append("<input type=\"checkbox\" name=\"forceMobileConsole\" ");
        if (forceMobileConsole)
            buf.append(CHECKED);
        buf.append("value=\"1\">")
           .append(_t("Force the mobile console to be used"))
           .append("</div>\n");
        return buf.toString();
    }

    static final String PROP_THEME_PFX = "routerconsole.theme.";

    /** @return standard and user-installed themes, sorted (untranslated) */
    private Set<String> themeSet() {
         Set<String> rv = new TreeSet<String>();
         // add a failsafe even if we can't find any themes
         rv.add(CSSHelper.DEFAULT_THEME);
         File dir = new File(_context.getBaseDir(), "docs/themes/console");
         File[] files = dir.listFiles();
         if (files == null)
             return rv;
         for (int i = 0; i < files.length; i++) {
             String name = files[i].getName();
             if (files[i].isDirectory() && ! name.equals("images"))
                 rv.add(name);
         }
         // user themes
         Set<String> props = _context.getPropertyNames();
         for (String prop : props) {
              if (prop.startsWith(PROP_THEME_PFX) && prop.length() > PROP_THEME_PFX.length())
                  rv.add(prop.substring(PROP_THEME_PFX.length()));
         }
         return rv;
    }

    /**
     *  Each language has the ISO code, the flag, the name, and the optional country name.
     *  Alphabetical by the ISO code please.
     *  See http://en.wikipedia.org/wiki/ISO_639-1 .
     *  Any language-specific flag added to the icon set must be
     *  added to the top-level build.xml for the updater.
     *  As of 0.9.12, ISO 639-2 three-letter codes are supported also.
     *  Note: To avoid truncation, ensure language name is no longer than 17 chars.
     */
    private static final String langs[][] = {
        //
        // Note: any additions, also add to:
        // apps/i2psnark/java/src/org/klomp/snark/standalone/ConfigUIHelper.java
        // apps/routerconsole/jsp/console.jsp
        // apps/routerconsole/jsp/home.jsp
        // .tx/config
        // New lang_xx flags: Add to top-level build.xml
        //
        { "ar", "lang_ar", _x("Arabic"), null },
        { "cs", "cz", _x("Czech"), null },
        { "da", "dk", _x("Danish"), null },
        { "de", "de", _x("German"), null },
        { "et", "ee", _x("Estonian"), null },
        { "el", "gr", _x("Greek"), null },
        { "en", "us", _x("English"), null },
        { "es", "es", _x("Spanish"), null },
        { "fi", "fi", _x("Finnish"), null },
        { "fr", "fr", _x("French"), null },
        { "gl", "lang_gl", _x("Galician"), null },
        { "hu", "hu", _x("Hungarian"), null },
        { "it", "it", _x("Italian"), null },
        { "ja", "jp", _x("Japanese"), null },
        { "ko", "kr", _x("Korean"), null },
        { "mg", "mg", _x("Malagasy"), null },
        { "nl", "nl", _x("Dutch"), null },
        { "nb", "no", _x("Norwegian Bokmaal"), null },
        { "pl", "pl", _x("Polish"), null },
        { "pt", "pt", _x("Portuguese"), null },
        { "pt_BR", "br", _x("Portuguese"), "Brazil" },
        { "ro", "ro", _x("Romanian"), null },
        { "ru", "ru", _x("Russian"), null },
        { "sk", "sk", _x("Slovak"), null },
        { "sv", "se", _x("Swedish"), null },
        { "tr", "tr", _x("Turkish"), null },
        { "uk", "ua", _x("Ukrainian"), null },
        { "vi", "vn", _x("Vietnamese"), null },
        { "zh", "cn", _x("Chinese"), null },
        { "zh_TW", "tw", _x("Chinese"), "Taiwan" },
        { "xx", "a1", "Untagged strings", null },
    };



    /** todo sort by translated string */
    public String getLangSettings() {
        String clang = Messages.getLanguage(_context);
        String current = clang;
        String country = Messages.getCountry(_context);
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
        for (int i = 0; i < langs.length; i++) {
            String lang = langs[i][0];
            if (lang.equals("xx") && !isAdvanced())
                continue;
            // we use "lang" so it is set automagically in CSSHelper
            buf.append("<div class=\"langselect\"><input type=\"radio\" class=\"optbox\" name=\"lang\" ");
            if (lang.equals(current))
                buf.append(CHECKED);
            buf.append("value=\"").append(lang).append("\">")
               .append("<img height=\"11\" width=\"16\" alt=\"\" src=\"/flags.jsp?c=").append(langs[i][1]).append("\">")
               .append("<div class=\"ui_lang\">");
            int under = lang.indexOf('_');
            String slang = (under > 0) ? lang.substring(0, under) : lang;
            buf.append(Messages.getDisplayLanguage(slang, langs[i][2], _context));
            String name = langs[i][3];
            if (name != null) {
                buf.append(" (")
                   .append(Messages.getString(name, _context, Messages.COUNTRY_BUNDLE_NAME))
                   .append(')');
            }
            buf.append("</div></div>\n");
        }
        return buf.toString();
    }

    /** @since 0.9.4 */
    public String getPasswordForm() {
        StringBuilder buf = new StringBuilder(512);
        ConsolePasswordManager mgr = new ConsolePasswordManager(_context);
        Map<String, String> userpw = mgr.getMD5(RouterConsoleRunner.PROP_CONSOLE_PW);
        buf.append("<table id=\"consolepass\">");
        if (userpw.isEmpty()) {
            buf.append("<tr><td colspan=\"3\">");
            buf.append(_t("Add a user and password to enable."));
            buf.append("</td></tr>");
        } else {
            buf.append("<tr><th>")
               .append(_t("Remove"))
               .append("</th><th>")
               .append(_t("Username"))
               .append("</th><th>&nbsp;</th></tr>\n");
            for (String name : userpw.keySet()) {
                buf.append("<tr><td align=\"center\"><input type=\"checkbox\" class=\"optbox\" name=\"delete_")
                   .append(name)
                   .append("\"></td><td colspan=\"2\">")
                   .append(name)
                   .append("</td></tr>\n");
            }
        }
        buf.append("<tr><td id=\"pw_adduser\" align=\"left\" colspan=\"3\"><b>")
           .append("<b>").append(_t("Username")).append(":</b> ")
           .append("<input type=\"text\" name=\"name\">")
           .append("<b>").append(_t("Password")).append(":</b> ")
           .append("<input type=\"password\" size=\"40\" name=\"nofilter_pw\">")
           .append("</td></tr>")
           .append("</table>\n");
        return buf.toString();
    }
}
