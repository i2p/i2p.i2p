package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;

import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;

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
        return RouterVersion.VERSION;
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
    
    /**
     * How many active peers the router has.
     *
     */
    public int getActivePeers() { 
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
        int scale = 0;
        if (bytes > 1024*1024*1024) {
            // gigs transferred
            scale = 3; 
            bytes /= (1024*1024*1024);
        } else if (bytes > 1024*1024) {
            // megs transferred
            scale = 2;
            bytes /= (1024*1024);
        } else if (bytes > 1024) {
            // kbytes transferred
            scale = 1;
            bytes /= 1024;
        } else {
            scale = 0;
        }
        
        DecimalFormat fmt = new DecimalFormat("##0.00");

        String str = fmt.format(bytes);
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        try {
            _context.clientManager().renderStatusHTML(baos);
            return new String(baos.toByteArray());
        } catch (IOException ioe) {
            _context.logManager().getLog(SummaryHelper.class).error("Error rendering client info", ioe);
            return "";
        }
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
        
        Rate delayRate = _context.statManager().getRate("transport.sendProcessingTime").getRate(60*1000);
        return ((int)delayRate.getAverageValue()) + "ms";
    }
    
    /**
     * How long it takes us to test our tunnels, averaged over the last 10 minutes
     * (pretty printed with the units attached)
     *
     */
    public String getTunnelLag() { 
        if (_context == null) 
            return "0ms";
        
        Rate lagRate = _context.statManager().getRate("tunnel.testSuccessTime").getRate(10*60*1000);
        return ((int)lagRate.getAverageValue()) + "ms";
    }
}