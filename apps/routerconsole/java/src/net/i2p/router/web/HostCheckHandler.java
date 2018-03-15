package net.i2p.router.web;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.servlets.gzip.GzipHandler;

/**
 * Block certain Host headers to prevent DNS rebinding attacks.
 *
 * This Handler wraps the ContextHandlerCollection, which handles
 * all the webapps (not just routerconsole).
 * Therefore, this protects all the webapps.
 *
 * @since 0.9.32
 */
public class HostCheckHandler extends GzipHandler
{
    private final I2PAppContext _context;
    private final PortMapper _portMapper;
    private final Set<String> _listenHosts;
    private static final String PROP_REDIRECT = "routerconsole.redirectToHTTPS";
    private static final String PROP_GZIP = "routerconsole.enableCompression";

    /**
     *  MUST call setListenHosts() afterwards.
     */
    public HostCheckHandler(I2PAppContext ctx) {
        super();
        _context = ctx;
        _portMapper = ctx.portMapper();
        _listenHosts = new HashSet<String>(8);
        setMinGzipSize(64*1024);
        if (_context.getBooleanPropertyDefaultTrue(PROP_GZIP)) {
            addIncludedMimeTypes(
                                 // our js is very small
                                 //"application/javascript", "application/x-javascript",
                                 "application/xhtml+xml", "application/xml",
                                 // ditto svg
                                 //"image/svg+xml",
                                 "text/css", "text/html", "text/plain"
                                );
        } else {
            // poorly documented, but we must put something in,
            // if empty all are matched,
            // see IncludeExcludeSet
            addIncludedMimeTypes("xyzzy");
        }
    }
    
    /**
     *  Set the legal hosts.
     *  Not synched. Call this BEFORE starting.
     *  If empty, all are allowed.
     *
     *  @param hosts contains hostnames or IPs. But we allow all IPs anyway.
     */
    public void setListenHosts(Set<String> hosts) {
        _listenHosts.clear();
        _listenHosts.addAll(hosts);
    }

    /**
     *  Block by Host header,
     *  redirect HTTP to HTTPS,
     *  pass everything else to the delegate.
     */
    public void handle(String pathInContext,
                       Request baseRequest,
                       HttpServletRequest httpRequest,
                       HttpServletResponse httpResponse)
         throws IOException, ServletException
    {

        String host = httpRequest.getHeader("Host");
        if (!allowHost(host)) {
            Log log = _context.logManager().getLog(HostCheckHandler.class);
            host = DataHelper.stripHTML(getHost(host));
            String s = "Console request denied.\n" +
                       "    To allow access using the hostname \"" + host + "\",\n" +
                       "    add the line \"" + RouterConsoleRunner.PROP_ALLOWED_HOSTS + '=' + host + "\"\n" +
                       "    to advanced configuration and restart.";
            log.logAlways(Log.WARN, s);
            httpResponse.sendError(403, s);
            baseRequest.setHandled(true);
            return;
        }

        // redirect HTTP to HTTPS if available, AND:
        // either 1) PROP_REDIRECT is set to true;
        // or 2) PROP_REDIRECT is unset and the Upgrade-Insecure-Requests request header is set
        // https://w3c.github.io/webappsec-upgrade-insecure-requests/
        if (!httpRequest.isSecure()) {
            int httpsPort = _portMapper.getPort(PortMapper.SVC_HTTPS_CONSOLE);
            if (httpsPort > 0 && httpRequest.getLocalPort() != httpsPort) {
                String redir = _context.getProperty(PROP_REDIRECT);
                if (Boolean.valueOf(redir) ||
                    (redir == null && "1".equals(httpRequest.getHeader("Upgrade-Insecure-Requests")))) {
                    sendRedirect(httpsPort, httpRequest, httpResponse);
                    baseRequest.setHandled(true);
                    return;
                }
            }
        }

        super.handle(pathInContext, baseRequest, httpRequest, httpResponse);
    }

    /**
     *  Should we allow a request with this Host header?
     *
     *  ref: https://en.wikipedia.org/wiki/DNS_rebinding
     *
     *  @param host the HTTP Host header, null ok
     *  @return true if OK
     */
    private boolean allowHost(String host) {
        if (host == null)
            return true;
        // common cases
        if (host.equals("127.0.0.1:7657") ||
            host.equals("localhost:7657") ||
            host.equals("[::1]:7657") ||
            host.equals("127.0.0.1:7667") ||
            host.equals("localhost:7667") ||
            host.equals("[::1]:7667"))
            return true;
        // all allowed?
        if (_listenHosts.isEmpty())
            return true;
        host = getHost(host);
        if (_listenHosts.contains(host))
            return true;
        // allow all IP addresses
        if (Addresses.isIPAddress(host))
            return true;
        //System.out.println(host + " not found in " + s);
        return false;
    }

    /**
     *  Strip [] and port from a host header
     *
     *  @param host the HTTP Host header non-null
     */
    private static String getHost(String host) {
        if (host.startsWith("[")) {
            host = host.substring(1);
            int brack = host.indexOf(']');
            if (brack >= 0)
                host = host.substring(0, brack);
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0)
                host = host.substring(0, colon);
        }
        return host;
    }

    /**
     *  Redirect to HTTPS
     *
     *  @since 0.9.34
     */
    private static void sendRedirect(int httpsPort, HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) throws IOException {
        StringBuilder buf = new StringBuilder(64);
        buf.append("https://");
        String name = httpRequest.getServerName();
        boolean ipv6 = name.indexOf(':') >= 0 && !name.startsWith("[");
        if (ipv6)
            buf.append('[');
        buf.append(name);
        if (ipv6)
            buf.append(']');
        buf.append(':').append(httpsPort)
           .append(httpRequest.getRequestURI());
        String q = httpRequest.getQueryString();
        if (q != null)
            buf.append('?').append(q);
        httpResponse.setHeader("Location", buf.toString());
        // https://w3c.github.io/webappsec-upgrade-insecure-requests/
        httpResponse.setHeader("Vary", "Upgrade-Insecure-Requests");
        httpResponse.setStatus(307);
        httpResponse.getOutputStream().close();
    }
}
