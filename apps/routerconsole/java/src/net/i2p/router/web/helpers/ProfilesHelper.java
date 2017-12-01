package net.i2p.router.web.helpers;

import java.io.IOException;

import net.i2p.router.web.HelperBase;


public class ProfilesHelper extends HelperBase {
    private int _full;
    private boolean _graphical;

    private static final String titles[] =
                                          {_x("High Capacity"),                 // 0
                                           _x("Floodfill"),                     // 1
                                           _x("Banned"),                        // 2
                                           _x("All"),      };                   // 3

    private static final String links[] =
                                          {"",                                  // 0
                                           "?f=2",                              // 1
                                           "?f=3",                              // 2
                                           "?f=1"          };                   // 3

    public void setFull(String f) {
        if (f != null) {
            try {
                _full = Integer.parseInt(f);
                if (_full < 0 || _full > 3)
                    _full = 0;
            } catch (NumberFormatException nfe) {}
        }
    }

    /**
     *  call for non-text-mode browsers
     *  @since 0.9.1
     */
    public void allowGraphical() {
        _graphical = true;
    }

    /**
     *  @return empty string, writes directly to _out
     *  @since 0.9.1
     */
    public String getSummary() {
        try {
            renderNavBar();
        } catch (IOException ioe) {}
        if (_full == 3)
            getBanlistSummary();
        else
            getProfileSummary();
        return "";
    }

    /** @return empty string, writes directly to _out */
    public String getProfileSummary() {
        try {
            ProfileOrganizerRenderer rend = new ProfileOrganizerRenderer(_context.profileOrganizer(), _context);
            rend.renderStatusHTML(_out, _full);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }
    
    /** @return empty string, writes directly to _out */
    public String getBanlistSummary() {
        try {
            BanlistRenderer rend = new BanlistRenderer(_context);
            rend.renderStatusHTML(_out);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }

    /**
     *  @since 0.9.1
     */
    private int getTab() {
        if (_full == 2)
            return 1;
        if (_full == 3)
            return 2;
        if (_full == 1)
            return 3;
        return 0;
    }

    /**
     *  @since 0.9.1
     */
    private void renderNavBar() throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<div class=\"confignav\" id=\"confignav\">");
        boolean span = _graphical;
        if (!span)
            buf.append("<center>");
        int tab = getTab();
        for (int i = 0; i < titles.length; i++) {
            if (i == tab) {
                // we are there
                if (span)
                    buf.append("<span class=\"tab2\">");
                buf.append(_t(titles[i]));
            } else {
                // we are not there, make a link
                if (span)
                    buf.append("<span class=\"tab\">");
                buf.append("<a href=\"profiles").append(links[i]).append("\">").append(_t(titles[i])).append("</a>");
            }
            if (span)
                buf.append("</span>\n");
            else if (i != titles.length - 1)
                buf.append("&nbsp;&nbsp;\n");
        }
        if (!span)
            buf.append("</center>");
        buf.append("</div>");
        _out.write(buf.toString());
    }
}
