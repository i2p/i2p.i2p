package net.i2p.util;

import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;

/**
 * Map services to internal or external application ports
 * for this context. Not intended for the router's NTCP or SSU ports.
 *
 * @since 0.8.12
 */
public class PortMapper {
    private final ConcurrentHashMap<String, Integer> _dir;

    public static final String SVC_CONSOLE = "console";
    public static final String SVC_HTTPS_CONSOLE = "https_console";
    public static final String SVC_HTTP_PROXY = "HTTP";
    public static final String SVC_HTTPS_PROXY = "HTTPS";
    public static final String SVC_EEPSITE = "eepsite";
    public static final String SVC_IRC = "irc";
    public static final String SVC_SOCKS = "socks";
    public static final String SVC_TAHOE = "tahoe-lafs";
    public static final String SVC_SMTP = "SMTP";
    public static final String SVC_POP = "POP3";
    public static final String SVC_SAM = "SAM";
    public static final String SVC_BOB = "BOB";
    /** not necessary, already in config? */
    public static final String SVC_I2CP = "I2CP";

    /**
     *  @param context unused for now
     */
    public PortMapper(I2PAppContext context) {
        _dir = new ConcurrentHashMap(8);
    }

    /**
     *  Add the service
     *  @param port > 0
     *  @return success, false if already registered
     */
    public boolean register(String service, int port) {
        if (port <= 0)
            return false;
        return _dir.putIfAbsent(service, Integer.valueOf(port)) == null;
    }

    /**
     *  Remove the service
     */
    public void unregister(String service) {
        _dir.remove(service);
    }

    /**
     *  Get the registered port for a service
     *  @return -1 if not registered
     */
    public int getPort(String service) {
        return getPort(service, -1);
    }

    /**
     *  Get the registered port for a service
     *  @param def default
     *  @return def if not registered
     */
    public int getPort(String service, int def) {
        Integer port = _dir.get(service);
        if (port == null)
            return def;
        return port.intValue();
    }
}
