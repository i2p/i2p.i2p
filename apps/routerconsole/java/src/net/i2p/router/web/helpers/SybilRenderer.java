package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.math.BigInteger;
import java.text.Collator;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
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
import net.i2p.router.sybil.Analysis;
import net.i2p.router.sybil.Pair;
import net.i2p.router.sybil.PersistSybil;
import net.i2p.router.sybil.Points;
import static net.i2p.router.sybil.Util.biLog2;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.util.HashDistance;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.util.ConvertToHash;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SystemVersion;
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
public class SybilRenderer {

    private final RouterContext _context;
    private final Log _log;
    private final DecimalFormat fmt = new DecimalFormat("#0.00");

    private static final int PAIRMAX = Analysis.PAIRMAX;
    private static final int MAX = Analysis.MAX;
    private static final double MIN_CLOSE = Analysis.MIN_CLOSE;
    private static final double MIN_DISPLAY_POINTS = 20.0;
    private static final int[] HOURS = { 1, 6, 24, 7*24, 30*24, 0 };
    private static final int[] DAYS = { 2, 7, 30, 90, 365, 0 };

    public SybilRenderer(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(SybilRenderer.class);
    }

    /**
     *   Entry point
     *
     *  @param mode what tab to show
     *  @param date only for mode = 12
     */
    public String getNetDbSummary(Writer out, String nonce, int mode, long date) throws IOException {
        renderRouterInfoHTML(out, nonce, mode, date);
        return "";
    }

    private static class PointsComparator implements Comparator<Hash>, Serializable {
         private final Map<Hash, Points> _points;

         public PointsComparator(Map<Hash, Points> points) {
             _points = points;
         }
         public int compare(Hash l, Hash r) {
             // reverse
             return _points.get(r).compareTo(_points.get(l));
        }
    }

    /**
     *  Reverse points, then forward by text
     *  @since 0.9.38
     */
    private static class ReasonComparator implements Comparator<String>, Serializable {
         public int compare(String l, String r) {
             int lc = l.indexOf(':');
             int rc = r.indexOf(':');
             if (lc <= 0 || rc <= 0)
                 return 0;
             double ld, rd;
             try {
                 ld = Double.parseDouble(l.substring(0, lc));
                 rd = Double.parseDouble(r.substring(0, rc));
             } catch (NumberFormatException nfe) {
                 return 0;
             }
             int rv = Double.compare(rd, ld);
             if (rv != 0)
                 return rv;
             return l.compareTo(r);
        }
    }

    /**
     *  The whole thing
     *
     *  @param mode what tab to show
     *  @param date only for mode = 12
     */
    private void renderRouterInfoHTML(Writer out, String nonce, int mode, long date) throws IOException {
        Hash us = _context.routerHash();
        Analysis analysis = Analysis.getInstance(_context);
        List<RouterInfo> ris = null;
        if (mode != 0 && mode < 12) {
            if (mode >= 2 && mode <= 6) {
                // review all routers for family and IP analysis
                ris = analysis.getAllRouters(us);
            } else {
                ris = analysis.getFloodfills(us);
            }
            if (ris.isEmpty()) {
                out.write("<h3 class=\"sybils\">No known routers</h3>");
                return;
            }
        }

        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<p id=\"sybilinfo\"><b>This is an experimental network database tool for debugging and analysis. Do not panic even if you see warnings below. " +
                   "Possible \"threats\" are summarized, however these are unlikely to be real threats. " +
                   "If you see anything you would like to discuss with the devs, contact us on IRC #i2p-dev.</b></p>" +
                   "<div id=\"sybilnav\"><ul><li><a href=\"netdb?f=3\">Review stored analysis</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=14\">Run new analysis</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=15\">Configure periodic analysis</a>" +
                   "</li><li><a href=\"/profiles?f=3\">Review current bans</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=1\">Floodfill Summary</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=2\">Same Family</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=3\">IP close to us</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=4\">Same IP</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=5\">Same /24</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=6\">Same /16</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=7\">Pair distance</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=8\">Close to us</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=9\">Close to us tomorrow</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=10\">DHT neighbors</a>" +
                   "</li><li><a href=\"netdb?f=3&amp;m=11\">Close to our destinations</a>" +
                   "</li></ul></div>");
        writeBuf(out, buf);

        double avgMinDist = 0;
        if (mode == 1 || mode == 8 || mode == 9 || mode == 10 || mode == 11) {
            avgMinDist = analysis.getAvgMinDist(ris);
        }
        Map<Hash, Points> points = new HashMap<Hash, Points>(64);

