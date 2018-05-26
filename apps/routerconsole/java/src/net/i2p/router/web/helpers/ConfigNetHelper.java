package net.i2p.router.web.helpers;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Router;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.web.HelperBase;
import net.i2p.util.Addresses;

/**
 *
 * Used for both /config and /confignet
 */
public class ConfigNetHelper extends HelperBase {
    
    /** copied from various private components */
    final static String PROP_I2NP_NTCP_HOSTNAME = "i2np.ntcp.hostname";
    final static String PROP_I2NP_NTCP_PORT = "i2np.ntcp.port";
    final static String PROP_I2NP_NTCP_AUTO_PORT = "i2np.ntcp.autoport";
    final static String PROP_I2NP_NTCP_AUTO_IP = "i2np.ntcp.autoip";

    public String getUdphostname() {
        return _context.getProperty(UDPTransport.PROP_EXTERNAL_HOST, ""); 
    }

    public String getNtcphostname() {
        return _context.getProperty(PROP_I2NP_NTCP_HOSTNAME, "");
    }

    public String getNtcpport() { 
        return _context.getProperty(PROP_I2NP_NTCP_PORT, ""); 
    }
    
    /** @return host or "unknown" */
    public String getUdpIP() {
        String rv = _context.getProperty(UDPTransport.PROP_IP);
        if (rv != null)
            return rv;
        RouterAddress addr = _context.router().getRouterInfo().getTargetAddress("SSU");
        if (addr != null) {
            rv = addr.getHost();
            if (rv != null)
                return rv;
        }
        addr = _context.router().getRouterInfo().getTargetAddress("NTCP");
        if (addr != null) {
            rv = addr.getHost();
            if (rv != null)
                return rv;
        }
        return _t("unknown");
    }

    /**
     *  To reduce confusion caused by NATs, this is the current internal SSU port,
     *  not the external port.
     */
    public String getUdpPort() {
      /****
        RouterAddress addr = _context.router().getRouterInfo().getTargetAddress("SSU");
        if (addr == null)
            return _t("unknown");
        UDPAddress ua = new UDPAddress(addr);
        if (ua.getPort() <= 0)
            return _t("unknown");
        return "" + ua.getPort();
      ****/
        // Since we can't get to UDPTransport.getRequestedPort() from here, just use
        // configured port. If UDPTransport is changed such that the actual port
        // could be different, fix this.
        return getConfiguredUdpPort();
    }

    /**
     *  This should always be the actual internal SSU port, as UDPTransport udpates
     *  the config when it changes.
     */
    public String getConfiguredUdpPort() {
        return _context.getProperty(UDPTransport.PROP_INTERNAL_PORT, "unset");
    }

    public String getDynamicKeysChecked() {
        return getChecked(Router.PROP_DYNAMIC_KEYS);
    }

    public String getLaptopChecked() {
        return getChecked(UDPTransport.PROP_LAPTOP_MODE);
    }

    /** @since 0.9.20 */
    public String getIPv4FirewalledChecked() {
        return getChecked(TransportUtil.PROP_IPV4_FIREWALLED);
    }

    /** @since 0.9.28 */
    public String getIPv6FirewalledChecked() {
        return getChecked(TransportUtil.PROP_IPV6_FIREWALLED);
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

    /**
     * default false, inverse of default true property
     * @since 0.8.13
     */
    public String getUdpDisabledChecked() {
        if (!_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP))
            return CHECKED;
        return "";
    }

