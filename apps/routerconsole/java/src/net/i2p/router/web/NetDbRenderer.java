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
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;

class NetDbRenderer {
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
             return HashDistance.getDistance(_us, l.getRoutingKey()).compareTo(HashDistance.getDistance(_us, r.getRoutingKey()));
        }
    }

    private static class RouterInfoComparator implements Comparator<RouterInfo>, Serializable {
         public int compare(RouterInfo l, RouterInfo r) {
             return l.getIdentity().getHash().toBase64().compareTo(r.getIdentity().getHash().toBase64());
        }
    }

    /**
     *  One String must be non-null
     *
     *  @param routerPrefix may be null. "." for our router only
     *  @param version may be null
     *  @param country may be null
     */
    public void renderRouterInfoHTML(Writer out, String routerPrefix, String version, String country) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        if (".".equals(routerPrefix)) {
            renderRouterInfo(buf, _context.router().getRouterInfo(), true, true);
        } else {
            boolean notFound = true;
            Set<RouterInfo> routers = _context.netDb().getRouters();
            for (RouterInfo ri : routers) {
                Hash key = ri.getIdentity().getHash();
                if ((routerPrefix != null && key.toBase64().startsWith(routerPrefix)) ||
                    (version != null && version.equals(ri.getVersion())) ||
                    (country != null && country.equals(_context.commSystem().getCountry(key)))) {
                    renderRouterInfo(buf, ri, false, true);
                    notFound = false;
                }
            }
            if (notFound) {
                buf.append(_t("Router")).append(' ');
                if (routerPrefix != null)
                    buf.append(routerPrefix);
                else if (version != null)
                    buf.append(version);
                else if (country != null)
                    buf.append(country);
                buf.append(' ').append(_t("not found in network database"));
            }
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
            buf.append("<p id=\"debugmode\">Debug mode - Sorted by hash distance, closest first</p>\n");
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


        // Summary
        FloodfillNetworkDatabaseFacade netdb = (FloodfillNetworkDatabaseFacade)_context.netDb();
        if (debug) {
            buf.append("<table id=\"leasesetdebug\">\n");
        } else {
            buf.append("<table id=\"leasesetsummary\">\n");
        }
        buf.append("<tr><th colspan=\"3\">Leaseset Summary</th>")
           .append("<th><a href=\"/configadvanced\" title=\"").append(_t("Manually Configure Floodfill Participation")).append("\">[")
           .append(_t("Configure Floodfill Participation"))
           .append("]</a></th></tr>\n")
           .append("<tr><td><b>Total Leasesets:</b></td><td colspan=\"3\">").append(leases.size()).append("</td></tr>\n");
        if (debug) {
            buf.append("<tr><td><b>Published (RAP) Leasesets:</b></td><td colspan=\"3\">").append(netdb.getKnownLeaseSets()).append("</td></tr>\n")
               .append("<tr><td><b>Mod Data:</b></td><td>").append(DataHelper.getUTF8(_context.routerKeyGenerator().getModData())).append("</td>")
               .append("<td><b>Last Changed:</b></td><td>").append(new Date(_context.routerKeyGenerator().getLastChanged())).append("</td></tr>\n")
               .append("<tr><td><b>Next Mod Data:</b></td><td>").append(DataHelper.getUTF8(_context.routerKeyGenerator().getNextModData())).append("</td>")
               .append("<td><b>Change in:</b></td><td>").append(DataHelper.formatDuration(_context.routerKeyGenerator().getTimeTillMidnight())).append("</td></tr>\n");
        }
        int ff = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL).size();
        buf.append("<tr><td><b>Known Floodfills:</b></td><td colspan=\"3\">").append(ff).append("</td></tr>\n")
           .append("<tr><td><b>Currently Floodfill?</b></td><td colspan=\"3\">").append(netdb.floodfillEnabled() ? "yes" : "no").append("</td></tr>\n");
        if (debug) {
            buf.append("<tr><td><b>Network data (only valid if floodfill):</b></td><td colspan=\"3\">");
            //buf.append("</b></p><p><b>Center of Key Space (router hash): " + ourRKey.toBase64());
            if (median != null) {
                double log2 = biLog2(median);
                buf.append("</td></tr>")
                   .append("<tr><td><b>Median distance (bits):</b></td><td colspan=\"3\">").append(fmt.format(log2)).append("</td></tr>\n");
                // 2 for 4 floodfills... -1 for median
                // this can be way off for unknown reasons
                int total = (int) Math.round(Math.pow(2, 2 + 256 - 1 - log2));
                buf.append("<tr><td><b>Estimated total floodfills:</b></td><td colspan=\"3\">").append(total).append("</td></tr>\n");
                buf.append("<tr><td><b>Estimated total leasesets:</b></td><td colspan=\"3\">").append(total * rapCount / 4);
            } else {
                buf.append("<i>Not floodfill or no data.</i>");
            }
            buf.append("</td></tr>\n");
        }
        buf.append("</table>\n");

        if (leases.isEmpty()) {
          if (!debug)
              buf.append("<div id=\"noleasesets\"><i>").append(_t("No Leasesets currently active.")).append("</i></div>");
        } else {
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
            buf.append("<table class=\"leaseset\">\n")
               .append("<tr><th><b>").append(_t("LeaseSet")).append(":</b> ").append(key.toBase64()).append("</th>");
            if (_context.clientManager().isLocal(dest)) {
                buf.append("<th><b><a href=\"tunnels#" + key.toBase64().substring(0,4) + "\">" + _t("Local") + "</a> ");
                if (! _context.clientManager().shouldPublishLeaseSet(key))
                    buf.append(_t("Unpublished") + ' ');
                buf.append("<b>").append(_t("Destination")).append(":</b> ");
                TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(key);
                if (in != null && in.getDestinationNickname() != null)
                    buf.append(in.getDestinationNickname());
                else
                    buf.append(dest.toBase64().substring(0, 6));
                buf.append("</th></tr>\n<tr><td>");
                String b32 = dest.toBase32();
                buf.append("<a href=\"http://").append(b32).append("\">").append(b32).append("</a></td>");
                String host = _context.namingService().reverseLookup(dest);
                if (host == null) {
                    buf.append("<td>").append("<a title=\"").append(_t("Add to addressbook"))
                       .append("\" href=\"/susidns/addressbook.jsp?book=private&amp;destination=")
                       .append(dest.toBase64()).append("#add\">").append(_t("Add to local addressbook")).append("</a></td>");
                }
            } else {
                buf.append("<th><b>").append(_t("Destination")).append(":</b> ");
                String host = _context.namingService().reverseLookup(dest);
                if (host != null) {
                    buf.append("<a href=\"http://").append(host).append("/\">").append(host).append("</a></th>");
                } else {
                    String b32 = dest.toBase32();
                    buf.append("<code>").append(dest.toBase64().substring(0, 6)).append("</code></th>")
                       .append("</tr>\n<tr>")
                       .append("<td><a href=\"http://").append(b32).append("\">").append(b32).append("</a></td>\n")
                       .append("<td><a title=\"").append(_t("Add to addressbook"))
                       .append("\" href=\"/susidns/addressbook.jsp?book=private&amp;destination=")
                       .append(dest.toBase64()).append("#add\">").append(_t("Add to local addressbook")).append("</a></td>");
                }
            }
            buf.append("</tr>\n<tr><td colspan=\"2\">\n");
            long exp = ls.getLatestLeaseDate()-now;
            if (exp > 0)
                buf.append("<b>").append(_t("Expires in {0}", DataHelper.formatDuration2(exp))).append("</b>");
            else
                buf.append("<b>").append(_t("Expired {0} ago", DataHelper.formatDuration2(0-exp))).append("</b>");
            buf.append("</td></tr>\n");
            if (debug) {
                buf.append("<tr><td colspan=\"2\">");
                buf.append("<b>RAP?</b> ").append(ls.getReceivedAsPublished());
                buf.append(" <b>RAR?</b> ").append(ls.getReceivedAsReply());
                BigInteger dist = HashDistance.getDistance(ourRKey, ls.getRoutingKey());
                if (ls.getReceivedAsPublished()) {
                    if (c++ == medianCount)
                        median = dist;
                }
                buf.append(" <b>Distance: </b><span id=\"distance\">").append(fmt.format(biLog2(dist))).append("</span></b>");
                buf.append("</td></tr>\n<tr><td colspan=\"2\">");
                //buf.append(dest.toBase32()).append("<br>");
                buf.append("<b>Signature type:</b> ").append(dest.getSigningPublicKey().getType());
                buf.append(" <b>Encryption Key:</b> ").append(ls.getEncryptionKey().toBase64().substring(0, 20)).append("&hellip;");
                buf.append("</td></tr>\n<tr><td colspan=\"2\">");
                buf.append("<b>Routing Key:</b> ").append(ls.getRoutingKey().toBase64());
                buf.append("</td></tr>");

            }
            for (int i = 0; i < ls.getLeaseCount(); i++) {
                Lease lease = ls.getLease(i);
                buf.append("<tr><td colspan=\"2\">");
                buf.append("<b>").append(_t("Lease")).append(' ').append(i + 1).append(":</b> ").append(_t("Gateway")).append(' ');
                buf.append(_context.commSystem().renderPeerHTML(lease.getGateway()));
                buf.append(' ').append(_t("Tunnel")).append(' ').append(lease.getTunnelId().getTunnelId()).append(' ');
                if (debug) {
                    long exl = lease.getEndDate().getTime() - now;
                    if (exl > 0)
                        buf.append("<b>").append(_t("Expires in {0}", DataHelper.formatDuration2(exl))).append("</b>");
                    else
                        buf.append("<b>").append(_t("Expired {0} ago", DataHelper.formatDuration2(0-exl))).append("</b>");
                }
                buf.append("</td></tr>\n");
            }
            buf.append("</table>\n");
            out.write(buf.toString());
            buf.setLength(0);
          } // for each
        }  // !empty
        out.write(buf.toString());
        out.flush();
    }

    /**
     * For debugging
     * http://forums.sun.com/thread.jspa?threadID=597652
     * @since 0.7.14
     */
    public static double biLog2(BigInteger a) {
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
            out.write(_t("Not initialized"));
            out.flush();
            return;
        }
        Log log = _context.logManager().getLog(NetDbRenderer.class);
        long start = System.currentTimeMillis();
        
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
        long end = System.currentTimeMillis();
        if (log.shouldWarn())
            log.warn("part 1 took " + (end - start));
        start = end;
            
     //
     // don't bother to reindent
     //
     if (!showStats) {

        // the summary table
        buf.append("<table id=\"netdboverview\" border=\"0\" cellspacing=\"30\"><tr><th colspan=\"3\">")
           .append(_t("Network Database Router Statistics"))
           .append("</th></tr><tr><td style=\"vertical-align: top;\">");
        // versions table
        List<String> versionList = new ArrayList<String>(versions.objects());
        if (!versionList.isEmpty()) {
            Collections.sort(versionList, Collections.reverseOrder(new VersionComparator()));
            buf.append("<table id=\"netdbversions\">\n");
            buf.append("<tr><th>" + _t("Version") + "</th><th>" + _t("Count") + "</th></tr>\n");
            for (String routerVersion : versionList) {
                int num = versions.count(routerVersion);
                String ver = DataHelper.stripHTML(routerVersion);
                buf.append("<tr><td align=\"center\"><a href=\"/netdb?v=").append(ver).append("\">").append(ver);
                buf.append("</a></td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
            buf.append("</table>\n");
        }
        buf.append("</td><td style=\"vertical-align: top;\">");
        out.write(buf.toString());
        buf.setLength(0);
        end = System.currentTimeMillis();
        if (log.shouldWarn())
            log.warn("part 2 took " + (end - start));
        start = end;
            
        // transports table
        buf.append("<table id=\"netdbtransports\">\n");
        buf.append("<tr><th align=\"left\">" + _t("Transports") + "</th><th>" + _t("Count") + "</th></tr>\n");
        for (int i = 0; i < TNAMES.length; i++) {
            int num = transportCount[i];
            if (num > 0) {
                buf.append("<tr><td>").append(_t(TNAMES[i]));
                buf.append("</td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
        }
        buf.append("</table>\n");
        buf.append("</td><td style=\"vertical-align: top;\">");
        out.write(buf.toString());
        buf.setLength(0);
        end = System.currentTimeMillis();
        if (log.shouldWarn())
            log.warn("part 3 took " + (end - start));
        start = end;

        // country table
        List<String> countryList = new ArrayList<String>(countries.objects());
        if (!countryList.isEmpty()) {
            Collections.sort(countryList, new CountryComparator());
            buf.append("<table id=\"netdbcountrylist\">\n");
            buf.append("<tr><th align=\"left\">" + _t("Country") + "</th><th>" + _t("Count") + "</th></tr>\n");
            for (String country : countryList) {
                int num = countries.count(country);
                buf.append("<tr><td><img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append("\"");
                buf.append(" src=\"/flags.jsp?c=").append(country).append("\"> <a href=\"/netdb?c=").append(country).append("\">");
                buf.append(getTranslatedCountry(country));
                buf.append("</a></td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
            buf.append("</table>\n");
        }

        buf.append("</td></tr></table>");
        end = System.currentTimeMillis();
        if (log.shouldWarn())
            log.warn("part 4 took " + (end - start));
        start = end;

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
        buf.append("<table class=\"netdbentry\">")
           .append("<tr><th colspan=\"2\"><a name=\"").append(hash.substring(0, 6)).append("\" ></a>");
        if (isUs) {
            buf.append("<a name=\"our-info\" ></a><b>" + _t("Our info") + ":</b>&nbsp;<code>").append(hash).append("</code></th><th>");
        } else {
            buf.append("<b>" + _t("Peer info for") + ":</b>&nbsp;<code>").append(hash).append("</code></th><th>");
            if (!full) {
                buf.append("<a title=\"").append(_t("View extended router info"))
                   .append("\" class=\"viewfullentry\" href=\"netdb?r=").append(hash.substring(0, 6))
                   .append("\" >[").append(_t("Full entry")).append("]</a>");
            }
        }
        buf.append("</th></tr>\n<tr>");
        long age = _context.clock().now() - info.getPublished();
        if (isUs && _context.router().isHidden()) {
            buf.append("<td><b>").append(_t("Hidden")).append(", ").append(_t("Updated")).append(":</b></td>")
               .append("<td colspan=\"2\">")
               .append(_t("{0} ago", DataHelper.formatDuration2(age)))
               .append("</td>");
        } else if (age > 0) {
            buf.append("<td><b>").append(_t("Published")).append(":</b></td>")
               .append("<td colspan=\"2\">")
               .append(_t("{0} ago", DataHelper.formatDuration2(age)))
               .append("</td>");
        } else {
            // shouldnt happen
            buf.append("<td colspan=\"2\"><b>").append(_t("Published")).append(":</b> in ").append(DataHelper.formatDuration2(0-age)).append("???</td>");
        }
        buf.append("</tr>\n<tr><td>");
        buf.append("<b>").append(_t("Signing Key")).append(":</b> ")
           .append("</td><td colspan=\"2\">")
           .append(info.getIdentity().getSigningPublicKey().getType().toString());
        buf.append("</td></tr>\n<tr>")
           .append("<td><b>" + _t("Address(es)") + ":</b></td>")
           .append("<td colspan=\"2\">");
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
                buf.append('[').append(_t("cost")).append('=').append("" + cost).append("] ");
            Map<Object, Object> p = addr.getOptionsMap();
            for (Map.Entry<Object, Object> e : p.entrySet()) {
                String name = (String) e.getKey();
                String val = (String) e.getValue();
                buf.append('[').append(_t(DataHelper.stripHTML(name))).append('=').append(DataHelper.stripHTML(val)).append("] ");
            }
        }
        buf.append("</td></tr>\n");
        if (full) {
            buf.append("<tr><td><b>" + _t("Stats") + ":</b><td colspan=\"2\"><code>");
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
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /** tag only */
    private static final String _x(String s) {
        return s;
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _t(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    private String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }
}
