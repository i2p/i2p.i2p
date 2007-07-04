package net.i2p.router.web;

import net.i2p.time.Timestamper;
import net.i2p.router.RouterContext;
import net.i2p.router.CommSystemFacade;
import net.i2p.data.RouterAddress;
import net.i2p.router.LoadTestManager;
import net.i2p.router.transport.udp.UDPAddress;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.Router;

public class ConfigNetHelper {
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

    public ConfigNetHelper() {}
    
    /** copied from various private TCP components */
    public final static String PROP_I2NP_TCP_HOSTNAME = "i2np.tcp.hostname";
    public final static String PROP_I2NP_TCP_PORT = "i2np.tcp.port";
    public final static String PROP_I2NP_UDP_PORT = "i2np.udp.port";
    public final static String PROP_I2NP_INTERNAL_UDP_PORT = "i2np.udp.internalPort";
    
    public String getHostname() {
        return _context.getProperty(PROP_I2NP_TCP_HOSTNAME);
    }
    public String getTcpPort() {
        int port = 8887;
        String val = _context.getProperty(PROP_I2NP_TCP_PORT);
        if (val != null) {
            try {
                port = Integer.parseInt(val);
            } catch (NumberFormatException nfe) {
                // ignore, use default from above
            }
        }
        return "" + port;
    }
    public final static String PROP_I2NP_NTCP_HOSTNAME = "i2np.ntcp.hostname";
    public final static String PROP_I2NP_NTCP_PORT = "i2np.ntcp.port";
    public String getNtcphostname() {
        String hostname = _context.getProperty(PROP_I2NP_NTCP_HOSTNAME); 
        if (hostname == null) return "";
        return hostname;
    }
    public String getNtcpport() { 
        String port = _context.getProperty(PROP_I2NP_NTCP_PORT); 
        if (port == null) return "";
        return port;
    }
    
    public String getUdpAddress() {
        RouterAddress addr = _context.router().getRouterInfo().getTargetAddress("SSU");
        if (addr == null)
            return "unknown";
        UDPAddress ua = new UDPAddress(addr);
        return ua.toString();
    }
    
    public String getEnableTimeSyncChecked() {
        String disabled = _context.getProperty(Timestamper.PROP_DISABLED, "false");
        if ( (disabled != null) && ("true".equalsIgnoreCase(disabled)) )
            return "";
        else
            return " checked ";
    }
    
    public String getHiddenModeChecked() {
        String enabled = _context.getProperty(Router.PROP_HIDDEN, "false");
        if ( (enabled != null) && ("true".equalsIgnoreCase(enabled)) )
            return " checked ";
        else
            return "";
    }

    public String getDynamicKeysChecked() {
        String enabled = _context.getProperty(Router.PROP_DYNAMIC_KEYS, "false");
        if ( (enabled != null) && ("true".equalsIgnoreCase(enabled)) )
            return " checked ";
        else
            return "";
    }

    public String getRequireIntroductionsChecked() {
        short status = _context.commSystem().getReachabilityStatus();
        switch (status) {
            case CommSystemFacade.STATUS_OK:
                if ("true".equalsIgnoreCase(_context.getProperty(UDPTransport.PROP_FORCE_INTRODUCERS, "false")))
                    return "checked=\"true\"";
                return "";
            case CommSystemFacade.STATUS_DIFFERENT:
            case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
                return "checked=\"true\"";
            case CommSystemFacade.STATUS_UNKNOWN:
                if ("true".equalsIgnoreCase(_context.getProperty(UDPTransport.PROP_FORCE_INTRODUCERS, "false")))
                    return "checked=\"true\"";
                return "";
            default:
                return "checked=\"true\"";
        }
    }
    
    public static final String PROP_INBOUND_KBPS = "i2np.bandwidth.inboundKBytesPerSecond";
    public static final String PROP_OUTBOUND_KBPS = "i2np.bandwidth.outboundKBytesPerSecond";
    public static final String PROP_INBOUND_BURST_KBPS = "i2np.bandwidth.inboundBurstKBytesPerSecond";
    public static final String PROP_OUTBOUND_BURST_KBPS = "i2np.bandwidth.outboundBurstKBytesPerSecond";
    public static final String PROP_INBOUND_BURST = "i2np.bandwidth.inboundBurstKBytes";
    public static final String PROP_OUTBOUND_BURST = "i2np.bandwidth.outboundBurstKBytes";
    public static final String PROP_SHARE_PERCENTAGE = "router.sharePercentage";
    public static final int DEFAULT_SHARE_PERCENTAGE = 80;

