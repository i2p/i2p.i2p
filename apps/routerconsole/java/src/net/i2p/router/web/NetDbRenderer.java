package net.i2p.router.web;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.ObjectCounter;
import net.i2p.util.VersionComparator;

public class NetDbRenderer {
    private RouterContext _context;

    public NetDbRenderer (RouterContext ctx) {
        _context = ctx;
    }

    private class LeaseSetComparator implements Comparator {
         public int compare(Object l, Object r) {
             Destination dl = ((LeaseSet)l).getDestination();
             Destination dr = ((LeaseSet)r).getDestination();
             boolean locall = _context.clientManager().isLocal(dl);
             boolean localr = _context.clientManager().isLocal(dr);
             if (locall && !localr) return -1;
             if (localr && !locall) return 1;
             return dl.calculateHash().toBase64().compareTo(dr.calculateHash().toBase64());
        }
    }

    private static class RouterInfoComparator implements Comparator {
         public int compare(Object l, Object r) {
             return ((RouterInfo)l).getIdentity().getHash().toBase64().compareTo(((RouterInfo)r).getIdentity().getHash().toBase64());
        }
    }

    public void renderRouterInfoHTML(Writer out, String routerPrefix) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<h2>" + _("Network Database RouterInfo Lookup") + "</h2>\n");
        if (".".equals(routerPrefix)) {
            renderRouterInfo(buf, _context.router().getRouterInfo(), true, true);
        } else {
            boolean notFound = true;
            Set routers = _context.netDb().getRouters();
            for (Iterator iter = routers.iterator(); iter.hasNext(); ) {
                RouterInfo ri = (RouterInfo)iter.next();
                Hash key = ri.getIdentity().getHash();
                if (key.toBase64().startsWith(routerPrefix)) {
                    renderRouterInfo(buf, ri, false, true);
                    notFound = false;
                }
            }
            if (notFound)
                buf.append(_("Router") + ' ').append(routerPrefix).append(' ' + _("not found in network database") );
        }
        out.write(buf.toString());
        out.flush();
    }

    public void renderLeaseSetHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<h2>" + _("Network Database Contents") + "</h2>\n");
        buf.append("<a href=\"netdb.jsp\">" + _("View RouterInfo") + "</a>");
        buf.append("<h3>").append(_("LeaseSets")).append("</h3>\n");
        Set leases = new TreeSet(new LeaseSetComparator());
        leases.addAll(_context.netDb().getLeases());
        long now = _context.clock().now();
        for (Iterator iter = leases.iterator(); iter.hasNext(); ) {
            LeaseSet ls = (LeaseSet)iter.next();
            Destination dest = ls.getDestination();
            Hash key = dest.calculateHash();
            buf.append("<b>").append(_("LeaseSet")).append(": ").append(key.toBase64());
            if (_context.clientManager().isLocal(dest)) {
                buf.append(" (<a href=\"tunnels.jsp#" + key.toBase64().substring(0,4) + "\">" + _("Local") + "</a> ");
                if (! _context.clientManager().shouldPublishLeaseSet(key))
                    buf.append(_("Unpublished") + ' ');
                buf.append(_("Destination") + ' ');
                TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(key);
                if (in != null && in.getDestinationNickname() != null)
                    buf.append(in.getDestinationNickname());
                else
                    buf.append(dest.toBase64().substring(0, 6));
            } else {
                buf.append(" (" + _("Destination") + ' ');
                String host = _context.namingService().reverseLookup(dest);
                if (host != null)
                    buf.append(host);
                else
                    buf.append(dest.toBase64().substring(0, 6));
            }
            buf.append(")</b><br>\n");
            long exp = ls.getEarliestLeaseDate()-now;
            if (exp > 0)
                buf.append(_("Expires in {0}", DataHelper.formatDuration(exp))).append("<br>\n");
            else
                buf.append(_("Expired {0} ago", DataHelper.formatDuration(0-exp))).append("<br>\n");
            for (int i = 0; i < ls.getLeaseCount(); i++) {
                buf.append(_("Lease")).append(' ').append(i + 1).append(": " + _("Gateway") + ' ');
                buf.append(_context.commSystem().renderPeerHTML(ls.getLease(i).getGateway()));
                buf.append(' ' + _("Tunnel") + ' ').append(ls.getLease(i).getTunnelId().getTunnelId()).append("<br>\n");
            }
            buf.append("<hr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }
        out.write(buf.toString());
        out.flush();
    }

    /**
     *  @param mode 0: our info and charts only; 1: full routerinfos and charts; 2: abbreviated routerinfos and charts
     */
    public void renderStatusHTML(Writer out, int mode) throws IOException {
        out.write("<h2>" + _("Network Database Contents") + " (<a href=\"netdb.jsp?l=1\">" + _("View LeaseSets") + "</a>)</h2>\n");
        if (!_context.netDb().isInitialized()) {
            out.write(_("Not initialized"));
            out.flush();
            return;
        }
        
        boolean full = mode == 1;
        boolean shortStats = mode == 2;
        boolean showStats = full || shortStats;
        Hash us = _context.routerHash();
        out.write("<a name=\"routers\" ></a><h3>" + _("Routers") + " (<a href=\"netdb.jsp");
        if (full || !showStats)
            out.write("?f=2#routers\" >" + _("Show all routers"));
        else
            out.write("?f=1#routers\" >" + _("Show all routers with full stats"));
        out.write("</a>)</h3>\n");
        
        StringBuilder buf = new StringBuilder(8192);
        RouterInfo ourInfo = _context.router().getRouterInfo();
        renderRouterInfo(buf, ourInfo, true, true);
        out.write(buf.toString());
        buf.setLength(0);
        
        ObjectCounter<String> versions = new ObjectCounter();
        ObjectCounter<String> countries = new ObjectCounter();
        int[] transportCount = new int[8];
        
        Set routers = new TreeSet(new RouterInfoComparator());
        routers.addAll(_context.netDb().getRouters());
        for (Iterator iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = (RouterInfo)iter.next();
            Hash key = ri.getIdentity().getHash();
            boolean isUs = key.equals(us);
            if (!isUs) {
                if (showStats) {
                    renderRouterInfo(buf, ri, false, full);
                    out.write(buf.toString());
                    buf.setLength(0);
                }
                String routerVersion = ri.getOption("router.version");
                if (routerVersion != null)
                    versions.increment(routerVersion);
                String country = _context.commSystem().getCountry(key);
                if(country != null)
                    countries.increment(country);
                transportCount[classifyTransports(ri)]++;
            }
        }
            
        buf.append("<table border=\"0\" cellspacing=\"30\"><tr><th colspan=\"3\">").append(_("Network Database Router Statistics")).append("</th><tr><td>");
        // versions table
        List<String> versionList = new ArrayList(versions.objects());
        if (versionList.size() > 0) {
            Collections.sort(versionList, Collections.reverseOrder(new VersionComparator()));
            buf.append("<table>\n");
            buf.append("<tr><th>" + _("Version") + "</th><th>" + _("Count") + "</th></tr>\n");
            for (String routerVersion : versionList) {
                int num = versions.count(routerVersion);
                buf.append("<tr><td align=\"center\">").append(DataHelper.stripHTML(routerVersion));
                buf.append("</td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
            buf.append("</table>\n");
        }
        buf.append("</td><td>");
        out.write(buf.toString());
        buf.setLength(0);
            
        // transports table
        buf.append("<table>\n");
        buf.append("<tr><th align=\"left\">" + _("Transports") + "</th><th>" + _("Count") + "</th></tr>\n");
        for (int i = 0; i < 8; i++) {
            int num = transportCount[i];
            if (num > 0) {
                buf.append("<tr><td>").append(_(TNAMES[i]));
                buf.append("</td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
        }
        buf.append("</table>\n");
        buf.append("</td><td>");
        out.write(buf.toString());
        buf.setLength(0);

        // country table
        List<String> countryList = new ArrayList(countries.objects());
        if (countryList.size() > 0) {
            Collections.sort(countryList, new CountryComparator());
            buf.append("<table>\n");
            buf.append("<tr><th align=\"left\">" + _("Country") + "</th><th>" + _("Count") + "</th></tr>\n");
            for (String country : countryList) {
                int num = countries.count(country);
                buf.append("<tr><td><img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase()).append("\"");
                buf.append(" src=\"/flags.jsp?c=").append(country).append("\"> ");
                buf.append(_(_context.commSystem().getCountryName(country)));
                buf.append("</td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
            buf.append("</table>\n");
        }

        buf.append("</td></tr></table>");
        out.write(buf.toString());
        out.flush();
    }
    
    /** sort by translated country name */
    private class CountryComparator implements Comparator {
         public int compare(Object l, Object r) {
             return _(_context.commSystem().getCountryName((String)l))
                    .compareTo(_(_context.commSystem().getCountryName((String)r)));
        }
    }

    /**
     *  Be careful to use stripHTML for any displayed routerInfo data
     *  to prevent vulnerabilities
     */
    private void renderRouterInfo(StringBuilder buf, RouterInfo info, boolean isUs, boolean full) {
        String hash = info.getIdentity().getHash().toBase64();
        buf.append("<table><tr><th><a name=\"").append(hash.substring(0, 6)).append("\" ></a>");
        if (isUs) {
            buf.append("<a name=\"our-info\" ></a><b>" + _("Our info") + ": ").append(hash).append("</b></th></tr><tr><td>\n");
        } else {
            buf.append("<b>" + _("Peer info for") + ":</b> ").append(hash).append("\n");
            if (full) {
                buf.append("[<a href=\"netdb.jsp\" >Back</a>]</th></tr><td>\n");
            } else {
                buf.append("[<a href=\"netdb.jsp?r=").append(hash.substring(0, 6)).append("\" >").append(_("Full entry")).append("</a>]</th></tr><td>\n");
            }
        }
        
        long age = _context.clock().now() - info.getPublished();
        if (isUs && _context.router().isHidden()) {
            buf.append("<b>").append(_("Hidden")).append(", ").append(_("Updated")).append(":</b> ")
               .append(_("{0} ago", DataHelper.formatDuration(age))).append("<br>\n");
        } else if (age > 0) {
            buf.append("<b>").append(_("Published")).append(":</b> ")
               .append(_("{0} ago", DataHelper.formatDuration(age))).append("<br>\n");
        } else {
            // shouldnt happen
            buf.append("<b>" + _("Published") + ":</b> in ").append(DataHelper.formatDuration(0-age)).append("???<br>\n");
        }
        buf.append("<b>" + _("Address(es)") + ":</b> ");
        String country = _context.commSystem().getCountry(info.getIdentity().getHash());
        if(country != null) {
            buf.append("<img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase()).append('\"');
            buf.append(" title=\"").append(_(_context.commSystem().getCountryName(country))).append('\"');
            buf.append(" src=\"/flags.jsp?c=").append(country).append("\"> ");
        }
        for (Iterator iter = info.getAddresses().iterator(); iter.hasNext(); ) {
            RouterAddress addr = (RouterAddress)iter.next();
            buf.append("<b>").append(DataHelper.stripHTML(addr.getTransportStyle())).append(":</b> ");
            for (Iterator optIter = addr.getOptions().keySet().iterator(); optIter.hasNext(); ) {
                String name = (String)optIter.next();
                String val = addr.getOptions().getProperty(name);
                buf.append('[').append(DataHelper.stripHTML(name)).append('=').append(DataHelper.stripHTML(val)).append("] ");
            }
        }
        buf.append("</td></tr>\n");
        if (full) {
            buf.append("<tr><td>" + _("Stats") + ": <br><code>\n");
            for (Iterator iter = info.getOptions().keySet().iterator(); iter.hasNext(); ) {
                String key = (String)iter.next();
                String val = info.getOption(key);
                buf.append(DataHelper.stripHTML(key)).append(" = ").append(DataHelper.stripHTML(val)).append("<br>\n");
            }
            buf.append("</code></td></tr>\n");
        } else {
        }
        buf.append("</td></tr>\n");
    }

    private static final int SSU = 1;
    private static final int SSUI = 2;
    private static final int NTCP = 4;
    private static final String[] TNAMES = { _x("Hidden or starting up"), _x("SSU"), _x("SSU with introducers"), "",
                                  _x("NTCP"), _x("NTCP and SSU"), _x("NTCP and SSU with introducers"), "" };
    /**
     *  what transport types
     */
    private int classifyTransports(RouterInfo info) {
        int rv = 0;
        String hash = info.getIdentity().getHash().toBase64();
        for (Iterator iter = info.getAddresses().iterator(); iter.hasNext(); ) {
            RouterAddress addr = (RouterAddress)iter.next();
            String style = addr.getTransportStyle();
            if (style.equals("NTCP")) {
                rv |= NTCP;
            } else if (style.equals("SSU")) {
                if (addr.getOptions().getProperty("iport0") != null)
                    rv |= SSUI;
                else
                    rv |= SSU;
            }
        }
        return rv;
    }

    /** translate a string */
    private String _(String s) {
        return Messages.getString(s, _context);
    }

    /** tag only */
    private static final String _x(String s) {
        return s;
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To tranlslate parameter also, use _("foo {0} bar", _("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    private String _(String s, Object o) {
        return Messages.getString(s, o, _context);
    }
}
