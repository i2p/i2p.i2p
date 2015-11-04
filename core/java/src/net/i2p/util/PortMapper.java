package net.i2p.util;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;

/**
 * Map services to internal or external application ports
 * for this context. Not intended for the router's NTCP or SSU ports.
 *
 * @since 0.8.12
 */
public class PortMapper {
    private final ConcurrentHashMap<String, InetSocketAddress> _dir;

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
    /** @since 0.9.23 */
    public static final String SVC_I2CP_SSL = "I2CP-SSL";

    /**
     *  @param context unused for now
     */
    public PortMapper(I2PAppContext context) {
        _dir = new ConcurrentHashMap<String, InetSocketAddress>(8);
    }

    /**
     *  Add the service
     *  @param port > 0
     *  @return success, false if already registered
     */
    public boolean register(String service, int port) {
        return register(service, "127.0.0.1", port);
    }

    /**
     *  Add the service
     *  @param port > 0
     *  @return success, false if already registered
     *  @since 0.9.21
     */
    public boolean register(String service, String host, int port) {
        if (port <= 0 || port > 65535)
            return false;
        return _dir.putIfAbsent(service, InetSocketAddress.createUnresolved(host, port)) == null;
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
        int port = getPort(service, -1);
        return port;
    }

    /**
     *  Get the registered port for a service
     *  @param def default
     *  @return def if not registered
     */
    public int getPort(String service, int def) {
        InetSocketAddress ia = _dir.get(service);
        if (ia == null)
            return def;
        return ia.getPort();
    }

    /**
     *  Get the registered host for a service.
     *  Will return "127.0.0.1" if the service was registered without a host.
     *  @param def default
     *  @return def if not registered
     *  @since 0.9.21
     */
    public String getHost(String service, String def) {
        InetSocketAddress ia = _dir.get(service);
        if (ia == null)
            return def;
        return ia.getHostName();
    }

    /**
     *  For debugging only
     *  @since 0.9.20
     */
    public void renderStatusHTML(Writer out) throws IOException {
        List<String> services = new ArrayList<String>(_dir.keySet());
        out.write("<h2>Port Mapper</h2><table><tr><th>Service<th>Host<th>Port\n");
        Collections.sort(services);
        for (String s : services) {
            InetSocketAddress ia = _dir.get(s);
            if (ia == null)
                continue;
            out.write("<tr><td>" + s + "<td>" + ia.getHostName() + "<td>" + ia.getPort() + '\n');
        }
        out.write("</table>\n");
    }
}
