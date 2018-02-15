package net.i2p.router.update;

import java.net.URI;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.update.*;
import net.i2p.util.EepHead;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;
import net.i2p.util.RFC822Date;
import net.i2p.util.SystemVersion;

/**
 *  Does a simple EepHead to get the last-modified header.
 *  Moved from NewsFetcher and turned into an UpdateTask.
 *
 *  Overrides UpdateRunner for convenience, does not use super's Eepget StatusListener
 *
 *  @since 0.9.4
 */
class UnsignedUpdateChecker extends UpdateRunner {
    private final long _ms;
    private boolean _unsignedUpdateAvailable;

    public UnsignedUpdateChecker(RouterContext ctx, ConsoleUpdateManager mgr,
                                 List<URI> uris, long lastUpdateTime) { 
        super(ctx, mgr, UpdateType.ROUTER_UNSIGNED, uris);
        _ms = lastUpdateTime;
    }

    @Override
    public void run() {
        _isRunning = true;
        boolean success = false;
        try {
            success = fetchUnsignedHead();
        } catch (Throwable t) {
            _mgr.notifyTaskFailed(this, "", t);
        } finally {
            _mgr.notifyCheckComplete(this, _unsignedUpdateAvailable, success);
            _isRunning = false;
        }
    }


    /**
     * HEAD the update url, and if the last-mod time is newer than the last update we
     * downloaded, as stored in the properties, then we download it using eepget.
     */
    private boolean fetchUnsignedHead() {
        if (_urls.isEmpty())
            return false;
        _currentURI = _urls.get(0);
        String url = _currentURI.toString();
        // assume always proxied for now
        //boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
        String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
        int proxyPort = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_PORT, ConfigUpdateHandler.DEFAULT_PROXY_PORT_INT);
        if (proxyPort == ConfigUpdateHandler.DEFAULT_PROXY_PORT_INT &&
            proxyHost.equals(ConfigUpdateHandler.DEFAULT_PROXY_HOST) &&
            _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY) < 0) {
            String msg = _t("HTTP client proxy tunnel must be running");
            if (_log.shouldWarn())
                _log.warn(msg);
            updateStatus("<b>" + msg + "</b>");
            return false;
        }

        //updateStatus("<b>" + _t("Checking for development build update") + "</b>");
        try {
            EepHead get = new EepHead(_context, proxyHost, proxyPort, 0, url);
            if (get.fetch()) {
                String lastmod = get.getLastModified();
                if (lastmod != null) {
                    long modtime = RFC822Date.parse822Date(lastmod);
                    if (modtime <= 0) return false;
                    if (_ms <= 0) return false;
                    if (modtime > _ms) {
                        String newVersion = Long.toString(modtime);
                        if (SystemVersion.isJava7()) {
                            _unsignedUpdateAvailable = true;
                            _mgr.notifyVersionAvailable(this, _urls.get(0), getType(), "", getMethod(), _urls,
                                                        newVersion, "");
                        } else {
                            String ourJava = System.getProperty("java.version");
                            String msg = _mgr._t("Requires Java version {0} but installed Java version is {1}", "1.7", ourJava);
                            _log.logAlways(Log.WARN, "Cannot update to version " + newVersion + ": " + msg);
                            _mgr.notifyVersionConstraint(this, _urls.get(0), getType(), "", newVersion, msg);
                        }
                    }
                }
                return true;
            }
        } catch (Throwable t) {
            _log.error("Error fetching the update", t);
        }
        return false;
    }
}
