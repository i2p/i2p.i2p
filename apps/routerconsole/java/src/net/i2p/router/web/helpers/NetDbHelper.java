package net.i2p.router.web.helpers;

import java.io.IOException;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.router.web.HelperBase;

public class NetDbHelper extends HelperBase {
    private String _routerPrefix;
    private String _version;
    private String _country;
    private String _family, _caps, _ip, _sybil, _mtu, _ssucaps, _ipv6;
    private int _full, _port, _cost;
    private boolean _lease;
    private boolean _debug;
    private boolean _graphical;
    private SigType _type;
    
    private static final String titles[] =
                                          {_x("Summary"),                       // 0
                                           _x("Local Router"),                  // 1
                                           _x("Router Lookup"),                 // 2
                                           _x("All Routers"),                   // 3
                                           _x("All Routers with Full Stats"),   // 4
                                           "LeaseSet Debug",                    // 5
                                           _x("LeaseSets"),                     // 6
                                           "Sybil",                             // 7
                                           "Advanced Lookup"   };               // 8

    private static final String links[] =
                                          {"",                                  // 0
                                           "?r=.",                              // 1
                                           "",                                  // 2
                                           "?f=2",                              // 3
                                           "?f=1",                              // 4
                                           "?l=2",                              // 5
                                           "?l=1",                              // 6
                                           "?f=3",                              // 7
                                           "?f=4" };                            // 8

    public void setRouter(String r) {
        if (r != null && r.length() > 0)
            _routerPrefix = DataHelper.stripHTML(r);  // XSS
    }

    /** @since 0.9.21 */
    public void setVersion(String v) {
        if (v != null && v.length() > 0)
            _version = DataHelper.stripHTML(v);  // XSS
    }

    /** @since 0.9.21 */
    public void setCountry(String c) {
        if (c != null && c.length() > 0)
            _country = DataHelper.stripHTML(c);  // XSS
    }

    /** @since 0.9.28 */
    public void setFamily(String c) {
        if (c != null && c.length() > 0)
            _family = DataHelper.stripHTML(c);  // XSS
    }

    /** @since 0.9.28 */
    public void setCaps(String c) {
        if (c != null && c.length() > 0)
            _caps = DataHelper.stripHTML(c);  // XSS
    }

    /** @since 0.9.28 */
    public void setIp(String c) {
        if (c != null && c.length() > 0)
            _ip = DataHelper.stripHTML(c);  // XSS
    }

    /** @since 0.9.28 */
    public void setSybil(String c) {
        if (c != null)
            _sybil = DataHelper.stripHTML(c);  // XSS
    }

    /** For form, same as above but with a length check
     *  @since 0.9.28
     */
    public void setSybil2(String c) {
        if (c != null && c.length() > 0)
            _sybil = DataHelper.stripHTML(c);  // XSS
    }

