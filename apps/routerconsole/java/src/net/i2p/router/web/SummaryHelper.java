package net.i2p.router.web;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.LeaseSet;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.TunnelPoolSettings;

/**
 * Simple helper to query the appropriate router for data necessary to render
 * the summary sections on the router console.  
 */
public class SummaryHelper {
    private RouterContext _context;
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
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
        return RouterVersion.VERSION + "-" + RouterVersion.BUILD;
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
    
    private static final DateFormat _fmt = new java.text.SimpleDateFormat("HH:mm:ss", Locale.UK);
    public String getTime() {
        if (_context == null) return "";
        
        String now = null;
        synchronized (_fmt) {
            now = _fmt.format(new Date(_context.clock().now()));
        }
        
        long ms = _context.clock().getOffset();
        
        if (ms < 60 * 1000) {
            return now + " (" + (ms / 1000) + "s)";
        } else if (ms < 60 * 60 * 1000) {
            return now + " (" + (ms / (60 * 1000)) + "m)";
        } else if (ms < 24 * 60 * 60 * 1000) {
            return now + " (" + (ms / (60 * 60 * 1000)) + "h)";
        } else {
            return now + " (" + (ms / (24 * 60 * 60 * 1000)) + "d)";
        }
    }
    
    public boolean allowReseed() {
        return (_context.netDb().getKnownRouters() < 10);
    }
    
    public int getAllPeers() { return _context.netDb().getKnownRouters(); }
    
