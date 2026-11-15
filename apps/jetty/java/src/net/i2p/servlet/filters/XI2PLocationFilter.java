package net.i2p.servlet.filters;

import java.io.IOException;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * Advertises the I2P equivalent of a direct request. Configuration and private
 * key reads run on one bounded background worker, never on an HTTP worker.
 */
public class XI2PLocationFilter extends Handler.Wrapper {
    private static final long RECHECK_MILLIS = 10 * 60 * 1000;
    private final I2PAppContext _context;
    private final Log _log;
    private final ConcurrentMap<String, Location> _locations = new ConcurrentHashMap<String, Location>();
    private volatile ThreadPoolExecutor _lookup;

    private static class Location {
        final AtomicBoolean checking = new AtomicBoolean();
        volatile String host;
        volatile long nextCheck;
    }

    public XI2PLocationFilter() { this(I2PAppContext.getGlobalContext()); }

    public XI2PLocationFilter(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(XI2PLocationFilter.class);
    }

    @Override
    protected void doStart() throws Exception {
        _locations.clear();
        ThreadPoolExecutor lookup = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(4), task -> {
                Thread thread = new Thread(task, "I2P site metadata lookup");
                thread.setDaemon(true);
                return thread;
            });
        _lookup = lookup;
        try {
            super.doStart();
        } catch (Exception failure) {
            _lookup = null;
            lookup.shutdownNow();
            throw failure;
        }
    }

    @Override
    protected void doStop() throws Exception {
        ThreadPoolExecutor lookup = _lookup;
        _lookup = null;
        if (lookup != null)
            lookup.shutdownNow();
        _locations.clear();
        super.doStop();
    }

    private void refresh(Location location, String host, int port) {
        ThreadPoolExecutor lookup = _lookup;
        long now = System.currentTimeMillis();
        if (lookup == null || now < location.nextCheck || !location.checking.compareAndSet(false, true))
            return;
        location.nextCheck = now + RECHECK_MILLIS;
        try {
            lookup.execute(() -> {
                try {
                    String name = getXI2PLocation(host, Integer.toString(port));
                    location.host = validHost(name) ? name.toLowerCase(Locale.ROOT) : null;
                } catch (RuntimeException failure) {
                    if (_log.shouldWarn()) _log.warn("Unable to refresh I2P site metadata", failure);
                } finally {
                    location.checking.set(false);
                }
            });
        } catch (RejectedExecutionException busyOrStopping) {
            location.nextCheck = now + 1000;
            location.checking.set(false);
        }
    }

    private static boolean validHost(String name) {
        if (name == null || name.length() > 253 || !name.toLowerCase(Locale.ROOT).endsWith(".i2p"))
            return false;
        try {
            URI uri = new URI("http://" + name);
            return name.equalsIgnoreCase(uri.getHost()) && uri.getRawUserInfo() == null &&
                   uri.getPort() == -1 && "".equals(uri.getRawPath()) &&
                   uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException invalid) { return false; }
    }

    protected String getXI2PLocation(String host, String port) {
        if (host == null)
            return null;
        File configDir = _context.getConfigDir();
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

    private static String headerContents(Request request, String location) {
        HttpURI uri = request.getHttpURI();
        String path = uri.getPath();
        StringBuilder value = new StringBuilder(256);
        // A backend's local port is not the public I2P service port.
        value.append(request.isSecure() ? "https://" : "http://").append(location)
             .append(path == null ? "/" : path);
        String query = uri.getQuery();
        if (query != null) value.append('?').append(query);
        return value.toString();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        if (request.getHeaders().get("X-I2P-DestHash") == null) {
            String host = Request.getLocalAddr(request);
            int port = Request.getLocalPort(request);
            String key = host + ":" + port;
            Location location = _locations.computeIfAbsent(key, ignored -> new Location());
            refresh(location, host, port);
            String name = location.host;
            if (name != null)
                response.getHeaders().put("X-I2P-Location", headerContents(request, name));
        }
        return super.handle(request, response, callback);
    }
}