    public String getInboundRate() {
        String rate = _context.getProperty(PROP_INBOUND_KBPS);
        if (rate != null)
            return rate;
        else
            return "16";
    }
    public String getOutboundRate() {
        String rate = _context.getProperty(PROP_OUTBOUND_KBPS);
        if (rate != null)
            return rate;
        else
            return "16";
    }
    public String getInboundBurstRate() {
        String rate = _context.getProperty(PROP_INBOUND_BURST_KBPS);
        if (rate != null)
            return rate;
        else
            return "32";
    }
    public String getOutboundBurstRate() {
        String rate = _context.getProperty(PROP_OUTBOUND_BURST_KBPS);
        if (rate != null)
            return rate;
        else
            return "32";
    }
    public String getInboundBurstFactorBox() {
        String rate = _context.getProperty(PROP_INBOUND_BURST_KBPS);
        String burst = _context.getProperty(PROP_INBOUND_BURST);
        int numSeconds = 1;
        if ( (burst != null) && (rate != null) ) {
            int rateKBps = 0;
            int burstKB = 0;
            try {
                rateKBps = Integer.parseInt(rate);
                burstKB = Integer.parseInt(burst);
            } catch (NumberFormatException nfe) {
                // ignore
            }
            if ( (rateKBps > 0) && (burstKB > 0) ) {
                numSeconds = burstKB / rateKBps;
            }
        }
        return getBurstFactor(numSeconds, "inboundburstfactor");
    }
    
    public String getOutboundBurstFactorBox() {
        String rate = _context.getProperty(PROP_OUTBOUND_BURST_KBPS);
        String burst = _context.getProperty(PROP_OUTBOUND_BURST);
        int numSeconds = 1;
        if ( (burst != null) && (rate != null) ) {
            int rateKBps = 0;
            int burstKB = 0;
            try {
                rateKBps = Integer.parseInt(rate);
                burstKB = Integer.parseInt(burst);
            } catch (NumberFormatException nfe) {
                // ignore
            }
            if ( (rateKBps > 0) && (burstKB > 0) ) {
                numSeconds = burstKB / rateKBps;
            }
        }
        return getBurstFactor(numSeconds, "outboundburstfactor");
    }
    
    private static String getBurstFactor(int numSeconds, String name) {
        StringBuffer buf = new StringBuffer(256);
        buf.append("<select name=\"").append(name).append("\">\n");
        boolean found = false;
        for (int i = 10; i <= 60; i += 10) {
            buf.append("<option value=\"").append(i).append("\" ");
            if (i == numSeconds) {
                buf.append("selected ");
                found = true;
            } else if ( (i == 60) && (!found) ) {
                buf.append("selected ");
            }
            buf.append(">");
            buf.append(i).append(" seconds</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }
    
    public String getEnableLoadTesting() {
        if (LoadTestManager.isEnabled(_context))
            return " checked ";
        else
            return "";
    }
    
    public String getSharePercentageBox() {
        String pctStr = _context.getProperty(PROP_SHARE_PERCENTAGE);
        int pct = DEFAULT_SHARE_PERCENTAGE;
        if (pctStr != null)
            try { pct = Integer.parseInt(pctStr); } catch (NumberFormatException nfe) {}
        StringBuffer buf = new StringBuffer(256);
        buf.append("<select name=\"sharePercentage\">\n");
        boolean found = false;
        for (int i = 30; i <= 100; i += 10) {
            buf.append("<option value=\"").append(i).append("\" ");
            if (pct == i) {
                buf.append("selected=\"true\" ");
                found = true;
            } else if ( (i == DEFAULT_SHARE_PERCENTAGE) && (!found) ) {
                buf.append("selected=\"true\" ");
            }
            buf.append(">Up to ").append(i).append("%</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }

    public int getShareBandwidth() {
        String irate = _context.getProperty(PROP_INBOUND_KBPS);
        String orate = _context.getProperty(PROP_OUTBOUND_KBPS);
        String pctStr = _context.getProperty(PROP_SHARE_PERCENTAGE);
        if ( (irate != null) && (orate != null) && (pctStr != null)) {
            try {
                int irateKBps = Integer.parseInt(irate);
                int orateKBps = Integer.parseInt(orate);
                if (irateKBps < 0 || orateKBps < 0)
                    return 0;
                int pct = Integer.parseInt(pctStr);
                return (int) (((float) pct) * Math.min(irateKBps, orateKBps) / 100);
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }
        return 0;
    }
}