    /**
     * default true
     * @since 0.9.35
     */
    public String getUdpEnabledChecked() {
        if (_context.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP))
            return CHECKED;
        return "";
    }

    /**
     *  This isn't updated for the new statuses, but it's commented out in the jsp.
     *  @deprecated unused, to be fixed if needed
     */
    @Deprecated
    public String getRequireIntroductionsChecked() {
        Status status = _context.commSystem().getStatus();
        switch (status) {
            case OK:
            case UNKNOWN:
                return getChecked(UDPTransport.PROP_FORCE_INTRODUCERS);
            case DIFFERENT:
            case REJECT_UNSOLICITED:
            default:
                return CHECKED;
        }
    }

    /**
     * Combined SSU/NTCP
     * Use SSU setting, then NTCP setting, then default
     * @since IPv6
     */
    public String getIPv6Checked(String mode) {
        String s = _context.getProperty(TransportUtil.SSU_IPV6_CONFIG);
        if (s == null) {
            s = _context.getProperty(TransportUtil.NTCP_IPV6_CONFIG);
            if (s == null)
                s = TransportUtil.DEFAULT_IPV6_CONFIG.toConfigString();
        }
        if (s.equals(mode))
            return CHECKED;
        return "";
    }
    
    public Set<String> getAddresses() {
        // exclude local, include IPv6
        return Addresses.getAddresses(false, true);
    }

    /** @since IPv6 */
    public String getAddressSelector() {
        Set<String> addrs = getAddresses();
        // isPubliclyRoutable() rejects some IPv6 addresses that getAddresses() allows
        for (Iterator<String> iter = addrs.iterator(); iter.hasNext(); ) {
            byte[] ip = Addresses.getIP(iter.next());
            if (ip == null || !TransportUtil.isPubliclyRoutable(ip, true))
                iter.remove();
        }
        Set<String> configs;
        String cs = getUdphostname();
        if (cs.length() <= 0) {
            configs = Collections.emptySet();
        } else {
            configs = new HashSet<String>(4);
            String[] ca = DataHelper.split(cs, "[,; \r\n\t]");
            for (int i = 0; i < ca.length; i++) {
                String c = ca[i];
                if (c.length() > 0) {
                    configs.add(c);
                    addrs.add(c);
                }
            }
        }
        StringBuilder buf = new StringBuilder(128);
        for (String addr : addrs) {
            buf.append("<input type=\"checkbox\" class=\"optbox\" value=\"foo\" name=\"addr_");
            buf.append(addr);
            buf.append('"');
            if (addrs.size() == 1 || configs.contains(addr))
                buf.append(CHECKED);
            buf.append("> ");
            buf.append(addr);
            buf.append("<br>\n");
        }
        if (!addrs.isEmpty()) {
            buf.append(_t("Add host name or IP"))
               .append(": ");
        }
        buf.append("<input name=\"udpHost1\" type=\"text\" size=\"16\" >");
        return buf.toString();
    }

    /** @return decimal */
    public String getInboundRate() {
        return Integer.toString(Math.round(_context.bandwidthLimiter().getInboundKBytesPerSecond() * 1.024f));
    }

    /** @return decimal */
    public String getOutboundRate() {
        return Integer.toString(Math.round(_context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1.024f));
    }

    /** @return decimal */
    public String getInboundBurstRateBits() {
        return kbytesToBits(_context.bandwidthLimiter().getInboundBurstKBytesPerSecond());
    }

    /** @return decimal */
    public String getOutboundBurstRateBits() {
        return kbytesToBits(_context.bandwidthLimiter().getOutboundBurstKBytesPerSecond());
    }

    /** @return decimal */
    public String getShareRateBits() {
        return kbytesToBits(getShareBandwidth());
    }

    /** @param kbytes binary K */
    private String kbytesToBits(float kbytes) {
        return DataHelper.formatSize2Decimal((long) (kbytes * (8 * 1024))) + _t("bits per second") +
               "; " +
               _t("{0}Bytes per month maximum", DataHelper.formatSize2Decimal((long) (kbytes * (1024L * 60 * 60 * 24 * 31))));
    }

    /** @return decimal */
    public String getInboundBurstRate() {
        return Integer.toString(Math.round(_context.bandwidthLimiter().getInboundBurstKBytesPerSecond() * 1.024f));
    }

    /** @return decimal */
    public String getOutboundBurstRate() {
        return Integer.toString(Math.round(_context.bandwidthLimiter().getOutboundBurstKBytesPerSecond() * 1.024f));
    }

/*
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
    
    public String getEnableLoadTesting() {
        return "";
    }
****/
    
    public String getSharePercentageBox() {
        int pct = (int) (100 * _context.router().getSharePercentage());
        StringBuilder buf = new StringBuilder(256);
        buf.append("<select style=\"text-align: right !important;\" name=\"sharePercentage\">\n");
        boolean found = false;
        for (int i = 100; i >= -10; i -= 10) {
            int val = i;
            if (i == -10) {
                if (found)
                    break;
                else
                    val = pct;
            }
            buf.append("<option style=\"text-align: right;\" value=\"").append(val).append("\" ");
            if (pct == val) {
                buf.append("selected=\"selected\" ");
                found = true;
            }
            buf.append(">").append(val).append("%</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }

    public static final int DEFAULT_SHARE_KBPS = 12;

    /**
     *  @return in binary KBytes per second
     */
    public int getShareBandwidth() {
        int irateKBps = _context.bandwidthLimiter().getInboundKBytesPerSecond();
        int orateKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        double pct = _context.router().getSharePercentage();
        if (irateKBps < 0 || orateKBps < 0)
            return DEFAULT_SHARE_KBPS;
        return (int) (pct * Math.min(irateKBps, orateKBps));
    }
}
