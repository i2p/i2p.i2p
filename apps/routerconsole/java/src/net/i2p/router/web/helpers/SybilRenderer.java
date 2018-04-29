package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.math.BigInteger;
import java.text.Collator;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.kademlia.XORComparator;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.peermanager.DBHistory;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.util.HashDistance;
import net.i2p.router.web.Messages;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.util.ConvertToHash;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;

/**
 *  For debugging only.
 *  Parts may later move to router as a periodic monitor.
 *  Adapted from NetDbRenderer.
 *
 *  @since 0.9.24
 *
 */
class SybilRenderer {

    private final RouterContext _context;
    private final DecimalFormat fmt = new DecimalFormat("#0.00");

    private static final int PAIRMAX = 20;
    private static final int MAX = 10;
    // multiplied by size - 1, will also get POINTS24 added
    private static final double POINTS32 = 5.0;
    // multiplied by size - 1, will also get POINTS16 added
    private static final double POINTS24 = 5.0;
    // multiplied by size - 1
    private static final double POINTS16 = 0.25;
    private static final double POINTS_US32 = 25.0;
    private static final double POINTS_US24 = 25.0;
    private static final double POINTS_US16 = 10.0;
    private static final double POINTS_FAMILY = -2.0;
    private static final double POINTS_BAD_OUR_FAMILY = 100.0;
    private static final double POINTS_OUR_FAMILY = -100.0;
    private static final double MIN_CLOSE = 242.0;
    private static final double PAIR_DISTANCE_FACTOR = 2.0;
    private static final double OUR_KEY_FACTOR = 4.0;
    private static final double MIN_DISPLAY_POINTS = 5.0;
    private static final double VERSION_FACTOR = 1.0;
    private static final double POINTS_BAD_VERSION = 50.0;
    private static final double POINTS_UNREACHABLE = 4.0;
    private static final double POINTS_NEW = 4.0;
    private static final double POINTS_BANLIST = 25.0;

    public SybilRenderer(RouterContext ctx) {
        _context = ctx;
    }

    /**
     *   Entry point
     */
    public String getNetDbSummary(Writer out) throws IOException {
        renderRouterInfoHTML(out, (String)null);
        return "";
    }

    private static class RouterInfoRoutingKeyComparator implements Comparator<RouterInfo>, Serializable {
         private final Hash _us;
         /** @param us ROUTING KEY */
         public RouterInfoRoutingKeyComparator(Hash us) {
             _us = us;
         }
         public int compare(RouterInfo l, RouterInfo r) {
             return HashDistance.getDistance(_us, l.getHash()).compareTo(HashDistance.getDistance(_us, r.getHash()));
        }
    }

    /**
     *  A total score and a List of reason Strings
     */
    private static class Points implements Comparable<Points> {
         private double points;
         private final List<String> reasons;
         /** @param us ROUTING KEY */
         public Points(double points, String reason) {
             this.points = points;
             reasons = new ArrayList<String>(4);
             reasons.add(reason);
         }
         public int compareTo(Points r) {
             if (points > r.points)
                 return 1;
             if (points < r.points)
                 return -1;
             return 0;
        }
    }

    private static class PointsComparator implements Comparator<Hash>, Serializable {
         private final Map<Hash, Points> _points;
         /** @param us ROUTING KEY */
         public PointsComparator(Map<Hash, Points> points) {
             _points = points;
         }
         public int compare(Hash l, Hash r) {
             // reverse
             return _points.get(r).compareTo(_points.get(l));
        }
    }

    private void addPoints(Map<Hash, Points> points, Hash h, double d, String reason) {
        Points dd = points.get(h);
        if (dd != null) {
            dd.points += d;
            dd.reasons.add("<b>" + fmt.format(d) + ":</b> " + reason);
        } else {
            points.put(h, new Points(d, "<b>" + fmt.format(d) + ":</b> " + reason));
        }
    }

    /**
     *  The whole thing
     *
     *  @param routerPrefix ignored
     */
    private void renderRouterInfoHTML(Writer out, String routerPrefix) throws IOException {
        Set<Hash> ffs = _context.peerManager().getPeersByCapability('f');
        List<RouterInfo> ris = new ArrayList<RouterInfo>(ffs.size());
        Hash us = _context.routerHash();
        Hash ourRKey = _context.router().getRouterInfo().getRoutingKey();
        for (Hash ff : ffs) {
             if (ff.equals(us))
                 continue;
             RouterInfo ri = _context.netDb().lookupRouterInfoLocally(ff);
             if (ri != null)
                 ris.add(ri);
        }
        if (ris.isEmpty()) {
            out.write("<h3 class=\"sybils\">No known floodfills</h3>");
            return;
        }

        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<p id=\"sybilinfo\"><b>This is an experimental network database tool for debugging and analysis. Do not panic even if you see warnings below. " +
                   "Possible \"threats\" are summarized at the bottom, however these are unlikely to be real threats. " +
                   "If you see anything you would like to discuss with the devs, contact us on IRC #i2p-dev.</b></p>" +
                   "<div id=\"sybilnav\"><ul><li><a href=\"#known\">FF Summary</a>" +
                   "</li><li><a href=\"#family\">Same Family</a>" +
                   "</li><li><a href=\"#ourIP\">IP close to us</a>" +
                   "</li><li><a href=\"#sameIP\">Same IP</a>" +
                   "</li><li><a href=\"#same24\">Same /24</a>" +
                   "</li><li><a href=\"#same16\">Same /16</a>" +
                   "</li><li><a href=\"#pairs\">Pair distance</a>" +
                   "</li><li><a href=\"#ritoday\">Close to us</a>" +
                   "</li><li><a href=\"#ritmrw\">Close to us tomorrow</a>" +
                   "</li><li><a href=\"#dht\">DHT neighbors</a>" +
                   "</li><li><a href=\"#dest\">Close to our destinations</a>" +
                   "</li><li><a href=\"#threats\">Highest threats</a>" +
                   "</li></ul></div>");

