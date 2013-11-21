package net.i2p.router.update;

import java.io.File;
import java.net.URI;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.router.util.RFC822Date;
import net.i2p.router.web.ConfigUpdateHandler;
import static net.i2p.update.UpdateType.*;
import net.i2p.util.EepGet;
import net.i2p.util.Log;

    
/**
 *  Eepget the .zip file to the temp dir, then notify.r
 *  Moved from UnsignedUpdateHandler and turned into an UpdateTask.
 *
 *  @since 0.9.4
 */
class UnsignedUpdateRunner extends UpdateRunner {

    public UnsignedUpdateRunner(RouterContext ctx, ConsoleUpdateManager mgr, List<URI> uris) { 
        super(ctx, mgr, ROUTER_UNSIGNED, uris);
        if (!uris.isEmpty())
            _currentURI = uris.get(0);
    }


        /** Get the file */
        @Override
        protected void update() {
            String zipURL = _currentURI.toString();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Starting unsigned update URL: " + zipURL);
            // always proxy for now
            //boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
            String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
            int proxyPort = ConfigUpdateHandler.proxyPort(_context);
            try {
                // 40 retries!!
                _get = new EepGet(_context, proxyHost, proxyPort, 40, _updateFile, zipURL, false);
                _get.addStatusListener(UnsignedUpdateRunner.this);
                _get.fetch(CONNECT_TIMEOUT, -1, INACTIVITY_TIMEOUT);
            } catch (Throwable t) {
                _log.error("Error updating", t);
            }
            if (!this.done)
                _mgr.notifyTaskFailed(this, "", null);
        }
        
        /** eepget listener callback Overrides */
        @Override
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            String lastmod = _get.getLastModified();
            File tmp = new File(_updateFile);
            long modtime = RFC822Date.parse822Date(lastmod);
            if (modtime <= 0)
                modtime = _context.clock().now();
            if (_mgr.notifyComplete(this, Long.toString(modtime), tmp))
                this.done = true;
            else
                tmp.delete();  // corrupt
        }
}
