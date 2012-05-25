package net.i2p.router.web;

public class ConfigUIHelper extends HelperBase {
    public ConfigUIHelper() {}
    
    private static final String themes[] = {"classic", "dark", "light"};

    public String getSettings() {
        StringBuilder buf = new StringBuilder(512);
        String current = _context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        for (String theme : themes) {
            buf.append("<input type=\"radio\" class=\"optbox\" name=\"theme\" ");
            if (theme.equals(current))
                buf.append("checked=\"true\" ");
            buf.append("value=\"").append(theme).append("\"/>").append(theme).append("<br />\n");
        }
        return buf.toString();
    }
}
