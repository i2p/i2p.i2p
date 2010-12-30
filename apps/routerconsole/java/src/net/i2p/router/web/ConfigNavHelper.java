package net.i2p.router.web;

import java.io.IOException;

/**
 * refactored from confignav.jsp to reduce size and make translation easier
 * @author zzz
 */
public class ConfigNavHelper extends HelperBase {

    /** configX.jsp */
    private static final String pages[] =
                                          {"", "ui", "service", "update", "tunnels",
                                           "clients", "peer", "keyring", "logging", "stats",
                                           "reseed", "advanced" };

    private static final String titles[] =
                                          {_x("Network"), _x("UI"), _x("Service"), _x("Update"), _x("Tunnels"),
                                           _x("Clients"), _x("Peers"), _x("Keyring"), _x("Logging"), _x("Stats"),
                                           _x("Reseeding"), _x("Advanced") };

    public void renderNavBar(String requestURI) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        for (int i = 0; i < pages.length; i++) {
            String page = "config" + pages[i];
            if (requestURI.endsWith(page) || requestURI.endsWith(page + ".jsp")) {
                // we are there
                buf.append(_(titles[i]));
            } else {
                // we are not there, make a link
                buf.append("<a href=\"").append(page).append("\">").append(_(titles[i])).append("</a>");
            }
            if (i != pages.length - 1)
                buf.append(" |\n");
        }
        _out.write(buf.toString());
    }
}
