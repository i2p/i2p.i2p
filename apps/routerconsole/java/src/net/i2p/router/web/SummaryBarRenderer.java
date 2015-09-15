package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import net.i2p.app.ClientAppManager;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.news.NewsEntry;
import net.i2p.router.news.NewsManager;
import net.i2p.util.PortMapper;

/**
 *  Refactored from summarynoframe.jsp to save ~100KB
 *
 */
public class SummaryBarRenderer {

    static final String ALL_SECTIONS[] =
        {"HelpAndFAQ", "I2PServices", "I2PInternals", "General", "ShortGeneral", "NetworkReachability",
        "UpdateStatus", "RestartStatus", "Peers", "FirewallAndReseedStatus", "Bandwidth", "Tunnels",
        "Congestion", "TunnelStatus", "Destinations", "NewsHeadings" };
    static final Map<String, String> SECTION_NAMES;

    static {
        Map<String, String> aMap = new HashMap<String, String>();;
        aMap.put("HelpAndFAQ", "Help &amp; FAQ");
        aMap.put("I2PServices", "I2P Services");
        aMap.put("I2PInternals", "I2P Internals");
        aMap.put("General", "General");
        aMap.put("ShortGeneral", "Short General");
        aMap.put("NetworkReachability", "Network Reachability");
        aMap.put("UpdateStatus", "Update Status");
        aMap.put("RestartStatus", "Restart Status");
        aMap.put("Peers", "Peers");
        aMap.put("FirewallAndReseedStatus", "Firewall &amp; Reseed Status");
        aMap.put("Bandwidth", "Bandwidth");
        aMap.put("Tunnels", "Tunnels");
        aMap.put("Congestion", "Congestion");
        aMap.put("TunnelStatus", "Tunnel Status");
        aMap.put("Destinations", "Local Tunnels");
        aMap.put("NewsHeadings", "News &amp; Updates");
        SECTION_NAMES = Collections.unmodifiableMap(aMap);
    }

    private final RouterContext _context;
    private final SummaryHelper _helper;

    public SummaryBarRenderer(RouterContext context, SummaryHelper helper) {
        _context = context;
        _helper = helper;
    }

    /**
     *  Note - ensure all links in here are absolute, as the summary bar may be displayed
     *         on lower-level directory errors.
     */
    public void renderSummaryHTML(Writer out) throws IOException {
        String requestURI = _helper.getRequestURI();
        String page = requestURI.replace("/", "").replace(".jsp", "");
        List<String> sections = _helper.getSummaryBarSections(page);
        StringBuilder buf = new StringBuilder(8*1024);
        for (String section : sections) {
            // Commented out because broken. Replaced by if-elseif blob below.
            /*try {
                String section = (String)ALL_SECTIONS.get(sections[i]).invoke(this);
                if (section != null && section != "") {
                    out.write("<hr>" + i + "<hr>\n" + section);
                }
            } catch (Exception e) {
                out.write("<hr>" +i + " - Exception<hr>\n" + e);
            }*/
            buf.setLength(0);

            buf.append("<hr>\n");
            if ("HelpAndFAQ".equals(section))
                buf.append(renderHelpAndFAQHTML());
            else if ("I2PServices".equals(section))
                buf.append(renderI2PServicesHTML());
            else if ("I2PInternals".equals(section))
                buf.append(renderI2PInternalsHTML());
            else if ("General".equals(section))
                buf.append(renderGeneralHTML());
            else if ("ShortGeneral".equals(section))
                buf.append(renderShortGeneralHTML());
            else if ("NetworkReachability".equals(section))
                buf.append(renderNetworkReachabilityHTML());
            else if ("UpdateStatus".equals(section))
                buf.append(renderUpdateStatusHTML());
            else if ("RestartStatus".equals(section))
                buf.append(renderRestartStatusHTML());
            else if ("Peers".equals(section))
                buf.append(renderPeersHTML());
            else if ("FirewallAndReseedStatus".equals(section))
                buf.append(renderFirewallAndReseedStatusHTML());
            else if ("Bandwidth".equals(section))
                buf.append(renderBandwidthHTML());
            else if ("Tunnels".equals(section))
                buf.append(renderTunnelsHTML());
            else if ("Congestion".equals(section))
                buf.append(renderCongestionHTML());
            else if ("TunnelStatus".equals(section))
                buf.append(renderTunnelStatusHTML());
            else if ("Destinations".equals(section))
                buf.append(renderDestinationsHTML());
            else if ("NewsHeadings".equals(section))
                buf.append(renderNewsHeadingsHTML());

            // Only output section if there's more than the <hr> to print
            if (buf.length() > 5)
                out.write(buf.toString());
        }
    }