        renderRouterInfo(buf, _context.router().getRouterInfo(), null, true, false);
        buf.append("<h3 id=\"known\" class=\"sybils\">Known Floodfills: ").append(ris.size()).append("</h3>");

        double tot = 0;
        int count = 200;
        byte[] b = new byte[32];
        for (int i = 0; i < count; i++) {
            _context.random().nextBytes(b);
            Hash h = new Hash(b);
            double d = closestDistance(h, ris);
            tot += d;
        }
        double avgMinDist = tot / count;
        buf.append("<div id=\"sybils_summary\">\n");
        buf.append("<b>Average closest floodfill distance:</b> ").append(fmt.format(avgMinDist)).append("<br>\n");
        buf.append("<b>Routing Data:</b> \"").append(DataHelper.getUTF8(_context.routerKeyGenerator().getModData()))
           .append("\" <b>Last Changed:</b> ").append(new Date(_context.routerKeyGenerator().getLastChanged())).append("<br>\n");
        buf.append("<b>Next Routing Data:</b> \"").append(DataHelper.getUTF8(_context.routerKeyGenerator().getNextModData()))
           .append("\" <b>Rotates in:</b> ").append(DataHelper.formatDuration(_context.routerKeyGenerator().getTimeTillMidnight())).append("\n");
        buf.append("</div>\n");

        Map<Hash, Points> points = new HashMap<Hash, Points>(64);

        // IP analysis
        renderIPGroupsFamily(out, buf, ris, points);
        renderIPGroupsUs(out, buf, ris, points);
        renderIPGroups32(out, buf, ris, points);
        renderIPGroups24(out, buf, ris, points);
        renderIPGroups16(out, buf, ris, points);

        // Pairwise distance analysis
        renderPairDistance(out, buf, ris, points);

