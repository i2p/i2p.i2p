package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

/**
 *  For /tunnels.jsp, used by TunnelHelper.
 */
class TunnelRenderer {
    private RouterContext _context;

    private static final int DISPLAY_LIMIT = 200;

    public TunnelRenderer(RouterContext ctx) {
        _context = ctx;
    }

    public void renderStatusHTML(Writer out) throws IOException {
        TunnelPool ei = _context.tunnelManager().getInboundExploratoryPool();
        TunnelPool eo = _context.tunnelManager().getOutboundExploratoryPool();
        out.write("<h3 class=\"tabletitle\" id=\"exploratorytunnels\"><a name=\"exploratory\" ></a>" + _t("Exploratory tunnels"));
        // links are set to float:right in CSS so they will be displayed in reverse order
        out.write(" <a href=\"/configtunnels#exploratory\" title=\"" + _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
        writeGraphLinks(out, ei, eo);
        out.write("</h3>\n");
        renderPool(out, ei, eo);

        List<Hash> destinations = null;
        Map<Hash, TunnelPool> clientInboundPools = _context.tunnelManager().getInboundClientPools();
        Map<Hash, TunnelPool> clientOutboundPools = _context.tunnelManager().getOutboundClientPools();
        destinations = new ArrayList<Hash>(clientInboundPools.keySet());
        boolean debug = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        for (int i = 0; i < destinations.size(); i++) {
            Hash client = destinations.get(i);
            boolean isLocal = _context.clientManager().isLocal(client);
            if ((!isLocal) && (!debug))
                continue;
            TunnelPool in = clientInboundPools.get(client);
            TunnelPool outPool = clientOutboundPools.get(client);
            if ((in != null && in.getSettings().getAliasOf() != null) ||
                (outPool != null && outPool.getSettings().getAliasOf() != null)) {
                // skip aliases, we will print a header under the main tunnel pool below
                continue;
            }
            // TODO the following code is duplicated in SummaryHelper
            String name = (in != null) ? in.getSettings().getDestinationNickname() : null;
            if ( (name == null) && (outPool != null) )
                name = outPool.getSettings().getDestinationNickname();
            if (name == null)
                name = client.toBase64().substring(0,4);
            out.write("<h3 class=\"tabletitle\" id=\"" + client.toBase64().substring(0,4)
                      + "\" >" + _t("Client tunnels for") + ' ' + DataHelper.escapeHTML(_t(name)));
            if (isLocal) {
                // links are set to float:right in CSS so they will be displayed in reverse order
                out.write(" <a href=\"/configtunnels#" + client.toBase64().substring(0,4) +"\" title=\"" + _t("Configure tunnels for session") + "\">[" + _t("configure") + "]</a>");
                writeGraphLinks(out, in, outPool);
                out.write("</h3>\n");
            } else {
                out.write(" (" + _t("dead") + ")</h3>\n");
            }
            if (in != null) {
                // list aliases
                Set<Hash> aliases = in.getSettings().getAliases();
                if (aliases != null) {
                    for (Hash a : aliases) {
                        TunnelPool ain = clientInboundPools.get(a);
                        if (ain != null) {
                            String aname = ain.getSettings().getDestinationNickname();
                            if (aname == null)
                                aname = a.toBase64().substring(0,4);
                            out.write("<h3 class=\"tabletitle\" id=\"" + a.toBase64().substring(0,4)
                                      + "\" >" + _t("Client tunnels for") + ' ' + DataHelper.escapeHTML(_t(aname)));
                            if (isLocal)
                                out.write(" <a href=\"/configtunnels#" + client.toBase64().substring(0,4) +"\" title=\"" + _t("Configure tunnels for session") + "\">[" + _t("configure") + "]</a></h3>\n");
                            else
                                out.write(" (" + _t("dead") + ")</h3>\n");
                        }
                    }
                }     
            }         
            renderPool(out, in, outPool);
        }

        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        out.write("<h3 class=\"tabletitle\" id=\"participating\">" + _t("Participating tunnels") + "</h3>\n");
        int bwShare = getShareBandwidth();
        if (bwShare > 12) {
        // Don't bother re-indenting
        if (!participating.isEmpty()) {
            Collections.sort(participating, new TunnelComparator());
            out.write("<table class=\"tunneldisplay tunnels_participating\"><tr><th>" + _t("Receive on") + "</th><th>" + _t("From") + "</th><th>"
                  + _t("Send on") + "</th><th>" + _t("To") + "</th><th>" + _t("Expiration") + "</th>"
                  + "<th>" + _t("Usage") + "</th><th>" + _t("Rate") + "</th><th>" + _t("Role") + "</th></tr>\n");
        }
        long processed = 0;
        RateStat rs = _context.statManager().getRate("tunnel.participatingMessageCount");
        if (rs != null)
            processed = (long)rs.getRate(10*60*1000).getLifetimeTotalValue();
        int inactive = 0;
        int displayed = 0;
        for (int i = 0; i < participating.size(); i++) {
            HopConfig cfg = participating.get(i);
            int count = cfg.getProcessedMessagesCount();
            if (count <= 0) {
                inactive++;
                continue;
            }
            // everything that isn't 'recent' is already in the tunnel.participatingMessageCount stat
            processed += cfg.getRecentMessagesCount();
            if (++displayed > DISPLAY_LIMIT)
                continue;
            out.write("<tr>");
            if (cfg.getReceiveTunnel() != null)
                out.write("<td class=\"cells\" align=\"center\" title=\"" + _t("Tunnel identity") + "\"><span class=\"tunnel_id\">" +
                          cfg.getReceiveTunnel().getTunnelId() + "</span></td>");
            else
                out.write("<td class=\"cells\" align=\"center\">n/a</td>");
            if (cfg.getReceiveFrom() != null)
                out.write("<td class=\"cells\" align=\"center\"><span class=\"tunnel_peer\">" + netDbLink(cfg.getReceiveFrom()) +"</span></td>");
            else
                out.write("<td class=\"cells\">&nbsp;</td>");
            if (cfg.getSendTunnel() != null)
                out.write("<td class=\"cells\" align=\"center\" title=\"" + _t("Tunnel identity") + "\"><span class=\"tunnel_id\">" + cfg.getSendTunnel().getTunnelId() +"</span></td>");
            else
                out.write("<td class=\"cells\">&nbsp;</td>");
            if (cfg.getSendTo() != null)
                out.write("<td class=\"cells\" align=\"center\"><span class=\"tunnel_peer\">" + netDbLink(cfg.getSendTo()) +"</span></td>");
            else
                out.write("<td class=\"cells\">&nbsp;</td>");
            long timeLeft = cfg.getExpiration()-_context.clock().now();
            if (timeLeft > 0)
                out.write("<td class=\"cells\" align=\"center\">" + DataHelper.formatDuration2(timeLeft) + "</td>");
            else
                out.write("<td class=\"cells\" align=\"center\">(" + _t("grace period") + ")</td>");
            out.write("<td class=\"cells\" align=\"center\">" + (count * 1024 / 1000) + " KB</td>");
            int lifetime = (int) ((_context.clock().now() - cfg.getCreation()) / 1000);
            if (lifetime <= 0)
                lifetime = 1;
            if (lifetime > 10*60)
                lifetime = 10*60;
            int bps = 1024 * count / lifetime;
            out.write("<td class=\"cells\" align=\"center\">" + bps + " Bps</td>");
            if (cfg.getSendTo() == null)
                out.write("<td class=\"cells\" align=\"center\">" + _t("Outbound Endpoint") + "</td>");
            else if (cfg.getReceiveFrom() == null)
                out.write("<td class=\"cells\" align=\"center\">" + _t("Inbound Gateway") + "</td>");
            else
                out.write("<td class=\"cells\" align=\"center\">" + _t("Participant") + "</td>");
            out.write("</tr>\n");
        }
        if (!participating.isEmpty())
            out.write("</table>\n");
        if (displayed > DISPLAY_LIMIT)
            out.write("<div class=\"statusnotes\"><b>" + _t("Limited display to the {0} tunnels with the highest usage", DISPLAY_LIMIT)  + "</b></div>\n");
        if (inactive > 0)
            out.write("<div class=\"statusnotes\"><b>" + _t("Inactive participating tunnels") + ":&nbsp;&nbsp;" + inactive + "</b></div>\n");
        else if (displayed <= 0)
            out.write("<div class=\"statusnotes\"><b>" + _t("none") + "</b></div>\n");
        out.write("<div class=\"statusnotes\"><b>" + _t("Lifetime bandwidth usage") + ":&nbsp;&nbsp;" + DataHelper.formatSize2Decimal(processed*1024) + "B</b></div>\n");
        } else {   // bwShare > 12
            out.write("<div class=\"statusnotes noparticipate\"><b>" + _t("Not enough shared bandwidth to build participating tunnels.") +
                      "</b> <a href=\"config\">[" + _t("Configure") + "]</a></div>\n");
        }
        //renderPeers(out);

        out.write("<h3 class=\"tabletitle\">" + _t("Bandwidth Tiers") + "</h3>\n");
        out.write("<table id=\"tunnel_defs\"><tbody>");
        out.write("<tr><td>&nbsp;</td>"
                  + "<td><span class=\"tunnel_cap\"><b>L</b></span></td><td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_L, Router.MIN_BW_M)) + "</td>"
                  + "<td><span class=\"tunnel_cap\"><b>M</b></span></td><td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_M, Router.MIN_BW_N)) + "</td>"
                  + "<td>&nbsp;</td></tr>");
        out.write("<tr><td>&nbsp;</td>"
                  + "<td><span class=\"tunnel_cap\"><b>N</b></span></td><td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_N, Router.MIN_BW_O)) + "</td>"
                  + "<td><span class=\"tunnel_cap\"><b>O</b></span></td><td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_O, Router.MIN_BW_P)) + "</td>"
                  + "<td>&nbsp;</td></tr>");
        out.write("<tr><td>&nbsp;</td>"
                  + "<td><span class=\"tunnel_cap\"><b>P</b></span></td><td>" + _t("{0} shared bandwidth", range(Router.MIN_BW_P, Router.MIN_BW_X)) + "</td>"
                  + "<td><span class=\"tunnel_cap\"><b>X</b></span></td><td>" + _t("Over {0} shared bandwidth", Math.round(Router.MIN_BW_X * 1.024f) + " KBps") + "</td>"
                  + "<td>&nbsp;</td></tr>");
        out.write("</tbody></table>");

    }

    /** @since 0.9.33 */
    static String range(int f, int t) {
        return Math.round(f * 1.024f) + " - " + (Math.round(t * 1.024f) - 1) + " KBps";
    }

    private static class TunnelComparator implements Comparator<HopConfig>, Serializable {
         public int compare(HopConfig l, HopConfig r) {
             return (r.getProcessedMessagesCount() - l.getProcessedMessagesCount());
        }
    }

    /** @since 0.9.35 */
    private static class TunnelInfoComparator implements Comparator<TunnelInfo>, Serializable {
         public int compare(TunnelInfo l, TunnelInfo r) {
             long le = l.getExpiration();
             long re = r.getExpiration();
             if (le < re)
                 return -1;
             if (le > re)
                 return 1;
             return 0;
        }
    }

    /** @since 0.9.35 */
    private void writeGraphLinks(Writer out, TunnelPool in, TunnelPool outPool) throws IOException {
        if (in == null || outPool == null)
            return;
        String irname = in.getRateName();
        String orname = outPool.getRateName();
        RateStat irs = _context.statManager().getRate(irname);
        RateStat ors = _context.statManager().getRate(orname);
        if (irs == null || ors == null)
            return;
        Rate ir = irs.getRate(5*60*1000L);
        Rate or = ors.getRate(5*60*1000L);
        if (ir == null || or == null)
            return;
        final String tgd = _t("Graph Data");
        final String tcg = _t("Configure Graph Display");
        // links are set to float:right in CSS so they will be displayed in reverse order
        if (or.getSummaryListener() != null) {
            out.write("<a href=\"graph?stat=" + orname + ".300000&amp;w=600&amp;h=200\">" +
                      "<img src=\"/themes/console/images/outbound.png\" alt=\"" + tgd + "\" title=\"" + tgd + "\"></a>");
        } else {
            out.write("<a href=\"configstats#" + orname + "\">" +
                      "<img src=\"/themes/console/images/outbound.png\" alt=\"" + tcg + "\" title=\"" + tcg + "\"></a>");
        }
        if (ir.getSummaryListener() != null) {
            out.write("<a href=\"graph?stat=" + irname + ".300000&amp;w=600&amp;h=200\">" +
                      "<img src=\"/themes/console/images/inbound.png\" alt=\"" + tgd + "\" title=\"" + tgd + "\"></a> ");
        } else {
            out.write("<a href=\"configstats#" + irname + "\">" +
                      "<img src=\"/themes/console/images/inbound.png\" alt=\"" + tcg + "\" title=\"" + tcg + "\"></a>");
        }
    }

    private void renderPool(Writer out, TunnelPool in, TunnelPool outPool) throws IOException {
        Comparator<TunnelInfo> comp = new TunnelInfoComparator();
        List<TunnelInfo> tunnels;
        if (in == null) {
            tunnels = new ArrayList<TunnelInfo>();
        } else {
            tunnels = in.listTunnels();
            Collections.sort(tunnels, comp);
        }
        if (outPool != null) {
            List<TunnelInfo> otunnels = outPool.listTunnels();
            Collections.sort(otunnels, comp);
            tunnels.addAll(otunnels);
        }

        long processedIn = (in != null ? in.getLifetimeProcessed() : 0);
        long processedOut = (outPool != null ? outPool.getLifetimeProcessed() : 0);

        int live = 0;
        int maxLength = 1;
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            int length = info.getLength();
            if (length > maxLength)
                maxLength = length;
        }
        out.write("<table class=\"tunneldisplay tunnels_client\"><tr><th title=\"" + _t("Inbound or outbound?") + ("\">") + _t("In/Out")
                  + "</th><th>" + _t("Expiry") + "</th><th>" + _t("Usage") + "</th><th>" + _t("Gateway") + "</th>");
        if (maxLength > 3) {
            out.write("<th align=\"center\" colspan=\"" + (maxLength - 2));
            out.write("\">" + _t("Participants") + "</th>");
        }
        else if (maxLength == 3) {
            out.write("<th>" + _t("Participant") + "</th>");
        }
        if (maxLength > 1) {
            out.write("<th>" + _t("Endpoint") + "</th>");
        }
        out.write("</tr>\n");
        final String tib = _t("Inbound");
        final String tob = _t("Outbound");
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            long timeLeft = info.getExpiration()-_context.clock().now();
            if (timeLeft <= 0)
                continue; // don't display tunnels in their grace period
            live++;
            boolean isInbound = info.isInbound();
            if (isInbound)
                out.write("<tr><td class=\"cells\" align=\"center\"><img src=\"/themes/console/images/inbound.png\" alt=\"" + tib + "\" title=\"" +
                          tib + "\"></td>");
            else
                out.write("<tr><td class=\"cells\" align=\"center\"><img src=\"/themes/console/images/outbound.png\" alt=\"" + tob + "\" title=\"" +
                          tob + "\"></td>");
            out.write("<td class=\"cells\" align=\"center\">" + DataHelper.formatDuration2(timeLeft) + "</td>\n");
            int count = info.getProcessedMessagesCount() * 1024 / 1000;
            out.write("<td class=\"cells\" align=\"center\">" + count + " KB</td>\n");
            int length = info.getLength();
            for (int j = 0; j < length; j++) {
                Hash peer = info.getPeer(j);
                TunnelId id = (isInbound ? info.getReceiveTunnelId(j) : info.getSendTunnelId(j));
                if (_context.routerHash().equals(peer)) {
                    if (length < maxLength && length == 1 && isInbound) {
                        // pad before inbound zero hop
                        for (int k = 1; k < maxLength; k++) {
                            out.write("<td class=\"cells\" align=\"center\">&nbsp;</td>");
                        }
                    }
                    // Add empty content placeholders to force alignment.
                    out.write(" <td class=\"cells\" align=\"center\"><span class=\"tunnel_peer tunnel_local\" title=\"" +
                              _t("Locally hosted tunnel") + "\">" + _t("Local") + "</span>&nbsp;<span class=\"tunnel_id\" title=\"" +
                              _t("Tunnel identity") + "\">" + (id == null ? "" : "" + id) +
                              "</span><b class=\"tunnel_cap\" title=\"" + _t("Bandwidth tier") + "\"></b></td>");
                } else {
                    String cap = getCapacity(peer);
                    out.write(" <td class=\"cells\" align=\"center\"><span class=\"tunnel_peer\">" + netDbLink(peer) +
                              "</span>&nbsp;<span class=\"nowrap\"><span class=\"tunnel_id\" title=\"" + _t("Tunnel identity") + "\">" +
                              (id == null ? "" : " " + id) + "</span><b class=\"tunnel_cap\" title=\"" + _t("Bandwidth tier") + "\">" +
                              cap + "</b></span></td>");
                }
                if (length < maxLength && ((length == 1 && !isInbound) || j == length - 2)) {
                    // pad out outbound zero hop; non-zero-hop pads in middle
                    for (int k = length; k < maxLength; k++) {
                        out.write("<td class=\"cells\" align=\"center\">&nbsp;</td>");
                    }
                }
            }
            out.write("</tr>\n");

            if (info.isInbound()) 
                processedIn += count;
            else
                processedOut += count;
        }
        out.write("</table>\n");
        if (in != null) {
            // PooledTunnelCreatorConfig
            List<?> pending = in.listPending();
            if (!pending.isEmpty()) {
                out.write("<div class=\"statusnotes\"><center><b>" + _t("Build in progress") + ":&nbsp;&nbsp;" + pending.size() + " " + tib + "</b></center></div>\n");
                live += pending.size();
            }
        }
        if (outPool != null) {
            // PooledTunnelCreatorConfig
            List<?> pending = outPool.listPending();
            if (!pending.isEmpty()) {
                out.write("<div class=\"statusnotes\"><center><b>" + _t("Build in progress") + ":&nbsp;&nbsp;" + pending.size() + " " + tob + "</b></center></div>\n");
                live += pending.size();
            }
        }
        if (live <= 0)
            out.write("<div class=\"statusnotes\"><center><b>" + _t("none") + "</b></center></div>\n");
        out.write("<div class=\"statusnotes\"><center><b>" + _t("Lifetime bandwidth usage") + ":&nbsp;&nbsp;" +
                  DataHelper.formatSize2Decimal(processedIn*1024) + "B " + _t("in") + ", " +
                  DataHelper.formatSize2Decimal(processedOut*1024) + "B " + _t("out") + "</b></center></div>");
    }

