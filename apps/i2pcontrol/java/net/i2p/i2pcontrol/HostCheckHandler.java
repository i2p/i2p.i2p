package net.i2p.i2pcontrol;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

import net.i2p.apache.http.conn.util.InetAddressUtils;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Block certain Host headers to prevent DNS rebinding attacks.
 *
 * This Handler wraps the ContextHandlerCollection, which handles
 * all the webapps (not just routerconsole).
 * Therefore, this protects all the webapps.
 *
 * This class is NOT used for the webapp or the bare ServerSocket implementation.
 *
 * @since 0.12 copied from routerconsole
 */
public class HostCheckHandler extends Handler.Wrapper
{
    private final I2PAppContext _context;
    private final Set<String> _listenHosts;

    /**
     *  MUST call setListenHosts() afterwards.
     */
    public HostCheckHandler(I2PAppContext ctx) {
        super();
        _context = ctx;
        _listenHosts = new HashSet<String>(8);
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
     *  Block by Host header, pass everything else to the delegate.
     */
    @Override
    public boolean handle(Request request,
                          Response response,
                          Callback callback)
         throws Exception
    {
        String host = request.getHeaders().get("Host");
        if (!allowHost(host)) {
            Log log = _context.logManager().getLog(HostCheckHandler.class);
            host = DataHelper.stripHTML(getHost(host));
            String s = "Console request denied.\n" +
                       "    To allow access using the hostname \"" + host + "\", add the line \"" +
                       I2PControlController.PROP_ALLOWED_HOSTS + '=' + host +
                       "\" to I2PControl.conf and restart.";
            log.logAlways(Log.WARN, s);
            Response.writeError(request, response, callback, 403, s);
            callback.succeeded();
            return true;
        }

        return super.handle(request, response, callback);
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
        if (host.equals("127.0.0.1:7650") ||
            host.equals("localhost:7650"))
            return true;
        // all allowed?
        if (_listenHosts.isEmpty())
            return true;
        host = getHost(host);
        if (_listenHosts.contains(host))
            return true;
        // allow all IP addresses
        if (InetAddressUtils.isIPv4Address(host) || InetAddressUtils.isIPv6Address(host))
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