        // Distance to our router analysis
        buf.append("<h3 id=\"ritoday\" class=\"sybils\">Closest Floodfills to Our Routing Key (Where we Store our RI)</h3>");
        buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil\">See all</a></p>");
        renderRouterInfoHTML(out, buf, ourRKey, avgMinDist, ris, points);
        RouterKeyGenerator rkgen = _context.routerKeyGenerator();
        Hash nkey = rkgen.getNextRoutingKey(us);
        buf.append("<h3 id=\"ritmrw\" class=\"sybils\">Closest Floodfills to Tomorrow's Routing Key (Where we will Store our RI)</h3>");
        buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil\">See all</a></p>");
        renderRouterInfoHTML(out, buf, nkey, avgMinDist, ris, points);

        buf.append("<h3 id=\"dht\" class=\"sybils\">Closest Floodfills to Our Router Hash (DHT Neighbors if we are Floodfill)</h3>");
        renderRouterInfoHTML(out, buf, us, avgMinDist, ris, points);

        // Distance to our published destinations analysis
        buf.append("<h3 id=\"dest\" class=\"sybils\">Floodfills Close to Our Destinations</h3>");
        Map<Hash, TunnelPool> clientInboundPools = _context.tunnelManager().getInboundClientPools();
        List<Hash> destinations = new ArrayList<Hash>(clientInboundPools.keySet());
        //boolean debug = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        for (Hash client : destinations) {
            boolean isLocal = _context.clientManager().isLocal(client);
            if (!isLocal)
                continue;
            if (! _context.clientManager().shouldPublishLeaseSet(client))
                continue;
            LeaseSet ls = _context.netDb().lookupLeaseSetLocally(client);
            if (ls == null)
                continue;
            Hash rkey = ls.getRoutingKey();
            TunnelPool in = clientInboundPools.get(client);
            String name = (in != null) ? in.getSettings().getDestinationNickname() : client.toBase64().substring(0,4);
            buf.append("<h3 class=\"sybils\">Closest floodfills to the Routing Key for " + DataHelper.escapeHTML(name) + " (where we store our LS)</h3>");
            buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil=" + ls.getHash().toBase64() + "\">See all</a></p>");
            renderRouterInfoHTML(out, buf, rkey, avgMinDist, ris, points);
            nkey = rkgen.getNextRoutingKey(ls.getHash());
            buf.append("<h3 class=\"sybils\">Closest floodfills to Tomorrow's Routing Key for " + DataHelper.escapeHTML(name) + " (where we will store our LS)</h3>");
            buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil=" + ls.getHash().toBase64() + "\">See all</a></p>");
            renderRouterInfoHTML(out, buf, nkey, avgMinDist, ris, points);
        }

        // Profile analysis
        addProfilePoints(ris, points);
        addVersionPoints(ris, points);

        if (!points.isEmpty()) {
            List<Hash> warns = new ArrayList<Hash>(points.keySet());
            Collections.sort(warns, new PointsComparator(points));
            buf.append("<h3 id=\"threats\" class=\"sybils\">Routers with Most Threat Points</h3>");
            for (Hash h : warns) {
                RouterInfo ri = _context.netDb().lookupRouterInfoLocally(h);
                if (ri == null)
                    continue;
                Points pp = points.get(h);
                double p = pp.points;
                if (p < MIN_DISPLAY_POINTS)
                    break;  // sorted
                buf.append("<p class=\"threatpoints\"><b>Threat Points: " + fmt.format(p) + "</b></p><ul>");
                for (String s : pp.reasons) {
                    buf.append("<li>").append(s).append("</li>");
                }
                buf.append("</ul>");
                renderRouterInfo(buf, ri, null, false, false);
            }
        }

        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    private static class Pair implements Comparable<Pair> {
        public final RouterInfo r1, r2;
        public final BigInteger dist;
        public Pair(RouterInfo ri1, RouterInfo ri2, BigInteger distance) {
            r1 = ri1; r2 = ri2; dist = distance;
        }
        public int compareTo(Pair p) {
            return this.dist.compareTo(p.dist);
        }
    }

    private void renderPairDistance(Writer out, StringBuilder buf, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        int sz = ris.size();
        List<Pair> pairs = new ArrayList<Pair>(PAIRMAX);
        double total = 0;
        for (int i = 0; i < sz; i++) {
            RouterInfo info1 = ris.get(i);
            for (int j = i + 1; j < sz; j++) {
                RouterInfo info2 = ris.get(j);
                BigInteger dist = HashDistance.getDistance(info1.getHash(), info2.getHash());
                if (pairs.isEmpty()) {
                    pairs.add(new Pair(info1, info2, dist));
                } else if (pairs.size() < PAIRMAX) {
                    pairs.add(new Pair(info1, info2, dist));
                    Collections.sort(pairs);
                } else if (dist.compareTo(pairs.get(PAIRMAX - 1).dist) < 0) {
                    pairs.set(PAIRMAX - 1, new Pair(info1, info2, dist));
                    Collections.sort(pairs);
                }
                total += biLog2(dist);
            }
        }

        double avg = total / (sz * sz / 2d);
        buf.append("<h3 class=\"sybils\">Average Floodfill Distance is ").append(fmt.format(avg)).append("</h3>");

        buf.append("<h3 id=\"pairs\" class=\"sybils\">Closest Floodfill Pairs by Hash</h3>");
        for (Pair p : pairs) {
            double distance = biLog2(p.dist);
            double point = MIN_CLOSE - distance;
            if (point < 0)
                break;  // sorted;
            if (point >= 2) {
                // limit display
                buf.append("<p class=\"hashdist\"><b>Hash Distance: ").append(fmt.format(distance)).append(": </b>");
                buf.append("</p>");
                renderRouterInfo(buf, p.r1, null, false, false);
                renderRouterInfo(buf, p.r2, null, false, false);
            }
            point *= PAIR_DISTANCE_FACTOR;
            String b2 = p.r2.getHash().toBase64();
            addPoints(points, p.r1.getHash(), point, "Very close (" + fmt.format(distance) +
                          ") to other floodfill <a href=\"netdb?r=" + b2 + "\">" + b2 + "</a>");
            String b1 = p.r1.getHash().toBase64();
            addPoints(points, p.r2.getHash(), point, "Very close (" + fmt.format(distance) +
                          ") to other floodfill <a href=\"netdb?r=" + b1 + "\">" + b1 + "</a>");
        }
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    private double closestDistance(Hash h, List<RouterInfo> ris) throws IOException {
        BigInteger min = (new BigInteger("2")).pow(256);
        for (RouterInfo info : ris) {
            BigInteger dist = HashDistance.getDistance(h, info.getHash());
            if (dist.compareTo(min) < 0)
                min = dist;
        }
        return biLog2(min);
    }

    /** v4 only */
    private static byte[] getIP(RouterInfo ri) {
        for (RouterAddress ra : ri.getAddresses()) {
            byte[] rv = ra.getIP();
            if (rv != null && rv.length == 4)
                return rv;
        }
        return null;
    }

    private static class FooComparator implements Comparator<Integer>, Serializable {
         private final ObjectCounter<Integer> _o;
         public FooComparator(ObjectCounter<Integer> o) { _o = o;}
         public int compare(Integer l, Integer r) {
             // reverse by count
             int rv = _o.count(r) - _o.count(l);
             if (rv != 0)
                 return rv;
             // foward by IP
             return l.intValue() - r.intValue();
        }
    }

    private static class FoofComparator implements Comparator<String>, Serializable {
         private final ObjectCounter<String> _o;
         private final Collator _comp = Collator.getInstance();
         public FoofComparator(ObjectCounter<String> o) { _o = o;}
         public int compare(String l, String r) {
             // reverse by count
             int rv = _o.count(r) - _o.count(l);
             if (rv != 0)
                 return rv;
             // foward by name
             return _comp.compare(l, r);
        }
    }

    private void renderIPGroupsUs(Writer out, StringBuilder buf, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        RouterInfo us = _context.router().getRouterInfo();
        byte[] ourIP = getIP(us);
        if (ourIP == null)
            return;
        buf.append("<h3 id=\"ourIP\" class=\"sybils\">Floodfills close to Our IP</h3>");
        boolean found = false;
        for (RouterInfo info : ris) {
            byte[] ip = getIP(info);
            if (ip == null)
                continue;
            if (ip[0] == ourIP[0] && ip[1] == ourIP[1]) {
                buf.append("<p id=\"sybil_info\"><b>");
                if (ip[2] == ourIP[2]) {
                    if (ip[3] == ourIP[3]) {
                        buf.append("Same IP as us");
                        addPoints(points, info.getHash(), POINTS_US32, "Same IP as us");
                    } else {
                        buf.append("Same /24 as us");
                        addPoints(points, info.getHash(), POINTS_US24, "Same /24 as us");
                    }
                } else {
                    buf.append("Same /16 as us");
                    addPoints(points, info.getHash(), POINTS_US16, "Same /16 as us");
                }
                buf.append(":</b></p>");
                renderRouterInfo(buf, info, null, false, false);
                found = true;
            }
        }
        if (!found)
            buf.append("<p class=\"notfound\">None</p>");
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    private void renderIPGroups32(Writer out, StringBuilder buf, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        buf.append("<h3 id=\"sameIP\" class=\"sybils\">Floodfills with the Same IP</h3>");
        ObjectCounter<Integer> oc = new ObjectCounter<Integer>();
        for (RouterInfo info : ris) {
            byte[] ip = getIP(info);
            if (ip == null)
                continue;
            Integer x = Integer.valueOf((int) DataHelper.fromLong(ip, 0, 4));
            oc.increment(x);
        }
        List<Integer> foo = new ArrayList<Integer>();
        for (Integer ii : oc.objects()) {
            int count = oc.count(ii);
            if (count >= 2)
                foo.add(ii);
        }
        Collections.sort(foo, new FooComparator(oc));
        boolean found = false;
        for (Integer ii : foo) {
            int count = oc.count(ii);
            int i = ii.intValue();
            int i0 = (i >> 24) & 0xff;
            int i1 = (i >> 16) & 0xff;
            int i2 = (i >> 8) & 0xff;
            int i3 = i & 0xff;
            String sip = i0 + "." + i1 + '.' + i2 + '.' + i3;
            buf.append("<p class=\"sybil_info\"><b>").append(count).append(" floodfills with IP <a href=\"/netdb?ip=")
               .append(sip).append("&amp;sybil\">").append(sip)
               .append("</a>:</b></p>");
            for (RouterInfo info : ris) {
                byte[] ip = getIP(info);
                if (ip == null)
                    continue;
                if ((ip[0] & 0xff) != i0)
                    continue;
                if ((ip[1] & 0xff) != i1)
                    continue;
                if ((ip[2] & 0xff) != i2)
                    continue;
                if ((ip[3] & 0xff) != i3)
                    continue;
                found = true;
                renderRouterInfo(buf, info, null, false, false);
                double point = POINTS32 * (count - 1);
                addPoints(points, info.getHash(), point, "Same IP with " + (count - 1) + " other" + (( count > 2) ? "s" : ""));
            }
        }
        if (!found)
            buf.append("<p class=\"notfound\">None</p>");
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    private void renderIPGroups24(Writer out, StringBuilder buf, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        buf.append("<h3 id=\"same24\" class=\"sybils\">Floodfills in the Same /24 (2 minimum)</h3>");
        ObjectCounter<Integer> oc = new ObjectCounter<Integer>();
        for (RouterInfo info : ris) {
            byte[] ip = getIP(info);
            if (ip == null)
                continue;
            Integer x = Integer.valueOf((int) DataHelper.fromLong(ip, 0, 3));
            oc.increment(x);
        }
        List<Integer> foo = new ArrayList<Integer>();
        for (Integer ii : oc.objects()) {
            int count = oc.count(ii);
            if (count >= 2)
                foo.add(ii);
        }
        Collections.sort(foo, new FooComparator(oc));
        boolean found = false;
        for (Integer ii : foo) {
            int count = oc.count(ii);
            int i = ii.intValue();
            int i0 = i >> 16;
            int i1 = (i >> 8) & 0xff;
            int i2 = i & 0xff;
            String sip = i0 + "." + i1 + '.' + i2 + ".0/24";
            buf.append("<p class=\"sybil_info\"><b>").append(count).append(" floodfills with IP <a href=\"/netdb?ip=")
               .append(sip).append("&amp;sybil\">").append(sip)
               .append("</a>:</b></p>");
            for (RouterInfo info : ris) {
                byte[] ip = getIP(info);
                if (ip == null)
                    continue;
                if ((ip[0] & 0xff) != i0)
                    continue;
                if ((ip[1] & 0xff) != i1)
                    continue;
                if ((ip[2] & 0xff) != i2)
                    continue;
                found = true;
                renderRouterInfo(buf, info, null, false, false);
                double point = POINTS24 * (count - 1);
                addPoints(points, info.getHash(), point, "Same /24 IP with " + (count - 1) + " other" + (( count > 2) ? "s" : ""));
            }
        }
        if (!found)
            buf.append("<p class=\"notfound\">None</p>");
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    private void renderIPGroups16(Writer out, StringBuilder buf, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        buf.append("<h3 id=\"same16\" class=\"sybils\">Floodfills in the Same /16 (4 minimum)</h3>");
        ObjectCounter<Integer> oc = new ObjectCounter<Integer>();
        for (RouterInfo info : ris) {
            byte[] ip = getIP(info);
            if (ip == null)
                continue;
            Integer x = Integer.valueOf((int) DataHelper.fromLong(ip, 0, 2));
            oc.increment(x);
        }
        List<Integer> foo = new ArrayList<Integer>();
        for (Integer ii : oc.objects()) {
            int count = oc.count(ii);
            if (count >= 4)
                foo.add(ii);
        }
        Collections.sort(foo, new FooComparator(oc));
        boolean found = false;
        for (Integer ii : foo) {
            int count = oc.count(ii);
            int i = ii.intValue();
            int i0 = i >> 8;
            int i1 = i & 0xff;
            String sip = i0 + "." + i1 + ".0/16";
            buf.append("<p class=\"sybil_info\"><b>").append(count).append(" floodfills with IP <a href=\"/netdb?ip=")
               .append(sip).append("&amp;sybil\">").append(sip)
               .append("</a></b></p>");
            for (RouterInfo info : ris) {
                byte[] ip = getIP(info);
                if (ip == null)
                    continue;
                if ((ip[0] & 0xff) != i0)
                    continue;
                if ((ip[1] & 0xff) != i1)
                    continue;
                found = true;
                // limit display
                //renderRouterInfo(buf, info, null, false, false);
                double point = POINTS16 * (count - 1);
                addPoints(points, info.getHash(), point, "Same /16 IP with " + (count - 1) + " other" + (( count > 2) ? "s" : ""));
            }
        }
        if (!found)
            buf.append("<p class=\"notfound\">None</p>");
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    private void renderIPGroupsFamily(Writer out, StringBuilder buf, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        buf.append("<h3 id=\"samefamily\" class=\"sybils\">Floodfills in the Same Declared Family</h3><div class=\"sybil_container\">");
        ObjectCounter<String> oc = new ObjectCounter<String>();
        for (RouterInfo info : ris) {
            String fam = info.getOption("family");
            if (fam == null)
                continue;
            oc.increment(fam);
        }
        List<String> foo = new ArrayList<String>(oc.objects());
        Collections.sort(foo, new FoofComparator(oc));
        FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
        String ourFamily = fkc != null ? fkc.getOurFamilyName() : null;
        boolean found = false;
        for (String s : foo) {
            int count = oc.count(s);
            String ss = DataHelper.escapeHTML(s);
            if (count > 1) {
                buf.append("<p class=\"family\"><b>").append(count).append(" floodfills in family: &nbsp;<a href=\"/netdb?fam=")
                   .append(ss).append("&amp;sybil\">").append(ss).append("</a></b></p>");
            }
            for (RouterInfo info : ris) {
                String fam = info.getOption("family");
                if (fam == null)
                    continue;
                if (!fam.equals(s))
                    continue;
                found = true;
                // limit display
                //renderRouterInfo(buf, info, null, false, false);
                double point = POINTS_FAMILY;
                if (fkc != null && s.equals(ourFamily)) {
                    if (fkc.verifyOurFamily(info))
                        addPoints(points, info.getHash(), POINTS_OUR_FAMILY, "Our family \"" + DataHelper.escapeHTML(s) + "\" with " + (count - 1) + " other" + (( count > 2) ? "s" : ""));
                    else
                        addPoints(points, info.getHash(), POINTS_BAD_OUR_FAMILY, "Spoofed our family \"" + DataHelper.escapeHTML(s) + "\" with " + (count - 1) + " other" + (( count > 2) ? "s" : ""));
                } else if (count > 1) {
                    addPoints(points, info.getHash(), point, "Same declared family \"" + DataHelper.escapeHTML(s) + "\" with " + (count - 1) + " other" + (( count > 2) ? "s" : ""));
                } else {
                    addPoints(points, info.getHash(), point, "Declared family \"" + DataHelper.escapeHTML(s) + '"');
                }
            }
        }
        if (!found)
            buf.append("<p class=\"notfound\">None</p>");
        buf.append("</div>");
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    private static final long DAY = 24*60*60*1000L;

    private void addProfilePoints(List<RouterInfo> ris, Map<Hash, Points> points) {
        long now = _context.clock().now();
        RateAverages ra = RateAverages.getTemp();
        for (RouterInfo info : ris) {
            Hash h = info.getHash();
            if (_context.banlist().isBanlisted(h))
                addPoints(points, h, POINTS_BANLIST, "Banlisted");
            PeerProfile prof = _context.profileOrganizer().getProfileNonblocking(h);
            if (prof != null) {
                long heard = prof.getFirstHeardAbout();
                if (heard > 0) {
                    long age = Math.max(now - heard, 1);
                    if (age < 2 * DAY) {
                        // (POINTS_NEW / 48) for every hour under 48, max POINTS_NEW
                        double point = Math.min(POINTS_NEW, (2 * DAY - age) / (2 * DAY / POINTS_NEW));
                        addPoints(points, h, point,
                                  "First heard about: " + _t("{0} ago", DataHelper.formatDuration2(age)));
                    }
                }
                DBHistory dbh = prof.getDBHistory();
                if (dbh != null) {
                    RateStat rs = dbh.getFailedLookupRate();
                    if (rs != null) {
                        Rate r = rs.getRate(24*60*60*1000);
                        if (r != null) {
                            r.computeAverages(ra, false);
                            if (ra.getTotalEventCount() > 0) {
                                double avg = 100 * ra.getAverage();
                                if (avg > 40)
                                    addPoints(points, h, (avg - 40) / 6.0, "Lookup fail rate " + ((int) avg) + '%');
                            }
                        }
                    }
                }
            }
        }
    }

    private void addVersionPoints(List<RouterInfo> ris, Map<Hash, Points> points) {
        RouterInfo us = _context.router().getRouterInfo();
        if (us == null) return;
        String ourVer = us.getVersion();
        if (!ourVer.startsWith("0.9.")) return;
        ourVer = ourVer.substring(4);
        int dot = ourVer.indexOf('.');
        if (dot > 0)
            ourVer = ourVer.substring(0, dot);
        int minor;
        try {
            minor = Integer.parseInt(ourVer);
        } catch (NumberFormatException nfe) { return; }
        for (RouterInfo info : ris) {
            Hash h = info.getHash();
            String caps = info.getCapabilities();
            if (!caps.contains("R"))
                addPoints(points, h, POINTS_UNREACHABLE, "Unreachable: " + DataHelper.escapeHTML(caps));
            String hisFullVer = info.getVersion();
            if (!hisFullVer.startsWith("0.9.")) {
                addPoints(points, h, POINTS_BAD_VERSION, "Strange version " + DataHelper.escapeHTML(hisFullVer));
                continue;
            }
            String hisVer = hisFullVer.substring(4);
            dot = hisVer.indexOf('.');
            if (dot > 0)
                hisVer = hisVer.substring(0, dot);
            int hisMinor;
            try {
                hisMinor = Integer.parseInt(hisVer);
            } catch (NumberFormatException nfe) { continue; }
            int howOld = minor - hisMinor;
            if (howOld < 3)
                continue;
            addPoints(points, h, howOld * VERSION_FACTOR, howOld + " versions behind: " + DataHelper.escapeHTML(hisFullVer));
        }
    }

    private void renderRouterInfoHTML(Writer out, StringBuilder buf, Hash us, double avgMinDist,
                                      List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        Collections.sort(ris, new RouterInfoRoutingKeyComparator(us));
        double min = 256;
        double max = 0;
        double tot = 0;
        double median = 0;
        int count = Math.min(MAX, ris.size());
        boolean isEven = (count % 2) == 0;
        int medIdx = isEven ? (count / 2) - 1 : (count / 2);
        for (int i = 0; i < count; i++) {
            RouterInfo ri = ris.get(i);
            double dist = renderRouterInfo(buf, ri, us, false, false);
            if (dist < avgMinDist) {
                if (i == 0) {
                    //buf.append("<p><b>Not to worry, but above router is closer than average minimum distance " + fmt.format(avgMinDist) + "</b></p>");
                } else if (i == 1) {
                    buf.append("<p class=\"sybil_info\"><b>Not to worry, but above routers are closer than average minimum distance " + fmt.format(avgMinDist) + "</b></p>");
                } else if (i == 2) {
                    buf.append("<p class=\"sybil_info\"><b>Possible Sybil Warning - above routers are closer than average minimum distance " + fmt.format(avgMinDist) + "</b></p>");
                } else {
                    buf.append("<p class=\"sybil_info\"><b>Major Sybil Warning - above router is closer than average minimum distance " + fmt.format(avgMinDist) + "</b></p>");
                }
            }
            // this is dumb because they are already sorted
            if (dist < min)
                min = dist;
            if (dist > max)
                max = dist;
            tot += dist;
            if (i == medIdx)
                median = dist;
            else if (i == medIdx + 1 && isEven)
                median = (median + dist) / 2;
            double point = MIN_CLOSE - dist;
            if (point > 0) {
                point *= OUR_KEY_FACTOR;
                addPoints(points, ri.getHash(), point, "Very close (" + fmt.format(dist) + ") to our key " + us.toBase64());
            }
            if (i >= MAX - 1)
                break;
        }
        double avg = tot / count;
        buf.append("<p id=\"sybil_totals\"><b>Totals for " + count + " floodfills: &nbsp;</b><span class=\"netdb_name\">MIN:</span > " + fmt.format(min) + "&nbsp; <span class=\"netdb_name\">AVG:</span> " +
            fmt.format(avg) + "&nbsp; <span class=\"netdb_name\">MEDIAN:</span> " + fmt.format(median) + "&nbsp; <span class=\"netdb_name\">MAX:</span> " + fmt.format(max) + "</p>\n");
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    /**
     * For debugging
     * http://forums.sun.com/thread.jspa?threadID=597652
     * @since 0.7.14
     */
    private static double biLog2(BigInteger a) {
        return NetDbRenderer.biLog2(a);
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
     *  Be careful to use stripHTML for any displayed routerInfo data
     *  to prevent vulnerabilities
     *
     *  @param us ROUTING KEY or null
     *  @param full ignored
     *  @return distance to us if non-null, else 0
     */
    private double renderRouterInfo(StringBuilder buf, RouterInfo info, Hash us, boolean isUs, boolean full) {
        String hash = info.getIdentity().getHash().toBase64();
        buf.append("<a name=\"").append(hash.substring(0, 6)).append("\"></a><table class=\"sybil_routerinfo\"><tr>");
        double distance = 0;
        if (isUs) {
            buf.append("<th colspan=\"2\"><a name=\"our-info\" ></a><b>" + _t("Our info") + ":</b> <code>").append(hash)
               .append("</code></th></tr>\n<tr><td class=\"sybilinfo_params\" colspan=\"2\"><div class=\"sybilinfo_container\">");
        } else {
            buf.append("<th><b>" + _t("Router") + ":</b> <code>").append(hash).append("</code>\n");

            String country = _context.commSystem().getCountry(info.getIdentity().getHash());
            buf.append("</th><th>");
            if(country != null) {
                buf.append("<a href=\"/netdb?c=").append(country).append("\">");
                buf.append("<img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append('\"');
                buf.append(" title=\"").append(getTranslatedCountry(country)).append('\"');
                buf.append(" src=\"/flags.jsp?c=").append(country).append("\"> ").append("</a>");
            }
            if (!full) {
                buf.append("<a title=\"View extended router info\" class=\"viewfullentry\" href=\"netdb?r=")
                   .append(hash.substring(0, 6)).append("\" >[").append(_t("Full entry")).append("]</a></th><th>");
            }
            if (_context.portMapper().isRegistered("imagegen"))
                buf.append("<img src=\"/imagegen/id?s=32&amp;c=" + hash.replace("=", "%3d") + "\" height=\"32\" width=\"32\"> ");
            buf.append("</th></tr>\n<tr><td class=\"sybilinfo_params\" colspan=\"3\"><div class=\"sybilinfo_container\">");
            if (us != null) {
                BigInteger dist = HashDistance.getDistance(us, info.getHash());
                distance = biLog2(dist);
                buf.append("<p><b>Hash Distance:</b> ").append(fmt.format(distance)).append("</p>\n");
            }
        }
        buf.append("<p><b>Version:</b> ").append(DataHelper.stripHTML(info.getVersion())).append("</p>\n");
        buf.append("<p><b>Caps:</b> ").append(DataHelper.stripHTML(info.getCapabilities())).append("</p>\n");
        String kr = info.getOption("netdb.knownRouters");
;
        if (kr != null) {
            buf.append("<p><b>Routers:</b> ").append(DataHelper.stripHTML(kr)).append("</p>");
        } else {
            buf.append("<p class=\"sybil_filler\"><b>Routers:</b> ").append(_t("n/a")).append("</p>");
        }
        String kls = info.getOption("netdb.knownLeaseSets");
        if (kls != null) {
            buf.append("<p class=\"sybilinfo_leasesets\"><b>Lease Sets:</b> ").append(DataHelper.stripHTML(kls)).append("</p>\n");
        } else {
            buf.append("<p class=\"sybilinfo_leasesets filler\"><b>Lease Sets:</b> ").append(_t("n/a")).append("</p>");
        }
        String fam = info.getOption("family");
        if (fam != null) {
            buf.append("<p><b>Family:</b> <span class=\"sybilinfo_familyname\">").append(DataHelper.escapeHTML(fam)).append("</span></p>\n");
        }
        long now = _context.clock().now();
        if (!isUs) {
            PeerProfile prof = _context.profileOrganizer().getProfileNonblocking(info.getHash());
            if (prof != null) {
                long heard = prof.getFirstHeardAbout();
                if (heard > 0) {
                    long age = Math.max(now - heard, 1);
                    buf.append("<p><b>First heard about:</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>");
                } else {
                    buf.append("<p class=\"sybil_filler\"><b>First heard about:</b> ").append(_t("n/a")).append("</p>");
                }
                heard = prof.getLastHeardAbout();
                if (heard > 0) {
                    long age = Math.max(now - heard, 1);
                    buf.append("<p><b>Last heard about:</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>");
                } else {
                    buf.append("<p class=\"sybil_filler\"><b>Last heard about:</b> ").append(_t("n/a")).append("</p>");
                }
                heard = prof.getLastHeardFrom();
                if (heard > 0) {
                    long age = Math.max(now - heard, 1);
                    buf.append("<p><b>Last heard from:</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
                } else {
                    buf.append("<p class=\"sybil_filler\"><b>Last heard from:</b> ").append(_t("n/a")).append("</p>");
                }
                DBHistory dbh = prof.getDBHistory();
                if (dbh != null) {
                    heard = dbh.getLastLookupSuccessful();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>Last lookup successful:</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>");
                    } else {
                        buf.append("<p class=\"sybil_filler\"><b>Last lookup successful:</b> ").append(_t("n/a")).append("</p>");
                    }
                    heard = dbh.getLastLookupFailed();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>Last lookup failed:</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>");
                    } else {
                        buf.append("<p class=\"sybil_filler\"><b>Last lookup failed:</b> ").append(_t("n/a")).append("</p>");
                    }
                    heard = dbh.getLastStoreSuccessful();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>Last store successful:</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>");
                    } else {
                        buf.append("<p class=\"sybil_filler\"><b>Last store successful:</b> ").append(_t("n/a")).append("</p>");
                    }
                    heard = dbh.getLastStoreFailed();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>Last store failed:</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>");
                    } else {
                        buf.append("<p class=\"sybil_filler\"><b>Last store failed:</b> ").append(_t("n/a")).append("</p>");
                    }
                }
                // any other profile stuff?
            }
        }
        long age = Math.max(now - info.getPublished(), 1);
        if (isUs && _context.router().isHidden()) {
            buf.append("<p><b>").append(_t("Hidden")).append(", ").append(_t("Updated")).append(":</b> ")
               .append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
        } else {
            buf.append("<p><b>").append(_t("Published")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
        }
        buf.append("<p><b>").append(_t("Signing Key")).append(":</b> ")
           .append(info.getIdentity().getSigningPublicKey().getType().toString()).append("</p>\n");
        buf.append("<p class=\"sybil_filler\">&nbsp;</p>");
        buf.append("</div></td></tr><tr><td class=\"sybil_addresses\" colspan=\"3\"><table><tr><td><b>" + _t("Addresses") + ":</b></td><td>");
        for (RouterAddress addr : info.getAddresses()) {
            String style = addr.getTransportStyle();
            buf.append("<br><b class=\"netdb_transport\">").append(DataHelper.stripHTML(style)).append(":</b> ");
            Map<Object, Object> p = addr.getOptionsMap();
            for (Map.Entry<Object, Object> e : p.entrySet()) {
                String name = (String) e.getKey();
                if (name.equals("key") || name.startsWith("ikey") || name.startsWith("itag") ||
                    name.startsWith("iport") || name.equals("mtu"))
                    continue;
                String val = (String) e.getValue();
                buf.append(" <span class=\"nowrap\"><span class=\"netdb_name\">").append(_t(DataHelper.stripHTML(name))).append(":</span> <span class=\"netdb_info\">");
                buf.append(DataHelper.stripHTML(val));
                buf.append("</span></span>&nbsp;");
            }
        }
        buf.append("</table></td></tr>\n");
        buf.append("</table>\n");
        return distance;
    }

    /**
     *  Called from NetDbRenderer
     *
     *  @since 0.9.28
     */
    public static void renderSybilHTML(Writer out, RouterContext ctx, List<Hash> sybils, String victim) throws IOException {
        if (sybils.isEmpty())
            return;
        final DecimalFormat fmt = new DecimalFormat("#0.00");
        XORComparator<Hash> xor = new XORComparator<Hash>(Hash.FAKE_HASH);
        out.write("<h3 class=\"tabletitle\">Group Distances</h3><table class=\"sybil_distance\"><tr><th>Hash<th>Distance from previous</tr>\n");
        Collections.sort(sybils, xor);
        Hash prev = null;
        for (Hash h : sybils) {
            String hh = h.toBase64();
            out.write("<tr><td><a href=\"#" + hh.substring(0, 6) + "\"><tt>" + hh + "</tt></a><td>");
            if (prev != null) {
                BigInteger dist = HashDistance.getDistance(prev, h);
                writeDistance(out, fmt, dist);
            }
            prev = h;
            out.write("</tr>\n");
        }
        out.write("</table>\n");
        out.flush();

        RouterKeyGenerator rkgen = ctx.routerKeyGenerator();
        long now = ctx.clock().now();
        final int start = -3;
        now += start * 24*60*60*1000L;
        final int days = 10;
        Hash from = ctx.routerHash();
        if (victim != null) {
            Hash v = ConvertToHash.getHash(victim);
            if (v != null)
                from = v;
        }
        out.write("<h3>Distance to " + from.toBase64() + "</h3>");
        prev = null;
        final int limit = Math.min(10, sybils.size());
        for (int i = start; i <= days; i++) {
            out.write("<h3 class=\"tabletitle\">Distance for " + new Date(now) +
                      "</h3><table class=\"sybil_distance\"><tr><th>Hash<th>Distance<th>Distance from previous</tr>\n");
            Hash rkey = rkgen.getRoutingKey(from, now);
            xor = new XORComparator<Hash>(rkey);
            Collections.sort(sybils, xor);
            for (int j = 0; j < limit; j++) {
                Hash h = sybils.get(j);
                String hh = h.toBase64();
                out.write("<tr><td><a href=\"#" + hh.substring(0, 6) + "\"><tt>" + hh + "</tt></a><td>");
                BigInteger dist = HashDistance.getDistance(rkey, h);
                writeDistance(out, fmt, dist);
                out.write("<td>");
                if (prev != null) {
                    dist = HashDistance.getDistance(prev, h);
                    writeDistance(out, fmt, dist);
                }
                prev = h;
                out.write("</tr>\n");
            }
            out.write("</table>\n");
            out.flush();
            now += 24*60*60*1000;
            prev = null;
        }
    }

    /** @since 0.9.28 */
    private static void writeDistance(Writer out, DecimalFormat fmt, BigInteger dist) throws IOException {
        double distance = biLog2(dist);
        if (distance < MIN_CLOSE)
            out.write("<font color=\"red\">");
        out.write(fmt.format(distance));
        if (distance < MIN_CLOSE)
            out.write("</font>");
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
