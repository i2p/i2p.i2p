package net.i2p.servlet.filters;

import java.io.IOException;
import java.io.File;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.Callback;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKeyFile;
import net.i2p.util.Log;

/**
 * Adds a header, X-I2P-Location, to requests when they do **not** come in on an I2P hostname.
 * This header contains a URL that looks like: [scheme://][i2phostname.i2p][/path][?query]
 * and expresses the I2P-Equivalent URL of the clearnet query. Clients can use this to prompt
 * users to switch from a non-I2P host to an I2P host or to redirect them automatically. It
 * automatically enabled on the default I2P site located on port 7658 by default.
 *
 *  @since 0.9.51
 */
public class XI2PLocationFilter extends Handler.Wrapper {
    private static final long failTimeout = 600000;
    private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(XI2PLocationFilter.class);
    /**
     *  Resolved X-I2P-Location prefix per local listener, keyed by "address:port".
     *  A single shared field would let one listener's cached destination be served
     *  for a different listener; this keeps each endpoint's prefix separate.
     */
    private final ConcurrentMap<String, Location> _locations = new ConcurrentHashMap<String, Location>();

    /** Per-listener cache entry. */
    private static class Location {
        volatile String prefix;        // resolved spoofed .i2p host / b32, or null until found
        long lastFailure = -1;         // guarded by XI2PLocationFilter.this
    }

    private Location locationFor(String key) {
        Location loc = _locations.get(key);
        if (loc == null) {
            Location created = new Location();
            Location existing = _locations.putIfAbsent(key, created);
            loc = (existing != null) ? existing : created;
        }
        return loc;
    }

    private synchronized void setLocation(Location loc, String xi2plocation) {
        if (_log.shouldInfo())
            _log.info("Checking X-I2P-Location header prefix: " + xi2plocation);
        if (loc.prefix != null)
            return;
        if (xi2plocation == null)
            return;
        if (xi2plocation.equals(""))
            return;
        loc.prefix = xi2plocation;
        if (_log.shouldInfo())
            _log.info("Caching X-I2P-Location header prefix: " + loc.prefix);
    }

    private synchronized boolean shouldRecheck(Location loc) {
        boolean settable = (loc.prefix == null);
        if (!settable) return settable;
        if (loc.lastFailure == -1) {
            loc.lastFailure = System.currentTimeMillis();
            if (_log.shouldDebug())
                _log.debug("New instance, attempting to set X-I2P-Location header for the first time");
            return settable;
        }
        if ((System.currentTimeMillis() - loc.lastFailure) > failTimeout){
            loc.lastFailure = System.currentTimeMillis();
            if (_log.shouldDebug())
                _log.debug("More than ten minutes since failing attempt to re-check X-I2P-Location header");
            return settable;
        }
        if (_log.shouldDebug())
            _log.debug("Not attempting to re-check X-I2P-Location header");
        return false;
    }

    private String getXI2PLocation(String host, String port) {
        File configDir = I2PAppContext.getGlobalContext().getConfigDir();
        File tunnelConfig = new File(configDir, "i2ptunnel.config");
        boolean isSingleFile = tunnelConfig.exists();
        if (!isSingleFile) {
            if (host.startsWith("[") && host.endsWith("]"))
                host = host.substring(1, host.length() - 1);
            File tunnelConfigD = new File(configDir, "i2ptunnel.config.d");
            File[] configFiles = tunnelConfigD.listFiles(new net.i2p.util.FileSuffixFilter(".config"));
            if (configFiles == null)
                return null;
            for (int fnum=0; fnum < configFiles.length; fnum++) {
                Properties tunnelProps = new Properties();
                try {
                    DataHelper.loadProps(tunnelProps, configFiles[fnum]);
                    String targetHost = tunnelProps.getProperty("targetHost");
                    boolean hostmatch = (host.equals(targetHost) || "0.0.0.0".equals(targetHost) || "::".equals(targetHost));
                    if ( hostmatch && port.equals(tunnelProps.getProperty("targetPort")) ) {
                        String sh = tunnelProps.getProperty("spoofedHost");
                        if (sh != null) {
                            if (sh.endsWith(".i2p"))
                                return sh;
                        }
                        String kf = tunnelProps.getProperty("privKeyFile");
                        if (kf != null) {
                            File keyFile = new File(kf);
                            if (!keyFile.isAbsolute())
                                keyFile = new File(configDir, kf);
                            if (keyFile.exists()) {
                                PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
                                try {
                                    Destination rv = pkf.getDestination();
                                    if (rv != null)
                                        return rv.toBase32();
                                } catch (I2PException e) {
                                    if (_log.shouldWarn())
                                        _log.warn("I2PException Unable to set X-I2P-Location, keys arent ready. This is probably safe to ignore and will go away after the first run." + e);
                                    return null;
                                } catch (IOException e) {
                                    if (_log.shouldWarn())
                                        _log.warn("IOE Unable to set X-I2P-Location, location is uninitialized due file not found. This probably means the keys aren't ready. This is probably safe to ignore." + e);
                                    return null;
                                }
                            }
                        }
                        if (_log.shouldWarn())
                            _log.warn("Unable to set X-I2P-Location, location is target not found in any I2PTunnel config file. This should never happen.");
                        return null;
                    }
                } catch (IOException ioe) {
                    if (_log.shouldWarn())
                        _log.warn("IOE Unable to set X-I2P-Location, location is uninitialized. This is probably safe to ignore. location='" + ioe + "'");
                    return null;
                }
            }
        } else {
            // don't bother
        }
        return null;
    }

    /**
     *  @param prefix the cached per-listener location prefix, or null
     *  @return the value for the X-I2P-Location header, or null
     */
    private String headerContents(final Request request, String prefix) {
        HttpURI uri = request.getHttpURI();
        if (prefix != null) {
            String scheme = uri.getScheme();
            if (scheme == null)
                return null;
            // encoded
            String path = uri.getPath();
            if (path == null)
                path = "/";
            // encoded
            String query = uri.getQuery();
            int port = uri.getPort();
            StringBuilder buf = new StringBuilder(256);
            buf.append(scheme)
               .append("://")
               .append(prefix);
            if (port > 0)
                buf.append(':').append(port);
            buf.append(path);
            if (query != null)
                buf.append('?').append(query);
            return buf.toString();
        }
        return null;
    }

    @Override
    public boolean handle(final Request request, final Response response, Callback callback)
    throws Exception {
        final String hashHeader = request.getHeaders().get("X-I2P-DestHash");

        if (hashHeader == null) {
            String localAddr = Request.getLocalAddr(request);
            int localPort = Request.getLocalPort(request);
            Location loc = locationFor(localAddr + ':' + localPort);
            if (shouldRecheck(loc)) {
                String xi2plocation = getXI2PLocation(localAddr, String.valueOf(localPort));
                if (_log.shouldInfo())
                   _log.info("Checking X-I2P-Location header: IP " + localAddr + " port " + localPort + " prefix " + xi2plocation);
                if (xi2plocation != null)
                    setLocation(loc, xi2plocation);
            }
            String headerURL = headerContents(request, loc.prefix);
            if (headerURL != null) {
                if (_log.shouldInfo())
                    _log.info("Setting X-I2P-Location header: " + headerURL);
                response.getHeaders().put("X-I2P-Location", headerURL);
            }
        }

        return super.handle(request, response, callback);
    }
}
