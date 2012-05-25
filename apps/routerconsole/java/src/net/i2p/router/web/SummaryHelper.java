package net.i2p.router.web;

import java.text.Collator;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterAddress;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.ntcp.NTCPAddress;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

/**
 * Simple helper to query the appropriate router for data necessary to render
 * the summary sections on the router console.  
 */
public class SummaryHelper extends HelperBase {
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
            return DataHelper.formatDuration(router.getUptime());
    }
    
    private String timeSkew() {
        if (_context == null) return "";
        //if (!_context.clock().getUpdatedSuccessfully())
        //    return " (Unknown skew)";
        long ms = _context.clock().getOffset();
        long diff = Math.abs(ms);
        if (diff < 3000)
            return "";
        return " (" + DataHelper.formatDuration(diff) + " skew)";
    }
    
    public boolean allowReseed() {
        return _context.netDb().isInitialized() &&
               ((_context.netDb().getKnownRouters() < 30) ||
                Boolean.valueOf(_context.getProperty("i2p.alwaysAllowReseed")).booleanValue());
    }
    
    /** subtract one for ourselves, so if we know no other peers it displays zero */
    public int getAllPeers() { return Math.max(_context.netDb().getKnownRouters() - 1, 0); }
    
    public String getReachability() {
        return reachability() + timeSkew();
    }

    private String reachability() {
        if (_context.router().getUptime() > 60*1000 && (!_context.router().gracefulShutdownInProgress()) &&
            !_context.clientManager().isAlive())
            return "ERR-Client Manager I2CP Error - check logs";  // not a router problem but the user should know
        if (!_context.clock().getUpdatedSuccessfully())
            return "ERR-ClockSkew";
        if (_context.router().isHidden())
            return "Hidden";

        int status = _context.commSystem().getReachabilityStatus();
        switch (status) {
            case CommSystemFacade.STATUS_OK:
                RouterAddress ra = _context.router().getRouterInfo().getTargetAddress("NTCP");
                if (ra == null || (new NTCPAddress(ra)).isPubliclyRoutable())
                    return "OK";
                return "ERR-Private TCP Address";
            case CommSystemFacade.STATUS_DIFFERENT:
                return "ERR-SymmetricNAT";
            case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
                if (_context.router().getRouterInfo().getTargetAddress("NTCP") != null)
                    return "WARN-Firewalled with Inbound TCP Enabled";
                if (((FloodfillNetworkDatabaseFacade)_context.netDb()).floodfillEnabled())
                    return "WARN-Firewalled and Floodfill";
                if (_context.router().getRouterInfo().getCapabilities().indexOf('O') >= 0)
                    return "WARN-Firewalled and Fast";
                return "Firewalled";
            case CommSystemFacade.STATUS_HOSED:
                return "ERR-UDP Port In Use - Set i2np.udp.internalPort=xxxx in advanced config and restart";
            case CommSystemFacade.STATUS_UNKNOWN: // fallthrough
            default:
                ra = _context.router().getRouterInfo().getTargetAddress("SSU");
                if (ra == null && _context.router().getUptime() > 5*60*1000) {
                    if (_context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_HOSTNAME) == null ||
                        _context.getProperty(ConfigNetHelper.PROP_I2NP_NTCP_PORT) == null)
                        return "ERR-UDP Disabled and Inbound TCP host/port not set";
                    else
                        return "WARN-Firewalled with UDP Disabled";
                }
                return "Testing";
        }
    }
    
    /**
     * Retrieve amount of used memory.
     *
     */
    public String getMemory() {
        DecimalFormat integerFormatter = new DecimalFormat("###,###,##0");
        long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/1024;
        long usedPc = 100 - ((Runtime.getRuntime().freeMemory() * 100) / Runtime.getRuntime().totalMemory());
        return integerFormatter.format(used) + "KB (" + usedPc + "%)"; 
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
        else
            return _context.profileOrganizer().countWellIntegratedPeers();
    }
    /**
     * How many peers the router ranks as failing.
     *
     */
    public int getFailingPeers() { 
        if (_context == null) 
            return 0;
        else
            return _context.profileOrganizer().countFailingPeers();
    }
    /**
     * How many peers totally suck.
     *
     */
    public int getShitlistedPeers() { 
        if (_context == null) 
            return 0;
        else
            return _context.shitlist().getRouterCount();
    }
 
    /**
     * How fast we have been receiving data over the last second (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getInboundSecondKBps() { 
        if (_context == null) 
            return "0";
        double kbps = _context.bandwidthLimiter().getReceiveBps()/1024d;
        DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(kbps);
    }
    /**
     * How fast we have been sending data over the last second (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getOutboundSecondKBps() { 
        if (_context == null) 
            return "0";
        double kbps = _context.bandwidthLimiter().getSendBps()/1024d;
        DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(kbps);
    }
    
    /**
     * How fast we have been receiving data over the last 5 minutes (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getInboundFiveMinuteKBps() {
        if (_context == null) 
            return "0";
        
        RateStat receiveRate = _context.statManager().getRate("bw.recvRate");
        if (receiveRate == null) return "0";
        Rate rate = receiveRate.getRate(5*60*1000);
        double kbps = rate.getAverageValue()/1024;
        DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(kbps);
    }
    
    /**
     * How fast we have been sending data over the last 5 minutes (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getOutboundFiveMinuteKBps() { 
        if (_context == null) 
            return "0";
        
        RateStat receiveRate = _context.statManager().getRate("bw.sendRate");
        if (receiveRate == null) return "0";
        Rate rate = receiveRate.getRate(5*60*1000);
        double kbps = rate.getAverageValue()/1024;
        DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(kbps);
    }
    
    /**
     * How fast we have been receiving data since the router started (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getInboundLifetimeKBps() { 
        if (_context == null) 
            return "0";
        
        RateStat receiveRate = _context.statManager().getRate("bw.recvRate");
        if (receiveRate == null) return "0";
        double kbps = receiveRate.getLifetimeAverageValue()/1024;
        DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(kbps);
    }
    
    /**
     * How fast we have been sending data since the router started (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getOutboundLifetimeKBps() { 
        if (_context == null) 
            return "0";
        
        RateStat sendRate = _context.statManager().getRate("bw.sendRate");
        if (sendRate == null) return "0";
        double kbps = sendRate.getLifetimeAverageValue()/1024;
        DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(kbps);
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

        return DataHelper.formatSize(received) + 'B';
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
        return DataHelper.formatSize(sent) + 'B';
    }
    
    /**
     * How many client destinations are connected locally.
     *
     * @return html section summary
     */
    public String getDestinations() {
        // covert the set to a list so we can sort by name and not lose duplicates
        List clients = new ArrayList(_context.clientManager().listClients());
        Collections.sort(clients, new AlphaComparator());
        
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"i2ptunnel/index.jsp\" target=\"_blank\" title=\"Add/remove/edit &amp; control your client and server tunnels (local destinations).\"  title=\"View existing tunnels and tunnel build status.\">Local destinations</a></h3><hr><table>");
        
        for (Iterator iter = clients.iterator(); iter.hasNext(); ) {
            Destination client = (Destination)iter.next();
            String name = getName(client);
            Hash h = client.calculateHash();
            
            buf.append("<tr><td align=\"right\"><b><img src=\"/themes/console/images/");
            if (_context.clientManager().shouldPublishLeaseSet(h))
                buf.append("server.png\" alt=\"Server\" title=\"Server\" />");
            else
                buf.append("client.png\" alt=\"Client\" title=\"Client\" />");
            buf.append("</td><td align=\"left\"><a href=\"tunnels.jsp#").append(h.toBase64().substring(0,4));
            buf.append("\" target=\"_top\" title=\"Show tunnels\">");
            if (name.length() < 16)
                buf.append(name);
            else
                buf.append(name.substring(0,15)).append("&hellip;");
            buf.append("</a></td>\n");
            LeaseSet ls = _context.netDb().lookupLeaseSetLocally(h);
            if (ls != null) {
                long timeToExpire = ls.getEarliestLeaseDate() - _context.clock().now();
                if (timeToExpire < 0) {
                    // red or yellow light                 
                    buf.append("<td align=\right\"><img src=\"/themes/console/images/local_inprogress.png\" alt=\"Rebuilding&hellip;\" title=\"Leases expired ").append(DataHelper.formatDuration(0-timeToExpire));
                    buf.append(" ago. Rebuilding..\"></td></tr>\n");                    
            } else {
                    // green light 
                    buf.append("<td align=\right\"><img src=\"/themes/console/images/local_up.png\" alt=\"Ready\" title=\"Ready\"></td></tr>\n");
                }
            } else {
                // yellow light
                    buf.append("<td align=\right\"><img src=\"/themes/console/images/local_inprogress.png\" alt=\"Building&hellip;\" title=\"Tunnel building in progress&hellip;\"></td></tr>\n");
            }
        }
        buf.append("</table><hr>\n");
        return buf.toString();
    }
    
    private class AlphaComparator implements Comparator {
        public int compare(Object lhs, Object rhs) {
            String lname = getName((Destination)lhs);
            String rname = getName((Destination)rhs);
            if (lname.equals("shared clients"))
                return -1;
            if (rname.equals("shared clients"))
                return 1;
            return Collator.getInstance().compare(lname, rname);
        }
    }

    private String getName(Destination d) {
        TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(d.calculateHash());
        String name = (in != null ? in.getDestinationNickname() : null);
        if (name == null) {
            TunnelPoolSettings out = _context.tunnelManager().getOutboundSettings(d.calculateHash());
            name = (out != null ? out.getDestinationNickname() : null);
            if (name == null)
                name = d.calculateHash().toBase64().substring(0,6);
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
 
    /**
     * How lagged our job queue is over the last minute (pretty printed with
     * the units attached)
     *
     */
    public String getJobLag() { 
        if (_context == null) 
            return "0ms";
        
        Rate lagRate = _context.statManager().getRate("jobQueue.jobLag").getRate(60*1000);
        return ((int)lagRate.getAverageValue()) + "ms";
    }
 
    /**
     * How long it takes us to pump out a message, averaged over the last minute 
     * (pretty printed with the units attached)
     *
     */   
    public String getMessageDelay() { 
        if (_context == null) 
            return "0ms";
        
        return _context.throttle().getMessageDelay() + "ms";
    }
    
    /**
     * How long it takes us to test our tunnels, averaged over the last 10 minutes
     * (pretty printed with the units attached)
     *
     */
    public String getTunnelLag() { 
        if (_context == null) 
            return "0ms";
        
        return _context.throttle().getTunnelLag() + "ms";
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

    public boolean updateAvailable() { 
        return NewsFetcher.getInstance(_context).updateAvailable();
    }

    public boolean unsignedUpdateAvailable() { 
        return NewsFetcher.getInstance(_context).unsignedUpdateAvailable();
    }

    public String getUpdateVersion() { 
        return NewsFetcher.getInstance(_context).updateVersion();
    }

    public String getUnsignedUpdateVersion() { 
        return NewsFetcher.getInstance(_context).unsignedUpdateVersion();
    }
}