    public String renderHelpAndFAQHTML() {
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/help\" target=\"_top\" title=\"")
           .append(_("I2P Router Help &amp; FAQ"))
           .append("\">")
           .append(_("Help &amp; FAQ"))
           .append("</a></h3>");
        return buf.toString();
    }

    public String renderI2PServicesHTML() {
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/configclients\" target=\"_top\" title=\"")
           .append(_("Configure startup of clients and webapps (services); manually start dormant services"))
           .append("\">")
           .append(_("I2P Services"))
           .append("</a></h3>\n" +

                   "<hr class=\"b\"><table><tr><td>" +

                   "<a href=\"/susimail/susimail\" target=\"_blank\" title=\"")
           .append(_("Anonymous webmail client"))
           .append("\">")
           .append(nbsp(_("Email")))
           .append("</a>\n" +

                   "<a href=\"/i2psnark/\" target=\"_blank\" title=\"")
           .append(_("Built-in anonymous BitTorrent Client"))
           .append("\">")
           .append(nbsp(_("Torrents")))
           .append("</a>\n" +

                   "<a href=\"http://")
           .append(_context.portMapper().getHost(PortMapper.SVC_EEPSITE, "127.0.0.1"))
           .append(':')
           .append(_context.portMapper().getPort(PortMapper.SVC_EEPSITE, 7658))
           .append("/\" target=\"_blank\" title=\"")
           .append(_("Local web server"))
           .append("\">")
           .append(nbsp(_("Website")))
           .append("</a>\n")

           .append(NavHelper.getClientAppLinks(_context))

           .append("</td></tr></table>\n");
        return buf.toString();
    }

    public String renderI2PInternalsHTML() {
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/config\" target=\"_top\" title=\"")
           .append(_("Configure I2P Router"))
           .append("\">")
           .append(_("I2P Internals"))
           .append("</a></h3><hr class=\"b\">\n" +

                   "<table><tr><td>\n" +

                   "<a href=\"/tunnels\" target=\"_top\" title=\"")
           .append(_("View existing tunnels and tunnel build status"))
           .append("\">")
           .append(nbsp(_("Tunnels")))
           .append("</a>\n" +

                   "<a href=\"/peers\" target=\"_top\" title=\"")
           .append(_("Show all current peer connections"))
           .append("\">")
           .append(nbsp(_("Peers")))
           .append("</a>\n" +

                   "<a href=\"/profiles\" target=\"_top\" title=\"")
           .append(_("Show recent peer performance profiles"))
           .append("\">")
           .append(nbsp(_("Profiles")))
           .append("</a>\n" +

                   "<a href=\"/netdb\" target=\"_top\" title=\"")
           .append(_("Show list of all known I2P routers"))
           .append("\">")
           .append(nbsp(_("NetDB")))
           .append("</a>\n" +

                   "<a href=\"/logs\" target=\"_top\" title=\"")
           .append(_("Health Report"))
           .append("\">")
           .append(nbsp(_("Logs")))
           .append("</a>\n");

       //          "<a href=\"/jobs.jsp\" target=\"_top\" title=\"")
       //  .append(_("Show the router's workload, and how it's performing"))
       //  .append("\">")
       //  .append(_("Jobs"))
       //  .append("</a>\n" +

        if (!StatSummarizer.isDisabled()) {
            buf.append("<a href=\"/graphs\" target=\"_top\" title=\"")
               .append(_("Graph router performance"))
               .append("\">")
               .append(nbsp(_("Graphs")))
               .append("</a>\n");
        }

        buf.append("<a href=\"/stats\" target=\"_top\" title=\"")
           .append(_("Textual router performance statistics"))
           .append("\">")
           .append(nbsp(_("Stats")))
           .append("</a>\n" +

                   "<a href=\"/dns\" target=\"_top\" title=\"")
           .append(_("Manage your I2P hosts file here (I2P domain name resolution)"))
           .append("\">")
           .append(nbsp(_("Addressbook")))
           .append("</a>\n" +

                    "<a href=\"/i2ptunnelmgr\" target=\"_top\" title=\"")
           .append(_("Local Tunnels"))
           .append("\">")
           .append(nbsp(_("Hidden Services Manager")))
           .append("</a>\n");

        if (_context.getBooleanProperty(HelperBase.PROP_ADVANCED))
            buf.append("<a href=\"/debug\">Debug</a>\n");
        File javadoc = new File(_context.getBaseDir(), "docs/javadoc/index.html");
        if (javadoc.exists())
            buf.append("<a href=\"/javadoc/index.html\" target=\"_blank\">Javadoc</a>\n");
        buf.append("</td></tr></table>\n");
        return buf.toString();
    }

    public String renderGeneralHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/help\" target=\"_top\" title=\"")
           .append(_("I2P Router Help"))
           .append("\">")
           .append(_("General"))
           .append("</a></h3><hr class=\"b\">\n" +

                   "<table><tr>" +
                   "<td align=\"left\"><b title=\"")
           .append(_("Your Local Identity is your unique I2P router identity, similar to an ip address but tailored to I2P. "))
           .append(_("Never disclose this to anyone, as it can reveal your real world ip."))
           .append("\">")
           .append(_("Local Identity"))
           .append(":</b></td>" +
                   "<td align=\"right\">" +
                   "<a title=\"")
           .append(_("Your unique I2P router identity is"))
           .append(' ')
           .append(_helper.getIdent())
           .append(", ")
           .append(_("never reveal it to anyone"))
           .append("\" href=\"/netdb?r=.\" target=\"_top\">")
           .append(_("show"))
           .append("</a></td></tr>\n" +

                   "<tr title=\"")
           .append(_("The version of the I2P software we are running"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Version"))
           .append(":</b></td>" +
                   "<td align=\"right\">")
           .append(_helper.getVersion())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_("How long we've been running for this session"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Uptime"))
           .append(":</b></td>" +
                   "<td align=\"right\">")
           .append(_helper.getUptime())
           .append("</td></tr></table>\n");
        return buf.toString();
    }

    public String renderShortGeneralHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<table>" +
                   "<tr title=\"")
           .append(_("The version of the I2P software we are running"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Version"))
           .append(":</b></td>" +
                   "<td align=\"right\">")
           .append(_helper.getVersion())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_("How long we've been running for this session"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Uptime"))
           .append(":</b></td>" +
                   "<td align=\"right\">")
           .append(_helper.getUptime())
           .append("</td></tr></table>\n");
        return buf.toString();
    }

    public String renderNetworkReachabilityHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h4><a href=\"/confignet#help\" target=\"_top\" title=\"")
           .append(_("Help with configuring your firewall and router for optimal I2P performance"))
           .append("\">")
           .append(_("Network"))
           .append(": ")
           .append(_helper.getReachability())
           .append("</a></h4>\n");
        if (!SigType.ECDSA_SHA256_P256.isAvailable()) {
            buf.append("<hr>\n<h4><a href=\"http://trac.i2p2.i2p/wiki/Crypto/ECDSA");
            if ("ru".equals(Messages.getLanguage(_context)))
                buf.append("-ru");
            buf.append("\" target=\"_top\" title=\"")
               .append(_("See more information on the wiki"))
               .append("\">")
               .append(_("Warning: ECDSA is not available. Update your Java or OS"))
               .append("</a></h4>\n");
        }
        return buf.toString();
    }

    public String renderUpdateStatusHTML() {
        if (_helper == null) return "";
        String updateStatus = _helper.getUpdateStatus();
        if ("".equals(updateStatus)) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/configupdate\" target=\"_top\" title=\"")
           .append(_("Configure I2P Updates"))
           .append("\">")
           .append(_("I2P Update"))
           .append("</a></h3><hr class=\"b\">\n");
        buf.append(updateStatus);
        return buf.toString();
    }

    public String renderRestartStatusHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append(_helper.getRestartStatus());
        return buf.toString();
    }

    public String renderPeersHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/peers\" target=\"_top\" title=\"")
           .append(_("Show all current peer connections"))
           .append("\">")
           .append(_("Peers"))
           .append("</a></h3><hr class=\"b\">\n" +

                   "<table>\n" +

                   "<tr title=\"")
           .append(_("Peers we've been talking to in the last few minutes/last hour"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Active"))
           .append(":</b></td><td align=\"right\">");
        int active = _helper.getActivePeers();
        buf.append(active)
           .append(SummaryHelper.THINSP)
           .append(Math.max(active, _helper.getActiveProfiles()))
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_("The number of peers available for building client tunnels"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Fast"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getFastPeers())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_("The number of peers available for building exploratory tunnels"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("High capacity"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getHighCapacityPeers())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_("The number of peers available for network database inquiries"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Integrated"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getWellIntegratedPeers())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_("The total number of peers in our network database"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Known"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getAllPeers())
           .append("</td></tr>\n" +

                   "</table>\n");
        return buf.toString();
    }

    public String renderFirewallAndReseedStatusHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append(_helper.getFirewallAndReseedStatus());
        return buf.toString();
    }

    public String renderBandwidthHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/config\" title=\"")
           .append(_("Configure router bandwidth allocation"))
           .append("\" target=\"_top\">")
           .append(_("Bandwidth in/out"))
           .append("</a></h3><hr class=\"b\">" +
                   "<table>\n" +

                   "<tr><td align=\"left\"><b>")
           .append(DataHelper.formatDuration2(3 * 1000))   // lie and say 3 sec since 1 sec would appear as 1000 ms
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getSecondKBps())
           .append("Bps</td></tr>\n");

        if (_context.router().getUptime() > 6*60*1000) {
            buf.append("<tr><td align=\"left\"><b>")
           .append(DataHelper.formatDuration2(5 * 60 * 1000))   // 5 min
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getFiveMinuteKBps())
           .append("Bps</td></tr>\n");
        }

        if (_context.router().getUptime() > 2*60*1000) {
            buf.append("<tr><td align=\"left\"><b>")
           .append(_("Total"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getLifetimeKBps())
           .append("Bps</td></tr>\n");
        }

        buf.append("<tr><td align=\"left\"><b>")
           .append(_("Used"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getInboundTransferred())
           .append(SummaryHelper.THINSP)
           .append(_helper.getOutboundTransferred())
           .append("</td></tr>\n" +

                   "</table>\n");
        return buf.toString();
    }

    public String renderTunnelsHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/tunnels\" target=\"_top\" title=\"")
           .append(_("View existing tunnels and tunnel build status"))
           .append("\">")
           .append(_("Tunnels"))
           .append("</a></h3><hr class=\"b\">" +
                   "<table>\n" +

                   "<tr title=\"")
           .append(_("Used for building and testing tunnels, and communicating with floodfill peers"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Exploratory"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getInboundTunnels() + _helper.getOutboundTunnels())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_("Tunnels we are using to provide or access services on the network"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Client"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getInboundClientTunnels() + _helper.getOutboundClientTunnels())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_("Tunnels we are participating in, directly contributing bandwidth to the network"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Participating"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getParticipatingTunnels())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_("The ratio of tunnel hops we provide to tunnel hops we use - a value greater than 1.00 indicates a positive contribution to the network"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Share ratio"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getShareRatio())
           .append("</td></tr>\n" +

                   "</table>\n");
        return buf.toString();
    }

    public String renderCongestionHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/jobs\" target=\"_top\" title=\"")
           .append(_("What's in the router's job queue?"))
           .append("\">")
           .append(_("Congestion"))
           .append("</a></h3><hr class=\"b\">" +
                   "<table>\n" +

                   "<tr title=\"")
           .append(_("Indicates router performance"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Job lag"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getJobLag())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_("Indicates how quickly outbound messages to other I2P routers are sent"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Message delay"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getMessageDelay())
           .append("</td></tr>\n");

        if (!_context.getBooleanPropertyDefaultTrue("router.disableTunnelTesting")) {
            buf.append("<tr title=\"")
           .append(_("Round trip time for a tunnel test"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Tunnel lag"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getTunnelLag())
           .append("</td></tr>\n");
        }

        buf.append("<tr title=\"")
           .append(_("Queued requests from other routers to participate in tunnels"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_("Backlog"))
           .append(":</b></td><td align=\"right\">")
           .append(_helper.getInboundBacklog())
           .append("</td></tr>\n" +

                   "</table>\n");
        return buf.toString();
    }

    public String renderTunnelStatusHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(50);
        buf.append("<h4>")
           .append(_(_helper.getTunnelStatus()))
           .append("</h4>\n");
        return buf.toString();
    }

    public String renderDestinationsHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append(_helper.getDestinations());
        return buf.toString();
    }

    /** @since 0.9.1 */
    public String renderNewsHeadingsHTML() {
        if (_helper == null) return "";
        NewsHelper newshelper = _helper.getNewsHelper();
        if (newshelper == null || newshelper.shouldShowNews()) return "";
        StringBuilder buf = new StringBuilder(512);
        String consoleNonce = CSSHelper.getNonce();
        if (consoleNonce != null) {
            // Set up title and pre-headings stuff.
            //buf.append("<h3><a href=\"/configupdate\">")
            buf.append("<h3><a href=\"/news\">")
               .append(_("News &amp; Updates"))
               .append("</a></h3><hr class=\"b\"><div class=\"newsheadings\">\n");
            // Get news content.
            List<NewsEntry> entries = Collections.emptyList();
            ClientAppManager cmgr = _context.clientAppManager();
            if (cmgr != null) {
                NewsManager nmgr = (NewsManager) cmgr.getRegisteredApp(NewsManager.APP_NAME);
                if (nmgr != null)
                    entries = nmgr.getEntries();
            }
            if (!entries.isEmpty()) {
                buf.append("<ul>\n");
                DateFormat fmt = DateFormat.getDateInstance(DateFormat.SHORT);
                // the router sets the JVM time zone to UTC but saves the original here so we can get it
                String systemTimeZone = _context.getProperty("i2p.systemTimeZone");
                if (systemTimeZone != null)
                    fmt.setTimeZone(TimeZone.getTimeZone(systemTimeZone));
                int i = 0;
                final int max = 2;
                for (NewsEntry entry : entries) {
                    buf.append("<li><a href=\"/?news=1&amp;consoleNonce=")
                       .append(consoleNonce)
                       .append("\">");
                    if (entry.updated > 0) {
                        Date date = new Date(entry.updated);
                        buf.append(fmt.format(date))
                           .append(": ");
                    }
                    buf.append(entry.title)
                       .append("</a></li>\n");
                    if (++i >= max)
                        break;
                }
                buf.append("</ul>\n");
                //buf.append("<a href=\"/news\">")
                //   .append(_("Show all news"))
                //   .append("</a>\n");
            } else {
                buf.append("<center><i>")
                   .append(_("none"))
                   .append("</i></center>");
            }
            // Add post-headings stuff.
            buf.append("</div>\n");
        }
        return buf.toString();
    }

    /** translate a string */
    private String _(String s) {
        return Messages.getString(s, _context);
    }

    /**
     *  Where the translation is to two words or more,
     *  prevent splitting across lines
     *
     *  @since 0.9.18
     */
    private static String nbsp(String s) {
        // if it's too long, this makes it worse
        if (s.length() <= 30)
            return s.replace(" ", "&nbsp;");
        else
            return s;
    }
}
