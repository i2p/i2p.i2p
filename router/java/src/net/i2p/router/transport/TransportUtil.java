package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind { either expressed or implied.  
 * It probably won't make your computer catch on fire { or eat 
 * your children { but it might.  Use at your own risk.
 *
 */

import java.util.HashMap;
import java.util.Map;

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

    static {
        for (IPv6Config cfg : IPv6Config.values()) {
            BY_NAME.put(cfg.toConfigString(), cfg);
        }
    }

    public static IPv6Config getIPv6Config(RouterContext ctx, String transportStyle) {
        String cfg;
        if (transportStyle.equals("NTCP"))
            cfg = ctx.getProperty(NTCP_IPV6_CONFIG);
        else if (transportStyle.equals("SSU"))
            cfg = ctx.getProperty(SSU_IPV6_CONFIG);
        else
            return IPv6Config.IPV6_DISABLED;
        return getIPv6Config(cfg);
    }

    public static IPv6Config getIPv6Config(String cfg) {
        if (cfg == null)
            return IPv6Config.IPV6_DISABLED;
        IPv6Config c = BY_NAME.get(cfg);
        if (c != null)
            return c;
        return IPv6Config.IPV6_DISABLED;
    }
}
