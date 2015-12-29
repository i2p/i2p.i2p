package net.i2p.router.update;

import java.io.File;
import java.net.URI;
import java.util.List;

import net.i2p.crypto.TrustedUpdate;
import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.update.*;
import net.i2p.util.PartialEepGet;
import net.i2p.util.PortMapper;

/**
 * Check for an updated version of a plugin.
 * A plugin is a standard .sud file with a 40-byte signature,
 * a 16-byte version, and a .zip file.
 *
 * So we get the current version and update URL for the installed plugin,
 * then fetch the first 56 bytes of the URL, extract the version,
 * and compare.
 *
 *  Moved from web/ and turned into an UpdateTask.
 *
 *  @since 0.7.12
 */
class PluginUpdateChecker extends UpdateRunner {
    private final String _appName;
    private final String _oldVersion;

    public PluginUpdateChecker(RouterContext ctx, ConsoleUpdateManager mgr,
                               List<URI> uris, String appName, String oldVersion ) { 
        super(ctx, mgr, UpdateType.PLUGIN, uris, oldVersion);
        if (!uris.isEmpty())
            _currentURI = uris.get(0);
        _appName = appName;
        _oldVersion = oldVersion;
    }

    @Override
    public String getID() { return _appName; }
    
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
            updateStatus("<b>" + _t("Checking for update of plugin {0}", _appName) + "</b>");
            _baos.reset();
            try {
                _get = new PartialEepGet(_context, proxyHost, proxyPort, _baos, _currentURI.toString(), TrustedUpdate.HEADER_BYTES);
                _get.addStatusListener(this);
                _get.fetch(CONNECT_TIMEOUT);
            } catch (Throwable t) {
                _log.error("Error checking update for plugin", t);
            }
        }
        
        @Override
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
        }

        @Override
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining,
                                     String url, String outputFile, boolean notModified) {
            super.transferComplete(alreadyTransferred, bytesTransferred, bytesRemaining,
                                   url, outputFile, notModified);
            // super sets _newVersion if newer
            boolean newer = _newVersion != null;
            if (newer) {
                _mgr.notifyVersionAvailable(this, _currentURI, UpdateType.PLUGIN, _appName, UpdateMethod.HTTP,
                                            _urls, _newVersion, _oldVersion);
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
    
