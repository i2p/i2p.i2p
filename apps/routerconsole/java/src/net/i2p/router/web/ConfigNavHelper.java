package net.i2p.router.web;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Render the configuration menu at the top of all the config pages.
 * refactored from confignav.jsp to reduce size and make translation easier
 * @author zzz
 */
public class ConfigNavHelper extends HelperBase {

    /** configX.jsp */
    private static final String pages[] =
                                          {"", "net", "ui", "sidebar", "home", "service", "update", "tunnels",
                                           "clients", "peer", "keyring", "logging", "stats",
                                           "reseed", "advanced", "family" };

    private static final String titles[] =
                                          {_x("Bandwidth"), _x("Network"), _x("UI"), _x("Summary Bar"), _x("Home Page"),
                                           _x("Service"), _x("Update"), _x("Tunnels"),
                                           _x("Clients"), _x("Peers"), _x("Keyring"), _x("Logging"), _x("Stats"),
                                           _x("Reseeding"), _x("Advanced"), _x("Router Family") };

    /** @since 0.9.19 */
    private static class Tab {
        public final String page, title;
        public Tab(String p, String t) {
            page = p; title = t;
        }
    }

    /** @since 0.9.19 */
    private class TabComparator implements Comparator<Tab> {
         private static final long serialVersionUID = 1L;
         private final Collator coll;

         public TabComparator() {
             super();
             coll = Collator.getInstance(new Locale(Messages.getLanguage(_context)));
         }

         public int compare(Tab l, Tab r) {
             return coll.compare(l.title, r.title);
        }
    }

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
        List<Tab> tabs = new ArrayList<Tab>(pages.length);
        for (int i = 0; i < pages.length; i++) {
            tabs.add(new Tab(pages[i], _t(titles[i])));
        }
        Collections.sort(tabs, new TabComparator());
        for (int i = 0; i < tabs.size(); i++) {
            String page = "config" + tabs.get(i).page;
            if (requestURI.endsWith(page) || requestURI.endsWith(page + ".jsp")) {
                // we are there
                if (span)
                    buf.append("<span class=\"tab2\">");
                buf.append(tabs.get(i).title);
            } else {
                // we are not there, make a link
                if (span)
                    buf.append("<span class=\"tab\">");
                buf.append("<a href=\"").append(page).append("\">").append(tabs.get(i).title).append("</a>");
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
