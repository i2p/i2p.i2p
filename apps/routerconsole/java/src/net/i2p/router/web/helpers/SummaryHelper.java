package net.i2p.router.web.helpers;

import java.io.IOException;
import java.text.Collator;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.networkdb.reseed.ReseedChecker;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.NewsHelper;
import net.i2p.router.web.WebAppStarter;
import net.i2p.servlet.util.ServletUtil;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.PortMapper;
import net.i2p.util.SystemVersion;

/**
 * Simple helper to query the appropriate router for data necessary to render
 * the summary sections on the router console.
 *
 * For the full summary bar use renderSummaryBar()
 */
public class SummaryHelper extends HelperBase {

    // Opera 10.63 doesn't have the char, TODO check UA
    //static final String THINSP = "&thinsp;/&thinsp;";
    static final String THINSP = " / ";
    private static final char S = ',';
    static final String PROP_SUMMARYBAR = "routerconsole.summaryBar.";

    static final String DEFAULT_FULL =
        "RouterInfo" + S +
        "UpdateStatus" + S +
        "Bandwidth" + S +
        "NetworkReachability" + S +
        "FirewallAndReseedStatus" + S +
        "I2PServices" + S +
        "I2PInternals" + S +
        "HelpAndFAQ" + S +
        "Peers" + S +
        "Tunnels" + S +
        "TunnelStatus" + S +
        "RestartStatus" + S +
        "Destinations" + S +
        "";

    static final String DEFAULT_FULL_ADVANCED =
        "AdvancedRouterInfo" + S +
        "MemoryBar" + S +
        "UpdateStatus" + S +
        "Bandwidth" + S +
        "NetworkReachability" + S +
        "FirewallAndReseedStatus" + S +
        "I2PServices" + S +
        "I2PInternals" + S +
        "Advanced" + S +
        "Peers" + S +
        "Tunnels" + S +
        "TunnelStatus" + S +
        "Congestion" + S +
        "RestartStatus" + S +
        "Destinations" + S +
        "";

    static final String DEFAULT_MINIMAL =
        "ShortRouterInfo" + S +
        "Bandwidth" + S +
        "UpdateStatus" + S +
        "NewsHeadings" + S +
        "NetworkReachability" + S +
        "FirewallAndReseedStatus" + S +
        "RestartStatus" + S +
        "Destinations" + S +
        "";

     /** @since 0.9.32 */
    static final String DEFAULT_MINIMAL_ADVANCED =
        "AdvancedRouterInfo" + S +
        "MemoryBar" + S +
        "Bandwidth" + S +
        "UpdateStatus" + S +
        "NewsHeadings" + S +
        "NetworkReachability" + S +
        "FirewallAndReseedStatus" + S +
        "RestartStatus" + S +
        "Destinations" + S +
        "";

    /**
     * Retrieve the shortened 4 character ident for the router located within
     * the current JVM at the given context.
     *
     */
    public String getIdent() { 
        if (_context == null) return "[no router]";

        if (_context.routerHash() != null)
            return _context.routerHash().toBase64().substring(0, 4);
        else
            return "[unknown]";
    }
    /**
     * Retrieve the version number of the router.
     *
     */
    public String getVersion() { 
        return RouterVersion.FULL_VERSION;
    }
    /**
     * Retrieve a pretty printed uptime count (ala 4d or 7h or 39m)
     *
     */
    public String getUptime() { 
        if (_context == null) return "[no router]";

        Router router = _context.router();
        if (router == null) 
            return "[not up]";
        else
            return DataHelper.formatDuration2(router.getUptime());
    }

/**
    this displayed offset, not skew - now handled in reachability()

    private String timeSkew() {
        if (_context == null) return "";
        //if (!_context.clock().getUpdatedSuccessfully())
        //    return " (Unknown skew)";
        long ms = _context.clock().getOffset();
        long diff = Math.abs(ms);
        if (diff < 3000)
            return "";
        return " (" + DataHelper.formatDuration2(diff) + " " + _t("skew") + ")";
    }
**/

    /** allowReseed */
    public boolean allowReseed() {
        return _context.netDb().isInitialized() &&
               (_context.netDb().getKnownRouters() < ReseedChecker.MINIMUM) ||
                _context.getBooleanProperty("i2p.alwaysAllowReseed");
    }

    /** subtract one for ourselves, so if we know no other peers it displays zero */
    public int getAllPeers() { return Math.max(_context.netDb().getKnownRouters() - 1, 0); }

    public enum NetworkState {
        HIDDEN,
        TESTING,
        FIREWALLED,
        RUNNING,
        WARN,
        ERROR,
        CLOCKSKEW,
        VMCOMM;
    }

    /**
     * State message to be displayed to the user in the summary bar.
     *
     * @since 0.9.31
     */
    public static class NetworkStateMessage {
        private NetworkState state;
        private String msg;

        NetworkStateMessage(NetworkState state, String msg) {
            setMessage(state, msg);
        }

        public void setMessage(NetworkState state, String msg) {
            this.state = state;
            this.msg = msg;
        }

        public NetworkState getState() {
            return state;
        }

        public String getMessage() {
            return msg;
        }

        @Override
        public String toString() {
            return "(" + state + "; " + msg + ')';
        }
    }

