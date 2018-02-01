package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.Comparator;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.ntcp.NTCPConnection;
import net.i2p.router.transport.ntcp.NTCPTransport;
import net.i2p.router.transport.udp.PeerState;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.web.HelperBase;
import static net.i2p.router.web.helpers.UDPSorters.*;
import net.i2p.util.SystemVersion;



public class PeerHelper extends HelperBase {
    private int _sortFlags;
    private String _urlBase;

    // Opera doesn't have the char, TODO check UA
    //private static final String THINSP = "&thinsp;/&thinsp;";
    private static final String THINSP = " / ";

    public PeerHelper() {}

    public void setSort(String flags) {
        if (flags != null) {
            try {
                _sortFlags = Integer.parseInt(flags);
            } catch (NumberFormatException nfe) {
                _sortFlags = 0;
            }
        } else {
            _sortFlags = 0;
        }
    }
    public void setUrlBase(String base) { _urlBase = base; }

    public String getPeerSummary() {
        try {
            renderStatusHTML(_out, _urlBase, _sortFlags);
            // boring and not worth translating
            //_context.bandwidthLimiter().renderStatusHTML(_out);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }

    /**
     *  Warning - blocking, very slow, queries the active UPnP router,
     *  will take many seconds if it has vanished.
     *
     *  @since 0.9.31 moved from TransportManager
     */
    private void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException {
        if (isAdvanced()) {
            out.write("<p id=\"upnpstatus\"><b>");
            out.write(_t("Status"));
            out.write(": ");
            out.write(_t(_context.commSystem().getStatus().toStatusString()));
            out.write("</b></p>");
        }
        SortedMap<String, Transport> transports = _context.commSystem().getTransports();
        for (Map.Entry<String, Transport> e : transports.entrySet()) {
            String style = e.getKey();
            Transport t = e.getValue();
            if (style.equals("NTCP")) {
                NTCPTransport nt = (NTCPTransport) t;
                render(nt, out, urlBase, sortFlags);
            } else if (style.equals("SSU")) {
                UDPTransport ut = (UDPTransport) t;
                render(ut, out, urlBase, sortFlags);
            } else {
                // pluggable (none yet)
                t.renderStatusHTML(out, urlBase, sortFlags);
            }
        }

        if (!transports.isEmpty()) {
            out.write(getTransportsLegend());
        }

        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<h3 id=\"transports\">").append(_t("Router Transport Addresses")).append("</h3><pre id=\"transports\">\n");
        for (Transport t : transports.values()) {
            if (t.hasCurrentAddress()) {
                for (RouterAddress ra : t.getCurrentAddresses()) {
                    buf.append(ra.toString());
                    buf.append("\n\n");
                }
            } else {
                buf.append(_t("{0} is used for outbound connections only", t.getStyle()));
                buf.append("\n\n");
            }
        }
        buf.append("</pre>\n");
        out.write(buf.toString());
        // UPnP Status
        _context.commSystem().renderStatusHTML(_out, _urlBase, _sortFlags);
        out.write("</p>\n");
        out.flush();
    }

    /**
     *  @since 0.9.31 moved from TransportManager
     */
    private final String getTransportsLegend() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<p class=\"infohelp\">")
           .append(_t("Your transport connection limits are automatically set based on your configured bandwidth."))
           .append('\n')
           .append(_t("To override these limits, add the settings i2np.ntcp.maxConnections=nnn and i2np.udp.maxConnections=nnn on the advanced configuration page."))
           .append("</p>\n");
        buf.append("<h3 class=\"tabletitle\">").append(_t("Definitions")).append("</h3>")
           .append("<table id=\"peerdefs\">\n")
           .append("<tr><td><b id=\"def.peer\">").append(_t("Peer")).append("</b></td><td>").append(_t("The remote peer, identified by router hash")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.dir\">").append(_t("Dir")).append("</b></td><td><span class=\"peer_arrow\"><img alt=\"Inbound\" src=\"/themes/console/images/inbound.png\"></span> ").append(_t("Inbound connection")).append("<br>\n")
           .append("<span class=\"peer_arrow\"><img alt=\"Outbound\" src=\"/themes/console/images/outbound.png\"></span> ").append(_t("Outbound connection")).append("<br>\n")
           .append("<span class=\"peer_arrow\"><img src=\"/themes/console/images/inbound.png\" alt=\"V\" height=\"8\" width=\"12\"></span> ").append(_t("They offered to introduce us (help other peers traverse our firewall)")).append("<br>\n")
           .append("<span class=\"peer_arrow\"><img src=\"/themes/console/images/outbound.png\" alt=\"^\" height=\"8\" width=\"12\"></span> ").append(_t("We offered to introduce them (help other peers traverse their firewall)")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.idle\">").append(_t("Idle")).append("</b></td><td>").append(_t("How long since a packet has been received / sent")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.rate\">").append(_t("In/Out")).append("</b></td><td>").append(_t("The smoothed inbound / outbound transfer rate (KBytes per second)")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.up\">").append(_t("Up")).append("</b></td><td>").append(_t("How long ago this connection was established")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.skew\">").append(_t("Skew")).append("</b></td><td>").append(_t("The difference between the peer's clock and your own")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.cwnd\">CWND</b></td><td>").append(_t("The congestion window, which is how many bytes can be sent without an acknowledgement")).append(" /<br>\n")
           .append(_t("The number of sent messages awaiting acknowledgement")).append(" /<br>\n")
           .append(_t("The maximum number of concurrent messages to send")).append(" /<br>\n")
           .append(_t("The number of pending sends which exceed congestion window")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.ssthresh\">SST</b></td><td>").append(_t("The slow start threshold")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.rtt\">RTT</b></td><td>").append(_t("The round trip time in milliseconds")).append("</td></tr>\n")
           //.append("<tr><td><b id=\"def.dev\">").append(_t("Dev")).append("</b></td><td>").append(_t("The standard deviation of the round trip time in milliseconds")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.rto\">RTO</b></td><td>").append(_t("The retransmit timeout in milliseconds")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.mtu\">MTU</b></td><td>").append(_t("Current maximum send packet size / estimated maximum receive packet size (bytes)")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.send\">").append(_t("TX")).append("</b></td><td>").append(_t("The total number of messages sent to the peer")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.recv\">").append(_t("RX")).append("</b></td><td>").append(_t("The total number of messages received from the peer")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.resent\">").append(_t("Dup TX")).append("</b></td><td>").append(_t("The total number of packets retransmitted to the peer")).append("</td></tr>\n")
           .append("<tr><td><b id=\"def.dupRecv\">").append(_t("Dup RX")).append("</b></td><td>").append(_t("The total number of duplicate packets received from the peer")).append("</td></tr>\n")
           .append("</table>");
        return buf.toString();
    }

    /// begin NTCP

    /**
     *  @since 0.9.31 moved from NTCPTransport
     */
    private void render(NTCPTransport nt, Writer out, String urlBase, int sortFlags) throws IOException {
        TreeSet<NTCPConnection> peers = new TreeSet<NTCPConnection>(getNTCPComparator(sortFlags));
        peers.addAll(nt.getPeers());

        long offsetTotal = 0;
        float bpsSend = 0;
        float bpsRecv = 0;
        long totalUptime = 0;
        long totalSend = 0;
        long totalRecv = 0;

        if (!isAdvanced()) {
            for (Iterator<NTCPConnection> iter = peers.iterator(); iter.hasNext(); ) {
                 // outbound conns get put in the map before they are established
                 if (!iter.next().isEstablished())
                     iter.remove();
            }
        }

        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3 id=\"ntcpcon\">").append(_t("NTCP connections")).append(": ").append(peers.size());
        buf.append(". ").append(_t("Limit")).append(": ").append(nt.getMaxConnections());
        //buf.append(". ").append(_t("Timeout")).append(": ").append(DataHelper.formatDuration2(_pumper.getIdleTimeout()));
        if (_context.getBooleanProperty(PROP_ADVANCED)) {
            buf.append(". ").append(_t("Status")).append(": ").append(_t(nt.getReachabilityStatus().toStatusString()));
        }
        buf.append(".</h3>\n" +
                   "<div class=\"widescroll\"><table id=\"ntcpconnections\">\n" +
                   "<tr><th><a href=\"#def.peer\">").append(_t("Peer")).append("</a></th>" +
                   "<th><a href=\"#def.dir\" title=\"").append(_t("Direction/Introduction"))
                   .append("\">").append(_t("Dir")).append("</a></th>" +
                   "<th>").append(_t("IPv6")).append("</th>" +
                   "<th align=\"right\"><a href=\"#def.idle\">").append(_t("Idle")).append("</a></th>" +
                   "<th align=\"right\"><a href=\"#def.rate\">").append(_t("In/Out")).append("</a></th>" +
                   "<th align=\"right\"><a href=\"#def.up\">").append(_t("Up")).append("</a></th>" +
                   "<th align=\"right\"><a href=\"#def.skew\">").append(_t("Skew")).append("</a></th>" +
                   "<th align=\"right\"><a href=\"#def.send\">").append(_t("TX")).append("</a></th>" +
                   "<th align=\"right\"><a href=\"#def.recv\">").append(_t("RX")).append("</a></th>" +
                   "<th>").append(_t("Out Queue")).append("</th>" +
                   "<th title=\"").append(_t("Is peer backlogged?")).append("\">").append(_t("Backlogged?")).append("</th>" +
                   //"<th>").append(_t("Reading?")).append("</th>" +
                   " </tr>\n");
        out.write(buf.toString());
        buf.setLength(0);
        for (NTCPConnection con : peers) {
            buf.append("<tr><td class=\"cells\" align=\"left\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(con.getRemotePeer().calculateHash()));
            //byte[] ip = getIP(con.getRemotePeer().calculateHash());
            //if (ip != null)
            //    buf.append(' ').append(_context.blocklist().toStr(ip));
            buf.append("</td><td class=\"cells\" align=\"center\">");
            if (con.isInbound())
                buf.append("<img src=\"/themes/console/images/inbound.png\" alt=\"Inbound\" title=\"").append(_t("Inbound")).append("\"/>");
            else
                buf.append("<img src=\"/themes/console/images/outbound.png\" alt=\"Outbound\" title=\"").append(_t("Outbound")).append("\"/>");
            buf.append("</td><td class=\"cells peeripv6\" align=\"center\">");
            if (con.isIPv6())
                buf.append("<span class=\"backlogged\">&#x2714;</span>");
            else
                buf.append("");
            buf.append("</td><td class=\"cells\" align=\"center\"><span class=\"right\">");
            buf.append(DataHelper.formatDuration2(con.getTimeSinceReceive()));
            buf.append("</span>").append(THINSP).append("<span class=\"left\">").append(DataHelper.formatDuration2(con.getTimeSinceSend()));
            buf.append("</span></td><td class=\"cells\" align=\"center\"><span class=\"right\">");
            if (con.getTimeSinceReceive() < 2*60*1000) {
                float r = con.getRecvRate();
                buf.append(formatRate(r / 1000));
                bpsRecv += r;
            } else {
                buf.append(formatRate(0));
            }
            buf.append("</span>").append(THINSP).append("<span class=\"left\">");
            if (con.getTimeSinceSend() < 2*60*1000) {
                float r = con.getSendRate();
                buf.append(formatRate(r / 1000));
                bpsSend += r;
            } else {
                buf.append(formatRate(0));
            }
            //buf.append(" K/s");
            buf.append("</span></td><td class=\"cells\" align=\"right\">").append(DataHelper.formatDuration2(con.getUptime()));
            totalUptime += con.getUptime();
            offsetTotal = offsetTotal + con.getClockSkew();
            buf.append("</td><td class=\"cells\" align=\"right\">").append(DataHelper.formatDuration2(1000 * con.getClockSkew()));
            buf.append("</td><td class=\"cells\" align=\"right\">").append(con.getMessagesSent());
            totalSend += con.getMessagesSent();
            buf.append("</td><td class=\"cells\" align=\"right\">").append(con.getMessagesReceived());
            totalRecv += con.getMessagesReceived();
            long outQueue = con.getOutboundQueueSize();
            buf.append("</td><td class=\"cells\" align=\"center\">").append(outQueue);
            buf.append("</td><td class=\"cells\" align=\"center\">");
            if (con.isBacklogged())
                buf.append("<span class=\"backlogged\">&#x2714;</span>");
            else
                buf.append("&nbsp;");
            //long readTime = con.getReadTime();
            //if (readTime <= 0) {
            //    buf.append("</td> <td class=\"cells\" align=\"center\">0");
            //} else {
            //    buf.append("</td> <td class=\"cells\" align=\"center\">").append(DataHelper.formatDuration(readTime));
            //}
            buf.append("</td></tr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }

        if (!peers.isEmpty()) {
//            buf.append("<tr> <td colspan=\"11\"><hr></td></tr>\n");
            buf.append("<tr class=\"tablefooter\"><td colspan=\"4\" align=\"left\"><b>")
               .append(ngettext("{0} peer", "{0} peers", peers.size()));
            buf.append("</b></td><td align=\"center\" nowrap><span class=\"right\"><b>").append(formatRate(bpsRecv/1000)).append("</b></span>");
            buf.append(THINSP).append("<span class=\"left\"><b>").append(formatRate(bpsSend/1000)).append("</b></span>");
            buf.append("</td><td align=\"right\"><b>").append(DataHelper.formatDuration2(totalUptime/peers.size()));
            buf.append("</b></td><td align=\"right\"><b>").append(DataHelper.formatDuration2(offsetTotal*1000/peers.size()));
            buf.append("</b></td><td align=\"right\"><b>").append(totalSend).append("</b></td><td align=\"right\"><b>").append(totalRecv);
            buf.append("</b></td><td>&nbsp;</td><td>&nbsp;</td></tr>\n");
        }

        buf.append("</table></div>\n");
        out.write(buf.toString());
        buf.setLength(0);
    }

    private static final NumberFormat _rateFmt = new DecimalFormat("#,##0.00");

    private static String formatRate(float rate) {
        synchronized (_rateFmt) { return _rateFmt.format(rate); }
    }

    private Comparator<NTCPConnection> getNTCPComparator(int sortFlags) {
        Comparator<NTCPConnection> rv = null;
        switch (Math.abs(sortFlags)) {
            default:
                rv = AlphaComparator.instance();
        }
        if (sortFlags < 0)
            rv = Collections.reverseOrder(rv);
        return rv;
    }

    private static class AlphaComparator extends PeerComparator {
        private static final AlphaComparator _instance = new AlphaComparator();
        public static final AlphaComparator instance() { return _instance; }
    }

    private static class PeerComparator implements Comparator<NTCPConnection>, Serializable {
        public int compare(NTCPConnection l, NTCPConnection r) {
            if (l == null || r == null)
                throw new IllegalArgumentException();
            // base64 retains binary ordering
            // UM, no it doesn't, but close enough
            return l.getRemotePeer().calculateHash().toBase64().compareTo(r.getRemotePeer().calculateHash().toBase64());
        }
    }

    /// end NTCP
    /// begin SSU

    /**
     *  @since 0.9.31 moved from UDPTransport
     */
    private void render(UDPTransport ut, Writer out, String urlBase, int sortFlags) throws IOException {
        TreeSet<PeerState> peers = new TreeSet<PeerState>(getComparator(sortFlags));
        peers.addAll(ut.getPeers());
        long offsetTotal = 0;

        int bpsIn = 0;
        int bpsOut = 0;
        long uptimeMsTotal = 0;
        long cwinTotal = 0;
        long rttTotal = 0;
        long rtoTotal = 0;
        long sendTotal = 0;
        long recvTotal = 0;
        long resentTotal = 0;
        long dupRecvTotal = 0;
        int numPeers = 0;

        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3 id=\"udpcon\">").append(_t("UDP connections")).append(": ").append(peers.size());
        buf.append(". ").append(_t("Limit")).append(": ").append(ut.getMaxConnections());
        //buf.append(". ").append(_t("Timeout")).append(": ").append(DataHelper.formatDuration2(_expireTimeout));
        if (isAdvanced()) {
            buf.append(". ").append(_t("Status")).append(": ").append(_t(ut.getReachabilityStatus().toStatusString()));
        }
        buf.append(".</h3>\n");
        buf.append("<div class=\"widescroll\"><table id=\"udpconnections\">\n");
        buf.append("<tr class=\"smallhead\"><th nowrap><a href=\"#def.peer\">").append(_t("Peer")).append("</a><br>");
        if (sortFlags != FLAG_ALPHA)
            appendSortLinks(buf, urlBase, sortFlags, _t("Sort by peer hash"), FLAG_ALPHA);
        buf.append("</th><th nowrap><a href=\"#def.dir\" title=\"")
           .append(_t("Direction/Introduction")).append("\">").append(_t("Dir"))
           .append("</a></th><th nowrap>").append(_t("IPv6"))
           .append("</th><th nowrap><a href=\"#def.idle\">").append(_t("Idle")).append("</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by idle inbound"), FLAG_IDLE_IN);
        buf.append(" / ");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by idle outbound"), FLAG_IDLE_OUT);
        buf.append("</th>");
        buf.append("<th nowrap><a href=\"#def.rate\">").append(_t("In/Out")).append("</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by inbound rate"), FLAG_RATE_IN);
        buf.append(" / ");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by outbound rate"), FLAG_RATE_OUT);
        buf.append("</th>\n");
        buf.append("<th nowrap><span class=\"peersort\"><a href=\"#def.up\">").append(_t("Up")).append("</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by connection uptime"), FLAG_UPTIME);
        buf.append("</span></th><th nowrap><span class=\"peersort\"><a href=\"#def.skew\">").append(_t("Skew")).append("</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by clock skew"), FLAG_SKEW);
        buf.append("</span></th>\n");
        buf.append("<th nowrap><a href=\"#def.cwnd\">CWND</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by congestion window"), FLAG_CWND);
        buf.append("</th><th nowrap><span class=\"peersort\"><a href=\"#def.ssthresh\">SST</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by slow start threshold"), FLAG_SSTHRESH);
        buf.append("</span></th>\n");
        buf.append("<th nowrap><span class=\"peersort\"><a href=\"#def.rtt\">RTT</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by round trip time"), FLAG_RTT);
        //buf.append("</th><th nowrap><a href=\"#def.dev\">").append(_t("Dev")).append("</a><br>");
        //appendSortLinks(buf, urlBase, sortFlags, _t("Sort by round trip time deviation"), FLAG_DEV);
        buf.append("</span></th><th nowrap><span class=\"peersort\"><a href=\"#def.rto\">RTO</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by retransmission timeout"), FLAG_RTO);
        buf.append("</span></th>\n");
        buf.append("<th nowrap><a href=\"#def.mtu\">MTU</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by outbound maximum transmit unit"), FLAG_MTU);
        buf.append("</th><th nowrap><span class=\"peersort\"><a href=\"#def.send\">").append(_t("TX")).append("</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets sent"), FLAG_SEND);
        buf.append("</span></th><th nowrap><span class=\"peersort\"><a href=\"#def.recv\">").append(_t("RX")).append("</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets received"), FLAG_RECV);
        buf.append("</span></th>\n");
        buf.append("<th nowrap><span class=\"peersort\"><a href=\"#def.resent\">").append(_t("Dup TX")).append("</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets retransmitted"), FLAG_RESEND);
        buf.append("</span></th><th nowrap><span class=\"peersort\"><a href=\"#def.dupRecv\">").append(_t("Dup RX")).append("</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, _t("Sort by packets received more than once"), FLAG_DUP);
        buf.append("</span></th></tr>\n");
        out.write(buf.toString());
        buf.setLength(0);
        long now = _context.clock().now();
        for (PeerState peer : peers) {
            if (now-peer.getLastReceiveTime() > 60*60*1000)
                continue; // don't include old peers

            buf.append("<tr><td class=\"cells\" align=\"left\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(peer.getRemotePeer()));
            //byte ip[] = peer.getRemoteIP();
            //if (ip != null)
            //    buf.append(' ').append(_context.blocklist().toStr(ip));
            buf.append("</td><td class=\"cells\" nowrap align=\"left\">");
            if (peer.isInbound())
                buf.append("<img src=\"/themes/console/images/inbound.png\" alt=\"Inbound\" title=\"").append(_t("Inbound")).append("\">");
            else
                buf.append("<img src=\"/themes/console/images/outbound.png\" alt=\"Outbound\" title=\"").append(_t("Outbound")).append("\">");
            if (peer.getWeRelayToThemAs() > 0)
                buf.append("&nbsp;&nbsp;<img src=\"/themes/console/images/outbound.png\" height=\"8\" width=\"12\" alt=\"^\" title=\"").append(_t("We offered to introduce them")).append("\">");
            if (peer.getTheyRelayToUsAs() > 0)
                buf.append("&nbsp;&nbsp;<img src=\"/themes/console/images/inbound.png\" height=\"8\" width=\"12\" alt=\"V\" title=\"").append(_t("They offered to introduce us")).append("\">");

            boolean appended = false;
            //if (_activeThrottle.isChoked(peer.getRemotePeer())) {
            //    buf.append("<br><i>").append(_t("Choked")).append("</i>");
            //    appended = true;
            //}
            int cfs = peer.getConsecutiveFailedSends();
            if (cfs > 0) {
                if (!appended) buf.append("<br>");
                buf.append(" <i>");
                if (cfs == 1)
                    buf.append(_t("1 fail"));
                else
                    buf.append(_t("{0} fails", cfs));
                buf.append("</i>");
                appended = true;
            }
            if (_context.banlist().isBanlisted(peer.getRemotePeer(), "SSU")) {
                if (!appended) buf.append("<br>");
                buf.append(" <i>").append(_t("Banned")).append("</i>");
                appended = true;
            }
            //byte[] ip = getIP(peer.getRemotePeer());
            //if (ip != null)
            //    buf.append(' ').append(_context.blocklist().toStr(ip));
            buf.append("</td>");

            buf.append("<td class=\"cells peeripv6\" align=\"center\">");
            if (peer.isIPv6())
                buf.append("&#x2713;");
            else
                buf.append("");
            buf.append("</td>");

            long idleIn = Math.max(now-peer.getLastReceiveTime(), 0);
            long idleOut = Math.max(now-peer.getLastSendTime(), 0);

            buf.append("<td class=\"cells\" align=\"center\"><span class=\"right\">");
            buf.append(DataHelper.formatDuration2(idleIn));
            buf.append("</span>").append(THINSP);
            buf.append("<span class=\"left\">").append(DataHelper.formatDuration2(idleOut));
            buf.append("</span></td>");
 
            int recvBps = (idleIn > 15*1000 ? 0 : peer.getReceiveBps());
            int sendBps = (idleOut > 15*1000 ? 0 : peer.getSendBps());

            buf.append("<td class=\"cells\" align=\"center\" nowrap><span class=\"right\">");
            buf.append(formatKBps(recvBps));
            buf.append("</span>").append(THINSP);
            buf.append("<span class=\"left\">").append(formatKBps(sendBps));
            //buf.append(" K/s");
            //buf.append(formatKBps(peer.getReceiveACKBps()));
            //buf.append("K/s/");
            //buf.append(formatKBps(peer.getSendACKBps()));
            //buf.append("K/s ");
            buf.append("</span></td>");

            long uptime = now - peer.getKeyEstablishedTime();

            buf.append("<td class=\"cells\" align=\"right\">");
            buf.append(DataHelper.formatDuration2(uptime));
            buf.append("</td>");

            buf.append("<td class=\"cells\" align=\"right\">");
            long skew = peer.getClockSkew();
            buf.append(DataHelper.formatDuration2(skew));
            buf.append("</td>");
            offsetTotal = offsetTotal + skew;

            long sendWindow = peer.getSendWindowBytes();

            buf.append("<td class=\"cells cwnd\" align=\"center\"><span class=\"right\">");
            buf.append(sendWindow/1024);
            buf.append("K");
            buf.append("</span>").append(THINSP).append("<span class=\"right\">").append(peer.getConcurrentSends());
            buf.append("</span>").append(THINSP).append("<span class=\"right\">").append(peer.getConcurrentSendWindow());
            buf.append("</span>").append(THINSP).append("<span class=\"left\">").append(peer.getConsecutiveSendRejections());
            if (peer.isBacklogged())
                buf.append(' ').append(_t("backlogged"));
            buf.append("</span></td>");

            buf.append("<td class=\"cells\" align=\"right\">");
            buf.append(peer.getSlowStartThreshold()/1024);
            buf.append("K</td>");

            int rtt = peer.getRTT();
            int rto = peer.getRTO();

            buf.append("<td class=\"cells\" align=\"right\">");
            buf.append(DataHelper.formatDuration2(rtt));
            buf.append("</td>");

            //buf.append("<td class=\"cells\" align=\"right\">");
            //buf.append(DataHelper.formatDuration2(peer.getRTTDeviation()));
            //buf.append("</td>");

            buf.append("<td class=\"cells\" align=\"right\">");
            buf.append(DataHelper.formatDuration2(rto));
            buf.append("</td>");

            buf.append("<td class=\"cells\" align=\"center\"><span class=\"right\">");
            buf.append(peer.getMTU()).append("</span>").append(THINSP);
            buf.append("<span class=\"left\">").append(peer.getReceiveMTU());

            //.append('/');
            //buf.append(peer.getMTUIncreases()).append('/');
            //buf.append(peer.getMTUDecreases());
            buf.append("</span></td>");

            long sent = peer.getMessagesSent();
            long recv = peer.getMessagesReceived();

            buf.append("<td class=\"cells\" align=\"right\">");
            buf.append(sent);
            buf.append("</td>");

            buf.append("<td class=\"cells\" align=\"right\">");
            buf.append(recv);
            buf.append("</td>");

            //double sent = (double)peer.getPacketsPeriodTransmitted();
            //double sendLostPct = 0;
            //if (sent > 0)
            //    sendLostPct = (double)peer.getPacketsRetransmitted()/(sent);

            long resent = peer.getPacketsRetransmitted();
            long dupRecv = peer.getPacketsReceivedDuplicate();

            buf.append("<td class=\"cells\" align=\"right\">");
            //buf.append(formatPct(sendLostPct));
            buf.append(resent); // + "/" + peer.getPacketsPeriodRetransmitted() + "/" + sent);
            //buf.append(peer.getPacketRetransmissionRate());
            buf.append("</td>");

            buf.append("<td class=\"cells\" align=\"right\">");
            buf.append(dupRecv); //formatPct(recvDupPct));
            buf.append("</td>");

            buf.append("</tr>\n");
            out.write(buf.toString());
            buf.setLength(0);

            bpsIn += recvBps;
            bpsOut += sendBps;

            uptimeMsTotal += uptime;
            cwinTotal += sendWindow;
            rttTotal += rtt;
            rtoTotal += rto;

            sendTotal += sent;
            recvTotal += recv;
            resentTotal += resent;
            dupRecvTotal += dupRecv;

            numPeers++;
        }

      if (numPeers > 0) {
//        buf.append("<tr><td colspan=\"16\"><hr></td></tr>\n");
        buf.append("<tr class=\"tablefooter\"><td colspan=\"4\" align=\"left\"><b>")
           .append(ngettext("{0} peer", "{0} peers", peers.size()))
           .append("</b></td>" +
                   "<td align=\"center\" nowrap><span class=\"right\"><b>");
        buf.append(formatKBps(bpsIn)).append("</b></span>").append(THINSP);
        buf.append("<span class=\"left\"><b>").append(formatKBps(bpsOut));
        long x = uptimeMsTotal/numPeers;
        buf.append("</b></span></td>" +
                   "<td align=\"right\"><b>").append(DataHelper.formatDuration2(x));
        x = offsetTotal/numPeers;
        buf.append("</b></td><td align=\"right\"><b>").append(DataHelper.formatDuration2(x)).append("</b></td>\n" +
                   "<td align=\"center\"><b>");
        buf.append(cwinTotal/(numPeers*1024) + "K");
        buf.append("</b></td><td>&nbsp;</td>\n" +
                   "<td align=\"right\"><b>");
        buf.append(DataHelper.formatDuration2(rttTotal/numPeers));
        //buf.append("</b></td><td>&nbsp;</td><td align=\"center\"><b>");
        buf.append("</b></td><td align=\"right\"><b>");
        buf.append(DataHelper.formatDuration2(rtoTotal/numPeers));
        buf.append("</b></td><td align=\"center\"><b>").append(ut.getMTU(false)).append("</b></td><td align=\"right\"><b>");
        buf.append(sendTotal).append("</b></td><td align=\"right\"><b>").append(recvTotal).append("</b></td>\n" +
                   "<td align=\"right\"><b>").append(resentTotal);
        buf.append("</b></td><td align=\"right\"><b>").append(dupRecvTotal).append("</b></td></tr>\n");
/****
        if (sortFlags == FLAG_DEBUG) {
            buf.append("<tr><td colspan=\"16\">");
            buf.append("peersByIdent: ").append(_peersByIdent.size());
            buf.append(" peersByRemoteHost: ").append(_peersByRemoteHost.size());
            int dir = 0;
            int indir = 0;
            for (RemoteHostId rhi : _peersByRemoteHost.keySet()) {
                 if (rhi.getIP() != null)
                     dir++;
                 else
                     indir++;
            }
            buf.append(" pBRH direct: ").append(dir).append(" indirect: ").append(indir);
            buf.append("</td></tr>");
        }
****/
     }  // numPeers > 0
        buf.append("</table></div>\n");

      /*****
        long bytesTransmitted = _context.bandwidthLimiter().getTotalAllocatedOutboundBytes();
        // NPE here early
        double averagePacketSize = _context.statManager().getRate("udp.sendPacketSize").getLifetimeAverageValue();
        // lifetime value, not just the retransmitted packets of current connections
        resentTotal = (long)_context.statManager().getRate("udp.packetsRetransmitted").getLifetimeEventCount();
        double nondupSent = ((double)bytesTransmitted - ((double)resentTotal)*averagePacketSize);
        double bwResent = (nondupSent <= 0 ? 0d : ((((double)resentTotal)*averagePacketSize) / nondupSent));
        buf.append("<h3>Percentage of bytes retransmitted (lifetime): ").append(formatPct(bwResent));
        buf.append("</h3><i>(Includes retransmission required by packet loss)</i>\n");
      *****/

        out.write(buf.toString());
        buf.setLength(0);
    }

    private static final DecimalFormat _fmt = new DecimalFormat("#,##0.00");

    private static final String formatKBps(int bps) {
        synchronized (_fmt) {
            return _fmt.format((float)bps/1000);
        }
    }
}