/****
    private void renderPeers(Writer out) throws IOException {
        // count up the peers in the local pools
        ObjectCounter<Hash> lc = new ObjectCounter();
        int tunnelCount = countTunnelsPerPeer(lc);

        // count up the peers in the participating tunnels
        ObjectCounter<Hash> pc = new ObjectCounter();
        int partCount = countParticipatingPerPeer(pc);

        Set<Hash> peers = new HashSet(lc.objects());
        peers.addAll(pc.objects());
        List<Hash> peerList = new ArrayList(peers);
        Collections.sort(peerList, new CountryComparator(this._context.commSystem()));

        out.write("<h2><a name=\"peers\"></a>" + _t("Tunnel Counts By Peer") + "</h2>\n");
        out.write("<table><tr><th>" + _t("Peer") + "</th><th>" + _t("Our Tunnels") + "</th><th>" + _t("% of total") + "</th><th>" + _t("Participating Tunnels") + "</th><th>" + _t("% of total") + "</th></tr>\n");
        for (Hash h : peerList) {
             out.write("<tr> <td class=\"cells\" align=\"center\">");
             out.write(netDbLink(h));
             out.write(" <td class=\"cells\" align=\"center\">" + lc.count(h));
             out.write(" <td class=\"cells\" align=\"center\">");
             if (tunnelCount > 0)
                 out.write("" + (lc.count(h) * 100 / tunnelCount));
             else
                 out.write('0');
             out.write(" <td class=\"cells\" align=\"center\">" + pc.count(h));
             out.write(" <td class=\"cells\" align=\"center\">");
             if (partCount > 0)
                 out.write("" + (pc.count(h) * 100 / partCount));
             else
                 out.write('0');
             out.write('\n');
        }
        out.write("<tr class=\"tablefooter\"> <td align=\"center\"><b>" + _t("Totals") + "</b> <td align=\"center\"><b>" + tunnelCount);
        out.write("</b> <td>&nbsp;</td> <td align=\"center\"><b>" + partCount);
        out.write("</b> <td>&nbsp;</td></tr></table></div>\n");
    }
****/

    /* duplicate of that in tunnelPoolManager for now */
    /** @return total number of non-fallback expl. + client tunnels */
