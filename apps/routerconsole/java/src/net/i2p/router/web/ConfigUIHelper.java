package net.i2p.router.web;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class ConfigUIHelper extends HelperBase {

    public String getSettings() {
        StringBuilder buf = new StringBuilder(512);
        String current = _context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        Set<String> themes = themeSet();
        for (String theme : themes) {
            buf.append("<input type=\"radio\" class=\"optbox\" name=\"theme\" ");
            if (theme.equals(current))
                buf.append("checked=\"checked\" ");
            buf.append("value=\"").append(theme).append("\">").append(_(theme)).append("<br>\n");
        }
        boolean universalTheming = _context.getBooleanProperty(CSSHelper.PROP_UNIVERSAL_THEMING);
        buf.append("<input type=\"checkbox\" name=\"universalTheming\" ");
        if (universalTheming)
            buf.append("checked=\"checked\" ");
        buf.append("value=\"1\">")
           .append(_("Set theme universally across all apps"))
           .append("<br>\n");
        return buf.toString();
    }

    public String getForceMobileConsole() {
        StringBuilder buf = new StringBuilder(256);
        boolean forceMobileConsole = _context.getBooleanProperty(CSSHelper.PROP_FORCE_MOBILE_CONSOLE);
        buf.append("<input type=\"checkbox\" name=\"forceMobileConsole\" ");
        if (forceMobileConsole)
            buf.append("checked=\"checked\" ");
        buf.append("value=\"1\">")
           .append(_("Force the mobile console to be used"))
           .append("<br>\n");
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
         for (Iterator<String> iter = props.iterator(); iter.hasNext(); ) {
              String prop = iter.next();
              if (prop.startsWith(PROP_THEME_PFX) && prop.length() > PROP_THEME_PFX.length())
                  rv.add(prop.substring(PROP_THEME_PFX.length()));
         }
         return rv;
    }

    /**
     *  Each language has the ISO code, the flag, and the name.
     *  Alphabetical by the ISO code please.
     *  See http://en.wikipedia.org/wiki/ISO_639-1 .
     *  Any language-specific flag added to the icon set must be
     *  added to the top-level build.xml for the updater.
     */
    private static final String langs[] = {"ar", "cs", "da", "de", "et", "el", "en", "es", "fi",
                                           "fr", "hu", "it", "ja", "nb", "nl", "pl", "pt", "ro", "ru",
                                           "sv", "tr", "uk", "vi", "zh"};
    private static final String flags[] = {"lang_ar", "cz", "dk", "de", "ee", "gr", "us", "es", "fi",
                                           "fr", "hu", "it", "jp", "nl", "no", "pl", "pt", "ro", "ru",
                                           "se", "tr", "ua", "vn", "cn"};
    private static final String xlangs[] = {_x("Arabic"), _x("Czech"), _x("Danish"),
                                            _x("German"), _x("Estonian"), _x("Greek"), _x("English"), _x("Spanish"), _x("Finnish"),
                                            _x("French"), _x("Hungarian"), _x("Italian"), _x("Japanese"), _x("Dutch"), _x("Norwegian Bokmaal"), _x("Polish"),
                                            _x("Portuguese"), _x("Romanian"), _x("Russian"), _x("Swedish"),
                                            _x("Turkish"), _x("Ukrainian"), _x("Vietnamese"), _x("Chinese")};

    /** todo sort by translated string */
    public String getLangSettings() {
        StringBuilder buf = new StringBuilder(512);
        String current = Messages.getLanguage(_context);
        for (int i = 0; i < langs.length; i++) {
            // we use "lang" so it is set automagically in CSSHelper
            buf.append("<input type=\"radio\" class=\"optbox\" name=\"lang\" ");
            if (langs[i].equals(current))
                buf.append("checked=\"checked\" ");
            buf.append("value=\"").append(langs[i]).append("\">")
               .append("<img height=\"11\" width=\"16\" alt=\"\" src=\"/flags.jsp?c=").append(flags[i]).append("\"> ")
               .append(Messages.getDisplayLanguage(langs[i], xlangs[i], _context)).append("<br>\n");
        }
        return buf.toString();
    }

    /** @since 0.9.4 */
    public String getPasswordForm() {
        StringBuilder buf = new StringBuilder(512);
        ConsolePasswordManager mgr = new ConsolePasswordManager(_context);
        Map<String, String> userpw = mgr.getMD5(RouterConsoleRunner.PROP_CONSOLE_PW);
        buf.append("<table>");
        if (userpw.isEmpty()) {
            buf.append("<tr><td colspan=\"3\">");
            buf.append(_("Add a user and password to enable."));
            buf.append("</td></tr>");
        } else {
            buf.append("<tr><th>")
               .append(_("Remove"))
               .append("</th><th>")
               .append(_("User Name"))
               .append("</th><th>&nbsp;</th></tr>\n");
            for (String name : userpw.keySet()) {
                buf.append("<tr><td align=\"center\"><input type=\"checkbox\" class=\"optbox\" name=\"delete_")
                   .append(name)
                   .append("\"></td><td colspan=\"2\">")
                   .append(name)
                   .append("</td></tr>\n");
            }
        }
        buf.append("<tr><td align=\"center\"><b>")
           .append(_("Add")).append(":</b>" +
                   "</td><td align=\"left\"><input type=\"text\" name=\"name\">" +
                   "</td><td align=\"left\"><b>");
        buf.append(_("Password")).append(":</b> " +
                   "<input type=\"password\" size=\"40\" name=\"pw\"></td></tr>" +
                   "</table>\n");
        return buf.toString();
    }
}
