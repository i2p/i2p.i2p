package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind { either expressed or implied.  
 * It probably won't make your computer catch on fire { or eat 
 * your children { but it might.  Use at your own risk.
 *
 */

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import net.i2p.data.RouterAddress;
import net.i2p.router.RouterContext;

/**
 *  @since IPv6
 */
public abstract class TransportUtil {

    public static final String NTCP_IPV6_CONFIG = "i2np.ntcp.ipv6";
    public static final String SSU_IPV6_CONFIG = "i2np.udp.ipv6";

    public enum IPv6Config {
        /** IPv6 disabled */
        IPV6_DISABLED("false"),

        /** lower priority than IPv4 */
        IPV6_NOT_PREFERRED("preferIPv4"),

        /** equal priority to IPv4 */
        IPV6_ENABLED("enable"),

        /** higher priority than IPv4 */
        IPV6_PREFERRED("preferIPv6"),

        /** IPv4 disabled */
        IPV6_ONLY("only");

        private final String cfgstr;

        IPv6Config(String cfgstr) {
            this.cfgstr = cfgstr;
        }

        public String toConfigString() {
            return cfgstr;
        }
    }

    private static final Map<String, IPv6Config> BY_NAME = new HashMap<String, IPv6Config>();
    public static final IPv6Config DEFAULT_IPV6_CONFIG = IPv6Config.IPV6_PREFERRED;

    static {
        for (IPv6Config cfg : IPv6Config.values()) {
            BY_NAME.put(cfg.toConfigString(), cfg);
        }
        // alias
        BY_NAME.put("true", IPv6Config.IPV6_ENABLED);
        BY_NAME.put("disable", IPv6Config.IPV6_DISABLED);
    }

    public static IPv6Config getIPv6Config(RouterContext ctx, String transportStyle) {
        String cfg;
        if (transportStyle.equals("NTCP"))
            cfg = ctx.getProperty(NTCP_IPV6_CONFIG);
        else if (transportStyle.equals("SSU"))
            cfg = ctx.getProperty(SSU_IPV6_CONFIG);
        else
            return DEFAULT_IPV6_CONFIG;
        return getIPv6Config(cfg);
    }

    public static IPv6Config getIPv6Config(String cfg) {
        if (cfg == null)
            return DEFAULT_IPV6_CONFIG;
        IPv6Config c = BY_NAME.get(cfg);
        if (c != null)
            return c;
        return DEFAULT_IPV6_CONFIG;
    }

    /**
     *  Addresses without a host (i.e. w/introducers)
     *  are assumed to be IPv4
     */
    public static boolean isIPv6(RouterAddress addr) {
        // do this the fast way, without calling getIP() to parse the host string
        String host = addr.getOption(RouterAddress.PROP_HOST);
        return host != null && host.contains(":");
    }

    /**
     *  @param addr non-null
     *  @since IPv6 moved from TransportImpl
     */
    public static boolean isPubliclyRoutable(byte addr[], boolean allowIPv6) {
        return isPubliclyRoutable(addr, true, allowIPv6);
    }

    /**
     *  @param addr non-null
     *  @since IPv6
     */
    public static boolean isPubliclyRoutable(byte addr[], boolean allowIPv4, boolean allowIPv6) {
        if (addr.length == 4) {
            if (!allowIPv4)
                return false;
            int a0 = addr[0] & 0xFF;
            if (a0 == 127) return false;
            if (a0 == 10) return false;
            int a1 = addr[1] & 0xFF;
            if (a0 == 172 && a1 >= 16 && a1 <= 31) return false;
            if (a0 == 192 && a1 == 168) return false;
            if (a0 >= 224) return false; // no multicast
            if (a0 == 0) return false;
            if (a0 == 169 && a1 == 254) return false;
            // 5/8 allocated to RIPE (30 November 2010)
            //if ((addr[0]&0xFF) == 5) return false;  // Hamachi
            // Hamachi moved to 25/8 Nov. 2012
            // Assigned to UK Ministry of Defence
            // http://blog.logmein.com/products/changes-to-hamachi-on-november-19th
            if (a0 == 25) return false;
            return true; // or at least possible to be true
        } else if (addr.length == 16) {
            if (allowIPv6) {
                // loopback, broadcast,
                // IPv4 compat ::xxxx:xxxx
                if (addr[0] == 0)
                    return false;
                if (addr[0] == 0x20) {
                    // disallow 2002::/16 (6to4 RFC 3056)
                    if (addr[1] == 0x02)
                        return false;
                    if (addr[1] == 0x01) {
                        // disallow 2001:0::/32 (Teredo RFC 4380)
                        if (addr[2] == 0x00 && addr[3] == 0x00)
                            return false;
                        // Documenation (example) RFC 3849
                        if (addr[2] == 0x0d && (addr[3] & 0xff) == 0xb8)
                            return false;
                    }
                }
                // disallow fc00::/8 and fd00::/8 (Unique local addresses RFC 4193)
                // not recognized as local by InetAddress
                if ((addr[0] & 0xfe) == 0xfc)
                    return false;
                // Hamachi IPv6
                if (addr[0] == 0x26 && addr[1] == 0x20 && addr[2] == 0x00 && (addr[3] & 0xff) == 0x9b)
                    return false;
                // 6bone RFC 2471
                if (addr[0] == 0x3f && (addr[1] & 0xff) == 0xfe)
                    return false;
                try {
                    InetAddress ia = InetAddress.getByAddress(addr);
                    return
                        (!ia.isLinkLocalAddress()) &&
                        (!ia.isMulticastAddress()) &&
                        (!ia.isAnyLocalAddress()) &&
                        (!ia.isLoopbackAddress()) &&
                        (!ia.isSiteLocalAddress());
                } catch (UnknownHostException uhe) {}
            }
        }
        return false;
    }
}
