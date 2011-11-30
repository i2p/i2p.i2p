package net.i2p.router.web;

import java.util.ArrayList;

import net.i2p.data.DataHelper;
import net.i2p.data.RouterAddress;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.transport.udp.UDPAddress;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.time.Timestamper;
import net.i2p.util.Addresses;

public class ConfigNetHelper extends HelperBase {
    public ConfigNetHelper() {}
    
    /** copied from various private components */
    public final static String PROP_I2NP_NTCP_HOSTNAME = "i2np.ntcp.hostname";
    public final static String PROP_I2NP_NTCP_PORT = "i2np.ntcp.port";
    public final static String PROP_I2NP_NTCP_AUTO_PORT = "i2np.ntcp.autoport";
    public final static String PROP_I2NP_NTCP_AUTO_IP = "i2np.ntcp.autoip";
    private final static String CHECKED = " checked=\"true\" ";
    private final static String DISABLED = " disabled=\"true\" ";

    public String getUdphostname() {
        return _context.getProperty(UDPTransport.PROP_EXTERNAL_HOST, ""); 
    }

    public String getNtcphostname() {
        return _context.getProperty(PROP_I2NP_NTCP_HOSTNAME, "");
    }

    public String getNtcpport() { 
        return _context.getProperty(PROP_I2NP_NTCP_PORT, ""); 
    }
    
    public String getUdpAddress() {
        RouterAddress addr = _context.router().getRouterInfo().getTargetAddress("SSU");
        if (addr == null)
            return _("unknown");
        UDPAddress ua = new UDPAddress(addr);
        return ua.toString();
    }
    
    public String getUdpIP() {
        RouterAddress addr = _context.router().getRouterInfo().getTargetAddress("SSU");
        if (addr == null)
            return _("unknown");
        UDPAddress ua = new UDPAddress(addr);
        if (ua.getHost() == null)
            return _("unknown");
        return ua.getHost();
    }

    public String getUdpPort() {
        RouterAddress addr = _context.router().getRouterInfo().getTargetAddress("SSU");
        if (addr == null)
            return _("unknown");
        UDPAddress ua = new UDPAddress(addr);
        if (ua.getPort() <= 0)
            return _("unknown");
        return "" + ua.getPort();
    }

    public String getConfiguredUdpPort() {
        return "" + _context.getProperty(UDPTransport.PROP_INTERNAL_PORT, UDPTransport.DEFAULT_INTERNAL_PORT);
    }

    public String getEnableTimeSyncChecked() {
        boolean disabled = _context.getBooleanProperty(Timestamper.PROP_DISABLED);
        if (disabled)
            return "";
        else
            return CHECKED;
    }
    
    /** @param prop must default to false */
    public String getChecked(String prop) {
        if (_context.getBooleanProperty(prop))
            return CHECKED;
        return "";
    }

    public String getDynamicKeysChecked() {
        return getChecked(Router.PROP_DYNAMIC_KEYS);
    }

    public String getLaptopChecked() {
        return getChecked(UDPTransport.PROP_LAPTOP_MODE);
    }

    public String getTcpAutoPortChecked(int mode) {
        String port = _context.getProperty(PROP_I2NP_NTCP_PORT); 
        boolean specified = port != null && port.length() > 0;
        if ((mode == 1 && specified) ||
            (mode == 2 && !specified))
            return CHECKED;
        return "";
    }

    public String getTcpAutoIPChecked(int mode) {
        boolean enabled = TransportManager.isNTCPEnabled(_context);
        String hostname = _context.getProperty(PROP_I2NP_NTCP_HOSTNAME); 
        boolean specified = hostname != null && hostname.length() > 0;
        String auto = _context.getProperty(PROP_I2NP_NTCP_AUTO_IP, "true");
        if ((mode == 0 && (!specified) && auto.equals("false") && enabled) ||
            (mode == 1 && specified && auto.equals("false") && enabled) ||
            (mode == 2 && auto.equals("true") && enabled) ||
            (mode == 3 && auto.equals("always") && enabled) ||
            (mode == 4 && !enabled))
            return CHECKED;
        return "";
    }

    public String getUdpAutoIPChecked(int mode) {
        String hostname = _context.getProperty(UDPTransport.PROP_EXTERNAL_HOST);
        boolean specified = hostname != null && hostname.length() > 0;
        boolean hidden = _context.router().isHidden();
        String sources = _context.getProperty(UDPTransport.PROP_SOURCES, UDPTransport.DEFAULT_SOURCES);
        if ((mode == 0 && sources.equals("ssu") && !hidden) ||
            (mode == 1 && specified && !hidden) ||
            (mode == 2 && hidden) ||
            (mode == 3 && sources.equals("local,upnp,ssu") && !hidden) ||
            (mode == 4 && sources.equals("local,ssu") && !hidden) ||
            (mode == 5 && sources.equals("upnp,ssu") && !hidden))
            return CHECKED;
        return "";
    }

