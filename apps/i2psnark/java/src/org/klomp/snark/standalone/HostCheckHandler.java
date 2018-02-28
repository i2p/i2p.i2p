package org.klomp.snark.standalone;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.I2PAppContext;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

/**
 * Block certain Host headers to prevent DNS rebinding attacks.
 *
 * Unlike in the console, this is an AbstractHandler, not a HandlerWrapper.
 *
 * @since 0.9.34 adapted from router console
 */
public class HostCheckHandler extends AbstractHandler {
    private final I2PAppContext _context;
    private final Set<String> _listenHosts;
    private static final String PROP_ALLOWED_HOSTS = "i2psnark.allowedHosts";

    public HostCheckHandler() {
        this(I2PAppContext.getGlobalContext());
    }

    public HostCheckHandler(I2PAppContext ctx) {
        super();
        _context = ctx;
        _listenHosts = new HashSet<String>(8);
        _listenHosts.add("127.0.0.1");
        _listenHosts.add("::1");
        _listenHosts.add("localhost");
        String allowed = _context.getProperty(PROP_ALLOWED_HOSTS);
        if (allowed != null) {
            StringTokenizer tok = new StringTokenizer(allowed, " ,");
            while (tok.hasMoreTokens()) {
                _listenHosts.add(tok.nextToken());
            }
        }
    }
    
    /**
     *  Unused, we can't get here from RunStandalone
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
            host = getHost(host);
            String s = "Console request denied.\n" +
                       "    To allow access using the hostname \"" + host + "\", add the line \"" +
                       PROP_ALLOWED_HOSTS + '=' + host +
                       "\" in the file " + RunStandalone.APP_CONFIG_FILE.getAbsolutePath() + " and restart.";
            log.logAlways(Log.WARN, s);
            httpResponse.sendError(403, s);
            baseRequest.setHandled(true);
            return;
        }
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
        if (host.equals("127.0.0.1:8002") ||
            host.equals("localhost:8002") ||
            host.equals("[::1]:8002"))
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
}
