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
import java.math.BigInteger;         // debug
import java.text.Collator;
import java.text.DecimalFormat;      // debug
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.networkdb.kademlia.HashDistance;   // debug
import net.i2p.util.HexDump;                             // debug
import net.i2p.util.ObjectCounter;
import net.i2p.util.OrderedProperties;
import net.i2p.util.VersionComparator;

public class NetDbRenderer {
    private RouterContext _context;

    public NetDbRenderer (RouterContext ctx) {
        _context = ctx;
    }

    private class LeaseSetComparator implements Comparator<LeaseSet> {
         public int compare(LeaseSet l, LeaseSet r) {
             Destination dl = l.getDestination();
             Destination dr = r.getDestination();
             boolean locall = _context.clientManager().isLocal(dl);
             boolean localr = _context.clientManager().isLocal(dr);
             if (locall && !localr) return -1;
             if (localr && !locall) return 1;
             return dl.calculateHash().toBase64().compareTo(dr.calculateHash().toBase64());
        }
    }

    /** for debugging @since 0.7.14 */
    private static class LeaseSetRoutingKeyComparator implements Comparator<LeaseSet> {
         private final Hash _us;
         public LeaseSetRoutingKeyComparator(Hash us) {
             _us = us;
         }
         public int compare(LeaseSet l, LeaseSet r) {
             return HashDistance.getDistance(_us, l.getRoutingKey()).subtract(HashDistance.getDistance(_us, r.getRoutingKey())).signum();
        }
    }

