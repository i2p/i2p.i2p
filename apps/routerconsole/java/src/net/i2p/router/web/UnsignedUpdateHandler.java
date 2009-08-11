package net.i2p.router.web;

import java.io.File;

import net.i2p.I2PAppContext;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * <p>Handles the request to update the router by firing off an
 * {@link net.i2p.util.EepGet} call to download the latest unsigned zip file
 * and displaying the status to anyone who asks.
 * </p>
 * <p>After the download completes the signed update file is copied to the
 * router directory, and if configured the router is restarted to complete
 * the update process.
 * </p>
 */
public class UnsignedUpdateHandler extends UpdateHandler {
    private String _zipURL;
    private String _zipVersion;

    public UnsignedUpdateHandler(RouterContext ctx, String zipURL, String version) {
        super(ctx);
        _zipURL = zipURL;
        _zipVersion = version;
        _updateFile = (new File(ctx.getTempDir(), "tmp" + ctx.random().nextInt() + Router.UPDATE_FILE)).getAbsolutePath();
    }
    
    @Override
    public void update() {
        // don't block waiting for the other one to finish
        if ("true".equals(System.getProperty(PROP_UPDATE_IN_PROGRESS, "false"))) {
            _log.error("Update already running");
            return;
        }
        synchronized (UpdateHandler.class) {
            if (_updateRunner == null) {
                _updateRunner = new UnsignedUpdateRunner();
            }
            if (_updateRunner.isRunning()) {
                return;
            } else {
                System.setProperty(PROP_UPDATE_IN_PROGRESS, "true");
                I2PAppThread update = new I2PAppThread(_updateRunner, "Update");
                update.start();
            }
        }
    }
    
    /**
     *  Eepget the .zip file to the temp dir, then copy it over
     */
    public class UnsignedUpdateRunner extends UpdateRunner implements Runnable, EepGet.StatusListener {
        public UnsignedUpdateRunner() { 
            super();
        }

        /** Get the file */
        @Override
        protected void update() {
            _status = "<b>Updating</b>";
            // always proxy for now
            //boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
            String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
            int proxyPort = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_PORT, ConfigUpdateHandler.DEFAULT_PROXY_PORT_INT);
            try {
                // 40 retries!!
                _get = new EepGet(_context, proxyHost, proxyPort, 40, _updateFile, _zipURL, false);
                _get.addStatusListener(UnsignedUpdateRunner.this);
                _get.fetch();
            } catch (Throwable t) {
                _context.logManager().getLog(UpdateHandler.class).error("Error updating", t);
            }
        }
        
        /** eepget listener callback Overrides */
        @Override
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            _status = "<b>Update downloaded</b>";
            // we should really verify zipfile integrity here but there's no easy way to do it
            String to = (new File(_context.getBaseDir(), Router.UPDATE_FILE)).getAbsolutePath();
            boolean copied = FileUtil.copy(_updateFile, to, true);
            if (copied) {
                (new File(_updateFile)).delete();
                String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY);
                this.done = true;
                String lastmod = _get.getLastModified();
                long modtime = 0;
                if (lastmod != null)
                    modtime = NewsFetcher.parse822Date(lastmod);
                if (modtime <= 0)
                    modtime = _context.clock().now();
                _context.router().setConfigSetting(PROP_LAST_UPDATE_TIME, "" + modtime);
                _context.router().saveConfig();
                if ("install".equals(policy)) {
                    _log.log(Log.CRIT, "Update was downloaded, restarting to install it");
                    _status = "<b>Update downloaded</b><br />Restarting";
                    restart();
                } else {
                    _log.log(Log.CRIT, "Update was downloaded, will be installed at next restart");
                    _status = "<b>Update downloaded</b><br />";
                    if (System.getProperty("wrapper.version") != null)
                        _status += "Click Restart to install";
                    else
                        _status += "Click Shutdown and restart to install";
                    _status += " Version " + _zipVersion;
                }
            } else {
                _log.log(Log.CRIT, "Failed copy to " + to);
                _status = "<b>Failed copy to " + to + "</b>";
            }
        }
    }
}
