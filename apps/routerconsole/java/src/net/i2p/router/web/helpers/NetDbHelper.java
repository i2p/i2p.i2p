package net.i2p.router.web.helpers;

import java.io.IOException;
import java.text.Collator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.SystemVersion;
import net.i2p.router.sybil.Analysis;
import net.i2p.router.web.FormHandler;
import net.i2p.router.web.Messages;

/**
 *  /netdb
 *  A FormHandler since 0.9.38.
 *  Most output is generated in NetDbRenderer and SybilRender.
 */
public class NetDbHelper extends FormHandler {
    private String _routerPrefix;
    private String _version;
    private String _country;
    private String _family, _caps, _ip, _sybil, _mtu, _ssucaps, _ipv6, _transport, _hostname, _sort;
    private int _full, _port, _cost, _page, _mode, _highPort, _icount;
    private long _date;
    private int _limit = DEFAULT_LIMIT;
    private boolean _lease;
    private boolean _clientOnly;
    private boolean _debug;
    private boolean _graphical;
    private SigType _type;
    private EncType _etype;
    private String _newNonce;
    private boolean _postOK;

    private static final int DEFAULT_LIMIT = SystemVersion.isARM() ? 250 : 500;
    private static final int DEFAULT_PAGE = 0;
    
    private static final String titles[] =
                                          {_x("Summary"),                       // 0
                                           _x("Local Router"),                  // 1
                                           _x("Router Lookup"),                 // 2
                                           // advanced below here
                                           _x("All Routers"),                   // 3
                                           _x("All Routers with Full Stats"),   // 4
                                           _x("LeaseSets"),                     // 5
                                           "LeaseSet Debug",                    // 6
                                           "Sybil",                             // 7
                                           "Advanced Lookup",                   // 8
                                           "LeaseSet Lookup",                   // 9
                                           "LeaseSets (Client DBs)"             // 10
                                          };

    private static final String links[] =
                                          {"",                                  // 0
                                           "?r=.",                              // 1
                                           "",                                  // 2
                                           "?f=2",                              // 3
                                           "?f=1",                              // 4
                                           "?l=1",                              // 5
                                           "?l=2",                              // 6
                                           "?f=3",                              // 7
                                           "?f=4",                              // 8
                                           "",                                  // 9
                                           "?l=7",                              // 10
                                          };
                                           

    public void setRouter(String r) {
        if (r != null && r.length() > 0)
            _routerPrefix = DataHelper.stripHTML(r.trim());  // XSS
    }

    /** @since 0.9.21 */
    public void setVersion(String v) {
        if (v != null && v.length() > 0)
            _version = DataHelper.stripHTML(v.trim());  // XSS
    }

    /** @since 0.9.21 */
    public void setCountry(String c) {
        if (c != null && c.length() > 0)
            _country = DataHelper.stripHTML(c.trim());  // XSS
    }

    /** @since 0.9.28 */
    public void setFamily(String c) {
        if (c != null && c.length() > 0)
            _family = DataHelper.stripHTML(c.trim());  // XSS
    }

    /** @since 0.9.28 */
    public void setCaps(String c) {
        if (c != null && c.length() > 0)
            _caps = DataHelper.stripHTML(c.trim());  // XSS
    }

    /** @since 0.9.28 */
    public void setIp(String c) {
        if (c != null && c.length() > 0)
            _ip = DataHelper.stripHTML(c.trim());  // XSS
    }

    /** @since 0.9.28 */
    public void setSybil(String c) {
        if (c != null)
            _sybil = DataHelper.stripHTML(c.trim());  // XSS
    }

    /** For form, same as above but with a length check
     *  @since 0.9.28
     */
    public void setSybil2(String c) {
        if (c != null && c.length() > 0)
            _sybil = DataHelper.stripHTML(c.trim());  // XSS
    }