    private static class RouterInfoComparator implements Comparator<RouterInfo> {
         public int compare(RouterInfo l, RouterInfo r) {
             return l.getIdentity().getHash().toBase64().compareTo(r.getIdentity().getHash().toBase64());
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

    /**
     *  @param debug @since 0.7.14 sort by distance from us, display
     *               median distance, and other stuff, useful when floodfill
     */
    public void renderLeaseSetHTML(Writer out, boolean debug) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<h2>" + _("Network Database Contents") + "</h2>\n");
        buf.append("<a href=\"netdb\">" + _("View RouterInfo") + "</a>");
        buf.append("<h3>").append(_("LeaseSets")).append("</h3>\n");
        Hash ourRKey;
        Set<LeaseSet> leases;
        DecimalFormat fmt;
        if (debug) {
            ourRKey = _context.routerHash();
            leases = new TreeSet(new LeaseSetRoutingKeyComparator(ourRKey));
            fmt = new DecimalFormat("#0.00");
        } else {
            ourRKey = null;
            leases = new TreeSet(new LeaseSetComparator());
            fmt = null;
        }
        leases.addAll(_context.netDb().getLeases());
        int medianCount = leases.size() / 2;
        BigInteger median = null;
        int c = 0;
        long now = _context.clock().now();
        for (Iterator iter = leases.iterator(); iter.hasNext(); ) {
            LeaseSet ls = (LeaseSet)iter.next();
            Destination dest = ls.getDestination();
            Hash key = dest.calculateHash();
            buf.append("<b>").append(_("LeaseSet")).append(": ").append(key.toBase64());
            if (_context.clientManager().isLocal(dest)) {
                buf.append(" (<a href=\"tunnels#" + key.toBase64().substring(0,4) + "\">" + _("Local") + "</a> ");
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
                buf.append(_("Expires in {0}", DataHelper.formatDuration2(exp))).append("<br>\n");
            else
                buf.append(_("Expired {0} ago", DataHelper.formatDuration2(0-exp))).append("<br>\n");
            if (debug) {
                buf.append("RAP? " + ls.getReceivedAsPublished() + ' ');
                buf.append("RAR? " + ls.getReceivedAsReply() + ' ');
                BigInteger dist = HashDistance.getDistance(ourRKey, ls.getRoutingKey());
                if (c++ == medianCount)
                    median = dist;
                buf.append("Dist: <b>" + fmt.format(biLog2(dist)) + "</b> ");
                buf.append("RKey: " + ls.getRoutingKey().toBase64() + ' ');
                buf.append("<br>");
            }
            for (int i = 0; i < ls.getLeaseCount(); i++) {
                buf.append(_("Lease")).append(' ').append(i + 1).append(": " + _("Gateway") + ' ');
                buf.append(_context.commSystem().renderPeerHTML(ls.getLease(i).getGateway()));
                buf.append(' ' + _("Tunnel") + ' ').append(ls.getLease(i).getTunnelId().getTunnelId()).append("<br>\n");
            }
            buf.append("<hr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }
        if (debug) {
            buf.append("<p><b>Total Leasesets: " + leases.size());
            buf.append("</b></p><p><b>Published (RAP) Leasesets: " + _context.netDb().getKnownLeaseSets());
            buf.append("</b></p><p><b>Mod Data: " + HexDump.dump(_context.routingKeyGenerator().getModData()));
            buf.append("</b></p><p><b>Network data (only valid if floodfill):");
            buf.append("</b></p><p><b>Center of Key Space (router hash): " + ourRKey.toBase64());
            if (median != null) {
                double log2 = biLog2(median);
                buf.append("</b></p><p><b>Median distance (bits): " + fmt.format(log2));
                // 3 for 8 floodfills... -1 for median
                int total = (int) Math.round(Math.pow(2, 3 + 256 - 1 - log2));
                buf.append("</b></p><p><b>Estimated total floodfills: " + total);
                buf.append("</b></p><p><b>Estimated network total leasesets: " + (total * leases.size() / 8));
            }
            buf.append("</b></p>");
        }
        out.write(buf.toString());
        out.flush();
    }

    /**
     * For debugging
     * http://forums.sun.com/thread.jspa?threadID=597652
     * @since 0.7.14
     */
    private static double biLog2(BigInteger a) {
        int b = a.bitLength() - 1;
        double c = 0;
        double d = 0.5;
        for (int i = b; i >= 0; --i) {
             if (a.testBit(i))
                 c += d;
             d /= 2;
        }
        return b + c;
    }

    /**
     *  @param mode 0: our info and charts only; 1: full routerinfos and charts; 2: abbreviated routerinfos and charts
     */
    public void renderStatusHTML(Writer out, int mode) throws IOException {
        out.write("<h2>" + _("Network Database Contents") + " (<a href=\"netdb?l=1\">" + _("View LeaseSets") + "</a>)</h2>\n");
        if (!_context.netDb().isInitialized()) {
            out.write(_("Not initialized"));
            out.flush();
            return;
        }
        
        boolean full = mode == 1;
        boolean shortStats = mode == 2;
        boolean showStats = full || shortStats;
        Hash us = _context.routerHash();
        out.write("<a name=\"routers\" ></a><h3>" + _("Routers") + " (<a href=\"netdb");
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
        
        Set<RouterInfo> routers = new TreeSet(new RouterInfoComparator());
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
            
        buf.append("<table border=\"0\" cellspacing=\"30\"><tr><th colspan=\"3\">")
           .append(_("Network Database Router Statistics"))
           .append("</th></tr><tr><td style=\"vertical-align: top;\">");
        // versions table
        List<String> versionList = new ArrayList(versions.objects());
        if (!versionList.isEmpty()) {
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
        buf.append("</td><td style=\"vertical-align: top;\">");
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
        buf.append("</td><td style=\"vertical-align: top;\">");
        out.write(buf.toString());
        buf.setLength(0);

        // country table
        List<String> countryList = new ArrayList(countries.objects());
        if (!countryList.isEmpty()) {
            Collections.sort(countryList, new CountryComparator());
            buf.append("<table>\n");
            buf.append("<tr><th align=\"left\">" + _("Country") + "</th><th>" + _("Count") + "</th></tr>\n");
            for (String country : countryList) {
                int num = countries.count(country);
                buf.append("<tr><td><img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append("\"");
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
    
    /** sort by translated country name using rules for the current language setting */
    private class CountryComparator implements Comparator<String> {
         Collator coll;

         public CountryComparator() {
             super();
             coll = Collator.getInstance(new Locale(Messages.getLanguage(_context)));
         }

         public int compare(String l, String r) {
             return coll.compare(_(_context.commSystem().getCountryName(l)),
                                 _(_context.commSystem().getCountryName(r)));
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
                buf.append("[<a href=\"netdb\" >Back</a>]</th></tr><tr><td>\n");
            } else {
                buf.append("[<a href=\"netdb?r=").append(hash.substring(0, 6)).append("\" >").append(_("Full entry")).append("</a>]</th></tr><tr><td>\n");
            }
        }
        
        long age = _context.clock().now() - info.getPublished();
        if (isUs && _context.router().isHidden()) {
            buf.append("<b>").append(_("Hidden")).append(", ").append(_("Updated")).append(":</b> ")
               .append(_("{0} ago", DataHelper.formatDuration2(age))).append("<br>\n");
        } else if (age > 0) {
            buf.append("<b>").append(_("Published")).append(":</b> ")
               .append(_("{0} ago", DataHelper.formatDuration2(age))).append("<br>\n");
        } else {
            // shouldnt happen
            buf.append("<b>" + _("Published") + ":</b> in ").append(DataHelper.formatDuration2(0-age)).append("???<br>\n");
        }
        buf.append("<b>" + _("Address(es)") + ":</b> ");
        String country = _context.commSystem().getCountry(info.getIdentity().getHash());
        if(country != null) {
            buf.append("<img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append('\"');
            buf.append(" title=\"").append(_(_context.commSystem().getCountryName(country))).append('\"');
            buf.append(" src=\"/flags.jsp?c=").append(country).append("\"> ");
        }
        for (Iterator iter = info.getAddresses().iterator(); iter.hasNext(); ) {
            RouterAddress addr = (RouterAddress)iter.next();
            String style = addr.getTransportStyle();
            buf.append("<b>").append(DataHelper.stripHTML(style)).append(":</b> ");
            int cost = addr.getCost();
            if (!((style.equals("SSU") && cost == 5) || (style.equals("NTCP") && cost == 10)))
                buf.append('[').append(_("cost")).append('=').append("" + cost).append("] ");
            Map p = addr.getOptionsMap();
            for (Map.Entry e : (Set<Map.Entry>) p.entrySet()) {
                String name = (String) e.getKey();
                String val = (String) e.getValue();
                buf.append('[').append(_(DataHelper.stripHTML(name))).append('=').append(DataHelper.stripHTML(val)).append("] ");
            }
        }
        buf.append("</td></tr>\n");
        if (full) {
            buf.append("<tr><td>" + _("Stats") + ": <br><code>");
            Map p = info.getOptionsMap();
            for (Map.Entry e : (Set<Map.Entry>) p.entrySet()) {
                String key = (String) e.getKey();
                String val = (String) e.getValue();
                buf.append(DataHelper.stripHTML(key)).append(" = ").append(DataHelper.stripHTML(val)).append("<br>\n");
            }
            buf.append("</code></td></tr>\n");
        }
        buf.append("</table>\n");
    }

    private static final int SSU = 1;
    private static final int SSUI = 2;
    private static final int NTCP = 4;
    private static final String[] TNAMES = { _x("Hidden or starting up"), _x("SSU"), _x("SSU with introducers"), "",
                                  _x("NTCP"), _x("NTCP and SSU"), _x("NTCP and SSU with introducers"), "" };
    /**
     *  what transport types
     */
    private static int classifyTransports(RouterInfo info) {
        int rv = 0;
        for (RouterAddress addr : info.getAddresses()) {
            String style = addr.getTransportStyle();
            if (style.equals("NTCP")) {
                rv |= NTCP;
            } else if (style.equals("SSU")) {
                if (addr.getOption("iport0") != null)
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
