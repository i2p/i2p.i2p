package net.i2p.router.web.helpers;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.math.BigInteger;         // debug
import java.text.Collator;
import java.text.DecimalFormat;      // debug
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.PublicKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.JobImpl;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.util.HashDistance;   // debug
import static net.i2p.router.sybil.Util.biLog2;
import net.i2p.router.transport.GeoIP;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.router.web.WebAppStarter;
import net.i2p.util.Addresses;
import net.i2p.util.ConvertToHash;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounterUnsafe;
import net.i2p.util.SystemVersion;
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
             Hash dl = l.getHash();
             Hash dr = r.getHash();
             boolean locall = _context.clientManager().isLocal(dl);
             boolean localr = _context.clientManager().isLocal(dr);
             if (locall && !localr) return -1;
             if (localr && !locall) return 1;
             return dl.toBase32().compareTo(dr.toBase32());
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

    /**
     *  At least one String must be non-null, non-empty
     *
     *  @param page zero-based
     *  @param routerPrefix may be null. "." for our router only
     *  @param version may be null
     *  @param country may be null
     *  @param family may be null
     *  @param highPort if nonzero, a range from port to highPort inclusive
     */
    public void renderRouterInfoHTML(Writer out, int pageSize, int page,
                                     String routerPrefix, String version,
                                     String country, String family, String caps,
                                     String ip, String sybil, int port, int highPort, SigType type, EncType etype,
                                     String mtu, String ipv6, String ssucaps,
                                     String tr, int cost, int icount) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        List<Hash> sybils = sybil != null ? new ArrayList<Hash>(128) : null;
        NetworkDatabaseFacade netdb = _context.netDb();

        if (".".equals(routerPrefix)) {
            buf.append("<table><tr><td class=\"infohelp\">")
               .append(_t("Never reveal your router identity to anyone, as it is uniquely linked to your IP address in the network database."))
               .append("</td></tr></table>");
            renderRouterInfo(buf, _context.router().getRouterInfo(), true, true);
        } else if (routerPrefix != null && routerPrefix.length() >= 44) {
            // easy way, full hash
            byte[] h = Base64.decode(routerPrefix);
            if (h != null && h.length == Hash.HASH_LENGTH) {
                Hash hash = new Hash(h);
                RouterInfo ri = (RouterInfo) netdb.lookupLocallyWithoutValidation(hash);
                boolean banned = false;
                if (ri == null) {
                    banned = _context.banlist().isBanlisted(hash);
                    if (!banned) {
                        // remote lookup
                        LookupWaiter lw = new LookupWaiter();
                        netdb.lookupRouterInfo(hash, lw, lw, 8*1000);
                        // just wait right here in the middle of the rendering, sure
                        synchronized(lw) {
                            try { lw.wait(9*1000); } catch (InterruptedException ie) {}
                        }
                        ri = (RouterInfo) netdb.lookupLocallyWithoutValidation(hash);
                    }
                }
                if (ri != null) {
                   renderRouterInfo(buf, ri, false, true);
                } else {
                    buf.append("<div class=\"netdbnotfound\">");
                    buf.append(_t("Router")).append(' ');
                    buf.append(routerPrefix);
                    buf.append(' ').append(banned ? "is banned" : _t("not found in network database"));
                    buf.append("</div>");
                }
            } else {
                buf.append("<div class=\"netdbnotfound\">");
                buf.append("Bad Base64 router hash").append(' ');
                buf.append(DataHelper.escapeHTML(routerPrefix));
                buf.append("</div>");
            }
        } else {
            StringBuilder ubuf = new StringBuilder();
            if (routerPrefix != null)
                ubuf.append("&amp;r=").append(routerPrefix);
            if (version != null)
                ubuf.append("&amp;v=").append(version);
            if (country != null)
                ubuf.append("&amp;c=").append(country);
            if (family != null)
                ubuf.append("&amp;fam=").append(family);
            if (caps != null)
                ubuf.append("&amp;caps=").append(caps);
            if (tr != null)
                ubuf.append("&amp;tr=").append(tr);
            if (type != null)
                ubuf.append("&amp;type=").append(type);
            if (etype != null)
                ubuf.append("&amp;etype=").append(etype);
            if (ip != null)
                ubuf.append("&amp;ip=").append(ip);
            if (port != 0)
                ubuf.append("&amp;port=").append(port);
            if (mtu != null)
                ubuf.append("&amp;mtu=").append(mtu);
            if (ipv6 != null)
                ubuf.append("&amp;ipv6=").append(ipv6);
            if (ssucaps != null)
                ubuf.append("&amp;ssucaps=").append(ssucaps);
            if (cost != 0)
                ubuf.append("&amp;cost=").append(cost);
            if (sybil != null)
                ubuf.append("&amp;sybil=").append(sybil);
            String itag;
            if (icount > 0) {
                ubuf.append("&amp;i=").append(icount);
                itag = "itag" + (icount - 1);
            } else {
                itag = null;
            }
            Set<RouterInfo> routers = new HashSet<RouterInfo>();
            routers.addAll(_context.netDb().getRouters());
            int ipMode = 0;
            String ipArg = ip;  // save for error message
            String altIPv6 = null;
            if (ip != null) {
                if (ip.endsWith("/24")) {
                    ipMode = 1;
                } else if (ip.endsWith("/16")) {
                    ipMode = 2;
                } else if (ip.endsWith("/8")) {
                    ipMode = 3;
                } else if (ip.indexOf(':') > 0) {
                    ipMode = 4;
                    if (ip.endsWith("::")) {
                        // truncate for prefix search
                        ip = ip.substring(0, ip.length() - 1);
                    } else {
                        // We don't canonicalize as we search, so create alt string to check also
                        altIPv6 = getAltIPv6(ip);
                    }
                }
                if (ipMode > 0 && ipMode < 4) {
                    for (int i = 0; i < ipMode; i++) {
                        int last = ip.substring(0, ip.length() - 1).lastIndexOf('.');
                        if (last > 0)
                            ip = ip.substring(0, last + 1);
                    }
                }
            }
            if (ipv6 != null) {
                if (ipv6.endsWith("::")) {
                    // truncate for prefix search
                    ipv6 = ipv6.substring(0, ipv6.length() - 1);
                } else {
                    // We don't canonicalize as we search, so create alt string to check also
                    altIPv6 = getAltIPv6(ipv6);
                }
            }
            String familyArg = family;  // save for error message
            if (family != null)
                family = family.toLowerCase(Locale.US);

            if (routerPrefix != null && !routers.isEmpty())
                filterHashPrefix(routers, routerPrefix);
            if (version != null && !routers.isEmpty())
                filterVersion(routers, version);
            if (country != null && !routers.isEmpty())
                filterCountry(routers, country);
            if (caps != null && !routers.isEmpty())
                filterCaps(routers, caps);
            if (type != null && !routers.isEmpty())
                filterSigType(routers, type);
            if (etype != null && !routers.isEmpty())
                filterEncType(routers, etype);
            if (tr != null && !routers.isEmpty())
                filterTransport(routers, tr);
            if (family != null && !routers.isEmpty())
                filterFamily(routers, family);
            if (ip != null && !routers.isEmpty()) {
                if (ipMode == 0)
                    filterIP(routers, ip);
                else
                    filterIP(routers, ip, altIPv6);
            }
            if (port != 0 && !routers.isEmpty())
                filterPort(routers, port, highPort);
            if (mtu != null && !routers.isEmpty())
                filterMTU(routers, mtu);
            if (ipv6 != null && !routers.isEmpty())
                filterIP(routers, ipv6, altIPv6);
            if (ssucaps != null && !routers.isEmpty())
                filterSSUCaps(routers, ssucaps);
            if (cost != 0 && !routers.isEmpty())
                filterCost(routers, cost);
            if (itag != null && !routers.isEmpty())
                filterITag(routers, itag);


            if (routers.isEmpty()) {
                buf.append("<div class=\"netdbnotfound\">");
                buf.append(_t("Router")).append(' ');
                if (routerPrefix != null)
                    buf.append(routerPrefix).append(' ');
                if (version != null)
                    buf.append(_t("Version")).append(' ').append(version).append(' ');
                if (country != null)
                    buf.append(_t("Country")).append(' ').append(country).append(' ');
                if (familyArg != null)
                    buf.append(_t("Family")).append(' ').append(familyArg).append(' ');
                if (ipArg != null)
                    buf.append("IP ").append(ipArg).append(' ');
                if (ipv6 != null)
                    buf.append("IP ").append(ipv6).append(' ');
                if (port != 0) {
                    buf.append(_t("Port")).append(' ').append(port);
                    if (highPort != 0)
                        buf.append('-').append(highPort);
                    buf.append(' ');
                }
                if (mtu != null)
                    buf.append(_t("MTU")).append(' ').append(mtu).append(' ');
                if (cost != 0)
                    buf.append("Cost ").append(cost).append(' ');
                if (type != null)
                    buf.append(_t("Type")).append(' ').append(type).append(' ');
                if (etype != null)
                    buf.append(_t("Type")).append(' ').append(etype).append(' ');
                if (caps != null)
                    buf.append("Caps ").append(caps).append(' ');
                if (ssucaps != null)
                    buf.append("Transport caps ").append(ssucaps).append(' ');
                if (tr != null)
                    buf.append(_t("Transport")).append(' ').append(tr).append(' ');
                if (icount > 0)
                    buf.append("with ").append(icount).append(" introducers ");
                buf.append(_t("not found in network database"));
                buf.append("</div>");
            } else {
                List<RouterInfo> results = new ArrayList<RouterInfo>(routers);
                int sz = results.size();
                if (sz > 1)
                    Collections.sort(results, RouterInfoComparator.getInstance());
                boolean morePages = false;
                int toSkip = pageSize * page;
                int last = Math.min(toSkip + pageSize, sz - 1);
                if (last < sz - 1)
                    morePages = true;
                if (page > 0 || morePages)
                    outputPageLinks(buf, ubuf, page, pageSize, morePages);
                for (int i = toSkip; i <= last; i++) {
                    RouterInfo ri = results.get(i);
                    renderRouterInfo(buf, ri, false, true);
                    if (sybil != null)
                        sybils.add(ri.getIdentity().getHash());
                    if ((i & 0x07) == 0) {
                        out.append(buf);
                        buf.setLength(0);
                    }
                }
                if (page > 0 || morePages)
                    outputPageLinks(buf, ubuf, page, pageSize, morePages);
            }
        }
        out.append(buf);
        out.flush();
        if (sybil != null)
            SybilRenderer.renderSybilHTML(out, _context, sybils, sybil);
    }

    /**
     *  @since 0.9.64 split out from above
     */
    private void outputPageLinks(StringBuilder buf, StringBuilder ubuf, int page, int pageSize, boolean morePages) {
        buf.append("<div class=\"netdbnotfound\">");
        if (page > 0) {
            buf.append("<a href=\"/netdb?pg=").append(page)
               .append("&amp;ps=").append(pageSize).append(ubuf).append("\">");
            buf.append(_t("Previous Page"));
            buf.append("</a>&nbsp;&nbsp;&nbsp;");
        }
        buf.append(_t("Page")).append(' ').append(page + 1);
        if (morePages) {
            buf.append("&nbsp;&nbsp;&nbsp;<a href=\"/netdb?pg=").append(page + 2)
               .append("&amp;ps=").append(pageSize).append(ubuf).append("\">");
            buf.append(_t("Next Page"));
            buf.append("</a>");
        }
        buf.append("</div>");
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterHashPrefix(Set<RouterInfo> routers, String routerPrefix) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            Hash key = ri.getIdentity().getHash();
            if (!key.toBase64().startsWith(routerPrefix))
                iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterVersion(Set<RouterInfo> routers, String version) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            if (!ri.getVersion().equals(version))
                iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private void filterCountry(Set<RouterInfo> routers, String country) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            Hash key = ri.getIdentity().getHash();
            if (!country.equals(_context.commSystem().getCountry(key)))
                iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterCaps(Set<RouterInfo> routers, String caps) {
        int len = caps.length();
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            String ca = ri.getCapabilities();
            for (int i = 0; i < len; i++) {
                // must contain all caps specified
                if (ca.indexOf(caps.charAt(i)) < 0) {
                    iter.remove();
                    continue outer;
                }
            }
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterSigType(Set<RouterInfo> routers, SigType type) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            if (ri.getIdentity().getSigType() != type)
                iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterEncType(Set<RouterInfo> routers, EncType type) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            if (ri.getIdentity().getEncType() != type)
                iter.remove();
        }
    }


    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterTransport(Set<RouterInfo> routers, String tr) {
        String transport;
        int mode;
        if (tr.equals("NTCP_1")) {
            transport = "NTCP";
            mode = 0;
        } else if (tr.equals("NTCP_2")) {
            transport = "NTCP";
            mode = 1;
        } else if (tr.equals("SSU_1")) {
            transport = "SSU";
            mode = 2;
        } else if (tr.equals("SSU_2")) {
            transport = "SSU";
            mode = 3;
        } else {
            transport = tr;
            mode = 4;
        }
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            RouterAddress ra = ri.getTargetAddress(transport);
            if (ra != null) {
                switch (mode) {
                    case 0:
                    case 2:
                        if (ra.getOption("v") == null)
                            continue;
                        break;

                    case 1:
                    case 3:
                        if (ra.getOption("v") != null)
                            continue;
                        break;

                    case 4:
                        continue;

                }
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterFamily(Set<RouterInfo> routers, String family) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            String fam = ri.getOption("family");
            if (fam != null) {
                if (fam.toLowerCase(Locale.US).contains(family))
                    continue;
            }
            iter.remove();
        }
    }

    /**
     *  Exact match
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterIP(Set<RouterInfo> routers, String ip) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                if (ip.equals(ra.getHost()))
                    continue outer;
            }
            iter.remove();
        }
    }

    /**
     *  Prefix
     *  Remove all non-matching from routers
     *  @param altip may be null
     *  @since 0.9.64 split out from above
     */
    private static void filterIP(Set<RouterInfo> routers, String ip, String altip) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                String host = ra.getHost();
                if (host != null &&
                    (host.startsWith(ip) || (altip != null && host.startsWith(altip))))
                    continue outer;
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterPort(Set<RouterInfo> routers, int port, int highPort) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                int raport = ra.getPort();
                if (port == raport ||
                    (highPort > 0 && raport >= port && raport <= highPort)) {
                    continue outer;
                }
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterMTU(Set<RouterInfo> routers, String smtu) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                if (smtu.equals(ra.getOption("mtu")))
                    continue outer;
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterSSUCaps(Set<RouterInfo> routers, String caps) {
        int len = caps.length();
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            inner:
            for (RouterAddress ra : ri.getAddresses()) {
                String ca = ra.getOption("caps");
                if (ca == null)
                    continue;
                for (int i = 0; i < len; i++) {
                    // must contain all caps specified
                    if (ca.indexOf(caps.charAt(i)) < 0)
                        break inner;
                }
                continue outer;
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterCost(Set<RouterInfo> routers, int cost) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                if (ra.getCost() == cost)
                    continue outer;
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterITag(Set<RouterInfo> routers, String itag) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                if (ra.getOption(itag) != null)
                    continue outer;
            }
            iter.remove();
        }
    }

    /**
     *  @since 0.9.48
     */
    private class LookupWaiter extends JobImpl {
        public LookupWaiter() { super(_context); }
        public void runJob() {
            synchronized(this) {
                notifyAll();
            }
        }
        public String getName() { return "Console netdb lookup"; }
    }

    /**
     *  Special handling for 'O' cap
     *  @param caps non-null
     *  @since 0.9.38
     */
    private static boolean hasCap(RouterInfo ri, String caps) {
        String ricaps = ri.getCapabilities();
        if (caps.equals("O")) {
            return ricaps.contains(caps) &&
                   !ricaps.contains("P") &&
                   !ricaps.contains("X");
        } else {
            return ricaps.contains(caps);
        }
    }

    /**
     *  All the leasesets
     *
     *  @param debug @since 0.7.14 sort by distance from us, display
     *               median distance, and other stuff, useful when floodfill
     *  @param client null for main db; non-null for client db
     */
    public void renderLeaseSetHTML(Writer out, boolean debug, Hash client) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        if (debug)
            buf.append("<p id=\"debugmode\">Debug mode - Sorted by hash distance, closest first</p>\n");
        Hash ourRKey;
        Set<LeaseSet> leases;
        DecimalFormat fmt;
        NetworkDatabaseFacade netdb;
        if (client == null)
            netdb = _context.netDb();
        else
            netdb = _context.clientNetDb(client);
        if (debug) {
            ourRKey = _context.routerHash();
            leases = new TreeSet<LeaseSet>(new LeaseSetRoutingKeyComparator(ourRKey));
            fmt = new DecimalFormat("#0.00");
        } else {
            ourRKey = null;
            leases = new TreeSet<LeaseSet>(new LeaseSetComparator());
            fmt = null;
        }
        leases.addAll(netdb.getLeases());
        LeaseSet myLeaseSet = new LeaseSet();
        if (leases.size() > 0)
            if (client != null)
                myLeaseSet = netdb.lookupLeaseSetLocally(client);
        int medianCount = 0;
        int rapCount = 0;
        BigInteger median = null;
        int c = 0;
        boolean linkSusi = _context.portMapper().isRegistered("susidns");
        long now = _context.clock().now();

        // Summary
        if (debug) {
            buf.append("<table id=\"leasesetdebug\">\n");
        } else {
            buf.append("<table id=\"leasesetsummary\">\n");
        }
        if (client != null) {
            buf.append("<tr><th colspan=\"3\">").append(_t("Leasesets for Client")).append(": ");
            buf.append(client.toBase32());
            if (leases.size() > 0) {
                TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(myLeaseSet.getHash());
                if (in != null && in.getDestinationNickname() != null)
                    buf.append("  -  ").append(DataHelper.escapeHTML(in.getDestinationNickname()));
                buf.append("</th><th></th></tr>\n" +
                           "<tr><td colspan=\"3\">\n");
                renderLeaseSet(buf, myLeaseSet, true, now, linkSusi, null);
                buf.append("</td></tr>\n");
            }
            buf.append("<tr><td><b>Total Known Remote Leasesets:</b></td><td colspan=\"3\">").append(Math.max(leases.size()-1, 0)).append("</td></tr>\n");
        } else {
            buf.append("<tr><th colspan=\"3\">Leaseset Summary for Floodfill</th>" +
                       "<th><a href=\"/configadvanced\" title=\"").append(_t("Manually Configure Floodfill Participation")).append("\">[")
               .append(_t("Configure Floodfill Participation"))
               .append("]</a></th></tr>\n");
               buf.append("<tr><td><b>Total Known Leasesets:</b></td><td colspan=\"3\">").append(leases.size()).append("</td></tr>\n");
        }

        if (debug) {
            RouterKeyGenerator gen = _context.routerKeyGenerator();
            buf.append("<tr><td><b>Published (RAP) Leasesets:</b></td><td colspan=\"3\">").append(netdb.getKnownLeaseSets()).append("</td></tr>\n")
               .append("<tr><td><b>Mod Data:</b></td><td>").append(DataHelper.getUTF8(gen.getModData())).append("</td>")
               .append("<td><b>Last Changed:</b></td><td>").append(DataHelper.formatTime(gen.getLastChanged())).append("</td></tr>\n")
               .append("<tr><td><b>Next Mod Data:</b></td><td>").append(DataHelper.getUTF8(gen.getNextModData())).append("</td>")
               .append("<td><b>Change in:</b></td><td>").append(DataHelper.formatDuration(gen.getTimeTillMidnight())).append("</td></tr>\n");
        }
        if (client == null) {
            int ff = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL).size();
            buf.append("<tr><td><b>Known Floodfills:</b></td><td colspan=\"3\">").append(ff).append("</td></tr>\n");
            buf.append("<tr><td><b>Currently Floodfill?</b></td><td>").append(netdb.floodfillEnabled() ? "yes" : "no");
            if (debug)
                buf.append("</td><td><b>Routing Key:</b></td><td>").append(ourRKey.toBase64());
            else
                buf.append("</td><td colspan=\"2\">");
            buf.append("</td></tr>\n");
        }
        buf.append("</table>\n");

        if (leases.isEmpty()) {
            //if (!debug)
            //    buf.append("<div id=\"noleasesets\"><i>").append(_t("No Leasesets currently active.")).append("</i></div>");
        } else {
          if (debug) {
            // Find the center of the RAP leasesets
            for (LeaseSet ls : leases) {
                if (ls.getReceivedAsPublished())
                    rapCount++;
            }
            medianCount = rapCount / 2;
          }

          buf.append("<div class=\"leasesets_container\">");
          boolean ldebug = debug || client != null;
          for (LeaseSet ls : leases) {
            String distance;
            if (debug) {
                BigInteger dist = HashDistance.getDistance(ourRKey, ls.getRoutingKey());
                if (ls.getReceivedAsPublished()) {
                    if (c++ == medianCount)
                        median = dist;
                }
                distance = fmt.format(biLog2(dist));
            } else {
                distance = null;
            }
            if (!ls.getHash().equals(myLeaseSet.getHash())) {
                renderLeaseSet(buf, ls, ldebug, now, linkSusi, distance);
                out.append(buf);
                buf.setLength(0);
            }
          } // for each
          buf.append("</div>");
          if (debug) {
              buf.append("<table id=\"leasesetdebug\"><tr><td><b>Network data (only valid if floodfill):</b></td><td colspan=\"3\">");
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
              buf.append("</td></tr></table>\n");
          } // median table
        }  // !empty
        out.append(buf);
        out.flush();
    }

    /**
     * Single LeaseSet
     * @since 0.9.57
     */
    public void renderLeaseSet(Writer out, String hostname, boolean debug) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        Hash hash = ConvertToHash.getHash(hostname);
        if (hash == null) {
            buf.append("<div class=\"netdbnotfound\">");
            buf.append("Hostname not found").append(' ');
            buf.append(hostname);
            buf.append("</div>");
        } else {
            LeaseSet ls = _context.netDb().lookupLeaseSetLocally(hash);
            if (ls == null) {
                // remote lookup
                LookupWaiter lw = new LookupWaiter();
                // use-case for the exploratory netDb here?
                _context.netDb().lookupLeaseSetRemotely(hash, lw, lw, 8*1000, null);
                // just wait right here in the middle of the rendering, sure
                synchronized(lw) {
                    try { lw.wait(9*1000); } catch (InterruptedException ie) {}
                }
                ls = _context.netDb().lookupLeaseSetLocally(hash);
            }
            if (ls != null) {
                BigInteger dist = HashDistance.getDistance(_context.routerHash(), ls.getRoutingKey());
                DecimalFormat fmt = new DecimalFormat("#0.00");
                String distance = fmt.format(biLog2(dist));
                buf.append("<div class=\"leasesets_container\">");
                renderLeaseSet(buf, ls, true, _context.clock().now(), false, distance);
                buf.append("</div>");
            } else {
                buf.append("<div class=\"netdbnotfound\">");
                buf.append(_t("LeaseSet")).append(" for ");
                buf.append(hostname);
                buf.append(' ').append(_t("not found in network database"));
                buf.append("</div>");
            }
        }
        out.append(buf);
        out.flush();
    }

    /** @since 0.9.57 split out from above */
    private void renderLeaseSet(StringBuilder buf, LeaseSet ls, boolean debug, long now,
                                boolean linkSusi, String distance) {
            // warning - will be null for non-local encrypted
            Destination dest = ls.getDestination();
            Hash key = ls.getHash();
            buf.append("<table class=\"leaseset\">\n")
               .append("<tr><th><b>").append(_t("LeaseSet")).append(":</b>&nbsp;<code>").append(key.toBase64()).append("</code>");
            int type = ls.getType();
            if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2 || _context.keyRing().get(key) != null)
                buf.append(" <b>(").append(_t("Encrypted")).append(")</b>");
            buf.append("</th>");
            if (_context.clientManager().isLocal(key)) {
                buf.append("<th><a href=\"tunnels#").append(key.toBase64(), 0, 4).append("\">").append(_t("Local")).append("</a> ");
                boolean unpublished = ! _context.clientManager().shouldPublishLeaseSet(key);
                if (unpublished)
                    buf.append("<b>").append(_t("Unpublished")).append("</b> ");
                buf.append("<b>").append(_t("Destination")).append(":</b> ");
                TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(key);
                if (in != null && in.getDestinationNickname() != null)
                    buf.append(DataHelper.escapeHTML(in.getDestinationNickname()));
                else
                    buf.append(dest.toBase64(), 0, 6);
                buf.append("</th></tr>\n");
                // we don't show a b32 or addressbook links if encrypted
                if (type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                    buf.append("<tr><td");
                    // If the dest is published but not in the addressbook, an extra
                    // <td> is appended with an "Add to addressbook" link, so this
                    // <td> should not span 2 columns.
                    String host = null;
                    if (!unpublished) {
                        host = _context.namingService().reverseLookup(dest);
                    }
                    if (unpublished || host != null || !linkSusi) {
                        buf.append(" colspan=\"2\"");
                    }
                    buf.append(">");
                    String b32 = key.toBase32();
                    buf.append("<a href=\"http://").append(b32).append("\">").append(b32).append("</a></td>");
                    if (linkSusi && !unpublished && host == null) {
                        buf.append("<td class=\"addtobook\" colspan=\"2\">").append("<a title=\"").append(_t("Add to address book"))
                           .append("\" href=\"/dns?book=private&amp;destination=")
                           .append(dest.toBase64()).append("#add\">").append(_t("Add to local address book")).append("</a></td>");
                    } // else probably a client
                }
            } else {
                buf.append("<th><b>").append(_t("Destination")).append(":</b> ");
                String host = (dest != null) ? _context.namingService().reverseLookup(dest) : null;
                if (host != null) {
                    buf.append("<a href=\"http://").append(host).append("/\">").append(host).append("</a></th>");
                } else {
                    String b32 = key.toBase32();
                    buf.append("<code>");
                    if (dest != null)
                        buf.append(dest.toBase64(), 0, 6);
                    else
                        buf.append("n/a");
                    buf.append("</code></th>");
                    if (dest != null) {
                        buf.append("</tr>\n<tr><td");
                        if (!linkSusi)
                            buf.append(" colspan=\"2\"");
                        buf.append("><a href=\"http://").append(b32).append("\">").append(b32).append("</a></td>\n");
                        if (linkSusi) {
                           buf.append("<td class=\"addtobook\"><a title=\"").append(_t("Add to address book"))
                           .append("\" href=\"/dns?book=private&amp;destination=")
                           .append(dest.toBase64()).append("#add\">").append(_t("Add to local address book")).append("</a></td>");
                        }
                    }
                }
            }
            long exp;
            if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
                exp = ls.getLatestLeaseDate() - now;
            } else {
                LeaseSet2 ls2 = (LeaseSet2) ls;
                long pub = now - ls2.getPublished();
                buf.append("</tr>\n<tr><td colspan=\"2\">\n<b>")
                   .append(_t("Published {0} ago", DataHelper.formatDuration2(pub)))
                   .append("</b>");
                exp = ((LeaseSet2)ls).getExpires()-now;
            }
            buf.append("</tr>\n<tr><td colspan=\"2\">\n<b>");
            if (exp > 0)
                buf.append(_t("Expires in {0}", DataHelper.formatDuration2(exp)));
            else
                buf.append(_t("Expired {0} ago", DataHelper.formatDuration2(0-exp)));
            buf.append("</b></td></tr>\n");
            if (debug) {
                buf.append("<tr><td colspan=\"2\">");
                buf.append("<b>RAP?</b> ").append(ls.getReceivedAsPublished());
                buf.append("&nbsp;&nbsp;<b>RAR?</b> ").append(ls.getReceivedAsReply());
                buf.append("&nbsp;&nbsp;<b>Distance: </b>").append(distance);
                buf.append("&nbsp;&nbsp;<b>").append(_t("Type")).append(": </b>").append(type);
                if (dest != null && dest.isCompressible()) {
                    buf.append("&nbsp;&nbsp;<b>Compressible?</b> true");
                }
                if (type != DatabaseEntry.KEY_TYPE_LEASESET) {
                    LeaseSet2 ls2 = (LeaseSet2) ls;
                    buf.append("&nbsp;&nbsp;<b>Unpublished? </b>").append(ls2.isUnpublished());
                    if (ls2.isOffline()) {
                        buf.append("&nbsp;&nbsp;<b>Offline signed: </b>");
                        exp = ls2.getTransientExpiration() - now;
                        if (exp > 0)
                            buf.append(_t("Expires in {0}", DataHelper.formatDuration2(exp)));
                        else
                            buf.append(_t("Expired {0} ago", DataHelper.formatDuration2(0-exp)));
                        buf.append("&nbsp;&nbsp;<b>Type: </b>").append(ls2.getTransientSigningKey().getType());
                    }
                }
                buf.append("</td></tr>\n<tr><td colspan=\"2\">");
                //buf.append(dest.toBase32()).append("<br>");
                buf.append("<b>Signature type:</b> ");
                if (dest != null && type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                    buf.append(dest.getSigningPublicKey().getType());
                } else {
                    // encrypted, show blinded key type
                    buf.append(ls.getSigningKey().getType());
                }
                if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
                    buf.append("</td></tr>\n<tr><td colspan=\"2\"><b>Encryption Key:</b> ELGAMAL_2048 ")
                       .append(ls.getEncryptionKey().toBase64(), 0, 20)
                       .append("&hellip;");
                } else if (type == DatabaseEntry.KEY_TYPE_LS2) {
                    LeaseSet2 ls2 = (LeaseSet2) ls;
                    for (PublicKey pk : ls2.getEncryptionKeys()) {
                        buf.append("</td></tr>\n<tr><td colspan=\"2\"><b>Encryption Key:</b> ");
                        EncType etype = pk.getType();
                        if (etype != null)
                            buf.append(etype);
                        else
                            buf.append("Unsupported type ").append(pk.getUnknownTypeCode());
                        buf.append(' ')
                           .append(pk.toBase64(), 0, 20)
                           .append("&hellip;");
                    }
                }
                buf.append("</td></tr>\n<tr><td colspan=\"2\">");
                buf.append("<b>Routing Key:</b> ").append(ls.getRoutingKey().toBase64());
                buf.append("</td></tr>");
            }

            buf.append("\n<tr><td colspan=\"2\"><ul class=\"netdb_leases\">");
            boolean isMeta = ls.getType() == DatabaseEntry.KEY_TYPE_META_LS2;
            for (int i = 0; i < ls.getLeaseCount(); i++) {
                Lease lease = ls.getLease(i);
                buf.append("<li><b>").append(_t("Lease")).append(' ').append(i + 1).append(":</b> <span class=\"tunnel_peer\">");
                buf.append(_context.commSystem().renderPeerHTML(lease.getGateway()));
                buf.append("</span> ");
                if (!isMeta) {
                    buf.append("<span class=\"netdb_tunnel\">").append(_t("Tunnel")).append(" <span class=\"tunnel_id\">")
                       .append(lease.getTunnelId().getTunnelId()).append("</span></span> ");
                }
                if (debug) {
                    long exl = lease.getEndTime() - now;
                    buf.append("<b class=\"netdb_expiry\">");
                    if (exl > 0)
                        buf.append(_t("Expires in {0}", DataHelper.formatDuration2(exl)));
                    else
                        buf.append(_t("Expired {0} ago", DataHelper.formatDuration2(0-exl)));
                    buf.append("</b>");
                }
                buf.append("</li>");
            }
            buf.append("</ul></td></tr>\n" +
                       "</table>\n");
    }

    /**
     *  @param mode 0: charts only; 1: full routerinfos; 2: abbreviated routerinfos
     *         mode 3: Same as 0 but sort countries by count
     *         Codes greater than 16 are map codes * 16
     */
    public void renderStatusHTML(Writer out, int pageSize, int page, int mode) throws IOException {
        if (!_context.netDb().isInitialized()) {
            out.write("<div id=\"notinitialized\">");
            out.write(_t("Not initialized"));
            out.write("</div>");
            out.flush();
            return;
        }
        Log log = _context.logManager().getLog(NetDbRenderer.class);
        long start = System.currentTimeMillis();

        boolean full = mode == 1;
        boolean shortStats = mode == 2;
        boolean showStats = full || shortStats;  // this means show the router infos
        Hash us = _context.routerHash();

        Set<RouterInfo> routers = new TreeSet<RouterInfo>(RouterInfoComparator.getInstance());
        routers.addAll(_context.netDb().getRouters());
        int toSkip = pageSize * page;
        boolean nextpg = routers.size() > toSkip + pageSize;
        StringBuilder buf = new StringBuilder(8192);
        if (showStats && (page > 0 || nextpg)) {
            buf.append("<div class=\"netdbnotfound\">");
            if (page > 0) {
                buf.append("<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page)
                   .append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Previous Page"));
                buf.append("</a>&nbsp;&nbsp;&nbsp;");
            }
            buf.append(_t("Page")).append(' ').append(page + 1);
            if (nextpg) {
                buf.append("&nbsp;&nbsp;&nbsp;<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page + 2)
                   .append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Next Page"));
                buf.append("</a>");
            }
            buf.append("</div>");
        }
        if (showStats && page == 0) {
            RouterInfo ourInfo = _context.router().getRouterInfo();
            renderRouterInfo(buf, ourInfo, true, true);
            out.append(buf);
            buf.setLength(0);
        }

        ObjectCounterUnsafe<String> versions = new ObjectCounterUnsafe<String>();
        ObjectCounterUnsafe<String> countries = new ObjectCounterUnsafe<String>();
        int[] transportCount = new int[TNAMES.length];

        int skipped = 0;
        int written = 0;
        boolean morePages = false;
        for (RouterInfo ri : routers) {
            Hash key = ri.getIdentity().getHash();
            boolean isUs = key.equals(us);
            if (!isUs) {
                if (showStats) {
                    if (skipped < toSkip) {
                        skipped++;
                        continue;
                    }
                    if (written++ >= pageSize) {
                        morePages = true;
                        break;
                    }
                    renderRouterInfo(buf, ri, false, full);
                    out.append(buf);
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
        if (showStats && (page > 0 || morePages)) {
            buf.append("<div class=\"netdbnotfound\">");
            if (page > 0) {
                buf.append("<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page).append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Previous Page"));
                buf.append("</a>&nbsp;&nbsp;&nbsp;");
            }
            buf.append(_t("Page")).append(' ').append(page + 1);
            if (morePages) {
                buf.append("&nbsp;&nbsp;&nbsp;<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page + 2).append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Next Page"));
                buf.append("</a>");
            }
            buf.append("</div>");
        }
        if (log.shouldWarn()) {
            long end = System.currentTimeMillis();
            log.warn("part 1 took " + (end - start));
            start = end;
        }

     //
     // don't bother to reindent
     //
     if (!showStats) {

        // the summary table
        buf.append("<table id=\"netdboverview\" border=\"0\" cellspacing=\"30\"><tr><th colspan=\"3\">");
        buf.append(_t("Network Database Router Statistics"));
        buf.append("</th></tr>");
        if (!SystemVersion.isSlow() && !_context.commSystem().isDummy()) {
            // svg inline part 1
            out.append(buf);
            buf.setLength(0);
            buf.append("<tr><td id=\"mapcontainer\" colspan=\"3\">");
            boolean ok = embedResource(buf, "mapbase75p1.svg");
            if (ok) {
                out.append(buf);
                buf.setLength(0);
                // overlay
                MapMaker mm = new MapMaker(_context);
                out.write(mm.render(mode >> 4));
                // svg inline part 2
                embedResource(buf, "mapbase75p2.svg");
                buf.append("</td></tr>");
                out.append(buf);
            }
            buf.setLength(0);
        }
        mode &= 0x0f;
        buf.append("<tr><td style=\"vertical-align: top;\">");
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
        out.append(buf);
        buf.setLength(0);
        if (log.shouldWarn()) {
            long end = System.currentTimeMillis();
            log.warn("part 2 took " + (end - start));
            start = end;
        }

        // transports table
        boolean showTransports = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        if (showTransports) {
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
            out.append(buf);
            buf.setLength(0);
            if (log.shouldWarn()) {
                long end = System.currentTimeMillis();
                log.warn("part 3 took " + (end - start));
                start = end;
            }
        }

        // country table
        List<String> countryList = new ArrayList<String>(countries.objects());
        if (!countryList.isEmpty()) {
            if (mode == 3)
                Collections.sort(countryList, new CountryCountComparator(countries));
            else
                Collections.sort(countryList, new CountryComparator());
            buf.append("<table id=\"netdbcountrylist\">\n");
            buf.append("<tr><th align=\"left\">");
            if (mode == 3)
                buf.append("<a href=\"/netdb\" title=\"").append(_t("Sort by country")).append("\">");
            buf.append(_t("Country"));
            if (mode == 3)
                buf.append("</a>");
            buf.append("</th><th>");
            if (mode == 0)
                buf.append("<a href=\"/netdb?s=1\" title=\"").append(_t("Sort by count")).append("\">");
            buf.append(_t("Count"));
            if (mode == 0)
                buf.append("</a>");
            buf.append("</th></tr>\n");
            for (String country : countryList) {
                int num = countries.count(country);
                buf.append("<tr><td><a href=\"/netdb?c=").append(country).append("\">");
                buf.append("<img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append("\"");
                buf.append(" src=\"/flags.jsp?c=").append(country).append("\">");
                buf.append(getTranslatedCountry(country));
                buf.append("</a></td><td align=\"center\">").append(num).append("</td></tr>\n");
            }
            // https://db-ip.com/db/download/ip-to-country-lite
            String geoDir = _context.getProperty(GeoIP.PROP_GEOIP_DIR, GeoIP.GEOIP_DIR_DEFAULT);
            File geoFile = new File(geoDir);
            if (!geoFile.isAbsolute())
                geoFile = new File(_context.getBaseDir(), geoDir);
            geoFile = new File(geoFile, GeoIP.GEOIP2_FILE_DEFAULT);
            if (geoFile.exists()) {
                // we'll assume we are using it, ignore case where Debian file is newer
                buf.append("<tr><td colspan=\"2\"><font size=\"-2\"><a href=\"https://db-ip.com/\">IP Geolocation by DB-IP</a></font></td></tr>");
            }
            buf.append("</table>\n");
        }

        buf.append("</td></tr></table>");
        if (log.shouldWarn()) {
            long end = System.currentTimeMillis();
            log.warn("part 4 took " + (end - start));
        }

     //
     // don't bother to reindent
     //
     } // if !showStats

        out.append(buf);
        out.flush();
    }

    /**
     * @return success
     * @since 0.9.66
     */
    private boolean embedResource(StringBuilder buf, String rsc) {
        InputStream is = this.getClass().getResourceAsStream("/net/i2p/router/web/resources/" + rsc);
        if (is == null)
            return false;
        Reader br = null;
        try {
            br = new InputStreamReader(is, "UTF-8");
            char[] c = new char[4096];
            int read;
            while ( (read = br.read(c)) >= 0) {
                buf.append(c, 0, read);
            }
        } catch (IOException ioe) {
            return false;
        } finally {
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
        return true;
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
     *  Reverse sort by count, then forward by translated name.
     *
     *  @since 0.9.57
     */
    private class CountryCountComparator implements Comparator<String> {
         private static final long serialVersionUID = 1L;
         private final ObjectCounterUnsafe<String> counts;
         private final Collator coll;

         public CountryCountComparator(ObjectCounterUnsafe<String> counts) {
             super();
             this.counts = counts;
             coll = Collator.getInstance(new Locale(Messages.getLanguage(_context)));
         }

         public int compare(String l, String r) {
             int rv = counts.count(r) - counts.count(l);
             if (rv != 0)
                 return rv;
             return coll.compare(getTranslatedCountry(l),
                                 getTranslatedCountry(r));
        }
    }

    /**
     *  Sort by style, then host
     *  @since 0.9.38
     */
    static class RAComparator implements Comparator<RouterAddress> {
         private static final long serialVersionUID = 1L;

         public int compare(RouterAddress l, RouterAddress r) {
             int rv = l.getTransportStyle().compareTo(r.getTransportStyle());
             if (rv != 0)
                 return rv;
             String lh = l.getHost();
             String rh = r.getHost();
             if (lh == null)
                 return (rh == null) ? 0 : -1;
             if (rh == null)
                 return 1;
             return lh.compareTo(rh);
        }
    }

    /**
     *  Be careful to use stripHTML for any displayed routerInfo data
     *  to prevent vulnerabilities
     */
    private void renderRouterInfo(StringBuilder buf, RouterInfo info, boolean isUs, boolean full) {
        RouterIdentity ident = info.getIdentity();
        String hash = ident.getHash().toBase64();
        buf.append("<table class=\"netdbentry\">" +
                   "<tr id=\"").append(hash, 0, 6).append("\"><th colspan=\"2\"");
        if (isUs) {
            buf.append(" id=\"our-info\"><b>").append(_t("Our Router Identity")).append(":</b> <code>")
               .append(hash).append("</code></th><th>");
        } else {
            buf.append("><b>").append(_t("Router")).append(":</b> <code>")
               .append(hash).append("</code></th><th>");
            String country = _context.commSystem().getCountry(ident.getHash());
            if (country != null) {
                buf.append("<a href=\"/netdb?c=").append(country).append("\">");
                buf.append("<img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append('\"');
                buf.append(" title=\"").append(getTranslatedCountry(country)).append('\"');
                buf.append(" src=\"/flags.jsp?c=").append(country).append("\"> ").append("</a>");
            }
            if (!full) {
                buf.append("<a title=\"").append(_t("View extended router info"))
                   .append("\" class=\"viewfullentry\" href=\"netdb?r=").append(hash, 0, 6)
                   .append("\" >[").append(_t("Full entry")).append("]</a>");
            }
        }
        buf.append("</th></tr>\n<tr>");
        long age = _context.clock().now() - info.getPublished();
        if (isUs && _context.router().isHidden()) {
            buf.append("<td><b>").append(_t("Hidden")).append(", ").append(_t("Updated")).append(":</b></td>" +
                       "<td colspan=\"2\"><span class=\"netdb_info\">")
               .append(_t("{0} ago", DataHelper.formatDuration2(age)))
               .append("</span>");
        } else if (age > 0) {
            buf.append("<td><b>").append(_t("Published")).append(":</b></td>" +
                       "<td colspan=\"2\"><span class=\"netdb_info\">")
               .append(_t("{0} ago", DataHelper.formatDuration2(age)))
               .append("</span>");
        } else {
            // published slightly in the future
            buf.append("<td><b>").append(_t("Published")).append(":</b></td><td colspan=\"2\"><span class=\"netdb_info\">")
               .append(DataHelper.formatDuration2(0-age)).append(" from now???</span>");
        }
        boolean debug = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        if (full) {
            buf.append("</td></tr><tr><td><b>").append(_t("Signing Key")).append(":</b></td><td colspan=\"2\">")
               .append(ident.getSigningPublicKey().getType());
            buf.append("</td></tr><tr><td><b>").append(_t("Encryption Key")).append(":</b></td><td colspan=\"2\">")
               .append(ident.getPublicKey().getType());
            if (debug) {
                buf.append("</td></tr>\n<tr><td><b>Routing Key:</b></td><td colspan=\"2\">").append(info.getRoutingKey().toBase64());
                buf.append("</td></tr>");
                if (ident.isCompressible()) {
                    buf.append("</td></tr><tr><td><b>Compressible:</b></td><td colspan=\"2\">true");
                }
            }
        }
        buf.append("</td></tr>\n");
        if (full) {
            String family = info.getOption("family");
            if (family != null) {
                FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
                if (fkc != null) {
                    String f = DataHelper.stripHTML(family);
                    buf.append("<tr><td><b>").append(_t("Family"))
                       .append(":</b><td colspan=\"2\"><span class=\"netdb_info\">")
                       .append(fkc.verify(info) == FamilyKeyCrypto.Result.STORED_KEY ? "Verified" : "Unverified")
                       .append(" <a href=\"/netdb?fam=")
                       .append(f)
                       .append("\">")
                       .append(f)
                       .append("</a></span></td></tr>\n");
                }
            }
        }
        buf.append("<tr><td><b>").append(_t("Addresses")).append(":</b></td><td colspan=\"2\"");
        Collection<RouterAddress> addrs = info.getAddresses();
        if (addrs.isEmpty()) {
            buf.append('>').append(_t("none"));
        } else {
            buf.append(" class=\"netdb_addresses\">");
            if (addrs.size() > 1) {
                // addrs is unmodifiable
                List<RouterAddress> laddrs = new ArrayList<RouterAddress>(addrs);
                Collections.sort(laddrs, new RAComparator());
                addrs = laddrs;
            }
            for (RouterAddress addr : addrs) {
                String style = addr.getTransportStyle();
                buf.append("<br><b class=\"netdb_transport\">").append(DataHelper.stripHTML(style)).append(":</b>");
                if (debug) {
                    int cost = addr.getCost();
                    if (!((style.equals("SSU") && cost == 5) || (style.startsWith("NTCP") && cost == 10)))
                        buf.append("&nbsp;<span class=\"netdb_name\">").append(_t("cost")).append("</span>: <span class=\"netdb_info\">").append("" + cost).append("</span>&nbsp;");
                }
                Map<Object, Object> p = addr.getOptionsMap();
                for (Map.Entry<Object, Object> e : p.entrySet()) {
                    String name = (String) e.getKey();
                    String val = (String) e.getValue();
                    if (name.equals("host"))
                        val = Addresses.toCanonicalString(val);
                    buf.append(" <span class=\"nowrap\"><span class=\"netdb_name\">").append(_t(DataHelper.stripHTML(name)))
                       .append(":</span> <span class=\"netdb_info\">").append(DataHelper.stripHTML(val)).append("</span></span>&nbsp;");
                }
            }
        }
        buf.append("</td></tr>\n");
        if (full) {
            buf.append("<tr><td><b>").append(_t("Stats")).append(":</b><td colspan=\"2\"><code>");
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
            if (style.equals("NTCP2") || style.equals("NTCP")) {
                rv |= NTCP;
            } else if (style.equals("SSU") || style.equals("SSU2")) {
                if (addr.getOption("itag0") != null)
                    rv |= SSUI;
                else
                    rv |= SSU;
            }
            String host = addr.getHost();
            if (host != null && host.contains(":")) {
                rv |= IPV6;
            } else {
                String caps = addr.getOption("caps");
                if (caps != null && caps.contains("6"))
                    rv |= IPV6;
            }
        }
        // map invalid values with "" in TNAMES
        if (rv == 3)
            rv = 2;
        else if (rv == 7)
            rv = 6;
        else if (rv == 8)
            rv = 0;
        return rv;
    }

    /**
     *  If ipv6 is in compressed form, return expanded form.
     *  If ipv6 is in expanded form, return compressed form.
     *  Else return null.
     *
     *  @param ip ipv6 only, not ending with ::
     *  @return alt string or null
     *  @since 0.9.57
     */
    private static String getAltIPv6(String ip) {
        if (ip.contains("::")) {
            // convert to expanded
            byte[] bip = Addresses.getIPOnly(ip);
            if (bip != null)
                return Addresses.toString(bip);
        } else if (ip.contains(":0:")) {
            // convert to canonical
            return Addresses.toCanonicalString(ip);
        }
        return null;
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
