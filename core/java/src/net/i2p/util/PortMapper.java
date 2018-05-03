package net.i2p.util;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
    public static final String PROP_PREFER_HTTPS = "routerconsole.preferHTTPS";

    public static final String SVC_CONSOLE = "console";
    public static final String SVC_HTTPS_CONSOLE = "https_console";
    public static final String SVC_HTTP_PROXY = "HTTP";
    public static final String SVC_HTTPS_PROXY = "HTTPS";
    public static final String SVC_EEPSITE = "eepsite";
    /** @since 0.9.34 */
    public static final String SVC_HTTPS_EEPSITE = "https_eepsite";
    public static final String SVC_IRC = "irc";
    public static final String SVC_SOCKS = "socks";
    public static final String SVC_TAHOE = "tahoe-lafs";
    public static final String SVC_SMTP = "SMTP";
    public static final String SVC_POP = "POP3";
    public static final String SVC_SAM = "SAM";
    /** @since 0.9.24 */
    public static final String SVC_SAM_UDP = "SAM-UDP";
    /** @since 0.9.24 */
    public static final String SVC_SAM_SSL = "SAM-SSL";
    public static final String SVC_BOB = "BOB";
    /** not necessary, already in config? */
    public static final String SVC_I2CP = "I2CP";
    /** @since 0.9.23 */
    public static final String SVC_I2CP_SSL = "I2CP-SSL";
    /** @since 0.9.34 */
    public static final String SVC_HTTP_I2PCONTROL = "http_i2pcontrol";
    /** @since 0.9.34 */
    public static final String SVC_HTTPS_I2PCONTROL = "https_i2pcontrol";
    /**
     *  To indicate presence, alternative to WebAppStarter.isWebappRunning().
     *  For actual base URL, use getConsoleURL()
     *  @since 0.9.34
     */
    public static final String SVC_I2PSNARK = "i2psnark";
    /**
     *  To indicate presence, alternative to WebAppStarter.isWebappRunning().
     *  For actual base URL, use getConsoleURL()
     *  @since 0.9.34
     */
    public static final String SVC_I2PTUNNEL = "i2ptunnel";
    /**
     *  To indicate presence, alternative to WebAppStarter.isWebappRunning().
     *  For actual base URL, use getConsoleURL()
     *  @since 0.9.34
     */
    public static final String SVC_IMAGEGEN = "imagegen";
    /**
     *  To indicate presence, alternative to WebAppStarter.isWebappRunning().
     *  For actual base URL, use getConsoleURL()
     *  @since 0.9.34
     */
    public static final String SVC_SUSIDNS = "susidns";
    /**
     *  To indicate presence, alternative to WebAppStarter.isWebappRunning().
     *  For actual base URL, use getConsoleURL()
     *  @since 0.9.34
     */
    public static final String SVC_SUSIMAIL = "susimail";

    /** @since 0.9.34 */
    public static final int DEFAULT_CONSOLE_PORT = 7657;
    /** @since 0.9.34 */
    public static final int DEFAULT_HTTPS_CONSOLE_PORT = 7667;
    /** @since 0.9.34 */
    public static final String DEFAULT_HOST = "127.0.0.1";


    /**
     *  @param context unused for now
     */
    public PortMapper(I2PAppContext context) {
        _dir = new ConcurrentHashMap<String, InetSocketAddress>(8);
    }

    /**
     *  Add the service
     *  @param port &gt; 0
     *  @return success, false if already registered
     */
    public boolean register(String service, int port) {
        return register(service, DEFAULT_HOST, port);
    }

    /**
     *  Add the service
     *  @param port &gt; 0
     *  @return success, false if already registered
     *  @since 0.9.21
     */
    public boolean register(String service, String host, int port) {
        if (port <= 0 || port > 65535)
            return false;
        return _dir.putIfAbsent(service, InetSocketAddress.createUnresolved(host, port)) == null;
    }

    /**
     *  Is the service registered?
     *
     *  @since 0.9.34
     */
    public boolean isRegistered(String service) {
        return _dir.containsKey(service);
    }

    /**
     *  Remove the service
     */
    public void unregister(String service) {
        _dir.remove(service);
    }

    /**
     *  Remove the service,
     *  only if it is registered with the supplied port.
     *
     *  @since 0.9.34
     */
    public void unregister(String service, int port) {
        // not synched
        if (getPort(service) == port)
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
     *  Get the actual host for a service.
     *  Will return "127.0.0.1" if the service was registered without a host.
     *  If the service was registered with the host "0.0.0.0", "::", or "0:0:0:0:0:0:0:0",
     *  it will return a public IP if we have one,
     *  else a local IP if we have one, else def.
     *  If it was not registered with a wildcard address, it will return the registered host.
     *
     *  @param def default
     *  @return def if not registered
     *  @since 0.9.24
     */
    public String getActualHost(String service, String def) {
        InetSocketAddress ia = _dir.get(service);
        if (ia == null)
            return def;
        return convertWildcard(ia.getHostName(), def);
    }

    /*
     *  See above
     *  @param def default
     *  @return def if no ips
     *  @since 0.9.24
     */
    private static String convertWildcard(String ip, String def) {
        String rv = ip;
        if (rv.equals("0.0.0.0")) {
            // public
            rv = Addresses.getAnyAddress();
            if (rv == null) {
                rv = def;
                // local
                Set<String> addrs = Addresses.getAddresses(true, false);
                for (String addr : addrs) {
                    if (!addr.startsWith("127.") && !addr.equals("0.0.0.0")) {
                        rv = addr;
                        break;
                    }
                }
            }
        } else if (rv.equals("::") || rv.equals("0:0:0:0:0:0:0:0")) {
            rv = def;
            // public
            Set<String> addrs = Addresses.getAddresses(false, true);
            for (String addr : addrs) {
                if (!addr.contains(".")) {
                    return rv;
                }
            }
            // local
            addrs = Addresses.getAddresses(true, true);
            for (String addr : addrs) {
                if (!addr.contains(".") && !addr.equals("::") && !addr.equals("0:0:0:0:0:0:0:0")) {
                    rv = addr;
                    break;
                }
            }
        }
        return rv;
    }

    /**
     *  If PROP_PREFER_HTTPS is true or unset,
     *  return https URL unless console is http only. Default https://127.0.0.1:7667/
     *  If PROP_PREFER_HTTPS is set to false,
     *  return http URL unless console is https only. Default http://127.0.0.1:7657/
     *
     *  @since 0.9.33 consolidated from i2ptunnel and desktopgui
     */
    public String getConsoleURL() {
        return getConsoleURL(I2PAppContext.getGlobalContext().getBooleanPropertyDefaultTrue(PROP_PREFER_HTTPS));
    }

    /**
     *  If preferHTTPS is true,
     *  return https URL unless console is http only. Default https://127.0.0.1:7667/
     *  If preferHTTPS is false,
     *  return http URL unless console is https only. Default http://127.0.0.1:7657/
     *
     *  @since 0.9.34
     */
    public String getConsoleURL(boolean preferHTTPS) {
        return preferHTTPS ? getHTTPSConsoleURL() : getHTTPConsoleURL();
    }

    /**
     *  @return http URL unless console is https only. Default http://127.0.0.1:7657/
     */
    private String getHTTPConsoleURL() {
        String unset = "*unset*";
        String httpHost = getActualHost(SVC_CONSOLE, unset);
        String httpsHost = getActualHost(SVC_HTTPS_CONSOLE, unset);
        int httpPort = getPort(SVC_CONSOLE, DEFAULT_CONSOLE_PORT);
        int httpsPort = getPort(SVC_HTTPS_CONSOLE);
        boolean httpsOnly = httpsPort > 0 && httpHost.equals(unset) && !httpsHost.equals(unset);
        if (httpsOnly)
            return "https://" + httpsHost + ':' + httpsPort + '/';
        if (httpHost.equals(unset))
            httpHost = DEFAULT_HOST;
        return "http://" + httpHost + ':' + httpPort + '/';
    }

    /**
     *  @return https URL unless console is http only. Default http://127.0.0.1:7657/
     *  @since 0.9.34
     */
    private String getHTTPSConsoleURL() {
        String unset = "*unset*";
        String httpHost = getActualHost(SVC_CONSOLE, unset);
        String httpsHost = getActualHost(SVC_HTTPS_CONSOLE, unset);
        int httpPort = getPort(SVC_CONSOLE);
        int httpsPort = getPort(SVC_HTTPS_CONSOLE, DEFAULT_HTTPS_CONSOLE_PORT);
        boolean httpOnly = httpPort > 0 && httpsHost.equals(unset) && !httpHost.equals(unset);
        if (httpOnly)
            return "http://" + httpHost + ':' + httpPort + '/';
        if (httpsHost.equals(unset))
            return "http://" + DEFAULT_HOST + ':' + DEFAULT_CONSOLE_PORT + '/';
        return "https://" + httpsHost + ':' + httpsPort + '/';
    }

    /**
     *  For debugging only
     *  @since 0.9.20
     */
    public void renderStatusHTML(Writer out) throws IOException {
        List<String> services = new ArrayList<String>(_dir.keySet());
        out.write("<h2 id=\"debug_portmapper\">Port Mapper</h2><table id=\"portmapper\"><tr><th>Service<th>Host<th>Port\n");
        Collections.sort(services, Collator.getInstance());
        for (String s : services) {
            InetSocketAddress ia = _dir.get(s);
            if (ia == null)
                continue;
            out.write("<tr><td>" + s + "<td>" + convertWildcard(ia.getHostName(), DEFAULT_HOST) + "<td>" + ia.getPort() + '\n');
        }
        out.write("</table>\n");
    }
}
