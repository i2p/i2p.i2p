package net.i2p.router.web;

import java.io.File;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.Set;

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

    static final String PROP_THEME_PFX = "routerconsole.theme.";

    /** @return standard and user-installed themes, sorted (untranslated) */
    private Set<String> themeSet() {
         Set<String> rv = new TreeSet();
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
         Set props = _context.getPropertyNames();
         for (Iterator iter = props.iterator(); iter.hasNext(); ) {
              String prop = (String) iter.next();
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
    private static final String langs[] = {"ar", "cs", "da", "de", "ee", "el", "en", "es", "fi",
                                           "fr", "hu", "it", "nl", "pl", "pt", "ru",
                                           "sv", "uk", "vi", "zh"};
    private static final String flags[] = {"lang_ar", "cz", "dk", "de", "ee", "gr", "us", "es", "fi",
                                           "fr", "hu", "it", "nl", "pl", "pt", "ru",
                                           "se", "ua", "vn", "cn"};
    private static final String xlangs[] = {_x("Arabic"), _x("Czech"), _x("Danish"),
                                            _x("German"), _x("Estonian"), _x("Greek"), _x("English"), _x("Spanish"), _x("Finnish"),
                                            _x("French"), _x("Hungarian"), _x("Italian"), _x("Dutch"), _x("Polish"),
                                            _x("Portuguese"), _x("Russian"), _x("Swedish"),
                                            _x("Ukrainian"), _x("Vietnamese"), _x("Chinese")};

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
               .append(_(xlangs[i])).append("<br>\n");
        }
        return buf.toString();
    }
}
