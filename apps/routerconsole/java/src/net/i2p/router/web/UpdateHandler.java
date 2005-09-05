package net.i2p.router.web;

import java.io.File;
import java.text.DecimalFormat;

import net.i2p.I2PAppContext;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.util.EepGet;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * <p>Handles the request to update the router by firing off an
 * {@link net.i2p.util.EepGet} call to download the latest signed update file
 * and displaying the status to anyone who asks.
 * </p>
 * <p>After the download completes the signed update file is verified with
 * {@link net.i2p.crypto.TrustedUpdate}, and if it's authentic the payload
 * of the signed update file is unpacked and the router is restarted to complete
 * the update process.
 * </p>
 */
public class UpdateHandler {
    private static UpdateRunner _updateRunner;
    private RouterContext _context;
    private Log _log;
    private DecimalFormat _pct = new DecimalFormat("00.0%");
    
    private static final String SIGNED_UPDATE_FILE = "i2pupdate.sud";

    public UpdateHandler() {}
    public UpdateHandler(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(UpdateHandler.class);
    }
    
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
            _log = _context.logManager().getLog(UpdateHandler.class);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    
    public void setUpdateNonce(String nonce) { 
        if (nonce == null) return;
        if (nonce.equals(System.getProperty("net.i2p.router.web.UpdateHandler.nonce")) ||
            nonce.equals(System.getProperty("net.i2p.router.web.UpdateHandler.noncePrev"))) {
            update();
        }
    }

    public void update() {
        synchronized (UpdateHandler.class) {
            if (_updateRunner == null)
                _updateRunner = new UpdateRunner();
            if (_updateRunner.isRunning()) {
                return;
            } else {
                System.setProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "true");
                I2PThread update = new I2PThread(_updateRunner, "Update");
                update.start();
            }
        }

    }
    
    public String getStatus() {
        return _updateRunner.getStatus();
    }
    
    public class UpdateRunner implements Runnable, EepGet.StatusListener {
        private boolean _isRunning;
        private String _status;
        private long _startedOn;
        private long _written;
        public UpdateRunner() { 
            _isRunning = false; 
            _status = "<b>Updating</b><br />";
        }
        public boolean isRunning() { return _isRunning; }
        public String getStatus() { return _status; }
        public void run() {
            _isRunning = true;
            update();
            System.setProperty("net.i2p.router.web.ReseedHandler.updateInProgress", "false");
            _isRunning = false;
        }
        private void update() {
            _startedOn = -1;
            _status = "<b>Updating</b><br />";
            String updateURL = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_URL, ConfigUpdateHandler.DEFAULT_UPDATE_URL);
            boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
            String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
            String port = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_PORT, ConfigUpdateHandler.DEFAULT_PROXY_PORT);
            int proxyPort = -1;
            try {
                proxyPort = Integer.parseInt(port);
            } catch (NumberFormatException nfe) {
                System.setProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "false");
                return;
            }
            try {
                EepGet get = null;
                if (shouldProxy)
                    get = new EepGet(_context, proxyHost, proxyPort, 10, SIGNED_UPDATE_FILE, updateURL, false);
                else
                    get = new EepGet(_context, 10, SIGNED_UPDATE_FILE, updateURL, false);
                get.addStatusListener(UpdateRunner.this);
                _startedOn = _context.clock().now();
                get.fetch();
            } catch (Throwable t) {
                _context.logManager().getLog(UpdateHandler.class).error("Error updating", t);
                System.setProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "false");
            }
        }
        
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Attempt failed on " + url, cause);
            _written = 0;
            // ignored
        }
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
            _written += currentWrite;
            StringBuffer buf = new StringBuffer(64);
            buf.append("<b>Updating</b> ");
            double pct = ((double)alreadyTransferred + (double)_written) / ((double)alreadyTransferred + (double)bytesRemaining);
            synchronized (_pct) {
                buf.append(_pct.format(pct));
            }
            buf.append(":<br />\n").append(_written+alreadyTransferred);
            buf.append(" transferred<br />");
            _status = buf.toString();
        }
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile) {
            _status = "<b>Update downloaded</b><br />";
            TrustedUpdate up = new TrustedUpdate(_context);
            boolean ok = up.migrateVerified(RouterVersion.VERSION, SIGNED_UPDATE_FILE, "i2pupdate.zip");
            File f = new File(SIGNED_UPDATE_FILE);
            f.delete();
            if (ok) {
                _log.log(Log.CRIT, "Update was VERIFIED, restarting to install it");
                _status = "<b>Update verified</b><br />Restarting<br />";
                restart();
            } else {
                _log.log(Log.CRIT, "Update was INVALID - have you changed your keys?");
                System.setProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "false");
            }
        }
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            _log.log(Log.CRIT, "Update did not download completely (" + bytesTransferred + " with " 
                               + bytesRemaining + " after " + currentAttempt + " tries)");

            _status = "<b>Transfer failed</b><br />";
            System.setProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "false");
        }
        public void headerReceived(String url, int attemptNum, String key, String val) {}
    }
    
    private void restart() {
        _context.router().addShutdownTask(new ConfigServiceHandler.UpdateWrapperManagerTask(Router.EXIT_GRACEFUL_RESTART));
        _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
    }
}