    /** @since 0.9.28 */
    public void setPort(String f) {
        try {
            _port = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.28 */
    public void setType(String f) {
        if (f != null && f.length() > 0)
            _type = SigType.parseSigType(f);
    }

    /** @since 0.9.28 */
    public void setMtu(String f) {
        if (f != null && f.length() > 0)
            _mtu = DataHelper.stripHTML(f);  // XSS
    }

    /** @since 0.9.28 */
    public void setIpv6(String f) {
        if (f != null && f.length() > 0) {
            _ipv6 = DataHelper.stripHTML(f);  // XSS
            if (!_ipv6.endsWith(":"))
                _ipv6 = _ipv6 + ':';
        }
    }

    /** @since 0.9.28 */
    public void setSsucaps(String f) {
        if (f != null && f.length() > 0)
            _ssucaps = DataHelper.stripHTML(f);  // XSS
    }

    /** @since 0.9.28 */
    public void setCost(String f) {
        try {
            _cost = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }

    public void setFull(String f) {
        try {
            _full = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }

    public void setLease(String l) {
        _debug = "2".equals(l);
        _lease = _debug || "1".equals(l);
    }
    
    /**
     *  call for non-text-mode browsers
     *  @since 0.9.1
     */
    public void allowGraphical() {
        _graphical = true;
    }

    /**
     *   storeWriter() must be called previously
     */
    public String getNetDbSummary() {
        NetDbRenderer renderer = new NetDbRenderer(_context);
        try {
            renderNavBar();
            if (_routerPrefix != null || _version != null || _country != null ||
                _family != null || _caps != null || _ip != null || _sybil != null ||
                _port != 0 || _type != null || _mtu != null || _ipv6 != null ||
                _ssucaps != null || _cost != 0)
                renderer.renderRouterInfoHTML(_out, _routerPrefix, _version, _country,
                                              _family, _caps, _ip, _sybil, _port, _type,
                                              _mtu, _ipv6, _ssucaps, _cost);
            else if (_lease)
                renderer.renderLeaseSetHTML(_out, _debug);
            else if (_full == 3)
                (new SybilRenderer(_context)).getNetDbSummary(_out);
            else if (_full == 4)
                renderLookupForm();
            else
                renderer.renderStatusHTML(_out, _full);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }

    /**
     *  @since 0.9.1
     */
    private int getTab() {
        if (_debug)
            return 5;
        if (_lease)
            return 6;
        if (".".equals(_routerPrefix))
            return 1;
        if (_routerPrefix != null || _version != null || _country != null ||
            _family != null || _caps != null || _ip != null || _sybil != null ||
            _port != 0 || _type != null || _mtu != null || _ipv6 != null ||
            _ssucaps != null || _cost != 0)
            return 2;
        if (_full == 2)
            return 3;
        if (_full == 1)
            return 4;
        if (_full == 3)
            return 7;
        if (_full == 4)
            return 8;
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
            if (i == 2 && tab != 2)
                continue;   // can't nav to lookup
            if ((i == 5 || i == 7 || i == 8) && !_context.getBooleanProperty(PROP_ADVANCED))
                continue;
            if (i == tab) {
                // we are there
                if (span)
                    buf.append("<span class=\"tab2\">");
                buf.append(_t(titles[i]));
            } else {
                // we are not there, make a link
                if (span)
                    buf.append("<span class=\"tab\">");
                buf.append("<a href=\"netdb").append(links[i]).append("\">").append(_t(titles[i])).append("</a>");
            }
            if (span)
                buf.append("</span>\n");
            else if (i != titles.length - 1)
                buf.append("&nbsp;&nbsp;\n");
        }
        if (!span)
            buf.append("</center>");
        buf.append("</div>\n");
        _out.write(buf.toString());
    }

    /**
     *  @since 0.9.28
     */
    private void renderLookupForm() throws IOException {
        _out.write("<form action=\"/netdb\" method=\"GET\">\n" + 
                   "<table id=\"netdblookup\"><tr><th colspan=\"3\">Network Database Search</th></tr>\n" +
                   "<tr><td colspan=\"3\" class=\"subheading\"><b>Select one search field <i>only</i>:</b></td></tr>\n" +
                   "<tr><td>Capabilities:</td><td><input type=\"text\" name=\"caps\"></td><td>e.g. f or XOfR</td></tr>\n" +
                   "<tr><td>Cost:</td><td><input type=\"text\" name=\"cost\"></td><td></td></tr>\n" +
                   "<tr><td>Country Code:</td><td><input type=\"text\" name=\"c\"></td><td>e.g. ru</td></tr>\n" +
                   "<tr><td>Router Family:</td><td><input type=\"text\" name=\"fam\"></td><td></td></tr>\n" +
                   "<tr><td>Hash Prefix:</td><td><input type=\"text\" name=\"r\"></td><td></td></tr>\n" +
                   "<tr><td>IP or Hostname:</td><td><input type=\"text\" name=\"ip\"></td><td>host name, IPv4, or IPv6, /24,/16,/8 suffixes optional for IPv4</td></tr>\n" +
                   "<tr><td>IPv6 Prefix:</td><td><input type=\"text\" name=\"ipv6\"></td><td></td></tr>\n" +
                   "<tr><td>MTU:</td><td><input type=\"text\" name=\"mtu\"></td><td></td></tr>\n" +
                   "<tr><td>Port Number:</td><td><input type=\"text\" name=\"port\"></td><td></td></tr>\n" +
                   "<tr><td>Signature Type:</td><td><input type=\"text\" name=\"type\"></td><td></td></tr>\n" +
                   "<tr><td>SSU Capabilities:</td><td><input type=\"text\" name=\"ssucaps\"></td><td></td></tr>\n" +
                   "<tr><td>Router Version:</td><td><input type=\"text\" name=\"v\"></td><td></td></tr>\n" +
                   "<tr><td colspan=\"3\" class=\"subheading\"><b>Add Sybil analysis (must pick one above):</b></td></tr>\n" +
                   "<tr><td>Sybil close to:</td><td><input type=\"text\" name=\"sybil2\"></td><td>Router hash, dest hash, b32, or from address book</td>\n" +
                   "<tr><td><label for=\"closetorouter\">or Sybil close to this router:</label></td><td><input type=\"checkbox\" class=\"optbox\" value=\"1\" name=\"sybil\" id=\"closetorouter\"></td><td></td></tr>\n" +
                   "<tr><td colspan=\"3\" class=\"optionsave\"><button type=\"submit\" class=\"search\" value=\"Lookup\">Lookup</button></td></tr>\n" +
                   "</table>\n</form>\n");
    }
}
