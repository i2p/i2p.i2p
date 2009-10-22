package net.i2p.router.web;

public class ConfigUIHelper extends HelperBase {
    public ConfigUIHelper() {}
    
    private static final String themes[] = {_x("classic"), _x("dark"), _x("light")};

    public String getSettings() {
        StringBuilder buf = new StringBuilder(512);
        String current = _context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        for (String theme : themes) {
            buf.append("<input type=\"radio\" class=\"optbox\" name=\"theme\" ");
            if (theme.equals(current))
                buf.append("checked=\"true\" ");
            buf.append("value=\"").append(theme).append("\">").append(_(theme)).append("<br>\n");
        }
        return buf.toString();
    }

    private static final String langs[] = {"de", "en", "fr", "nl", "se", "zh"};
    private static final String xlangs[] = {_x("German"), _x("English"), _x("French"),
                                            _x("Dutch"), _x("Swedish"), _x("Chinese")};

    public String getLangSettings() {
        StringBuilder buf = new StringBuilder(512);
        String current = Messages.getLanguage(_context);
        for (int i = 0; i < langs.length; i++) {
            // we use "lang" so it is set automagically in CSSHelper
            buf.append("<input type=\"radio\" class=\"optbox\" name=\"lang\" ");
            if (langs[i].equals(current))
                buf.append("checked=\"true\" ");
            buf.append("value=\"").append(langs[i]).append("\">").append(_(xlangs[i])).append("<br>\n");
        }
        return buf.toString();
    }
}
