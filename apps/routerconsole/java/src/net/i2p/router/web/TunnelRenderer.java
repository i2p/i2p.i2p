package net.i2p.router.web;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.pool.TunnelPool;
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
        out.write("<div class=\"wideload\"><h2><a name=\"exploratory\" ></a>" + _t("Exploratory tunnels") + " (<a href=\"/configtunnels#exploratory\">" + _t("configure") + "</a>)</h2>\n");
        renderPool(out, _context.tunnelManager().getInboundExploratoryPool(), _context.tunnelManager().getOutboundExploratoryPool());
        
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
            // TODO the following code is duplicated in SummaryHelper
            String name = (in != null) ? in.getSettings().getDestinationNickname() : null;
            if ( (name == null) && (outPool != null) )
                name = outPool.getSettings().getDestinationNickname();
            if (name == null)
                name = client.toBase64().substring(0,4);
            out.write("<h2><a name=\"" + client.toBase64().substring(0,4)
                      + "\" ></a>" + _t("Client tunnels for") + ' ' + DataHelper.escapeHTML(_t(name)));
            if (isLocal)
                out.write(" (<a href=\"/configtunnels#" + client.toBase64().substring(0,4) +"\">" + _t("configure") + "</a>)</h2>\n");
            else
                out.write(" (" + _t("dead") + ")</h2>\n");
            renderPool(out, in, outPool);
        }
        
        List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
        out.write("<h2><a name=\"participating\"></a>" + _t("Participating tunnels") + "</h2>\n");
        if (!participating.isEmpty()) {
            Collections.sort(participating, new TunnelComparator());
            out.write("<table><tr><th>" + _t("Receive on") + "</th><th>" + _t("From") + "</th><th>"
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
                out.write("<td class=\"cells\" align=\"center\">" + cfg.getReceiveTunnel().getTunnelId() +"</td>");
            else
                out.write("<td class=\"cells\" align=\"center\">n/a</td>");
            if (cfg.getReceiveFrom() != null)
                out.write("<td class=\"cells\" align=\"center\">" + netDbLink(cfg.getReceiveFrom()) +"</td>");
            else
                out.write("<td class=\"cells\">&nbsp;</td>");
            if (cfg.getSendTunnel() != null)
                out.write("<td class=\"cells\" align=\"center\">" + cfg.getSendTunnel().getTunnelId() +"</td>");
            else
                out.write("<td class=\"cells\">&nbsp;</td>");
            if (cfg.getSendTo() != null)
                out.write("<td class=\"cells\" align=\"center\">" + netDbLink(cfg.getSendTo()) +"</td>");
            else
                out.write("<td class=\"cells\">&nbsp;</td>");
            long timeLeft = cfg.getExpiration()-_context.clock().now();
            if (timeLeft > 0)
                out.write("<td class=\"cells\" align=\"center\">" + DataHelper.formatDuration2(timeLeft) + "</td>");
            else
                out.write("<td class=\"cells\" align=\"center\">(" + _t("grace period") + ")</td>");
            out.write("<td class=\"cells\" align=\"center\">" + count + " KB</td>");
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
            out.write("<div class=\"statusnotes\"><b>" + _t("Inactive participating tunnels") + ": " + inactive + "</b></div>\n");
        else if (displayed <= 0)
            out.write("<div class=\"statusnotes\"><b>" + _t("none") + "</b></div>\n");
        out.write("<div class=\"statusnotes\"><b>" + _t("Lifetime bandwidth usage") + ": " + DataHelper.formatSize2(processed*1024) + "B</b></div>\n");
        //renderPeers(out);
        out.write("</div>");
    }
    
    private static class TunnelComparator implements Comparator<HopConfig>, Serializable {
         public int compare(HopConfig l, HopConfig r) {
             return (r.getProcessedMessagesCount() - l.getProcessedMessagesCount());
        }
    }

    private void renderPool(Writer out, TunnelPool in, TunnelPool outPool) throws IOException {
        List<TunnelInfo> tunnels = null;
        if (in == null)
            tunnels = new ArrayList<TunnelInfo>();
        else
            tunnels = in.listTunnels();
        if (outPool != null)
            tunnels.addAll(outPool.listTunnels());
        
        long processedIn = (in != null ? in.getLifetimeProcessed() : 0);
        long processedOut = (outPool != null ? outPool.getLifetimeProcessed() : 0);
        
        int live = 0;
        int maxLength = 1;
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            if (info.getLength() > maxLength)
                maxLength = info.getLength();
        }
        out.write("<table><tr><th>" + _t("In/Out") + "</th><th>" + _t("Expiry") + "</th><th>" + _t("Usage") + "</th><th>" + _t("Gateway") + "</th>");
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
        for (int i = 0; i < tunnels.size(); i++) {
            TunnelInfo info = tunnels.get(i);
            long timeLeft = info.getExpiration()-_context.clock().now();
            if (timeLeft <= 0)
                continue; // don't display tunnels in their grace period
            live++;
            if (info.isInbound())
                out.write("<tr> <td class=\"cells\" align=\"center\"><img src=\"/themes/console/images/inbound.png\" alt=\"Inbound\" title=\"Inbound\"></td>");
            else
                out.write("<tr> <td class=\"cells\" align=\"center\"><img src=\"/themes/console/images/outbound.png\" alt=\"Outbound\" title=\"Outbound\"></td>");
            out.write(" <td class=\"cells\" align=\"center\">" + DataHelper.formatDuration2(timeLeft) + "</td>\n");
            int count = info.getProcessedMessagesCount();
            out.write(" <td class=\"cells\" align=\"center\">" + count + " KB</td>\n");
            for (int j = 0; j < info.getLength(); j++) {
                Hash peer = info.getPeer(j);
                TunnelId id = (info.isInbound() ? info.getReceiveTunnelId(j) : info.getSendTunnelId(j));
                if (_context.routerHash().equals(peer)) {
                    out.write(" <td class=\"cells\" align=\"center\">" + (id == null ? "" : "" + id) + "</td>");
                } else {
                    String cap = getCapacity(peer);
                    out.write(" <td class=\"cells\" align=\"center\">" + netDbLink(peer) + (id == null ? "" : " " + id) + cap + "</td>");                
                }
                if (info.getLength() < maxLength && (info.getLength() == 1 || j == info.getLength() - 2)) {
                    for (int k = info.getLength(); k < maxLength; k++)
                        out.write(" <td class=\"cells\" align=\"center\">&nbsp;</td>");
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
                out.write("<div class=\"statusnotes\"><center><b>" + _t("Build in progress") + ": " + pending.size() + " " + _t("inbound") + "</b></center></div>\n");
                live += pending.size();
            }
        }
        if (outPool != null) {
            // PooledTunnelCreatorConfig
            List<?> pending = outPool.listPending();
            if (!pending.isEmpty()) {
                out.write("<div class=\"statusnotes\"><center><b>" + _t("Build in progress") + ": " + pending.size() + " " + _t("outbound") + "</b></center></div>\n");
                live += pending.size();
            }
        }
        if (live <= 0)
            out.write("<div class=\"statusnotes\"><center><b>" + _t("No tunnels; waiting for the grace period to end.") + "</b></center></div>\n");
        out.write("<div class=\"statusnotes\"><center><b>" + _t("Lifetime bandwidth usage") + ": " +
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

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /** translate a string */
    public String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }
}
