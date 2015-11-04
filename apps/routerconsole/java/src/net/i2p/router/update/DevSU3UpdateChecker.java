package net.i2p.router.update;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.util.List;

import net.i2p.crypto.TrustedUpdate;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.update.*;
import net.i2p.util.Log;
import net.i2p.util.PartialEepGet;
import net.i2p.util.PortMapper;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Check for an updated su3 version.
 *
 * Take the update URL
 * then fetch the first 56 bytes of the URL, extract the version,
 * and compare to current full router version.
 *
 *  @since 0.9.20 from PluginUpdateChecker
 */
class DevSU3UpdateChecker extends UpdateRunner {

    public DevSU3UpdateChecker(RouterContext ctx, ConsoleUpdateManager mgr,
                               List<URI> uris) { 
        super(ctx, mgr, UpdateType.ROUTER_DEV_SU3, uris, RouterVersion.FULL_VERSION);
        if (!uris.isEmpty())
            _currentURI = uris.get(0);
    }

    @Override
    protected void update() {
        // must be set for super
        _isPartial = true;
        // use the same settings as for updater
        // always proxy, or else FIXME
        //boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
        String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
        int proxyPort = ConfigUpdateHandler.proxyPort(_context);
        if (proxyPort == ConfigUpdateHandler.DEFAULT_PROXY_PORT_INT &&
            proxyHost.equals(ConfigUpdateHandler.DEFAULT_PROXY_HOST) &&
            _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY) < 0) {
            String msg = _t("HTTP client proxy tunnel must be running");
            if (_log.shouldWarn())
                _log.warn(msg);
            updateStatus("<b>" + msg + "</b>");
            _mgr.notifyCheckComplete(this, false, false);
            return;
        }
        //updateStatus("<b>" + _t("Checking for development build update") + "</b>");
        _baos.reset();
        try {
            _get = new PartialEepGet(_context, proxyHost, proxyPort, _baos, _currentURI.toString(), TrustedUpdate.HEADER_BYTES);
            _get.addStatusListener(this);
            _get.fetch(CONNECT_TIMEOUT);
        } catch (Throwable t) {
            _log.error("Error fetching the update", t);
        }
    }
        
    @Override
    public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred,
                                 long bytesRemaining, String url) {
    }

    @Override
    public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining,
                                 String url, String outputFile, boolean notModified) {
        String newVersion = TrustedUpdate.getVersionString(new ByteArrayInputStream(_baos.toByteArray()));
        boolean newer = VersionComparator.comp(newVersion, RouterVersion.FULL_VERSION) > 0;
        if (newer) {
            if (SystemVersion.isJava7()) {
                _mgr.notifyVersionAvailable(this, _currentURI, UpdateType.ROUTER_DEV_SU3, "", UpdateMethod.HTTP,
                                        _urls, newVersion, RouterVersion.FULL_VERSION);
            } else {
                String ourJava = System.getProperty("java.version");
                String msg = _mgr._t("Requires Java version {0} but installed Java version is {1}", "1.7", ourJava);
                _log.logAlways(Log.WARN, "Cannot update to version " + newVersion + ": " + msg);
                _mgr.notifyVersionConstraint(this, _currentURI, UpdateType.ROUTER_DEV_SU3, "", newVersion, msg);
            }
        } else {
            //updateStatus("<b>" + _t("No new version found at {0}", linkify(url)) + "</b>");
            if (_log.shouldWarn())
                _log.warn("Found old version \"" + newVersion + "\" at " + url);
        }
        _mgr.notifyCheckComplete(this, newer, true);
    }

    @Override
    public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
        File f = new File(_updateFile);
        f.delete();
        _mgr.notifyCheckComplete(this, false, false);
    }
}
    
