package net.i2p.router.web;

import java.io.IOException;

/**
 * Render the configuration menu at the top of all the config pages.
 * refactored from confignav.jsp to reduce size and make translation easier
 * @author zzz
 */
public class ConfigNavHelper extends HelperBase {

    /** configX.jsp */
    private static final String pages[] =
                                          {"", "net", "ui", "home", "service", "update", "tunnels",
                                           "clients", "peer", "keyring", "logging", "stats",
                                           "reseed", "advanced" };

    private static final String titles[] =
                                          {_x("Bandwidth"), _x("Network"), _x("UI"), _x("Home Page"),
                                           _x("Service"), _x("Update"), _x("Tunnels"),
                                           _x("Clients"), _x("Peers"), _x("Keyring"), _x("Logging"), _x("Stats"),
                                           _x("Reseeding"), _x("Advanced") };

    /**
     *  @param graphical false for text-mode browsers
     */
    public void renderNavBar(String requestURI, boolean graphical) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        // TODO fix up the non-light themes
        String theme = _context.getProperty(CSSHelper.PROP_THEME_NAME);
        boolean span = graphical && (theme == null || theme.equals(CSSHelper.DEFAULT_THEME));
        if (!span)
            buf.append("<center>");
        for (int i = 0; i < pages.length; i++) {
            String page = "config" + pages[i];
            if (requestURI.endsWith(page) || requestURI.endsWith(page + ".jsp")) {
                // we are there
                if (span)
                    buf.append("<span class=\"tab2\">");
                buf.append(_(titles[i]));
            } else {
                // we are not there, make a link
                if (span)
                    buf.append("<span class=\"tab\">");
                buf.append("<a href=\"").append(page).append("\">").append(_(titles[i])).append("</a>");
            }
            if (span)
                buf.append(" </span>\n");
            else if (i != pages.length - 1)
                buf.append(" |\n");
        }
        if (!span)
            buf.append("</center>");
        _out.write(buf.toString());
    }
}
