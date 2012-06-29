package net.i2p.router.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.Properties;

import net.i2p.crypto.TrustedUpdate;
import net.i2p.router.RouterContext;
import net.i2p.util.EepGet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.PartialEepGet;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;
import net.i2p.util.VersionComparator;

/**
 * Check for an updated version of a plugin.
 * A plugin is a standard .sud file with a 40-byte signature,
 * a 16-byte version, and a .zip file.
 *
 * So we get the current version and update URL for the installed plugin,
 * then fetch the first 56 bytes of the URL, extract the version,
 * and compare.
 *
 * @since 0.7.12
 * @author zzz
 */
public class PluginUpdateChecker extends UpdateHandler {
    private static PluginUpdateCheckerRunner _pluginUpdateCheckerRunner;
    private String _appName;
    private String _oldVersion;
    private String _xpi2pURL;
    private volatile boolean _isNewerAvailable;

    private static PluginUpdateChecker _instance;
    public static final synchronized PluginUpdateChecker getInstance(RouterContext ctx) { 
        if (_instance != null)
            return _instance;
        _instance = new PluginUpdateChecker(ctx);
        return _instance;
    }

    private PluginUpdateChecker(RouterContext ctx) {
        super(ctx);
    }
    
    /**
     *  check all plugins
     *  @deprecated not finished
     */
    public void update() {
        Thread t = new I2PAppThread(new AllCheckerRunner(), "AllAppChecker", true);
        t.start();
    }

    /**
     *  check all plugins
     *  @deprecated not finished
     */
    public class AllCheckerRunner implements Runnable {
        public void run() {
            List<String> plugins = PluginStarter.getPlugins();
            // TODO
        }
    }

    /** check a single plugin */
    public void update(String appName) {
        // don't block waiting for the other one to finish
        if ("true".equals(System.getProperty(PROP_UPDATE_IN_PROGRESS))) {
            _log.error("Update already running");
            return;
        }
        synchronized (UpdateHandler.class) {
            Properties props = PluginStarter.pluginProperties(_context, appName);
            String oldVersion = props.getProperty("version");
            String xpi2pURL = props.getProperty("updateURL");
            if (oldVersion == null || xpi2pURL == null) {
                updateStatus("<b>" + _("Cannot check, plugin {0} is not installed", appName) + "</b>");
                return;
            }

            if (_pluginUpdateCheckerRunner == null)
                _pluginUpdateCheckerRunner = new PluginUpdateCheckerRunner();
            if (_pluginUpdateCheckerRunner.isRunning())
                return;
            _xpi2pURL = xpi2pURL;
            _appName = appName;
            _oldVersion = oldVersion;
            _isNewerAvailable = false;
            System.setProperty(PROP_UPDATE_IN_PROGRESS, "true");
            I2PAppThread update = new I2PAppThread(_pluginUpdateCheckerRunner, "AppChecker", true);
            update.start();
        }
    }
    
    /** @since 0.8.13 */
    public void setAppStatus(String status) {
        updateStatus(status);
    }
    
    /** @since 0.8.13 */
    public void setDoneStatus(String status) {
        updateStatus(status);
        scheduleStatusClean(status);
    }

    public boolean isRunning() {
        return _pluginUpdateCheckerRunner != null && _pluginUpdateCheckerRunner.isRunning();
    }
    
    @Override
    public boolean isDone() {
        // FIXME
        return false;
    }
    
    /** @since 0.8.13 */
    public boolean isNewerAvailable() {
        return _isNewerAvailable;
    }

    private void scheduleStatusClean(String msg) {
        SimpleScheduler.getInstance().addEvent(new Cleaner(msg), 20*60*1000);
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        private final String _msg;
        public Cleaner(String msg) {
            _msg = msg;
        }
        public void timeReached() {
            if (_msg.equals(getStatus()))
                updateStatus("");
        }
    }

    public class PluginUpdateCheckerRunner extends UpdateRunner implements Runnable, EepGet.StatusListener {
        ByteArrayOutputStream _baos;

        public PluginUpdateCheckerRunner() { 
            super();
            _baos = new ByteArrayOutputStream(TrustedUpdate.HEADER_BYTES);
        }

        @Override
        protected void update() {
            _isNewerAvailable = false;
            updateStatus("<b>" + _("Checking for update of plugin {0}", _appName) + "</b>");
            // use the same settings as for updater
            // always proxy, or else FIXME
            //boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
            String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
            int proxyPort = ConfigUpdateHandler.proxyPort(_context);
            _baos.reset();
            try {
                _get = new PartialEepGet(_context, proxyHost, proxyPort, _baos, _xpi2pURL, TrustedUpdate.HEADER_BYTES);
                _get.addStatusListener(PluginUpdateCheckerRunner.this);
                _get.fetch(CONNECT_TIMEOUT);
            } catch (Throwable t) {
                _log.error("Error checking update for plugin", t);
            }
        }
        
        public boolean isNewerAvailable() {
            return _isNewerAvailable;
        }

        @Override
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
        }

        @Override
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            String newVersion = TrustedUpdate.getVersionString(new ByteArrayInputStream(_baos.toByteArray()));
            boolean newer = (new VersionComparator()).compare(newVersion, _oldVersion) > 0;
            String msg;
            if (newer) {
                msg = "<b>" + _("New plugin version {0} is available", newVersion) + "</b>";
                _isNewerAvailable = true;
            } else {
                msg = "<b>" + _("No new version is available for plugin {0}", _appName) + "</b>";
            }
            updateStatus(msg);
            scheduleStatusClean(msg);
        }

        @Override
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            File f = new File(_updateFile);
            f.delete();
            String msg = "<b>" + _("Update check failed for plugin {0}", _appName) + "</b>";
            updateStatus(msg);
            scheduleStatusClean(msg);
        }
    }
}
    
