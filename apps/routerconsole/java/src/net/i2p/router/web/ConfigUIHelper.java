package net.i2p.router.web;

public class ConfigUIHelper extends HelperBase {
    public ConfigUIHelper() {}
    
    public static final String PROP_THEME = "routerconsole.theme";
    private static final String themes[] = {"default", "classic", "dark", "defCon1", "light"};

    public String getSettings() {
        StringBuilder buf = new StringBuilder(512);
        String current = _context.getProperty(PROP_THEME, "default");
        for (String theme : themes) {
            buf.append("<input type=\"radio\" name=\"theme\" ");
            if (theme.equals(current))
                buf.append("checked=\"true\" ");
            buf.append("value=\"").append(theme).append("\"/>").append(theme).append("<br />\n");
        }
        return buf.toString();
    }
}