    public String getReachability() {
        int status = _context.commSystem().getReachabilityStatus();
        switch (status) {
            case CommSystemFacade.STATUS_OK:
                return "OK";
            case CommSystemFacade.STATUS_DIFFERENT:
                return "ERR-SymmetricNAT";
            case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
                return "ERR-Reject";
            case CommSystemFacade.STATUS_UNKNOWN: // fallthrough
            default:
                return "Unknown";
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
     * How fast we have been receiving data over the last minute (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getInboundMinuteKBps() { 
        if (_context == null) 
            return "0.0";
        
        RateStat receiveRate = _context.statManager().getRate("transport.receiveMessageSize");
        if (receiveRate == null) return "0.0";
        Rate rate = receiveRate.getRate(60*1000);
        double bytes = rate.getLastTotalValue();
        double bps = (bytes*1000.0d)/(rate.getPeriod()*1024.0d); 

	DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(bps);
    }
    /**
     * How fast we have been sending data over the last minute (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getOutboundMinuteKBps() { 
        if (_context == null) 
            return "0.0";
        
        RateStat receiveRate = _context.statManager().getRate("transport.sendMessageSize");
        if (receiveRate == null) return "0.0";
        Rate rate = receiveRate.getRate(60*1000);
        double bytes = rate.getLastTotalValue();
        double bps = (bytes*1000.0d)/(rate.getPeriod()*1024.0d); 

	DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(bps);
    }
    
    /**
     * How fast we have been receiving data over the last 5 minutes (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getInboundFiveMinuteKBps() {
        if (_context == null) 
            return "0.0";
        
        RateStat receiveRate = _context.statManager().getRate("transport.receiveMessageSize");
        if (receiveRate == null) return "0.0";
        Rate rate = receiveRate.getRate(5*60*1000);
        double bytes = rate.getLastTotalValue();
        double bps = (bytes*1000.0d)/(rate.getPeriod()*1024.0d); 

	DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(bps);
    }
    
    /**
     * How fast we have been sending data over the last 5 minutes (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getOutboundFiveMinuteKBps() { 
        if (_context == null) 
            return "0.0";
        
        RateStat receiveRate = _context.statManager().getRate("transport.sendMessageSize");
        if (receiveRate == null) return "0.0";
        Rate rate = receiveRate.getRate(5*60*1000);
        double bytes = rate.getLastTotalValue();
        double bps = (bytes*1000.0d)/(rate.getPeriod()*1024.0d); 

	DecimalFormat fmt = new DecimalFormat("##0.00");
        return fmt.format(bps);
    }
    
    /**
     * How fast we have been receiving data since the router started (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getInboundLifetimeKBps() { 
        if (_context == null) 
            return "0.0";
        
        long received = _context.bandwidthLimiter().getTotalAllocatedInboundBytes();

        DecimalFormat fmt = new DecimalFormat("##0.00");

        // we use the unadjusted time, since thats what getWhenStarted is based off
        long lifetime = _context.clock().now()-_context.clock().getOffset()
                        - _context.router().getWhenStarted();
        lifetime /= 1000;
        if (received > 0) {
            double receivedKBps = received / (lifetime*1024.0);
            return fmt.format(receivedKBps);
        } else {
            return "0.0";
        }
    }
    
    /**
     * How fast we have been sending data since the router started (pretty printed
     * string with 2 decimal places representing the KBps)
     *
     */
    public String getOutboundLifetimeKBps() { 
        if (_context == null) 
            return "0.0";
        
        long sent = _context.bandwidthLimiter().getTotalAllocatedOutboundBytes();

        DecimalFormat fmt = new DecimalFormat("##0.00");

        // we use the unadjusted time, since thats what getWhenStarted is based off
        long lifetime = _context.clock().now()-_context.clock().getOffset() 
                        - _context.router().getWhenStarted();
        lifetime /= 1000;
        if (sent > 0) {
            double sendKBps = sent / (lifetime*1024.0);
            return fmt.format(sendKBps);
        } else {
            return "0.0";
        }
    }
    
    /**
     * How much data have we received since the router started (pretty printed
     * string with 2 decimal places and the appropriate units - GB/MB/KB/bytes)
     *
     */
    public String getInboundTransferred() { 
        if (_context == null) 
            return "0.0";
        
        long received = _context.bandwidthLimiter().getTotalAllocatedInboundBytes();

        return getTransferred(received);
    }
    
    /**
     * How much data have we sent since the router started (pretty printed
     * string with 2 decimal places and the appropriate units - GB/MB/KB/bytes)
     *
     */
    public String getOutboundTransferred() { 
        if (_context == null) 
            return "0.0";
        
        long sent = _context.bandwidthLimiter().getTotalAllocatedOutboundBytes();
        return getTransferred(sent);
    }
    
    private static String getTransferred(long bytes) {
        double val = bytes;
        int scale = 0;
        if (bytes > 1024*1024*1024) {
            // gigs transferred
            scale = 3; 
            val /= (double)(1024*1024*1024);
        } else if (bytes > 1024*1024) {
            // megs transferred
            scale = 2;
            val /= (double)(1024*1024);
        } else if (bytes > 1024) {
            // kbytes transferred
            scale = 1;
            val /= (double)1024;
        } else {
            scale = 0;
        }
        
        DecimalFormat fmt = new DecimalFormat("##0.00");

        String str = fmt.format(val);
        switch (scale) {
            case 1: return str + "KB";
            case 2: return str + "MB";
            case 3: return str + "GB";
            default: return bytes + "bytes";
        }
    }
    
    /**
     * How many client destinations are connected locally.
     *
     * @return html section summary
     */
    public String getDestinations() {
        Set clients = _context.clientManager().listClients();
        
        StringBuffer buf = new StringBuffer(512);
        buf.append("<u><b>Local destinations</b></u><br />");
        
        for (Iterator iter = clients.iterator(); iter.hasNext(); ) {
            Destination client = (Destination)iter.next();
            TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(client.calculateHash());
            TunnelPoolSettings out = _context.tunnelManager().getOutboundSettings(client.calculateHash());
            String name = (in != null ? in.getDestinationNickname() : null);
            if (name == null)
                name = (out != null ? out.getDestinationNickname() : null);
            if (name == null)
                name = client.calculateHash().toBase64().substring(0,6);
            
            buf.append("<b>*</b> ").append(name).append("<br />\n");
            LeaseSet ls = _context.netDb().lookupLeaseSetLocally(client.calculateHash());
            if (ls != null) {
                long timeToExpire = ls.getEarliestLeaseDate() - _context.clock().now();
                if (timeToExpire < 0) {
                    buf.append("<i>expired ").append(DataHelper.formatDuration(0-timeToExpire));
                    buf.append(" ago</i><br />\n");
                }
            } else {
                buf.append("<i>No leases</i><br />\n");
            }
            buf.append("<a href=\"tunnels.jsp#").append(client.calculateHash().toBase64().substring(0,4));
            buf.append("\">Details</a> ");
            buf.append("<a href=\"configtunnels.jsp#").append(client.calculateHash().toBase64().substring(0,4));
            buf.append("\">Config</a><br />\n");
        }
        buf.append("<hr />\n");
        return buf.toString();
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
    
    public boolean updateAvailable() { 
        return NewsFetcher.getInstance(_context).updateAvailable();
    }
}