    public NetworkStateMessage getReachability() {
        return reachability(); // + timeSkew();
        // testing
        //return reachability() +
        //       " Offset: " + DataHelper.formatDuration(_context.clock().getOffset()) +
        //       " Slew: " + DataHelper.formatDuration(((RouterClock)_context.clock()).getDeltaOffset());
    }

    private NetworkStateMessage reachability() {
        if (_context.commSystem().isDummy())
            return new NetworkStateMessage(NetworkState.VMCOMM, "VM Comm System");
        if (_context.router().getUptime() > 60*1000 && (!_context.router().gracefulShutdownInProgress()) &&
            !_context.clientManager().isAlive())
            return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-Client Manager I2CP Error - check logs"));  // not a router problem but the user should know
        // Warn based on actual skew from peers, not update status, so if we successfully offset
        // the clock, we don't complain.
        //if (!_context.clock().getUpdatedSuccessfully())
        long skew = _context.commSystem().getFramedAveragePeerClockSkew(33);
        // Display the actual skew, not the offset
        if (Math.abs(skew) > 30*1000)
            return new NetworkStateMessage(NetworkState.CLOCKSKEW, _t("ERR-Clock Skew of {0}", DataHelper.formatDuration2(Math.abs(skew))));
        if (_context.router().isHidden())
            return new NetworkStateMessage(NetworkState.HIDDEN, _t("Hidden"));
        RouterInfo routerInfo = _context.router().getRouterInfo();
        if (routerInfo == null)
            return new NetworkStateMessage(NetworkState.TESTING, _t("Testing"));

        Status status = _context.commSystem().getStatus();
        NetworkState state = NetworkState.RUNNING;
        switch (status) {
            case OK:
            case IPV4_OK_IPV6_UNKNOWN:
            case IPV4_OK_IPV6_FIREWALLED:
            case IPV4_UNKNOWN_IPV6_OK:
            case IPV4_DISABLED_IPV6_OK:
            case IPV4_SNAT_IPV6_OK:
                RouterAddress ra = routerInfo.getTargetAddress("NTCP");
                if (ra == null)
                    return new NetworkStateMessage(NetworkState.RUNNING, _t(status.toStatusString()));
                byte[] ip = ra.getIP();
                if (ip == null)
                    return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-Unresolved TCP Address"));
                // TODO set IPv6 arg based on configuration?
                if (TransportUtil.isPubliclyRoutable(ip, true))
                    return new NetworkStateMessage(NetworkState.RUNNING, _t(status.toStatusString()));
                return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-Private TCP Address"));

            case IPV4_SNAT_IPV6_UNKNOWN:
            case DIFFERENT:
                return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-SymmetricNAT"));

            case REJECT_UNSOLICITED:
                state = NetworkState.FIREWALLED;
            case IPV4_DISABLED_IPV6_FIREWALLED:
                if (routerInfo.getTargetAddress("NTCP") != null)
                    return new NetworkStateMessage(NetworkState.WARN, _t("WARN-Firewalled with Inbound TCP Enabled"));
                // fall through...
            case IPV4_FIREWALLED_IPV6_OK:
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
                if (((FloodfillNetworkDatabaseFacade)_context.netDb()).floodfillEnabled())
                    return new NetworkStateMessage(NetworkState.WARN, _t("WARN-Firewalled and Floodfill"));
                //if (_context.router().getRouterInfo().getCapabilities().indexOf('O') >= 0)
                //    return new NetworkStateMessage(NetworkState.WARN, _t("WARN-Firewalled and Fast"));
                return new NetworkStateMessage(state, _t(status.toStatusString()));

            case DISCONNECTED:
                return new NetworkStateMessage(NetworkState.TESTING, _t("Disconnected - check network connection"));

            case HOSED:
                return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-UDP Port In Use - Set i2np.udp.internalPort=xxxx in advanced config and restart"));

            case UNKNOWN:
                state = NetworkState.TESTING;
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
            case IPV4_DISABLED_IPV6_UNKNOWN:
            default:
                ra = routerInfo.getTargetAddress("SSU");
                if (ra == null && _context.router().getUptime() > 5*60*1000) {
                    if (getActivePeers() <= 0)
                        return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-No Active Peers, Check Network Connection and Firewall"));
                    else if (_context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME) == null ||
                        _context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_PORT) == null)
                        return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-UDP Disabled and Inbound TCP host/port not set"));
                    else
                        return new NetworkStateMessage(NetworkState.WARN, _t("WARN-Firewalled with UDP Disabled"));
                }
                return new NetworkStateMessage(state, _t(status.toStatusString()));
        }
    }

    /**
     * Retrieve amount of used memory.
     * @since 0.9.32 uncommented
     */
    public String getMemory() {
        DecimalFormat integerFormatter = new DecimalFormat("###,###,##0");
        long tot = SystemVersion.getMaxMemory();
        // This reads much higher than the graph, possibly because it's right in
        // the middle of a console refresh... so get it from the Rate instead.
        //long free = Runtime.getRuntime().freeMemory();
        long used = (long) _context.statManager().getRate("router.memoryUsed").getRate(60*1000).getAvgOrLifetimeAvg();
        if (used <= 0) {
            long free = Runtime.getRuntime().freeMemory();
            used = tot - free;
        }
        used /= 1024*1024;
        long total = tot / (1024*1024);
        if (used > total)
            used = total;
        // long free = Runtime.getRuntime().freeMemory()/1024/1024;
        // return integerFormatter.format(used) + "MB (" + usedPc + "%)";
        // return integerFormatter.format(used) + "MB / " + free + " MB";
        return integerFormatter.format(used) + " / " + total + " MiB";
    }

    /** @since 0.9.32 */
    public String getMemoryBar() {
        DecimalFormat integerFormatter = new DecimalFormat("###,###,##0");
        long tot = SystemVersion.getMaxMemory();
        // This reads much higher than the graph, possibly because it's right in
        // the middle of a console refresh... so get it from the Rate instead.
        //long free = Runtime.getRuntime().freeMemory();
        long used = (long) _context.statManager().getRate("router.memoryUsed").getRate(60*1000).getAvgOrLifetimeAvg();
        long usedPc;
        if (used <= 0) {
            long free = Runtime.getRuntime().freeMemory();
            usedPc = 100 - ((free * 100) / tot);
            used = (tot - free) / (1024*1024);
        } else {
            usedPc = used * 100 / tot;
            used /= 1024*1024;
        }
        long total = tot / (1024*1024);
        if (used > total)
            used = total;
        if (usedPc > 100)
            usedPc = 100;
        // long free = Runtime.getRuntime().freeMemory()/1024/1024;
        // return integerFormatter.format(used) + "MB (" + usedPc + "%)";
        // return integerFormatter.format(used) + "MB / " + free + " MB";
        return "<div class=\"percentBarOuter\" id=\"sb_memoryBar\"><div class=\"percentBarText\">RAM: " +
               integerFormatter.format(used) + " / " + total + " MiB" +
               "</div><div class=\"percentBarInner\" style=\"width: " + integerFormatter.format(usedPc) +
               "%;\"></div></div>";
    }

    /**
     * How many peers we are talking to now
     *
     */
    public int getActivePeers() {
        if (_context == null)
            return 0;
        else
            return _context.commSystem().countActivePeers();
    }

    /**
     * Should we warn about a possible firewall problem?
     */
    public boolean showFirewallWarning() {
        return _context != null &&
               _context.netDb().isInitialized() &&
               _context.router().getUptime() > 2*60*1000 &&
               (!_context.commSystem().isDummy()) &&
               _context.commSystem().countActivePeers() <= 0 &&
               _context.netDb().getKnownRouters() > 5;
    }

    /**
     * How many active identities have we spoken with recently
     *
     */
    public int getActiveProfiles() {
        if (_context == null)
            return 0;
        else
            return _context.profileOrganizer().countActivePeers();
    }
    /**
     * How many active peers the router ranks as fast.
     *
     */
    public int getFastPeers() {
        if (_context == null)
            return 0;
        else
            return _context.profileOrganizer().countFastPeers();
    }
    /**
     * How many active peers the router ranks as having a high capacity.
     *
     */
    public int getHighCapacityPeers() {
        if (_context == null)
            return 0;
        else
            return _context.profileOrganizer().countHighCapacityPeers();
    }
    /**
     * How many active peers the router ranks as well integrated.
     *
     */
    public int getWellIntegratedPeers() {
        if (_context == null)
            return 0;
        //return _context.profileOrganizer().countWellIntegratedPeers();
        return _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL).size();
    }

    /**
     * How many peers the router ranks as failing.
     * @since 0.9.32 uncommented
     */
    public int getFailingPeers() {
        if (_context == null)
            return 0;
        else
            return _context.profileOrganizer().countFailingPeers();
    }

    /**
     * How many peers are banned.
     * @since 0.9.32 uncommented
     */
    public int getBanlistedPeers() {
        if (_context == null)
            return 0;
        else
            return _context.banlist().getRouterCount();
    }


    /**
     *    @return "x.xx / y.yy {K|M}"
     */
    public String getSecondKBps() {
        if (_context == null)
            return "0 / 0";
        return formatPair(_context.bandwidthLimiter().getReceiveBps(),
                          _context.bandwidthLimiter().getSendBps());
    }

    /**
     *    @return "x.xx / y.yy {K|M}"
     */
    public String getFiveMinuteKBps() {
        if (_context == null)
            return "0 / 0";

        RateStat receiveRate = _context.statManager().getRate("bw.recvRate");
        double in = 0;
        if (receiveRate != null) {
            Rate r = receiveRate.getRate(5*60*1000);
            if (r != null)
                in = r.getAverageValue();
        }
        RateStat sendRate = _context.statManager().getRate("bw.sendRate");
        double out = 0;
        if (sendRate != null) {
            Rate r = sendRate.getRate(5*60*1000);
            if (r != null)
                out = r.getAverageValue();
        }
        return formatPair(in, out);
    }

    /**
     *    @return "x.xx / y.yy {K|M}"
     */
    public String getLifetimeKBps() {
        if (_context == null)
            return "0 / 0";

        RateStat receiveRate = _context.statManager().getRate("bw.recvRate");
        double in;
        if (receiveRate == null)
            in = 0;
        else
            in = receiveRate.getLifetimeAverageValue();
        RateStat sendRate = _context.statManager().getRate("bw.sendRate");
        double out;
        if (sendRate == null)
            out = 0;
        else
            out = sendRate.getLifetimeAverageValue();
        return formatPair(in, out);
    }

    /**
     *  Output is decimal, not binary
     *  @return "x.xx / y.yy {K|M}"
     */
    private static String formatPair(double in, double out) {
        boolean mega = in >= 1000*1000 || out >= 1000*1000;
        // scale both the same
        if (mega) {
            in /= 1000*1000;
            out /= 1000*1000;
        } else {
            in /= 1000;
            out /= 1000;
        }
        // control total width
        DecimalFormat fmt;
        if (in >= 1000 || out >= 1000)
            fmt = new DecimalFormat("#0");
        else if (in >= 100 || out >= 100)
            fmt = new DecimalFormat("#0.0");
        else
            fmt = new DecimalFormat("#0.00");
        return fmt.format(in) + THINSP + fmt.format(out) + "&nbsp;" +
               (mega ? 'M' : 'K');
    }

    /**
     * How much data have we received since the router started (pretty printed
     * string with 2 decimal places and the appropriate units - GB/MB/KB/bytes)
     *
     */
    public String getInboundTransferred() {
        if (_context == null)
            return "0";
        long received = _context.bandwidthLimiter().getTotalAllocatedInboundBytes();
        return DataHelper.formatSize2Decimal(received) + 'B';
    }

    /**
     * How much data have we sent since the router started (pretty printed
     * string with 2 decimal places and the appropriate units - GB/MB/KB/bytes)
     *
     */
    public String getOutboundTransferred() {
        if (_context == null)
            return "0";
        long sent = _context.bandwidthLimiter().getTotalAllocatedOutboundBytes();
        return DataHelper.formatSize2Decimal(sent) + 'B';
    }

    /**
     * Client destinations connected locally.
     *
     * @return html section summary
     */
    public String getDestinations() {
        // convert the set to a list so we can sort by name and not lose duplicates
        List<Destination> clients = new ArrayList<Destination>(_context.clientManager().listClients());

        StringBuilder buf = new StringBuilder(512);
        boolean link = _context.portMapper().isRegistered("i2ptunnel");
        buf.append("<h3>");
        if (link) {
            buf.append("<a href=\"/i2ptunnelmgr\" target=\"_top\" title=\"")
           .append(_t("Add/remove/edit &amp; control your client and server tunnels"))
           .append("\">");
        }
        buf.append(_t("Local Tunnels"));
        if (link) {
           buf.append("</a>");
        }
        buf.append("</h3><hr class=\"b\">");
        if (!clients.isEmpty()) {
            Collections.sort(clients, new AlphaComparator());
            buf.append("<table id=\"sb_localtunnels\">");

            for (Destination client : clients) {
                String name = getName(client);
                Hash h = client.calculateHash();

                buf.append("<tr><td align=\"right\"><img src=\"/themes/console/images/");
                if (_context.clientManager().shouldPublishLeaseSet(h))
                    buf.append("server.png\" alt=\"Server\" title=\"").append(_t("Hidden Service")).append("\">");
                else
                    buf.append("client.png\" alt=\"Client\" title=\"").append(_t("Client")).append("\">");
                buf.append("</td><td align=\"left\"><b><a href=\"tunnels#").append(h.toBase64().substring(0,4));
                buf.append("\" target=\"_top\" title=\"").append(_t("Show tunnels")).append("\">");
                // Increase permitted max length of tunnel name & handle overflow with css
                if (name.length() <= 32)
                    buf.append(DataHelper.escapeHTML(name));
                else
                    buf.append(DataHelper.escapeHTML(ServletUtil.truncate(name, 29))).append("&hellip;");
                buf.append("</a></b></td>\n");
                LeaseSet ls = _context.netDb().lookupLeaseSetLocally(h);
                if (ls != null && _context.tunnelManager().getOutboundClientTunnelCount(h) > 0) {
                    long timeToExpire = ls.getEarliestLeaseDate() - _context.clock().now();
                    if (timeToExpire < 0) {
                        // red or yellow light
                        buf.append("<td><img src=\"/themes/console/images/local_inprogress.png\" alt=\"").append(_t("Rebuilding")).append("&hellip;\" title=\"").append(_t("Leases expired")).append(" ").append(DataHelper.formatDuration2(0-timeToExpire));
                        buf.append(" ").append(_t("ago")).append(". ").append(_t("Rebuilding")).append("&hellip;\"></td></tr>\n");
                    } else {
                        // green light
                        buf.append("<td><img src=\"/themes/console/images/local_up.png\" alt=\"Ready\" title=\"").append(_t("Ready")).append("\"></td></tr>\n");
                    }
                } else {
                    // yellow light
                    buf.append("<td><img src=\"/themes/console/images/local_inprogress.png\" alt=\"").append(_t("Building")).append("&hellip;\" title=\"").append(_t("Building tunnels")).append("&hellip;\"></td></tr>\n");
                }
            }
            buf.append("</table>");
        } else {
            buf.append("<center><i>").append(_t("none")).append("</i></center>");
        }
        return buf.toString();
    }

    /**
     *  Compare translated nicknames - put "shared clients" first in the sort
     *  Inner class, can't be Serializable
     */
    private class AlphaComparator implements Comparator<Destination> {
        private final String xsc = _t("shared clients");

        public int compare(Destination lhs, Destination rhs) {
            String lname = getName(lhs);
            String rname = getName(rhs);
            boolean lshared = lname.startsWith("shared clients") || lname.startsWith(xsc);
            boolean rshared = rname.startsWith("shared clients") || rname.startsWith(xsc);
            if (lshared && !rshared)
                return -1;
            if (rshared && !lshared)
                return 1;
            return Collator.getInstance().compare(lname, rname);
        }
    }

    /** translate here so collation works above */
    private String getName(Destination d) {
        TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(d.calculateHash());
        String name = (in != null ? in.getDestinationNickname() : null);
        if (name == null) {
            TunnelPoolSettings out = _context.tunnelManager().getOutboundSettings(d.calculateHash());
            name = (out != null ? out.getDestinationNickname() : null);
            if (name == null)
                name = d.calculateHash().toBase64().substring(0,6);
            else
                name = _t(name);
        } else {
            name = _t(name);
        }
        return name;
    }

    /**
     * How many free inbound tunnels we have.
     *
     */
    public int getInboundTunnels() {
        if (_context == null)
            return 0;
        else
            return _context.tunnelManager().getFreeTunnelCount();
    }

    /**
     * How many active outbound tunnels we have.
     *
     */
    public int getOutboundTunnels() {
        if (_context == null)
            return 0;
        else
            return _context.tunnelManager().getOutboundTunnelCount();
    }

    /**
     * How many inbound client tunnels we have.
     *
     */
    public int getInboundClientTunnels() {
        if (_context == null)
            return 0;
        else
            return _context.tunnelManager().getInboundClientTunnelCount();
    }

    /**
     * How many active outbound client tunnels we have.
     *
     */
    public int getOutboundClientTunnels() {
        if (_context == null)
            return 0;
        else
            return _context.tunnelManager().getOutboundClientTunnelCount();
    }

    /**
     * How many tunnels we are participating in.
     *
     */
    public int getParticipatingTunnels() {
        if (_context == null)
            return 0;
        else
            return _context.tunnelManager().getParticipatingCount();
    }
 
    /** @since 0.7.10 */
    public String getShareRatio() {
        if (_context == null)
            return "0";
        double sr = _context.tunnelManager().getShareRatio();
        DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(sr);
    }

    /**
     * How lagged our job queue is over the last minute (pretty printed with
     * the units attached)
     *
     */
    public String getJobLag() {
        if (_context == null)
            return "0";

        RateStat rs = _context.statManager().getRate("jobQueue.jobLag");
        if (rs == null)
            return "0";
        Rate lagRate = rs.getRate(60*1000);
        return DataHelper.formatDuration2((long)lagRate.getAverageValue());
    }
 
    /**
     * How long it takes us to pump out a message, averaged over the last minute 
     * (pretty printed with the units attached)
     *
     */
    public String getMessageDelay() {
        if (_context == null)
            return "0";

        return DataHelper.formatDuration2(_context.throttle().getMessageDelay());
    }

    /**
     * How long it takes us to test our tunnels, averaged over the last 10 minutes
     * (pretty printed with the units attached)
     *
     */
    public String getTunnelLag() {
        if (_context == null)
            return "0";

        return DataHelper.formatDuration2(_context.throttle().getTunnelLag());
    }

    public String getTunnelStatus() {
        if (_context == null)
            return "";
        return _context.throttle().getTunnelStatus();
    }

    public String getInboundBacklog() {
        if (_context == null)
            return "0";

        return String.valueOf(_context.tunnelManager().getInboundBuildQueueSize());
    }