    /** @since 0.9.28 */
    public void setPort(String f) {
        if (f == null)
            return;
        try {
            int dash = f.indexOf('-');
            if (dash > 0) {
                _port = Integer.parseInt(f.substring(0, dash).trim());
                _highPort = Integer.parseInt(f.substring(dash + 1).trim());
            } else {
                _port = Integer.parseInt(f.trim());
            }
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.28 */
    public void setType(String f) {
        if (f != null && f.length() > 0)
            _type = SigType.parseSigType(f);
    }

    /** @since 0.9.49 */
    public void setEtype(String f) {
        if (f != null && f.length() > 0)
            _etype = EncType.parseEncType(f);
    }

    /** @since 0.9.28 */
    public void setMtu(String f) {
        if (f != null && f.length() > 0)
            _mtu = DataHelper.stripHTML(f.trim());  // XSS
    }

    /** @since 0.9.28 */
    public void setIpv6(String f) {
        if (f != null && f.length() > 0) {
            _ipv6 = DataHelper.stripHTML(f.trim());  // XSS
            if (!_ipv6.endsWith(":"))
                _ipv6 = _ipv6 + ':';
        }
    }

    /** @since 0.9.28 */
    public void setSsucaps(String f) {
        if (f != null && f.length() > 0)
            _ssucaps = DataHelper.stripHTML(f.trim());  // XSS
    }

    /** @since 0.9.36 */
    public void setTransport(String f) {
        if (f != null && f.length() > 0)
            _transport = DataHelper.stripHTML(f).toUpperCase(Locale.US);
    }

    /** @since 0.9.28 */
    public void setCost(String f) {
        try {
            _cost = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.38 */
    public void setMode(String f) {
        try {
            _mode = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.38 */
    public void setDate(String f) {
        try {
            _date = Long.parseLong(f);
        } catch (NumberFormatException nfe) {}
    }

    public void setFull(String f) {
        try {
            _full = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }

    public void setLease(String l) {
        _clientOnly = "7".equals(l);
        _debug = "2".equals(l);
        _lease = _debug || "1".equals(l);
    }

    /** @since 0.9.57 */
    public void setLeaseset(String f) {
        if (f != null && f.length() > 0)
            _hostname = DataHelper.stripHTML(f);
    }

    /** @since 0.9.36 */
    public void setLimit(String f) {
        try {
            _limit = Integer.parseInt(f);
            if (_limit <= 0)
                _limit = Integer.MAX_VALUE;
            else if (_limit <= 10)
                _limit = 10;
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.36 */
    public void setPage(String f) {
        try {
            _page = Integer.parseInt(f) - 1;
            if (_page < 0)
                _page = 0;
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.57 */
    public void setSort(String f) {
        _sort = f;
    }
    
    /** @since 0.9.58 */
    public void setIntros(String f) {
        try {
            _icount = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }
    
    /**
     *  call for non-text-mode browsers
     *  @since 0.9.1
     */
    public void allowGraphical() {
        _graphical = true;
    }
    
    /**
     *  Override to save it
     *  @since 0.9.38
     */
    @Override
    public String getNewNonce() {
        _newNonce = super.getNewNonce();
        return _newNonce;
    }

    /**
     *  Now we're a FormHandler
     *  @since 0.9.38
     */
    protected void processForm() {
        _postOK = "Run new analysis".equals(_action) ||
                  "Review analysis".equals(_action);
        if ("Save".equals(_action)) {
                try {
                    Map<String, String> toSave = new HashMap<String, String>(4);
                    String newTime = getJettyString("runFrequency");
                    if (newTime != null) {
                        long ntime = Long.parseLong(newTime) * 60*60*1000;
                        toSave.put(Analysis.PROP_FREQUENCY, Long.toString(ntime));
                    }
                    String thresh = getJettyString("threshold");
                    if (thresh != null && thresh.length() > 0) {
                        float val = Math.max(Float.parseFloat(thresh), Analysis.MIN_BLOCK_POINTS);
                        toSave.put(Analysis.PROP_THRESHOLD, Float.toString(val));
                    }
                    String days = getJettyString("days");
                    if (days != null && days.length() > 0) {
                        long val = 24*60*60*1000L * Integer.parseInt(days);
                        toSave.put(Analysis.PROP_BLOCKTIME, Long.toString(val));
                    }
                    String age = getJettyString("deleteAge");
                    if (age != null && age.length() > 0) {
                        long val = 24*60*60*1000L * Integer.parseInt(age);
                        toSave.put(Analysis.PROP_REMOVETIME, Long.toString(val));
                    }
                    String enable = getJettyString("block");
                    toSave.put(Analysis.PROP_BLOCK, Boolean.toString(enable != null));
                    String nonff = getJettyString("nonff");
                    toSave.put(Analysis.PROP_NONFF, Boolean.toString(nonff != null));
                    if (_context.router().saveConfig(toSave, null))
                        addFormNotice(_t("Configuration saved successfully."));
                    else
                        addFormError("Error saving the configuration (applied but not saved) - please see the error logs");
                    Analysis.getInstance(_context).schedule();
                } catch (NumberFormatException nfe) {
                        addFormError("bad value");
                }
        }
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
                _ssucaps != null || _transport != null || _cost != 0 || _etype != null ||
                _icount > 0) {
                renderer.renderRouterInfoHTML(_out, _limit, _page,
                                              _routerPrefix, _version, _country,
                                              _family, _caps, _ip, _sybil, _port, _highPort, _type, _etype,
                                              _mtu, _ipv6, _ssucaps, _transport, _cost, _icount);
            } else if (_lease) {
                renderer.renderLeaseSetHTML(_out, _debug, null);
            } else if (_hostname != null) {
                renderer.renderLeaseSet(_out, _hostname, true);
            } else if (_full == 3) {
                if (_mode == 12 && !_postOK)
                    _mode = 0;
                else if ((_mode == 13 || _mode == 16) && !_postOK)
                    _mode = 14;
                (new SybilRenderer(_context)).getNetDbSummary(_out, _newNonce, _mode, _date);
            } else if (_full == 4) {
                renderLookupForm();
            } else if (_clientOnly) {
                for (Hash client : _context.clientManager().getPrimaryHashes()) {
                    renderer.renderLeaseSetHTML(_out, false, client);
                }
            } else {
                if (_full == 0 && _sort != null)
                    _full = 3;
                renderer.renderStatusHTML(_out, _limit, _page, _full);
            }
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
            return 6;
        if (_lease)
            return 5;
        if (".".equals(_routerPrefix))
            return 1;
        if (_routerPrefix != null || _version != null || _country != null ||
            _family != null || _caps != null || _ip != null || _sybil != null ||
            _port != 0 || _type != null || _mtu != null || _ipv6 != null ||
            _ssucaps != null || _transport != null || _cost != 0 || _etype != null)
            return 2;
        if (_full == 2)
            return 3;
        if (_full == 1)
            return 4;
        if (_full == 3)
            return 7;
        if (_full == 4)
            return 8;
        if (_hostname != null)
            return 9;
        if (_clientOnly)
            return 10;
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
            if (i == 9 && tab != 9)
                continue;   // can't nav to lookup
            if (i > 2 && i != tab && !isAdvanced())
                continue;
            if (i == 10) {
                // skip if no clients
                if (_context.clientManager().getPrimaryHashes().isEmpty() && i != tab)
                    continue;
            }
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
            if (span) {
                buf.append("</span>\n");
            } else if (i != titles.length - 1)
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
        _out.write("<form action=\"/netdb\" method=\"POST\">\n" + 
                   "<input type=\"hidden\" name=\"nonce\" value=\"" + _newNonce + "\" >\n" +
                   "<table id=\"netdblookup\"><tr><th colspan=\"3\">Network Database Search</th></tr>\n" +
                   "<tr><td>Capabilities:</td><td><input type=\"text\" name=\"caps\"></td><td>e.g. f or XfR</td></tr>\n" +
                   "<tr><td>Cost:</td><td><input type=\"text\" name=\"cost\"></td><td></td></tr>\n" +
                   "<tr><td>" + _t("Country") + ":</td><td><select name=\"c\"><option value=\"\" selected=\"selected\"></option>");
        Map<String, String> sorted = new TreeMap<String, String>(Collator.getInstance());
        for (Map.Entry<String, String> e : _context.commSystem().getCountries().entrySet()) {
            String tr = Messages.getString(e.getValue(), _context, Messages.COUNTRY_BUNDLE_NAME);
            sorted.put(tr, e.getKey());
        }
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            _out.write("<option value=\"" + e.getValue() + "\">" + e.getKey() + "</option>\n");
        }
        _out.write("</select></td><td></td></tr>\n" +
                   "<tr><td>Encryption Type:</td><td><select name=\"etype\"><option value=\"\" selected=\"selected\"></option>");
        for (EncType type : EnumSet.allOf(EncType.class)) {
            _out.write("<option value=\"" + type + "\">" + type + "</option>\n");
        }
        _out.write("</select></td><td></td></tr>\n" +
                   "<tr><td>" + _t("Router Family") + ":</td><td><input type=\"text\" name=\"fam\"></td><td></td></tr>\n" +
                   "<tr><td>Hash Prefix:</td><td><input type=\"text\" name=\"r\"></td><td></td></tr>\n" +
                   "<tr><td>" + _t("Full destination, name, Base32, or hash") + ":</td><td><input type=\"text\" name=\"ls\"></td><td></td></tr>\n" +
                   "<tr><td>Min. Introducer Count:</td><td><input type=\"text\" name=\"i\"></td><td></td></tr>\n" +
                   "<tr><td>IP:</td><td><input type=\"text\" name=\"ip\"></td><td>IPv4 or IPv6, /24,/16,/8 suffixes optional for IPv4, prefix ok for IPv6</td></tr>\n" +
                   "<tr><td>IPv6 Prefix:</td><td><input type=\"text\" name=\"ipv6\"></td><td></td></tr>\n" +
                   "<tr><td>" + _t("MTU") + ":</td><td><input type=\"text\" name=\"mtu\"></td><td></td></tr>\n" +
                   "<tr><td>" + _t("Port") + " or Port Range:</td><td><input type=\"text\" name=\"port\"></td><td>e.g. 1024-1028</td></tr>\n" +
                   "<tr><td>Signature Type:</td><td><select name=\"type\"><option value=\"\" selected=\"selected\"></option>");
        for (SigType type : EnumSet.allOf(SigType.class)) {
            _out.write("<option value=\"" + type + "\">" + type + "</option>\n");
        }
        _out.write("</select></td><td></td></tr>\n" +
                   "<tr><td>Transport:</td><td><select name=\"tr\"><option value=\"\" selected=\"selected\">" +
                   "<option value=\"NTCP\">NTCP</option>\n" +
                   "<option value=\"NTCP_1\">NTCP (v1 only)</option>\n" +
                   "<option value=\"NTCP_2\">NTCP (v2 supported)</option>\n" +
                   "<option value=\"NTCP2\">NTCP2</option>\n" +
                   "<option value=\"SSU\">SSU</option>\n" +
                   "<option value=\"SSU_1\">SSU (v1 only)</option>\n" +
                   "<option value=\"SSU_2\">SSU (v2 supported)</option>\n" +
                   "<option value=\"SSU2\">SSU2</option>\n" +
                   "</select></td><td></td></tr>\n" +
                   "<tr><td>Transport Capabilities:</td><td><input type=\"text\" name=\"ssucaps\"></td><td></td></tr>\n" +
                   "<tr><td>Router Version:</td><td><input type=\"text\" name=\"v\"></td><td></td></tr>\n" +
                   "<tr><td colspan=\"3\" class=\"subheading\"><b>Add Sybil analysis (must pick one above):</b></td></tr>\n" +
                   "<tr><td>Sybil close to:</td><td><input type=\"text\" name=\"sybil2\"></td><td>Router hash, destination hash, b32, or from address book</td>\n" +
                   "<tr><td><label for=\"closetorouter\">or Sybil close to this router:</label></td><td><input type=\"checkbox\" class=\"optbox\" value=\"1\" name=\"sybil\" id=\"closetorouter\"></td><td></td></tr>\n" +
                   "<tr><td colspan=\"3\" class=\"optionsave\">" +
                   "<button type=\"reset\" class=\"cancel\" value=\"Cancel\">" + _t("Cancel") + "</button> " +
                   "<button type=\"submit\" class=\"search\" value=\"Lookup\">Lookup</button></td></tr>\n" +
                   "</table>\n</form>\n");
    }
}
