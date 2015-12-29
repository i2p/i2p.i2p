package net.i2p.router.update;

import java.io.File;
import java.net.URI;
import java.util.List;

import net.i2p.crypto.TrustedUpdate;
import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigUpdateHandler;
import static net.i2p.update.UpdateType.*;
import net.i2p.util.EepGet;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;

    
/**
 *  Eepget the .su3 file to the temp dir, then notify.
 *  ConsoleUpdateManager will do the rest.
 *
 *  @since 0.9.20
 */
class DevSU3UpdateRunner extends UpdateRunner {

    public DevSU3UpdateRunner(RouterContext ctx, ConsoleUpdateManager mgr, List<URI> uris) { 
        super(ctx, mgr, ROUTER_DEV_SU3, uris);
        if (!uris.isEmpty())
            _currentURI = uris.get(0);
    }

    /** Get the file */
    @Override
    protected void update() {
        // always proxy for now
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
            _mgr.notifyTaskFailed(this, msg, null);
            return;
        }
        String zipURL = _currentURI.toString();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Starting signed dev update URL: " + zipURL);
        try {
            // 40 retries!!
            _get = new EepGet(_context, proxyHost, proxyPort, 40, _updateFile, zipURL, false);
            _get.addStatusListener(DevSU3UpdateRunner.this);
            _get.fetch(CONNECT_TIMEOUT, -1, INACTIVITY_TIMEOUT);
        } catch (Throwable t) {
            _log.error("Error updating", t);
        }
        if (!this.done)
            _mgr.notifyTaskFailed(this, "", null);
    }
        
    /** eepget listener callback Overrides */
    @Override
    public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining,
                                 String url, String outputFile, boolean notModified) {
        File tmp = new File(_updateFile);
        // We use TrustedUpdate here to get the version without any su3 checks,
        // which will be done later.
        // Only gets 16 bytes max since we aren't using the SU3 version extraction.
        String version = TrustedUpdate.getVersionString(tmp);
        if (version.equals(""))
            version = "unknown";
        if (_mgr.notifyComplete(this, version, tmp))
            this.done = true;
        else
            tmp.delete();  // corrupt
    }
}