/****
    private int countTunnelsPerPeer(ObjectCounter<Hash> lc) {
        List<TunnelPool> pools = new ArrayList();
        _context.tunnelManager().listPools(pools);
        int tunnelCount = 0;
        for (TunnelPool tp : pools) {
            for (TunnelInfo info : tp.listTunnels()) {
                if (info.getLength() > 1) {
                    tunnelCount++;
                    for (int j = 0; j < info.getLength(); j++) {
                        Hash peer = info.getPeer(j);
                        if (!_context.routerHash().equals(peer))
                            lc.increment(peer);
                    }
                }
            }
        }
        return tunnelCount;
    }
****/

    /** @return total number of part. tunnels */
/****
    private int countParticipatingPerPeer(ObjectCounter<Hash> pc) {
        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        for (HopConfig cfg : participating) {
            Hash from = cfg.getReceiveFrom();
            if (from != null)
                pc.increment(from);
            Hash to = cfg.getSendTo();
            if (to != null)
                pc.increment(to);
        }
        return participating.size();
    }

    private static class CountryComparator implements Comparator<Hash> {
        public CountryComparator(CommSystemFacade comm) {
            this.comm = comm;
        }
        public int compare(Hash l, Hash r) {
            // get both countries
            String lc = this.comm.getCountry(l);
            String rc = this.comm.getCountry(r);

            // make them non-null
            lc = (lc == null) ? "zzzz" : lc;
            rc = (rc == null) ? "zzzz" : rc;

            // let String handle the rest
            return lc.compareTo(rc);
        }

        private CommSystemFacade comm;
    }
****/

    /** cap string */
    private String getCapacity(Hash peer) {
        RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
        if (info != null) {
            String caps = info.getCapabilities();
            for (char c = Router.CAPABILITY_BW12; c <= Router.CAPABILITY_BW256; c++) {
                if (caps.indexOf(c) >= 0)
                    return " " + c;
            }
        }
        return "";
    }

    private String netDbLink(Hash peer) {
        return _context.commSystem().renderPeerHTML(peer);
    }

    /**
     * Copied from ConfigNetHelper.
     * @return in KBytes per second
     * @since 0.9.32
     */
    private int getShareBandwidth() {
        int irateKBps = _context.bandwidthLimiter().getInboundKBytesPerSecond();
        int orateKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        double pct = _context.router().getSharePercentage();
        if (irateKBps < 0 || orateKBps < 0)
            return ConfigNetHelper.DEFAULT_SHARE_KBPS;
        return (int) (pct * Math.min(irateKBps, orateKBps));
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /** translate a string */
    public String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }
}