/*******
    public String getPRNGStatus() {
        Rate r = _context.statManager().getRate("prng.bufferWaitTime").getRate(60*1000);
        int use = (int) r.getLastEventCount();
        int i = (int) (r.getAverageValue() + 0.5);
        if (i <= 0) {
            r = _context.statManager().getRate("prng.bufferWaitTime").getRate(10*60*1000);
            i = (int) (r.getAverageValue() + 0.5);
        }
        String rv = i + "/";
        r = _context.statManager().getRate("prng.bufferFillTime").getRate(60*1000);
        i = (int) (r.getAverageValue() + 0.5);
        if (i <= 0) {
            r = _context.statManager().getRate("prng.bufferFillTime").getRate(10*60*1000);
            i = (int) (r.getAverageValue() + 0.5);
        }
        rv = rv + i + "ms";
        // margin == fill time / use time
        if (use > 0 && i > 0)
            rv = rv + ' ' + (60*1000 / (use * i)) + 'x';
        return rv;
    }
********/

    private static boolean updateAvailable() {
        return NewsHelper.isUpdateAvailable();
    }

    private boolean unsignedUpdateAvailable() {
        return NewsHelper.isUnsignedUpdateAvailable(_context);
    }

    /** @since 0.9.20 */
    private boolean devSU3UpdateAvailable() {
        return NewsHelper.isDevSU3UpdateAvailable(_context);
    }

    private static String getUpdateVersion() {
        return DataHelper.escapeHTML(NewsHelper.updateVersion());
    }

    private static String getUnsignedUpdateVersion() {
        // value is a formatted date, does not need escaping
        return NewsHelper.unsignedUpdateVersion();
    }

    /** @since 0.9.20 */
    private static String getDevSU3UpdateVersion() {
        return DataHelper.escapeHTML(NewsHelper.devSU3UpdateVersion());
    }

    /**
     *  The update status and buttons
     *  @since 0.8.13 moved from SummaryBarRenderer
     */
    public String getUpdateStatus() {
        StringBuilder buf = new StringBuilder(512);
        // display all the time so we display the final failure message, and plugin update messages too
        String status = NewsHelper.getUpdateStatus();
        boolean needSpace = false;
        if (status.length() > 0) {
            buf.append("<h4 class=\"sb_info sb_update\">").append(status).append("</h4>\n");
            needSpace = true;
        }
        String dver = NewsHelper.updateVersionDownloaded();
        if (dver == null) {
            dver = NewsHelper.devSU3VersionDownloaded();
            if (dver == null)
                dver = NewsHelper.unsignedVersionDownloaded();
        }
        if (dver != null &&
            !NewsHelper.isUpdateInProgress() &&
            !_context.router().gracefulShutdownInProgress()) {
            if (needSpace)
                buf.append("<hr>");
            else
                needSpace = true;
            buf.append("<h4 class=\"sb_info sb_update\"><b>").append(_t("Update downloaded")).append("<br>");
            if (_context.hasWrapper())
                buf.append(_t("Click Restart to install"));
            else
                buf.append(_t("Click Shutdown and restart to install"));
            buf.append(' ').append(_t("Version {0}", DataHelper.escapeHTML(dver)));
            buf.append("</b></h4>");
        }
        boolean avail = updateAvailable();
        boolean unsignedAvail = unsignedUpdateAvailable();
        boolean devSU3Avail = devSU3UpdateAvailable();
        String constraint = avail ? NewsHelper.updateConstraint() : null;
        String unsignedConstraint = unsignedAvail ? NewsHelper.unsignedUpdateConstraint() : null;
        String devSU3Constraint = devSU3Avail ? NewsHelper.devSU3UpdateConstraint() : null;
        if (avail && constraint != null &&
            !NewsHelper.isUpdateInProgress() &&
            !_context.router().gracefulShutdownInProgress()) {
            if (needSpace)
                buf.append("<hr>");
            else
                needSpace = true;
            buf.append("<h4 class=\"sb_info sb_update\"><b>").append(_t("Update available")).append(":<br>");
            buf.append(_t("Version {0}", getUpdateVersion())).append("<br>");
            buf.append(constraint).append("</b></h4>");
            avail = false;
        }
        if (unsignedAvail && unsignedConstraint != null &&
            !NewsHelper.isUpdateInProgress() &&
            !_context.router().gracefulShutdownInProgress()) {
            if (needSpace)
                buf.append("<hr>");
            else
                needSpace = true;
            buf.append("<h4 class=\"sb_info sb_update\"><b>").append(_t("Update available")).append(":<br>");
            buf.append(_t("Version {0}", getUnsignedUpdateVersion())).append("<br>");
            buf.append(unsignedConstraint).append("</b></h4>");
            unsignedAvail = false;
        }
        if (devSU3Avail && devSU3Constraint != null &&
            !NewsHelper.isUpdateInProgress() &&
            !_context.router().gracefulShutdownInProgress()) {
            if (needSpace)
                buf.append("<hr>");
            else
                needSpace = true;
            buf.append("<h4 class=\"sb_info sb_update\"><b>").append(_t("Update available")).append(":<br>");
            buf.append(_t("Version {0}", getDevSU3UpdateVersion())).append("<br>");
            buf.append(devSU3Constraint).append("</b></h4>");
            devSU3Avail = false;
        }
        if ((avail || unsignedAvail || devSU3Avail) &&
            !NewsHelper.isUpdateInProgress() &&
            !_context.router().gracefulShutdownInProgress() &&
            _context.portMapper().isRegistered(PortMapper.SVC_HTTP_PROXY) &&  // assume using proxy for now
            getAction() == null &&
            getUpdateNonce() == null) {
                if (needSpace)
                    buf.append("<hr>");
                long nonce = _context.random().nextLong();
                String prev = System.getProperty("net.i2p.router.web.UpdateHandler.nonce");
                if (prev != null)
                    System.setProperty("net.i2p.router.web.UpdateHandler.noncePrev", prev);
                System.setProperty("net.i2p.router.web.UpdateHandler.nonce", nonce+"");
                String uri = getRequestURI();
                buf.append("<form action=\"").append(uri).append("\" method=\"POST\">\n");
                buf.append("<input type=\"hidden\" name=\"updateNonce\" value=\"").append(nonce).append("\" >\n");
                if (avail) {
                    buf.append("<button type=\"submit\" class=\"download\" name=\"updateAction\" value=\"signed\" >")
                       // Note to translators: parameter is a version, e.g. "0.8.4"
                       .append(_t("Download {0} Update", getUpdateVersion()))
                       .append("</button><br>\n");
                }
                if (devSU3Avail) {
                    buf.append("<button type=\"submit\" class=\"download\" name=\"updateAction\" value=\"DevSU3\" >")
                       // Note to translators: parameter is a router version, e.g. "0.9.19-16"
                       // <br> is optional, to help the browser make the lines even in the button
                       // If the translation is shorter than the English, you should probably not include <br>
                       .append(_t("Download Signed<br>Development Update<br>{0}", getDevSU3UpdateVersion()))
                       .append("</button><br>\n");
                }
                if (unsignedAvail) {
                    buf.append("<button type=\"submit\" class=\"download\" name=\"updateAction\" value=\"Unsigned\" >")
                       // Note to translators: parameter is a date and time, e.g. "02-Mar 20:34 UTC"
                       // <br> is optional, to help the browser make the lines even in the button
                       // If the translation is shorter than the English, you should probably not include <br>
                       .append(_t("Download Unsigned<br>Update {0}", getUnsignedUpdateVersion()))
                       .append("</button><br>\n");
                }
                buf.append("</form>\n");
        }
        return buf.toString();
    }

    /**
     *  The restart status and buttons
     *  @since 0.8.13 moved from SummaryBarRenderer
     */
    public String getRestartStatus() {
        return ConfigRestartBean.renderStatus(getRequestURI(), getAction(), getConsoleNonce());
    }

    /**
     *  The firewall status and reseed status/buttons
     *  @since 0.9 moved from SummaryBarRenderer
     */
    public String getFirewallAndReseedStatus() {
        StringBuilder buf = new StringBuilder(256);
        if (showFirewallWarning()) {
            buf.append("<h4 id=\"sb_warning\"><a href=\"/help#configurationhelp\" target=\"_top\" title=\"")
               .append(_t("Help with firewall configuration"))
               .append("\">")
               .append(_t("Check network connection and NAT/firewall"))
               .append("</a></h4>");
        }

        ReseedChecker checker = _context.netDb().reseedChecker();
        String status = checker.getStatus();
        if (status.length() > 0) {
            // Show status message even if not running, timer in ReseedChecker should remove after 20 minutes
            buf.append("<div class=\"sb_notice\"><i>").append(status).append("</i></div>");
        }
        if (!checker.inProgress()) {
            // If a new reseed isn't running, and the last reseed had errors, show error message
            String reseedErrorMessage = checker.getError();
            if (reseedErrorMessage.length() > 0) {
                buf.append("<div class=\"sb_notice\"><i>").append(reseedErrorMessage).append("</i></div>");
            }
            // If showing the reseed link is allowed
            if (allowReseed()) {
                // While no reseed occurring, show reseed link
                long nonce = _context.random().nextLong();
                String prev = System.getProperty("net.i2p.router.web.ReseedHandler.nonce");
                if (prev != null) System.setProperty("net.i2p.router.web.ReseedHandler.noncePrev", prev);
                System.setProperty("net.i2p.router.web.ReseedHandler.nonce", nonce+"");
                String uri = getRequestURI();
                buf.append("<p><form action=\"").append(uri).append("\" method=\"POST\">\n");
                buf.append("<input type=\"hidden\" name=\"reseedNonce\" value=\"").append(nonce).append("\" >\n");
                buf.append("<button type=\"submit\" title=\"").append(_t("Attempt to download router reference files (if automatic reseed has failed)"));
                buf.append("\" class=\"reload\" value=\"Reseed\" >").append(_t("Reseed")).append("</button></form></p>\n");
            }
        }
        if (buf.length() <= 0)
            return "";
        return buf.toString();
    }

    private NewsHelper _newshelper;
    public void storeNewsHelper(NewsHelper n) { _newshelper = n; }
    public NewsHelper getNewsHelper() { return _newshelper; }

    private static final String SS = Character.toString(S);

    public List<String> getSummaryBarSections(String page) {
        String config;
        if ("home".equals(page)) {
            config = _context.getProperty(PROP_SUMMARYBAR + page, isAdvanced() ? DEFAULT_MINIMAL_ADVANCED : DEFAULT_MINIMAL);
        } else {
            config = _context.getProperty(PROP_SUMMARYBAR + page);
            if (config == null)
                config = _context.getProperty(PROP_SUMMARYBAR + "default", isAdvanced() ? DEFAULT_FULL_ADVANCED : DEFAULT_FULL);
        }
        if (config.length() <= 0)
            return Collections.emptyList();
        return Arrays.asList(DataHelper.split(config, SS));
    }

    static void saveSummaryBarSections(RouterContext ctx, String page, Map<Integer, String> sections) {
        StringBuilder buf = new StringBuilder(512);
        for(String section : sections.values())
            buf.append(section).append(S);
        ctx.router().saveConfig(PROP_SUMMARYBAR + page, buf.toString());
    }

    /** output the summary bar to _out */
    public void renderSummaryBar() throws IOException {
        SummaryBarRenderer renderer = new SummaryBarRenderer(_context, this);
        renderer.renderSummaryHTML(_out);
    }

    /* below here is stuff we need to get from summarynoframe.jsp to SummaryBarRenderer */

    private String _action;
    public void setAction(String s) { _action = s == null ? null : DataHelper.stripHTML(s); }
    public String getAction() { return _action; }

    private String _consoleNonce;
    public void setConsoleNonce(String s) { _consoleNonce = s == null ? null : DataHelper.stripHTML(s); }
    public String getConsoleNonce() { return _consoleNonce; }

    private String _updateNonce;
    public void setUpdateNonce(String s) { _updateNonce = s == null ? null : DataHelper.stripHTML(s); }
    public String getUpdateNonce() { return _updateNonce; }

    private String _requestURI;
    public void setRequestURI(String s) { _requestURI = s == null ? null : DataHelper.stripHTML(s); }

    /**
     * @return non-null; "/home" if (strangely) not set by jsp
     */
    public String getRequestURI() {
        return _requestURI != null ? _requestURI : "/home";
    }

    public String getConfigTable() {
        String[] allSections = SummaryBarRenderer.ALL_SECTIONS;
        Map<String, String> sectionNames = SummaryBarRenderer.SECTION_NAMES;
        List<String> sections = getSummaryBarSections("default");
        // translated section name to section id
        TreeMap<String, String> sortedSections = new TreeMap<String, String>(Collator.getInstance());

        // Forward-convert old section names
        int pos = sections.indexOf("General");
        if (pos >= 0) {
            sections.set(pos, "RouterInfo");
        }
        pos = sections.indexOf("ShortGeneral");
        if (pos >= 0) {
            sections.set(pos, "ShortRouterInfo");
        }

        for (int i = 0; i < allSections.length; i++) {
            String section = allSections[i];
            if (!sections.contains(section)) {
                String name = sectionNames.get(section);
                if (name != null)
                    sortedSections.put(_t(name), section);
            }
        }

        String theme = _context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        String imgPath = CSSHelper.BASE_THEME_PATH + theme + "/images/";

        StringBuilder buf = new StringBuilder(2048);
        buf.append("<table id=\"sidebarconf\"><tr><th title=\"Mark section for removal from the sidebar\">")
           .append(_t("Remove"))
           .append("</th><th>")
           .append(_t("Name"))
           .append("</th><th colspan=\"2\">")
           .append(_t("Order"))
           .append("</th></tr>\n");
        for (String section : sections) {
            int i = sections.indexOf(section);
            String name = sectionNames.get(section);
            if (name == null)
                continue;
            buf.append("<tr><td align=\"center\"><input type=\"checkbox\" class=\"optbox\" id=\"")
               .append(name)
               .append("\" name=\"delete_")
               .append(i)
               .append("\"></td><td align=\"left\"><label for=\"")
               .append(name)
               .append("\">")
               .append(_t(name))
               .append("</label></td><td align=\"right\"><input type=\"hidden\" name=\"order_")
               .append(i).append('_').append(section)
               .append("\" value=\"")
               .append(i)
               .append("\">");
            if (i > 0) {
                buf.append("<button type=\"submit\" class=\"buttonTop\" name=\"action\" value=\"move_")
                   .append(i)
                   .append("_top\"><img alt=\"")
                   .append(_t("Top"))
                   .append("\" src=\"")
                   .append(imgPath)
                   .append("move_top.png")
                   .append("\" title=\"")
                   .append(_t("Move to top"))
                   .append("\"/></button>");
                buf.append("<button type=\"submit\" class=\"buttonUp\" name=\"action\" value=\"move_")
                   .append(i)
                   .append("_up\"><img alt=\"")
                   .append(_t("Up"))
                   .append("\" src=\"")
                   .append(imgPath)
                   .append("move_up.png")
                   .append("\" title=\"")
                   .append(_t("Move up"))
                   .append("\"/></button>");
            }
            buf.append("</td><td align=\"left\">");
            if (i < sections.size() - 1) {
                buf.append("<button type=\"submit\" class=\"buttonDown\" name=\"action\" value=\"move_")
                   .append(i)
                   .append("_down\"><img alt=\"")
                   .append(_t("Down"))
                   .append("\" src=\"")
                   .append(imgPath)
                   .append("move_down.png")
                   .append("\" title=\"")
                   .append(_t("Move down"))
                   .append("\"/></button>");
                buf.append("<button type=\"submit\" class=\"buttonBottom\" name=\"action\" value=\"move_")
                   .append(i)
                   .append("_bottom\"><img alt=\"")
                   .append(_t("Bottom"))
                   .append("\" src=\"")
                   .append(imgPath)
                   .append("move_bottom.png")
                   .append("\" title=\"")
                   .append(_t("Move to bottom"))
                   .append("\"/></button>");
            }
            buf.append("</td></tr>\n");
        }
        buf.append("<tr><td align=\"center\">" +
                   "<input type=\"submit\" name=\"action\" class=\"delete\" value=\"")
           .append(_t("Delete selected"))
           .append("\"></td><td align=\"left\">")
           .append("<select name=\"name\">\n" +
                   "<option value=\"\" selected=\"selected\">")
           .append(_t("Select a section to add"))
           .append("</option>\n");

        for (Map.Entry<String, String> e : sortedSections.entrySet()) {
            String name = e.getKey();
            String s = e.getValue();
            buf.append("<option value=\"").append(s).append("\">")
               .append(name).append("</option>\n");
        }

        buf.append("</select>\n" +
                   "<input type=\"hidden\" name=\"order\" value=\"")
           .append(sections.size())
           .append("\"></td>" +
                   "<td align=\"center\" colspan=\"2\">" +
                   "<input type=\"submit\" name=\"action\" class=\"add\" value=\"")
           .append(_t("Add item"))
           .append("\"></td></tr>")
           .append("</table>\n");
        return buf.toString();
    }
}
