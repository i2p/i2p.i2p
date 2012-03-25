package net.i2p.router.web;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.CommSystemFacade;
import net.i2p.stat.RateStat;
import net.i2p.util.ObjectCounter;

/**
 *  tunnels.jsp
 */
public class TunnelRenderer {
    private RouterContext _context;

    private static final int DISPLAY_LIMIT = 200;
    
    public TunnelRenderer(RouterContext ctx) {
        _context = ctx;
    }
    
    public void renderStatusHTML(Writer out) throws IOException {
        out.write("<div class=\"wideload\"><h2><a name=\"exploratory\" ></a>" + _("Exploratory tunnels") + " (<a href=\"/configtunnels#exploratory\">" + _("configure") + "</a>)</h2>\n");
        renderPool(out, _context.tunnelManager().getInboundExploratoryPool(), _context.tunnelManager().getOutboundExploratoryPool());
        
        List<Hash> destinations = null;
        Map<Hash, TunnelPool> clientInboundPools = _context.tunnelManager().getInboundClientPools();
        Map<Hash, TunnelPool> clientOutboundPools = _context.tunnelManager().getOutboundClientPools();
        destinations = new ArrayList(clientInboundPools.keySet());
        for (int i = 0; i < destinations.size(); i++) {
            Hash client = destinations.get(i);
            TunnelPool in = null;
            TunnelPool outPool = null;
            in = clientInboundPools.get(client);
            outPool = clientOutboundPools.get(client);
            // TODO the following code is duplicated in SummaryHelper
            String name = (in != null ? in.getSettings().getDestinationNickname() : null);
            if ( (name == null) && (outPool != null) )
                name = outPool.getSettings().getDestinationNickname();
            if (name == null)
                name = client.toBase64().substring(0,4);
            out.write("<h2><a name=\"" + client.toBase64().substring(0,4)
                      + "\" ></a>" + _("Client tunnels for") + ' ' + _(name));
            if (_context.clientManager().isLocal(client))
                out.write(" (<a href=\"/configtunnels#" + client.toBase64().substring(0,4) +"\">" + _("configure") + "</a>)</h2>\n");
            else
                out.write(" (" + _("dead") + ")</h2>\n");
            renderPool(out, in, outPool);
        }
        
        List participating = _context.tunnelDispatcher().listParticipatingTunnels();
        Collections.sort(participating, new TunnelComparator());
        out.write("<h2><a name=\"participating\"></a>" + _("Participating tunnels") + "</h2><table>\n");
        out.write("<tr><th>" + _("Receive on") + "</th><th>" + _("From") + "</th><th>"
                  + _("Send on") + "</th><th>" + _("To") + "</th><th>" + _("Expiration") + "</th>"
                  + "<th>" + _("Usage") + "</th><th>" + _("Rate") + "</th><th>" + _("Role") + "</th></tr>\n");
        long processed = 0;
        RateStat rs = _context.statManager().getRate("tunnel.participatingMessageCount");
        if (rs != null)
            processed = (long)rs.getRate(10*60*1000).getLifetimeTotalValue();
        int inactive = 0;
        int displayed = 0;
        for (int i = 0; i < participating.size(); i++) {
            HopConfig cfg = (HopConfig)participating.get(i);
            long count = cfg.getProcessedMessagesCount();
            if (count <= 0) {
                inactive++;
                continue;
            }
            processed += count;
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
                out.write("<td class=\"cells\" align=\"center\">(" + _("grace period") + ")</td>");
            out.write("<td class=\"cells\" align=\"center\">" + cfg.getProcessedMessagesCount() + " KB</td>");
            int lifetime = (int) ((_context.clock().now() - cfg.getCreation()) / 1000);
            if (lifetime <= 0)
                lifetime = 1;
            if (lifetime > 10*60)
                lifetime = 10*60;
            int bps = 1024 * cfg.getProcessedMessagesCount() / lifetime;
            out.write("<td class=\"cells\" align=\"center\">" + bps + " Bps</td>");
            if (cfg.getSendTo() == null)
                out.write("<td class=\"cells\" align=\"center\">" + _("Outbound Endpoint") + "</td>");
            else if (cfg.getReceiveFrom() == null)
                out.write("<td class=\"cells\" align=\"center\">" + _("Inbound Gateway") + "</td>");
            else
                out.write("<td class=\"cells\" align=\"center\">" + _("Participant") + "</td>");
            out.write("</tr>\n");
        }
        out.write("</table>\n");
        if (displayed > DISPLAY_LIMIT)
            out.write("<div class=\"statusnotes\"><b>" + _("Limited display to the {0} tunnels with the highest usage", DISPLAY_LIMIT)  + "</b></div>\n");
        out.write("<div class=\"statusnotes\"><b>" + _("Inactive participating tunnels") + ": " + inactive + "</b></div>\n");
        out.write("<div class=\"statusnotes\"><b>" + _("Lifetime bandwidth usage") + ": " + DataHelper.formatSize2(processed*1024) + "B</b></div>\n");
        //renderPeers(out);
        out.write("</div>");
    }
    
