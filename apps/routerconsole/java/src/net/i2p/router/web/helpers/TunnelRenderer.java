package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.text.Collator;
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
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.ObjectCounterUnsafe;

/**
 *  For /tunnels.jsp, used by TunnelHelper.
 */
class TunnelRenderer {
    private final RouterContext _context;

    private static final int DISPLAY_LIMIT = 200;

    public TunnelRenderer(RouterContext ctx) {
        _context = ctx;
    }

    public void renderStatusHTML(Writer out) throws IOException {
        TunnelManagerFacade tm = _context.tunnelManager();
        TunnelPool ei = tm.getInboundExploratoryPool();
        TunnelPool eo = tm.getOutboundExploratoryPool();
        out.write("<h3 class=\"tabletitle\" id=\"exploratorytunnels\"><a name=\"exploratory\" ></a>" + _t("Exploratory tunnels"));
        // links are set to float:right in CSS so they will be displayed in reverse order
        out.write(" <a href=\"/configtunnels#exploratory\" title=\"" + _t("Configure tunnels") + "\">[" + _t("configure") + "]</a>");
        writeGraphLinks(out, ei, eo);
        out.write("</h3>\n");
        renderPool(out, ei, eo);

        Map<Hash, TunnelPool> clientInboundPools = tm.getInboundClientPools();
        boolean debug = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
        // display name to in pool
        List<TunnelPool> sorted = new ArrayList<TunnelPool>(clientInboundPools.values());
        if (sorted.size() > 1)
            DataHelper.sort(sorted, new TPComparator());
        for (TunnelPool in : sorted) {
            Hash client = in.getSettings().getDestination();
            boolean isLocal = _context.clientManager().isLocal(client);
            if ((!isLocal) && (!debug))
                continue;
            TunnelPool outPool = tm.getOutboundPool(client);
            if (in.getSettings().getAliasOf() != null ||
                (outPool != null && outPool.getSettings().getAliasOf() != null)) {
                // skip aliases, we will print a header under the main tunnel pool below
                continue;
            }
            String b64 = client.toBase64().substring(0, 4);
            out.write("<h3 class=\"tabletitle\" id=\"" + b64
                      + "\" >" + _t("Client tunnels for {0}", getTunnelName(in)));
            if (isLocal) {
                // links are set to float:right in CSS so they will be displayed in reverse order
                out.write(" <a href=\"/configtunnels#" + b64 + "\" title=\"" + _t("Configure tunnels for session") + "\">[" + _t("configure") + "]</a>");
                writeGraphLinks(out, in, outPool);
                out.write("</h3>\n");
            } else {
                out.write(" (" + _t("dead") + ")</h3>\n");
            }

                // list aliases
                Set<Hash> aliases = in.getSettings().getAliases();
                if (aliases != null) {
                    for (Hash a : aliases) {
                        TunnelPool ain = clientInboundPools.get(a);
                        if (ain != null) {
                            String aname = ain.getSettings().getDestinationNickname();
                            String ab64 = a.toBase64().substring(0, 4);
                            if (aname == null)
                                aname = ab64;
                            out.write("<h3 class=\"tabletitle\" id=\"" + ab64
                                      + "\" >" + _t("Client tunnels for {0}", DataHelper.escapeHTML(_t(aname))));
                            if (isLocal)
                                out.write(" <a href=\"/configtunnels#" + b64 + "\" title=\"" + _t("Configure tunnels for session") + "\">[" + _t("configure") + "]</a></h3>\n");
                            else
                                out.write(" (" + _t("dead") + ")</h3>\n");
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
            DataHelper.sort(participating, new TunnelComparator());
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
        long now = _context.clock().now();
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
            long recv = cfg.getReceiveTunnelId();
            if (recv != 0)
                out.write("<td class=\"cells\" align=\"center\" title=\"" + _t("Tunnel identity") + "\"><span class=\"tunnel_id\">" +
                          recv + "</span></td>");
            else
                out.write("<td class=\"cells\" align=\"center\">n/a</td>");
            Hash from = cfg.getReceiveFrom();
            if (from != null)
                out.write("<td class=\"cells\" align=\"center\"><span class=\"tunnel_peer\">" + netDbLink(from) +"</span></td>");
            else
                out.write("<td class=\"cells\">&nbsp;</td>");
            long send = cfg.getSendTunnelId();
            if (send != 0)
                out.write("<td class=\"cells\" align=\"center\" title=\"" + _t("Tunnel identity") + "\"><span class=\"tunnel_id\">" + send +"</span></td>");
            else
                out.write("<td class=\"cells\">&nbsp;</td>");
            Hash to = cfg.getSendTo();
            if (to != null)
                out.write("<td class=\"cells\" align=\"center\"><span class=\"tunnel_peer\">" + netDbLink(to) +"</span></td>");
            else
                out.write("<td class=\"cells\">&nbsp;</td>");
            long timeLeft = cfg.getExpiration() - now;
            if (timeLeft > 0)
                out.write("<td class=\"cells\" align=\"center\">" + DataHelper.formatDuration2(timeLeft) + "</td>");
            else
                out.write("<td class=\"cells\" align=\"center\">(" + _t("grace period") + ")</td>");
            out.write("<td class=\"cells\" align=\"center\">" + DataHelper.formatSize2(count * 1024) + "B</td>");
            int lifetime = (int) ((now - cfg.getCreation()) / 1000);
            if (lifetime <= 0)
                lifetime = 1;
            if (lifetime > 10*60)
                lifetime = 10*60;
            long bps = 1024L * count / lifetime;
            out.write("<td class=\"cells\" align=\"center\">" + DataHelper.formatSize2Decimal(bps) + "Bps</td>");
            if (to == null)
                out.write("<td class=\"cells\" align=\"center\">" + _t("Outbound Endpoint") + "</td>");
            else if (from == null)
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
        out.write("<div class=\"statusnotes\"><b>" + _t("Lifetime bandwidth usage") + ":&nbsp;&nbsp;" + DataHelper.formatSize2(processed*1024) + "B</b></div>\n");

            if (debug && participating.size() > 1) {
                // peer table sorted by number of tunnels
                ObjectCounterUnsafe<Hash> counts = new ObjectCounterUnsafe<Hash>();
                ObjectCounterUnsafe<Hash> bws = new ObjectCounterUnsafe<Hash>();
                for (int i = 0; i < participating.size(); i++) {
                    HopConfig cfg = participating.get(i);
                    Hash from = cfg.getReceiveFrom();
                    Hash to = cfg.getSendTo();
                    int msgs = cfg.getProcessedMessagesCount();
                    if (from != null) {
                        counts.increment(from);
                        if (msgs > 0)
                            bws.add(from, msgs);
                    }
                    if (to != null) {
                        counts.increment(to);
                        if (msgs > 0)
                            bws.add(to, msgs);
                    }
                }
                // sort and output
                out.write("<h3 class=\"tabletitle\">Peers in multiple participating tunnels (including inactive)</h3>\n");
                out.write("<table class=\"tunneldisplay tunnels_participating\"><tr><th>" + _t("Router") + "</th><th>" + _t("Tunnels") + "</th><th>"
                          + _t("Usage") + "</th></tr>\n");
                displayed = 0;
                List<Hash> sort = counts.sortedObjects();
                for (Hash h : sort) {
                    int count = counts.count(h);
                    if (count <= 1)
                        break;
                    if (++displayed > DISPLAY_LIMIT)
                        break;
                    out.write("<tr><td class=\"cells\" align=\"center\"><span class=\"tunnel_peer\">" + netDbLink(h) + "</span></td>\n");
                    out.write("<td class=\"cells\" align=\"center\">" + count + "</td>\n");
                    out.write("<td class=\"cells\" align=\"center\">" + DataHelper.formatSize2(bws.count(h) * 1024) + "B</td></tr>\n");
                }
                out.write("</table>\n");
                if (displayed <= 0)
                    out.write("<div class=\"statusnotes\"><b>" + _t("none") + "</b></div>\n");
            }


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

    /**
     *  Sort tunnels by the name of the tunnel
     *  @since 0.9.57
     */
    private class TPComparator implements Comparator<TunnelPool> {
         private final Collator _comp = Collator.getInstance();
         public int compare(TunnelPool l, TunnelPool r) {
             int rv = _comp.compare(getTunnelName(l), getTunnelName(r));
             if (rv != 0)
                 return rv;
             return l.getSettings().getDestination().toBase32().compareTo(r.getSettings().getDestination().toBase32());
        }
    }

    /**
     *  Get display name for the tunnel
     *  @since 0.9.57
     */
    private String getTunnelName(TunnelPool in) {
        TunnelPoolSettings ins = in.getSettings();
        String name = ins.getDestinationNickname();
        if (name == null) {
            TunnelPoolSettings outPool = _context.tunnelManager().getOutboundSettings(ins.getDestination());
            if (outPool != null)
                name = outPool.getDestinationNickname();
        }
        if (name != null)
            return DataHelper.escapeHTML(_t(name));
        return ins.getDestination().toBase32();
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
                  + "</th><th>" + _t("Expiration") + "</th><th>" + _t("Usage") + "</th><th>" + _t("Gateway") + "</th>");
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
        long now = _context.clock().now();
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            long timeLeft = info.getExpiration() - now;
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
            int count = info.getProcessedMessagesCount() * 1024;
            out.write("<td class=\"cells\" align=\"center\">" + DataHelper.formatSize2(count) + "B</td>\n");
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
                    char cap = getCapacity(peer);
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
                  DataHelper.formatSize2(processedIn*1024) + "B " + _t("in") + ", " +
                  DataHelper.formatSize2(processedOut*1024) + "B " + _t("out") + "</b></center></div>");
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

    /** @return cap char or ' ' */
    private char getCapacity(Hash peer) {
        RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
        if (info != null) {
            String caps = info.getCapabilities();
            for (int i = 0; i < RouterInfo.BW_CAPABILITY_CHARS.length(); i++) {
                char c = RouterInfo.BW_CAPABILITY_CHARS.charAt(i);
                if (caps.indexOf(c) >= 0)
                    return c;
            }
        }
        return ' ';
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