        if (mode == 0) {
            renderOverview(out, buf, nonce, analysis);
        } else if (mode == 1) {
            renderFFSummary(out, buf, ris, avgMinDist);
        } else if (mode == 2) {
            renderFamilySummary(out, buf, analysis, ris, points);
        } else if (mode == 3) {
            renderIPUsSummary(out, buf, analysis, ris, points);
        } else if (mode == 4) {
            renderIP32Summary(out, buf, analysis, ris, points);
        } else if (mode == 5) {
            renderIP24Summary(out, buf, analysis, ris, points);
        } else if (mode == 6) {
            renderIP16Summary(out, buf, analysis, ris, points);
        } else if (mode == 7) {
            renderPairSummary(out, buf, analysis, ris, points);
        } else if (mode == 8) {
            renderCloseSummary(out, buf, analysis, avgMinDist, ris, points);
        } else if (mode == 9) {
            renderCloseTmrwSummary(out, buf, analysis, us, avgMinDist, ris, points);
        } else if (mode == 10) {
            renderDHTSummary(out, buf, analysis, us, avgMinDist, ris, points);
        } else if (mode == 11) {
            renderDestSummary(out, buf, analysis, avgMinDist, ris, points);
        } else if (mode == 12) {
            // load stored analysis
            PersistSybil ps = analysis.getPersister();
            try {
                points = ps.load(date);
            } catch (IOException ioe) {
                _log.error("loading stored analysis for date: " + date, ioe);
                out.write("<b>Failed to load analysis for " + DataHelper.formatTime(date) + "</b>: " +
                          DataHelper.escapeHTML(ioe.toString()));
                return;
            }
            if (points.isEmpty()) {
                _log.error("empty stored analysis or bad file format for date: " + date);
                out.write("<b>Corrupt analysis file for " + DataHelper.formatTime(date) + "</b>");
            } else {
                renderThreatsHTML(out, buf, date, points);
            }
        } else if (mode == 13 || mode == 16) {
            // run analysis and store it
            long now = _context.clock().now();
            points = analysis.backgroundAnalysis(mode == 16);
            if (!points.isEmpty()) {
                PersistSybil ps = analysis.getPersister();
                try {
                    ps.store(now, points);
                } catch (IOException ioe) {
                    out.write("<b>Failed to store analysis: " + ioe + "</b>");
                }
            }
            renderThreatsHTML(out, buf, now, points);
        } else if (mode == 14) {
            // show run form
            renderRunForm(out, buf, nonce);
        } else if (mode == 15) {
            // show background form
            renderBackgroundForm(out, buf, nonce);
        } else {
            out.write("Unknown mode " + mode);
        }
        writeBuf(out, buf);
    }

    /**
     *  @since 0.9.38
     */
    private void renderOverview(Writer out, StringBuilder buf, String nonce, Analysis analysis) throws IOException {
        PersistSybil ps = analysis.getPersister();
        List<Long> dates = ps.load();
        if (dates.isEmpty()) {
            out.write("No stored analysis");
        } else {
            buf.append("<form action=\"netdb\" method=\"POST\">\n" +
                       "<input type=\"hidden\" name=\"f\" value=\"3\">\n" +
                       "<input type=\"hidden\" name=\"m\" value=\"12\">\n" +
                       "<input type=\"hidden\" name=\"nonce\" value=\"").append(nonce).append("\" >\n" +
                       "Select stored analysis: " +
                       "<select name=\"date\">\n");
            boolean first = true;
            for (Long date : dates) {
                buf.append("<option value=\"").append(date).append('\"');
                if (first) {
                    buf.append(HelperBase.SELECTED);
                    first = false;
                }
                buf.append('>').append(DataHelper.formatTime(date.longValue())).append("</option>\n");
            }        
            buf.append("</select>\n" +
                       "<input type=\"submit\" name=\"action\" class=\"go\" value=\"Review analysis\" />" +
                       "</form>\n");
        }        
        writeBuf(out, buf);
    }

    /**
     *  @since 0.9.38
     */
    private static void renderRunForm(Writer out, StringBuilder buf, String nonce) throws IOException {
        buf.append("<form action=\"netdb\" method=\"POST\">\n" +
                   "<input type=\"hidden\" name=\"f\" value=\"3\">\n" +
                   "<input type=\"hidden\" name=\"m\" value=\"13\">\n" +
                   "<input type=\"hidden\" name=\"nonce\" value=\"").append(nonce).append("\" >\n" +
                   "<input type=\"submit\" name=\"action\" class=\"go\" value=\"Run new analysis\" />" +
                   "(floodfills only)" +
                   "</form><br>\n");
        buf.append("<form action=\"netdb\" method=\"POST\">\n" +
                   "<input type=\"hidden\" name=\"f\" value=\"3\">\n" +
                   "<input type=\"hidden\" name=\"m\" value=\"16\">\n" +
                   "<input type=\"hidden\" name=\"nonce\" value=\"").append(nonce).append("\" >\n" +
                   "<input type=\"submit\" name=\"action\" class=\"go\" value=\"Run new analysis\" />" +
                   "(all routers)" +
                   "</form>\n");
        writeBuf(out, buf);
    }

    /**
     *  @since 0.9.38
     */
    private void renderBackgroundForm(Writer out, StringBuilder buf, String nonce) throws IOException {
        long freq = _context.getProperty(Analysis.PROP_FREQUENCY, Analysis.DEFAULT_FREQUENCY);
        buf.append("<form action=\"netdb\" method=\"POST\">\n" +
                   "<input type=\"hidden\" name=\"f\" value=\"3\">\n" +
                   "<input type=\"hidden\" name=\"m\" value=\"15\">\n" +
                   "<input type=\"hidden\" name=\"nonce\" value=\"").append(nonce).append("\" >\n" +
                   "<table><tr><td>Background analysis run frequency:</td><td><select name=\"runFrequency\">");
        for (int i = 0; i < HOURS.length; i++) {
            buf.append("<option value=\"");
            buf.append(HOURS[i]);
            buf.append('"');
            long time = HOURS[i] * 60*60*1000L;
            if (time == freq)
                buf.append(HelperBase.SELECTED);
            buf.append('>');
            if (HOURS[i] > 0)
                buf.append(DataHelper.formatDuration2(time));
            else
                buf.append(_t("Never"));
            buf.append("</option>\n");
        }
        boolean auto = _context.getProperty(Analysis.PROP_BLOCK, Analysis.DEFAULT_BLOCK);
        boolean nonff = _context.getBooleanProperty(Analysis.PROP_NONFF);
        String thresh = _context.getProperty(Analysis.PROP_THRESHOLD, Double.toString(Analysis.DEFAULT_BLOCK_THRESHOLD));
        long days = _context.getProperty(Analysis.PROP_BLOCKTIME, Analysis.DEFAULT_BLOCK_TIME) / (24*60*60*1000L);
        buf.append("</select></td></tr>\n<tr><td>" +
                   "Auto-block routers?</td><td><input type=\"checkbox\" class=\"optbox\" value=\"1\" name=\"block\" ");
        if (auto)
            buf.append(HelperBase.CHECKED);
        buf.append("></td></tr>\n<tr><td>" +
                   "Include non-floodfills?</td><td><input type=\"checkbox\" class=\"optbox\" value=\"1\" name=\"nonff\" ");
        if (nonff)
            buf.append(HelperBase.CHECKED);
        buf.append("></td></tr>\n<tr><td>" +
                   "Minimum threat points to block:</td><td><input type=\"text\" name=\"threshold\" value=\"").append(thresh).append("\"></td></tr>\n<tr><td>" +
                   "Days to block:</td><td><input type=\"text\" name=\"days\" value=\"").append(days).append("\"></td></tr>\n<tr><td>" +
                   "Delete stored analysis older than:</td><td><select name=\"deleteAge\">");
        long age = _context.getProperty(Analysis.PROP_REMOVETIME, Analysis.DEFAULT_REMOVE_TIME);
        for (int i = 0; i <DAYS.length; i++) {
            buf.append("<option value=\"");
            buf.append(DAYS[i]);
            buf.append('"');
            long time = DAYS[i] * 24*60*60*1000L;
            if (time == age)
                buf.append(HelperBase.SELECTED);
            buf.append('>');
            if (DAYS[i] > 0)
                buf.append(DataHelper.formatDuration2(time));
            else
                buf.append(_t("Never"));
            buf.append("</option>\n");
        }
        buf.append("</td></tr>\n<tr><td></td><td>" +
                   "<input type=\"submit\" name=\"action\" class=\"accept\" value=\"Save\" />" +
                   "</td></tr></table></form>\n");
        writeBuf(out, buf);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderFFSummary(Writer out, StringBuilder buf, List<RouterInfo> ris, double avgMinDist) throws IOException {
        renderRouterInfo(buf, _context.router().getRouterInfo(), null, true, false);
        buf.append("<h3 id=\"known\" class=\"sybils\">Known Floodfills: ").append(ris.size()).append("</h3>");
        buf.append("<div id=\"sybils_summary\">\n" +
                   "<b>Average closest floodfill distance:</b> ").append(fmt.format(avgMinDist)).append("<br>\n" +
                   "<b>Routing Data:</b> \"").append(DataHelper.getUTF8(_context.routerKeyGenerator().getModData()))
           .append("\" <b>Last Changed:</b> ").append(DataHelper.formatTime(_context.routerKeyGenerator().getLastChanged())).append("<br>\n" +
                   "<b>Next Routing Data:</b> \"").append(DataHelper.getUTF8(_context.routerKeyGenerator().getNextModData()))
           .append("\" <b>Rotates in:</b> ").append(DataHelper.formatDuration(_context.routerKeyGenerator().getTimeTillMidnight())).append("\n" +
                   "</div>\n");
        writeBuf(out, buf);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderFamilySummary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        Map<String, List<RouterInfo>> fmap = analysis.calculateIPGroupsFamily(ris, points);
        renderIPGroupsFamily(out, buf, fmap);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderIPUsSummary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        List<RouterInfo> ri32 = new ArrayList<RouterInfo>(4);
        List<RouterInfo> ri24 = new ArrayList<RouterInfo>(4);
        List<RouterInfo> ri16 = new ArrayList<RouterInfo>(4);
        analysis.calculateIPGroupsUs(ris, points, ri32, ri24, ri16);
        renderIPGroupsUs(out, buf, ri32, ri24, ri16);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderIP32Summary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        Map<Integer, List<RouterInfo>> map = analysis.calculateIPGroups32(ris, points);
        renderIPGroups32(out, buf, map);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderIP24Summary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        Map<Integer, List<RouterInfo>> map = analysis.calculateIPGroups24(ris, points);
        renderIPGroups24(out, buf, map);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderIP16Summary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        Map<Integer, List<RouterInfo>> map = analysis.calculateIPGroups16(ris, points);
        renderIPGroups16(out, buf, map);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderPairSummary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        // Pairwise distance analysis
        List<Pair> pairs = new ArrayList<Pair>(PAIRMAX);
        double avg = analysis.calculatePairDistance(ris, points, pairs);
        renderPairDistance(out, buf, pairs, avg);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderCloseSummary(Writer out, StringBuilder buf, Analysis analysis, double avgMinDist, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        // Distance to our router analysis
        buf.append("<h3 id=\"ritoday\" class=\"sybils\">Closest Floodfills to Our Routing Key (Where we Store our RI)</h3>");
        buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil\">See all</a></p>");
        Hash ourRKey = _context.router().getRouterInfo().getRoutingKey();
        analysis.calculateRouterInfo(ourRKey, "our rkey", ris, points);
        renderRouterInfoHTML(out, buf, ourRKey, avgMinDist, ris);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderCloseTmrwSummary(Writer out, StringBuilder buf, Analysis analysis, Hash us, double avgMinDist, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        // Distance to our router analysis
        RouterKeyGenerator rkgen = _context.routerKeyGenerator();
        Hash nkey = rkgen.getNextRoutingKey(us);
        buf.append("<h3 id=\"ritmrw\" class=\"sybils\">Closest Floodfills to Tomorrow's Routing Key (Where we will Store our RI)</h3>");
        buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil\">See all</a></p>");
        analysis.calculateRouterInfo(nkey, "our rkey (tomorrow)", ris, points);
        renderRouterInfoHTML(out, buf, nkey, avgMinDist, ris);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderDHTSummary(Writer out, StringBuilder buf, Analysis analysis, Hash us, double avgMinDist, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        buf.append("<h3 id=\"dht\" class=\"sybils\">Closest Floodfills to Our Router Hash (DHT Neighbors if we are Floodfill)</h3>");
        analysis.calculateRouterInfo(us, "our router", ris, points);
        renderRouterInfoHTML(out, buf, us, avgMinDist, ris);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderDestSummary(Writer out, StringBuilder buf, Analysis analysis, double avgMinDist, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        // Distance to our published destinations analysis
        RouterKeyGenerator rkgen = _context.routerKeyGenerator();
        buf.append("<h3 id=\"dest\" class=\"sybils\">Floodfills Close to Our Destinations</h3>");
        Map<Hash, TunnelPool> clientInboundPools = _context.tunnelManager().getInboundClientPools();
        List<Hash> destinations = new ArrayList<Hash>(clientInboundPools.keySet());
        for (Iterator<Hash> iter = destinations.iterator(); iter.hasNext(); ) {
            Hash client = iter.next();
            if (!_context.clientManager().isLocal(client) ||
                !_context.clientManager().shouldPublishLeaseSet(client) ||
                _context.netDb().lookupLeaseSetLocally(client) == null) {
                iter.remove();
            }
        }
        if (destinations.isEmpty()) {
            buf.append("<p class=\"notfound\">None</p>");
            writeBuf(out, buf);
            return;
        }
        for (Hash client : destinations) {
            LeaseSet ls = _context.netDb().lookupLeaseSetLocally(client);
            if (ls == null)
                continue;
            Hash rkey = ls.getRoutingKey();
            TunnelPool in = clientInboundPools.get(client);
            String name = (in != null) ? DataHelper.escapeHTML(in.getSettings().getDestinationNickname()) : client.toBase64().substring(0,4);
            buf.append("<h3 class=\"sybils\">Closest floodfills to the Routing Key for " + name + " (where we store our LS)</h3>");
            buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil=" + ls.getHash().toBase64() + "\">See all</a></p>");
            analysis.calculateRouterInfo(rkey, name, ris, points);
            renderRouterInfoHTML(out, buf, rkey, avgMinDist, ris);
            Hash nkey = rkgen.getNextRoutingKey(ls.getHash());
            buf.append("<h3 class=\"sybils\">Closest floodfills to Tomorrow's Routing Key for " + name + " (where we will store our LS)</h3>");
            buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil=" + ls.getHash().toBase64() + "\">See all</a></p>");
            analysis.calculateRouterInfo(nkey, name + " (tomorrow)", ris, points);
            renderRouterInfoHTML(out, buf, nkey, avgMinDist, ris);
        }
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderThreatsHTML(Writer out, StringBuilder buf, long date, Map<Hash, Points> points) throws IOException {
        double threshold = Analysis.DEFAULT_BLOCK_THRESHOLD;
        try {
            threshold = Double.parseDouble(_context.getProperty(Analysis.PROP_THRESHOLD, Double.toString(threshold)));
            if (threshold < Analysis.MIN_BLOCK_POINTS)
                threshold = Analysis.MIN_BLOCK_POINTS;
        } catch (NumberFormatException nfe) {}
        final double minDisplay = Math.min(threshold, MIN_DISPLAY_POINTS);
        if (!points.isEmpty()) {
            List<Hash> warns = new ArrayList<Hash>(points.keySet());
            Collections.sort(warns, new PointsComparator(points));
            ReasonComparator rcomp = new ReasonComparator();
            buf.append("<h3 id=\"threats\" class=\"sybils\">Routers with Most Threat Points as of " + DataHelper.formatTime(date) + "</h3>");
            for (Hash h : warns) {
                Points pp = points.get(h);
                double p = pp.getPoints();
                if (p < minDisplay)
                    break;  // sorted
                buf.append("<p class=\"threatpoints\"><b>Threat Points: " + fmt.format(p) + "</b></p><ul>");
                List<String> reasons = pp.getReasons();
                if (reasons.size() > 1)
                    Collections.sort(reasons, rcomp);
                for (String s : reasons) {
                    int c = s.indexOf(':');
                    if (c <= 0)
                        continue;
                    buf.append("<li><b>").append(s, 0, c+1).append("</b>").append(s, c+1, s.length()).append("</li>");
                }
                buf.append("</ul>");
                RouterInfo ri = _context.netDb().lookupRouterInfoLocally(h);
                if (ri != null) {
                    renderRouterInfo(buf, ri, null, false, false);
                } else {
                    String hash = h.toBase64();
                    buf.append("<a name=\"").append(hash, 0, 6).append("\"></a><table class=\"sybil_routerinfo\"><tr>" +
                               "<th><b>" + _t("Router") + ":</b> <code>").append(hash).append("</code></th>" +
                               "<th><b>Router info not available</b></th><th></th></tr></table>\n");
                }
            }
        }
        writeBuf(out, buf);
    }

    /**
     *  @param pairs sorted
     */
    private void renderPairDistance(Writer out, StringBuilder buf, List<Pair> pairs, double avg) throws IOException {
        buf.append("<h3 class=\"sybils\">Average Floodfill Distance is ").append(fmt.format(avg)).append("</h3>" +
                   "<h3 id=\"pairs\" class=\"sybils\">Closest Floodfill Pairs by Hash</h3>");

        for (Pair p : pairs) {
            double distance = biLog2(p.dist);
            double point = MIN_CLOSE - distance;
            // limit display
            if (point < 2)
                break;  // sorted;
            buf.append("<p class=\"hashdist\"><b>Hash Distance: ").append(fmt.format(distance)).append("</b>" +
                       "</p>");
            renderRouterInfo(buf, p.r1, null, false, false);
            renderRouterInfo(buf, p.r2, null, false, false);
        }
        writeBuf(out, buf);
    }

    private static class FooComparator implements Comparator<Integer>, Serializable {
         private final Map<Integer, List<RouterInfo>> _o;
         public FooComparator(Map<Integer, List<RouterInfo>> o) { _o = o;}
         public int compare(Integer l, Integer r) {
             // reverse by count
             int rv = _o.get(r).size() - _o.get(l).size();
             if (rv != 0)
                 return rv;
             // foward by IP
             return l.intValue() - r.intValue();
        }
    }

    private static class FoofComparator implements Comparator<String>, Serializable {
         private final Map<String, List<RouterInfo>> _o;
         private final Collator _comp = Collator.getInstance();
         public FoofComparator(Map<String, List<RouterInfo>> o) { _o = o;}
         public int compare(String l, String r) {
             // reverse by count
             int rv = _o.get(r).size() - _o.get(l).size();
             if (rv != 0)
                 return rv;
             // foward by name
             return _comp.compare(l, r);
        }
    }

    /**
     *
     */
    private void renderIPGroupsUs(Writer out, StringBuilder buf, List<RouterInfo> ri32,
                                  List<RouterInfo> ri24, List<RouterInfo> ri16) throws IOException {
        buf.append("<h3 id=\"ourIP\" class=\"sybils\">Routers close to Our IP</h3>");
        boolean found = false;
        for (RouterInfo info : ri32) {
             buf.append("<p id=\"sybil_info\"><b>");
             buf.append("Same IP as us");
             buf.append(":</b></p>");
             renderRouterInfo(buf, info, null, false, false);
             found = true;
        }
        for (RouterInfo info : ri24) {
             buf.append("<p id=\"sybil_info\"><b>");
             buf.append("Same /24 as us");
             buf.append(":</b></p>");
             renderRouterInfo(buf, info, null, false, false);
             found = true;
        }
        for (RouterInfo info : ri16) {
             buf.append("<p id=\"sybil_info\"><b>");
             buf.append("Same /16 as us");
             buf.append(":</b></p>");
             renderRouterInfo(buf, info, null, false, false);
             found = true;
        }
        if (!found)
            buf.append("<p class=\"notfound\">None</p>");
        writeBuf(out, buf);
    }

    /**
     *
     */
    private void renderIPGroups32(Writer out, StringBuilder buf, Map<Integer, List<RouterInfo>> map) throws IOException {
        buf.append("<h3 id=\"sameIP\" class=\"sybils\">Routers with the Same IP</h3>");
        List<Integer> foo = new ArrayList<Integer>(map.keySet());
        Collections.sort(foo, new FooComparator(map));
        boolean found = false;
        for (Integer ii : foo) {
            List<RouterInfo> ris = map.get(ii);
            int count = ris.size();
            int i = ii.intValue();
            int i0 = (i >> 24) & 0xff;
            int i1 = (i >> 16) & 0xff;
            int i2 = (i >> 8) & 0xff;
            int i3 = i & 0xff;
            String sip = i0 + "." + i1 + '.' + i2 + '.' + i3;
            buf.append("<p class=\"sybil_info\"><b>").append(count).append(" routers with IP <a href=\"/netdb?ip=")
               .append(sip).append("&amp;sybil\">").append(sip)
               .append("</a>:</b></p>");
            for (RouterInfo info : ris) {
                found = true;
                renderRouterInfo(buf, info, null, false, false);
            }
        }
        if (!found)
            buf.append("<p class=\"notfound\">None</p>");
        writeBuf(out, buf);
    }

    /**
     *
     */
    private void renderIPGroups24(Writer out, StringBuilder buf, Map<Integer, List<RouterInfo>> map) throws IOException {
        buf.append("<h3 id=\"same24\" class=\"sybils\">Routers in the Same /24 (2 minimum)</h3>");
        List<Integer> foo = new ArrayList<Integer>(map.keySet());
        Collections.sort(foo, new FooComparator(map));
        boolean found = false;
        for (Integer ii : foo) {
            List<RouterInfo> ris = map.get(ii);
            int count = ris.size();
            int i = ii.intValue();
            int i0 = i >> 16;
            int i1 = (i >> 8) & 0xff;
            int i2 = i & 0xff;
            String sip = i0 + "." + i1 + '.' + i2 + ".0/24";
            buf.append("<p class=\"sybil_info\"><b>").append(count).append(" routers with IP <a href=\"/netdb?ip=")
               .append(sip).append("&amp;sybil\">").append(sip)
               .append("</a>:</b></p>");
            for (RouterInfo info : ris) {
                found = true;
                renderRouterInfo(buf, info, null, false, false);
            }
        }
        if (!found)
            buf.append("<p class=\"notfound\">None</p>");
        writeBuf(out, buf);
    }

    /**
     *
     */
    private void renderIPGroups16(Writer out, StringBuilder buf, Map<Integer, List<RouterInfo>> map) throws IOException {
        buf.append("<h3 id=\"same16\" class=\"sybils\">Routers in the Same /16 (4 minimum)</h3>");
        List<Integer> foo = new ArrayList<Integer>(map.keySet());
        Collections.sort(foo, new FooComparator(map));
        boolean found = false;
        for (Integer ii : foo) {
            List<RouterInfo> ris = map.get(ii);
            int count = ris.size();
            int i = ii.intValue();
            int i0 = i >> 8;
            int i1 = i & 0xff;
            String sip = i0 + "." + i1 + ".0.0/16";
            buf.append("<p class=\"sybil_info\"><b>").append(count).append(" routers with IP <a href=\"/netdb?ip=")
               .append(sip).append("&amp;sybil\">").append(sip)
               .append("</a></b></p>");
            for (RouterInfo info : ris) {
                found = true;
                renderRouterInfo(buf, info, null, false, false);
            }
        }
        if (!found)
            buf.append("<p class=\"notfound\">None</p>");
        writeBuf(out, buf);
    }

    /**
     *
     */
    private void renderIPGroupsFamily(Writer out, StringBuilder buf, Map<String, List<RouterInfo>> map) throws IOException {
        buf.append("<h3 id=\"samefamily\" class=\"sybils\">Routers in the same Family</h3><div class=\"sybil_container\">");
        List<String> foo = new ArrayList<String>(map.keySet());
        Collections.sort(foo, new FoofComparator(map));
        FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
        String ourFamily = fkc != null ? fkc.getOurFamilyName() : null;
        boolean found = false;
        for (String s : foo) {
            List<RouterInfo> list = map.get(s);
            int count = list.size();
            String ss = DataHelper.escapeHTML(s);
            if (count > 1) {
                buf.append("<p class=\"family\"><b>").append(count).append(" routers in family: &nbsp;<a href=\"/netdb?fam=")
                   .append(ss).append("&amp;sybil\">").append(ss).append("</a></b></p>");
                found = true;
            }
            //for (RouterInfo info : ris) {
                // limit display
                //renderRouterInfo(buf, info, null, false, false);
            //}
        }
        if (!found)
            buf.append("<p class=\"notfound\">None</p>");
        buf.append("</div>");
        writeBuf(out, buf);
    }

    /**
     *  Render routers closer than MIN_CLOSE up to MAX routers
     *  @param ris sorted, closest first
     *  @param usName HTML escaped
     */
    private void renderRouterInfoHTML(Writer out, StringBuilder buf, Hash us, double avgMinDist,
                                      List<RouterInfo> ris) throws IOException {
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
            if (dist < MIN_CLOSE)
                break;
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
        }
        double avg = tot / count;
        buf.append("<p id=\"sybil_totals\"><b>Totals for " + count +
                   " floodfills: &nbsp;</b><span class=\"netdb_name\">MIN:</span > " + fmt.format(min) +
                   "&nbsp; <span class=\"netdb_name\">AVG:</span> " + fmt.format(avg) +
                   "&nbsp; <span class=\"netdb_name\">MEDIAN:</span> " + fmt.format(median) +
                   "&nbsp; <span class=\"netdb_name\">MAX:</span> " + fmt.format(max) +
                   "</p>\n");
        writeBuf(out, buf);
    }

    private static void writeBuf(Writer out, StringBuilder buf) throws IOException {
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
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
        buf.append("<a name=\"").append(hash, 0, 6).append("\"></a><table class=\"sybil_routerinfo\"><tr>");
        double distance = 0;
        if (isUs) {
            buf.append("<th colspan=\"2\"><a name=\"our-info\" ></a><b>" + _t("Our info") + ":</b> <code>").append(hash)
               .append("</code></th></tr>\n<tr><td class=\"sybilinfo_params\" colspan=\"2\"><div class=\"sybilinfo_container\">");
        } else {
            buf.append("<th><b>" + _t("Router") + ":</b> <code>").append(hash).append("</code>\n");

            String country = _context.commSystem().getCountry(info.getIdentity().getHash());
            buf.append("</th><th>");
            if(country != null) {
                buf.append("<a href=\"/netdb?c=").append(country).append("\">" +
                           "<img height=\"11\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append('\"' +
                           " title=\"").append(getTranslatedCountry(country)).append('\"' +
                           " src=\"/flags.jsp?c=").append(country).append("\"> ").append("</a>");
            }
            if (!full) {
                buf.append("<a title=\"View extended router info\" class=\"viewfullentry\" href=\"netdb?r=")
                   .append(hash, 0, 6).append("\" >[").append(_t("Full entry")).append("]</a>");
                buf.append("<a title=\"View profile data\" class=\"viewfullentry\" href=\"viewprofile?peer=")
                   .append(hash).append("\" >[").append(_t("profile")).append("]</a>");
                buf.append("<a title=\"").append(_t("Configure peer")).append("\" class=\"viewfullentry\" href=\"configpeer?peer=")
                   .append(hash).append("\" >+-</a></th><th>");
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
        buf.append("<p><b>").append(_t("Version")).append(":</b> ").append(DataHelper.stripHTML(info.getVersion())).append("</p>\n" +
                   "<p><b>").append("Caps").append(":</b> ").append(DataHelper.stripHTML(info.getCapabilities())).append("</p>\n");
        String kr = info.getOption("netdb.knownRouters");
;
        if (kr != null) {
            buf.append("<p><b>Routers:</b> ").append(DataHelper.stripHTML(kr)).append("</p>");
        //} else {
        //    buf.append("<p class=\"sybil_filler\"><b>Routers:</b> ").append(_t("n/a")).append("</p>");
        }
        String kls = info.getOption("netdb.knownLeaseSets");
        if (kls != null) {
            buf.append("<p class=\"sybilinfo_leasesets\"><b>").append(_t("LeaseSets")).append(":</b> ").append(DataHelper.stripHTML(kls)).append("</p>\n");
        //} else {
        //    buf.append("<p class=\"sybilinfo_leasesets filler\"><b>").append(_t("LeaseSets")).append(":</b> ").append(_t("n/a")).append("</p>");
        }
        String fam = info.getOption("family");
        if (fam != null) {
            buf.append("<p><b>").append(_t("Family")).append(":</b> <span class=\"sybilinfo_familyname\">").append(DataHelper.escapeHTML(fam)).append("</span></p>\n");
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
                    buf.append("<p><b>").append(_t("Last Heard About")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>");
                } else {
                    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last Heard About")).append(":</b> ").append(_t("n/a")).append("</p>");
                }
                heard = prof.getLastHeardFrom();
                if (heard > 0) {
                    long age = Math.max(now - heard, 1);
                    buf.append("<p><b>").append(_t("Last Heard From")).append("</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
                } else {
                    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last Heard From")).append(":</b> ").append(_t("n/a")).append("</p>");
                }
                DBHistory dbh = prof.getDBHistory();
                if (dbh != null) {
                    heard = dbh.getLastLookupSuccessful();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>").append(_t("Last Good Lookup")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>");
                    //} else {
                    //    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last Good Lookup")).append(":</b> ").append(_t("n/a")).append("</p>");
                    }
                    heard = dbh.getLastLookupFailed();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>").append(_t("Last Bad Lookup")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>");
                    //} else {
                    //    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last Bad Lookup")).append(":</b> ").append(_t("n/a")).append("</p>");
                    }
                    heard = dbh.getLastStoreSuccessful();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>").append(_t("Last Good Store")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>");
                    //} else {
                    //    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last Good Store")).append(":</b> ").append(_t("n/a")).append("</p>");
                    }
                    heard = dbh.getLastStoreFailed();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>").append(_t("Last Bad Store")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>");
                    //} else {
                    //    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last Bad Store")).append(":</b> ").append(_t("n/a")).append("</p>");
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
        buf.append("<p class=\"sybil_filler\">&nbsp;</p>" +
                   "</div></td></tr><tr><td class=\"sybil_addresses\" colspan=\"3\"><table><tr><td><b>" + _t("Addresses") + ":</b></td><td>");
        Collection<RouterAddress> addrs = info.getAddresses();
        if (addrs.size() > 1) {
            // addrs is unmodifiable
            List<RouterAddress> laddrs = new ArrayList<RouterAddress>(addrs);
            Collections.sort(laddrs, new NetDbRenderer.RAComparator());
            addrs = laddrs;
        }
        for (RouterAddress addr : addrs) {
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
        buf.append("</table></td></tr>\n" +
                   "</table>\n");
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
        DateFormat utcfmt = DateFormat.getDateInstance(DateFormat.MEDIUM);
        for (int i = start; i <= days; i++) {
            out.write("<h3 class=\"tabletitle\">Distance for " + utcfmt.format(new Date(now)) +
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
