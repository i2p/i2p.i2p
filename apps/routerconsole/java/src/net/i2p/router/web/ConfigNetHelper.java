package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Iterator;
import java.util.TreeMap;

import net.i2p.util.Log;

import net.i2p.router.RouterContext;
import net.i2p.router.ClientTunnelSettings;

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
    
    public String getHostname() {
        return _context.getProperty(PROP_I2NP_TCP_HOSTNAME);
    }
    public String getPort() {
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
    
    public String getEnableTimeSyncChecked() {
        String enabled = System.getProperty("timestamper.enabled");
        if ( (enabled == null) || (!"true".equals(enabled)) )
            return "";
        else
            return " checked ";
    }
    
    public static final String PROP_INBOUND_KBPS = "i2np.bandwidth.inboundKBytesPerSecond";
    public static final String PROP_OUTBOUND_KBPS = "i2np.bandwidth.outboundKBytesPerSecond";
    public static final String PROP_INBOUND_BURST = "i2np.bandwidth.inboundBurstKBytes";
    public static final String PROP_OUTBOUND_BURST = "i2np.bandwidth.outboundBurstKBytes";

    public String getInboundRate() {
        String rate = _context.getProperty(PROP_INBOUND_KBPS);
        if (rate != null)
            return rate;
        else
            return "-1";
    }
    public String getOutboundRate() {
        String rate = _context.getProperty(PROP_OUTBOUND_KBPS);
        if (rate != null)
            return rate;
        else
            return "Unlimited";
    }
    public String getInboundBurstFactorBox() {
        String rate = _context.getProperty(PROP_INBOUND_KBPS);
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
        String rate = _context.getProperty(PROP_OUTBOUND_KBPS);
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
        for (int i = 1; i < 10; i++) {
            buf.append("<option value=\"").append(i).append("\" ");
            if ( (i == numSeconds) || (i == 10) )
                buf.append("selected ");
            buf.append(">");
            if (i == 1)
                buf.append("1 second (no burst)</option>\n");
            else
                buf.append(i).append(" seconds</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }
}