    /** default true */
    public String getUpnpChecked() {
        if (_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UPNP))
            return CHECKED;
        return "";
    }

    public String getRequireIntroductionsChecked() {
        short status = _context.commSystem().getReachabilityStatus();
        switch (status) {
            case CommSystemFacade.STATUS_OK:
            case CommSystemFacade.STATUS_UNKNOWN:
                return getChecked(UDPTransport.PROP_FORCE_INTRODUCERS);
            case CommSystemFacade.STATUS_DIFFERENT:
            case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
            default:
                return CHECKED;
        }
    }
    
    public String[] getAddresses() {
        ArrayList<String> al = new ArrayList(Addresses.getAddresses());
        return al.toArray(new String[al.size()]);
    }

    public String getInboundRate() {
        return "" + _context.bandwidthLimiter().getInboundKBytesPerSecond();
    }
    public String getOutboundRate() {
        return "" + _context.bandwidthLimiter().getOutboundKBytesPerSecond();
    }
    public String getInboundRateBits() {
        return kbytesToBits(_context.bandwidthLimiter().getInboundKBytesPerSecond());
    }
    public String getOutboundRateBits() {
        return kbytesToBits(_context.bandwidthLimiter().getOutboundKBytesPerSecond());
    }
    public String getShareRateBits() {
        return kbytesToBits(getShareBandwidth());
    }
    private String kbytesToBits(int kbytes) {
        return DataHelper.formatSize(kbytes * 8 * 1024) + ' ' + _("bits per second") +
               ' ' + _("or {0} bytes per month maximum", DataHelper.formatSize(kbytes * 1024l * 60 * 60 * 24 * 31));
    }
    public String getInboundBurstRate() {
        return "" + _context.bandwidthLimiter().getInboundBurstKBytesPerSecond();
    }
    public String getOutboundBurstRate() {
        return "" + _context.bandwidthLimiter().getOutboundBurstKBytesPerSecond();
    }
    public String getInboundBurstFactorBox() {
        int numSeconds = 1;
        int rateKBps = _context.bandwidthLimiter().getInboundBurstKBytesPerSecond();
        int burstKB = _context.bandwidthLimiter().getInboundBurstBytes() / 1024;
        if ( (rateKBps > 0) && (burstKB > 0) )
            numSeconds = burstKB / rateKBps;
        return getBurstFactor(numSeconds, "inboundburstfactor");
    }
    
    public String getOutboundBurstFactorBox() {
        int numSeconds = 1;
        int rateKBps = _context.bandwidthLimiter().getOutboundBurstKBytesPerSecond();
        int burstKB = _context.bandwidthLimiter().getOutboundBurstBytes() / 1024;
        if ( (rateKBps > 0) && (burstKB > 0) )
            numSeconds = burstKB / rateKBps;
        return getBurstFactor(numSeconds, "outboundburstfactor");
    }
    
    private static String getBurstFactor(int numSeconds, String name) {
        StringBuilder buf = new StringBuilder(256);
        buf.append("<select name=\"").append(name).append("\">\n");
        boolean found = false;
        for (int i = 10; i <= 70; i += 10) {
            int val = i;
            if (i == 70) {
                if (found)
                    break;
                else
                    val = numSeconds;
            }
            buf.append("<option value=\"").append(val).append("\" ");
            if (val == numSeconds) {
                buf.append("selected ");
                found = true;
            }
            buf.append(">");
            buf.append(val).append(" seconds</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }
    
    /** removed */
    public String getEnableLoadTesting() {
        return "";
    }
    
    public String getSharePercentageBox() {
        int pct = (int) (100 * _context.router().getSharePercentage());
        StringBuilder buf = new StringBuilder(256);
        buf.append("<select style=\"text-align: right !important;\" name=\"sharePercentage\">\n");
        boolean found = false;
        for (int i = 30; i <= 110; i += 10) {
            int val = i;
            if (i == 110) {
                if (found)
                    break;
                else
                    val = pct;
            }
            buf.append("<option style=\"text-align: right;\" value=\"").append(val).append("\" ");
            if (pct == val) {
                buf.append("selected=\"true\" ");
                found = true;
            }
            buf.append(">").append(val).append("%</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }

    public static final int DEFAULT_SHARE_KBPS = 12;
    public int getShareBandwidth() {
        int irateKBps = _context.bandwidthLimiter().getInboundKBytesPerSecond();
        int orateKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        double pct = _context.router().getSharePercentage();
        if (irateKBps < 0 || orateKBps < 0)
            return DEFAULT_SHARE_KBPS;
        return (int) (pct * Math.min(irateKBps, orateKBps));
    }
}