    private static class TunnelComparator implements Comparator<HopConfig> {
         public int compare(HopConfig l, HopConfig r) {
             return (r.getProcessedMessagesCount() - l.getProcessedMessagesCount());
        }
    }

    private void renderPool(Writer out, TunnelPool in, TunnelPool outPool) throws IOException {
        List<TunnelInfo> tunnels = null;
        if (in == null)
            tunnels = new ArrayList();
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
        out.write("<table><tr><th>" + _("In/Out") + "</th><th>" + _("Expiry") + "</th><th>" + _("Usage") + "</th><th>" + _("Gateway") + "</th>");
        if (maxLength > 3) {
            out.write("<th align=\"center\" colspan=\"" + (maxLength - 2));
            out.write("\">" + _("Participants") + "</th>");
        }
        else if (maxLength == 3) {
            out.write("<th>" + _("Participant") + "</th>");
        }
        if (maxLength > 1) {
            out.write("<th>" + _("Endpoint") + "</th>");
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
            out.write(" <td class=\"cells\" align=\"center\">" + info.getProcessedMessagesCount() + " KB</td>\n");
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
                processedIn += info.getProcessedMessagesCount();
            else
                processedOut += info.getProcessedMessagesCount();
        }
        out.write("</table>\n");
        if (in != null) {
            List pending = in.listPending();
            if (!pending.isEmpty()) {
                out.write("<div class=\"statusnotes\"><center><b>" + _("Build in progress") + ": " + pending.size() + " " + _("inbound") + "</b></center></div>\n");
                live += pending.size();
            }
        }
        if (outPool != null) {
            List pending = outPool.listPending();
            if (!pending.isEmpty()) {
                out.write("<div class=\"statusnotes\"><center><b>" + _("Build in progress") + ": " + pending.size() + " " + _("outbound") + "</b></center></div>\n");
                live += pending.size();
            }
        }
        if (live <= 0)
            out.write("<div class=\"statusnotes\"><center><b>" + _("No tunnels; waiting for the grace period to end.") + "</b></center></div>\n");
        out.write("<div class=\"statusnotes\"><center><b>" + _("Lifetime bandwidth usage") + ": " +
                  DataHelper.formatSize2(processedIn*1024) + "B " + _("in") + ", " +
                  DataHelper.formatSize2(processedOut*1024) + "B " + _("out") + "</b></center></div>");
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

        out.write("<h2><a name=\"peers\"></a>" + _("Tunnel Counts By Peer") + "</h2>\n");
        out.write("<table><tr><th>" + _("Peer") + "</th><th>" + _("Our Tunnels") + "</th><th>" + _("% of total") + "</th><th>" + _("Participating Tunnels") + "</th><th>" + _("% of total") + "</th></tr>\n");
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
        out.write("<tr class=\"tablefooter\"> <td align=\"center\"><b>" + _("Totals") + "</b> <td align=\"center\"><b>" + tunnelCount);
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
    private String _(String s) {
        return Messages.getString(s, _context);
    }

    /** translate a string */
    public String _(String s, Object o) {
        return Messages.getString(s, o, _context);
    }
}
