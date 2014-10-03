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
import java.io.Serializable;
import java.io.Writer;
import java.math.BigInteger;         // debug
import java.text.Collator;
import java.text.DecimalFormat;      // debug
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.util.HashDistance;   // debug
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.util.ObjectCounter;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;

public class NetDbRenderer {
    private final RouterContext _context;

    public NetDbRenderer (RouterContext ctx) {
        _context = ctx;
    }

    /**
     *  Inner class, can't be Serializable
     */
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
    private static class LeaseSetRoutingKeyComparator implements Comparator<LeaseSet>, Serializable {
         private final Hash _us;
         public LeaseSetRoutingKeyComparator(Hash us) {
             _us = us;
         }
         public int compare(LeaseSet l, LeaseSet r) {
             return HashDistance.getDistance(_us, l.getRoutingKey()).subtract(HashDistance.getDistance(_us, r.getRoutingKey())).signum();
        }
    }

    private static class RouterInfoComparator implements Comparator<RouterInfo>, Serializable {
         public int compare(RouterInfo l, RouterInfo r) {
             return l.getIdentity().getHash().toBase64().compareTo(r.getIdentity().getHash().toBase64());
        }
    }

    public void renderRouterInfoHTML(Writer out, String routerPrefix) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        if (".".equals(routerPrefix)) {
            renderRouterInfo(buf, _context.router().getRouterInfo(), true, true);
        } else {
            boolean notFound = true;
            Set<RouterInfo> routers = _context.netDb().getRouters();
            for (RouterInfo ri : routers) {
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
        if (debug)
            buf.append("<p>Debug mode - Sorted by hash distance, closest first</p>\n");
        Hash ourRKey;
        Set<LeaseSet> leases;
        DecimalFormat fmt;
        if (debug) {
            ourRKey = _context.routerHash();
            leases = new TreeSet<LeaseSet>(new LeaseSetRoutingKeyComparator(ourRKey));
            fmt = new DecimalFormat("#0.00");
        } else {
            ourRKey = null;
            leases = new TreeSet<LeaseSet>(new LeaseSetComparator());
            fmt = null;
        }
        leases.addAll(_context.netDb().getLeases());
        int medianCount = 0;
        int rapCount = 0;
        BigInteger median = null;
        int c = 0;
        if (debug) {
            // Find the center of the RAP leasesets
            for (LeaseSet ls : leases) {
                if (ls.getReceivedAsPublished())
                    rapCount++;
            }
            medianCount = rapCount / 2;
        }
        long now = _context.clock().now();
        for (LeaseSet ls : leases) {
            Destination dest = ls.getDestination();
            Hash key = dest.calculateHash();
            buf.append("<b>").append(_("LeaseSet")).append(": ").append(key.toBase64()).append("</b>\n");
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
                buf.append(")<br>\n");
            } else {
                buf.append(" (").append(_("Destination")).append(' ');
                String host = _context.namingService().reverseLookup(dest);
                if (host != null) {
                    buf.append("<a href=\"http://").append(host).append("/\">").append(host).append("</a>)<br>\n");
                } else {
                    String b32 = dest.toBase32();
                    buf.append(dest.toBase64().substring(0, 6)).append(")<br>\n" +
                               "<a href=\"http://").append(b32).append("\">").append(b32).append("</a><br>\n" +
                               "<a href=\"/susidns/addressbook.jsp?book=private&amp;destination=")
                       .append(dest.toBase64()).append("#add\">").append(_("Add to local addressbook")).append("</a><br>\n");    
                }
            }
            long exp = ls.getLatestLeaseDate()-now;
            if (exp > 0)
                buf.append(_("Expires in {0}", DataHelper.formatDuration2(exp)));
            else
                buf.append(_("Expired {0} ago", DataHelper.formatDuration2(0-exp)));
            buf.append("<br>\n");
            if (debug) {
                buf.append("RAP? " + ls.getReceivedAsPublished());
                buf.append(" RAR? " + ls.getReceivedAsReply());
                BigInteger dist = HashDistance.getDistance(ourRKey, ls.getRoutingKey());
                if (ls.getReceivedAsPublished()) {
                    if (c++ == medianCount)
                        median = dist;
                }
                buf.append(" Dist: <b>").append(fmt.format(biLog2(dist))).append("</b><br>");
                //buf.append(dest.toBase32()).append("<br>");
                buf.append("Sig type: ").append(dest.getSigningPublicKey().getType()).append("<br>");
                buf.append("Routing Key: ").append(ls.getRoutingKey().toBase64());
                buf.append("<br>");
                buf.append("Encryption Key: ").append(ls.getEncryptionKey().toBase64().substring(0, 20)).append("...<br>");
            }
            for (int i = 0; i < ls.getLeaseCount(); i++) {
                Lease lease = ls.getLease(i);
                buf.append(_("Lease")).append(' ').append(i + 1).append(": ").append(_("Gateway")).append(' ');
                buf.append(_context.commSystem().renderPeerHTML(lease.getGateway()));
                buf.append(' ').append(_("Tunnel")).append(' ').append(lease.getTunnelId().getTunnelId()).append(' ');
                if (debug) {
                    long exl = lease.getEndDate().getTime() - now;
                    if (exl > 0)
                        buf.append(_("Expires in {0}", DataHelper.formatDuration2(exl)));
                    else
                        buf.append(_("Expired {0} ago", DataHelper.formatDuration2(0-exl)));
                }
                buf.append("<br>\n");
            }
            buf.append("<hr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }
        if (debug) {
            FloodfillNetworkDatabaseFacade netdb = (FloodfillNetworkDatabaseFacade)_context.netDb();
            buf.append("<p><b>Total Leasesets: ").append(leases.size());
            buf.append("</b></p><p><b>Published (RAP) Leasesets: ").append(netdb.getKnownLeaseSets());
            buf.append("</b></p><p><b>Mod Data: \"").append(DataHelper.getUTF8(_context.routerKeyGenerator().getModData()))
               .append("\" Last Changed: ").append(new Date(_context.routerKeyGenerator().getLastChanged()));
            buf.append("</b></p><p><b>Next Mod Data: \"").append(DataHelper.getUTF8(_context.routerKeyGenerator().getNextModData()))
               .append("\" Change in: ").append(DataHelper.formatDuration(_context.routerKeyGenerator().getTimeTillMidnight()));
            int ff = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL).size();
            buf.append("</b></p><p><b>Known Floodfills: ").append(ff);
            buf.append("</b></p><p><b>Currently Floodfill? ");
            buf.append(netdb.floodfillEnabled() ? "yes" : "no");
            buf.append("</b></p><p><b>Network data (only valid if floodfill):");
            //buf.append("</b></p><p><b>Center of Key Space (router hash): " + ourRKey.toBase64());
            if (median != null) {
                double log2 = biLog2(median);
                buf.append("</b></p><p><b>Median distance (bits): ").append(fmt.format(log2));
                // 2 for 4 floodfills... -1 for median
                int total = (int) Math.round(Math.pow(2, 2 + 256 - 1 - log2));
                buf.append("</b></p><p><b>Estimated total floodfills: ").append(total);
                buf.append("</b></p><p><b>Estimated total leasesets: ").append(total * rapCount / 8);
            } else {
                buf.append("</b></p><p><b>Not floodfill or no data");
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
     *  @param mode 0: charts only; 1: full routerinfos; 2: abbreviated routerinfos
     */
    public void renderStatusHTML(Writer out, int mode) throws IOException {
        if (!_context.netDb().isInitialized()) {
            out.write(_("Not initialized"));
            out.flush();
            return;
        }
        
        boolean full = mode == 1;
        boolean shortStats = mode == 2;
        boolean showStats = full || shortStats;  // this means show the router infos
        Hash us = _context.routerHash();
        
        StringBuilder buf = new StringBuilder(8192);
        if (showStats) {
            RouterInfo ourInfo = _context.router().getRouterInfo();
            renderRouterInfo(buf, ourInfo, true, true);
            out.write(buf.toString());
            buf.setLength(0);
        }
        
        ObjectCounter<String> versions = new ObjectCounter<String>();
        ObjectCounter<String> countries = new ObjectCounter<String>();
        int[] transportCount = new int[TNAMES.length];
        
        Set<RouterInfo> routers = new TreeSet<RouterInfo>(new RouterInfoComparator());
        routers.addAll(_context.netDb().getRouters());
        for (RouterInfo ri : routers) {
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
            
     //
     // don't bother to reindent
     //
     if (!showStats) {

        // the summary table
        buf.append("<table border=\"0\" cellspacing=\"30\"><tr><th colspan=\"3\">")
           .append(_("Network Database Router Statistics"))
           .append("</th></tr><tr><td style=\"vertical-align: top;\">");
        // versions table
        List<String> versionList = new ArrayList<String>(versions.objects());
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
        for (int i = 0; i < TNAMES.length; i++) {
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
        List<String> countryList = new ArrayList<String>(countries.objects());
        if (!countryList.isEmpty()) {
            Collections.sort(countryList, new CountryComparator());
            buf.append("<table>\n");
            buf.append("<tr><th align=\"left\">" + _("Country") + "</th><th>" + _("Count") + "</th></tr>\n");
            for (String country : countryList) {
                int num = countries.count(country);
                buf.append("<tr><td><img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append("\"");
                buf.append(" src=\"/flags.jsp?c=").append(country).append("\"> ");
                buf.append(getTranslatedCountry(country));
                buf.append("</td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
            buf.append("</table>\n");
        }

        buf.append("</td></tr></table>");

     //
     // don't bother to reindent
     //
     } // if !showStats

        out.write(buf.toString());
        out.flush();
    }
    
    /**
     * Countries now in a separate bundle
     * @param code two-letter country code
     * @since 0.9.9
     */
    private String getTranslatedCountry(String code) {
        String name = _context.commSystem().getCountryName(code);
        return Translate.getString(name, _context, Messages.COUNTRY_BUNDLE_NAME);
    }

    /**
     *  Sort by translated country name using rules for the current language setting
     *  Inner class, can't be Serializable
     */
    private class CountryComparator implements Comparator<String> {
         private static final long serialVersionUID = 1L;
         private final Collator coll;

         public CountryComparator() {
             super();
             coll = Collator.getInstance(new Locale(Messages.getLanguage(_context)));
         }

         public int compare(String l, String r) {
             return coll.compare(getTranslatedCountry(l),
                                 getTranslatedCountry(r));
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
            if (!full) {
                buf.append("[<a href=\"netdb?r=").append(hash.substring(0, 6)).append("\" >").append(_("Full entry")).append("</a>]");
            }
            buf.append("</th></tr><tr><td>\n");
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
        buf.append("<b>").append(_("Signing Key")).append(":</b> ")
           .append(info.getIdentity().getSigningPublicKey().getType().toString());
        buf.append("<br>\n<b>" + _("Address(es)") + ":</b> ");
        String country = _context.commSystem().getCountry(info.getIdentity().getHash());
        if(country != null) {
            buf.append("<img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append('\"');
            buf.append(" title=\"").append(getTranslatedCountry(country)).append('\"');
            buf.append(" src=\"/flags.jsp?c=").append(country).append("\"> ");
        }
        for (RouterAddress addr : info.getAddresses()) {
            String style = addr.getTransportStyle();
            buf.append("<b>").append(DataHelper.stripHTML(style)).append(":</b> ");
            int cost = addr.getCost();
            if (!((style.equals("SSU") && cost == 5) || (style.equals("NTCP") && cost == 10)))
                buf.append('[').append(_("cost")).append('=').append("" + cost).append("] ");
            Map<Object, Object> p = addr.getOptionsMap();
            for (Map.Entry<Object, Object> e : p.entrySet()) {
                String name = (String) e.getKey();
                String val = (String) e.getValue();
                buf.append('[').append(_(DataHelper.stripHTML(name))).append('=').append(DataHelper.stripHTML(val)).append("] ");
            }
        }
        buf.append("</td></tr>\n");
        if (full) {
            buf.append("<tr><td>" + _("Stats") + ": <br><code>");
            Map<Object, Object> p = info.getOptionsMap();
            for (Map.Entry<Object, Object> e : p.entrySet()) {
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
    private static final int IPV6 = 8;
    private static final String[] TNAMES = { _x("Hidden or starting up"), _x("SSU"), _x("SSU with introducers"), "",
                                  _x("NTCP"), _x("NTCP and SSU"), _x("NTCP and SSU with introducers"), "",
                                  "", _x("IPv6 SSU"), _x("IPv6 Only SSU, introducers"), _x("IPv6 SSU, introducers"),
                                  _x("IPv6 NTCP"), _x("IPv6 NTCP, SSU"), _x("IPv6 Only NTCP, SSU, introducers"), _x("IPv6 NTCP, SSU, introducers") };
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
            String host = addr.getHost();
            if (host != null && host.contains(":"))
                rv |= IPV6;

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
