package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import net.i2p.CoreVersion;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFile;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;
import net.i2p.util.VersionComparator;

/**
 * Download and install a plugin.
 * A plugin is a standard .sud file with a 40-byte signature,
 * a 16-byte version, and a .zip file.
 * Unlike for router updates, we need not have the public key
 * for the signature in advance.
 *
 * The zip file must have a standard directory layout, with
 * a plugin.config file at the top level.
 * The config file contains properties for the package name, version,
 * signing public key, and other settings.
 * The zip file will typically contain a webapps/ or lib/ dir,
 * and a webapps.config and/or clients.config file.
 *
 * @since 0.7.12
 * @author zzz
 */
public class PluginUpdateHandler extends UpdateHandler {
    private static PluginUpdateRunner _pluginUpdateRunner;
    private String _xpi2pURL;
    private String _appStatus;
    private volatile boolean _updated;

    private static final String XPI2P = "app.xpi2p";
    private static final String ZIP = XPI2P + ".zip";
    public static final String PLUGIN_DIR = "plugins";

    private static PluginUpdateHandler _instance;
    public static final synchronized PluginUpdateHandler getInstance(RouterContext ctx) {
        if (_instance != null)
            return _instance;
        _instance = new PluginUpdateHandler(ctx);
        return _instance;
    }

    private PluginUpdateHandler(RouterContext ctx) {
        super(ctx);
        _appStatus = "";
    }

    public void update(String xpi2pURL) {
        // don't block waiting for the other one to finish
        if ("true".equals(System.getProperty(PROP_UPDATE_IN_PROGRESS))) {
            _log.error("Update already running");
            return;
        }
        synchronized (UpdateHandler.class) {
            if (_pluginUpdateRunner == null)
                _pluginUpdateRunner = new PluginUpdateRunner(_xpi2pURL);
            if (_pluginUpdateRunner.isRunning())
                return;
            _xpi2pURL = xpi2pURL;
            _updateFile = (new File(_context.getTempDir(), "tmp" + _context.random().nextInt() + XPI2P)).getAbsolutePath();
            System.setProperty(PROP_UPDATE_IN_PROGRESS, "true");
            I2PAppThread update = new I2PAppThread(_pluginUpdateRunner, "AppDownload");
            update.start();
        }
    }

    public String getAppStatus() {
        return _appStatus;
    }

    public boolean isRunning() {
        return _pluginUpdateRunner != null && _pluginUpdateRunner.isRunning();
    }

    @Override
    public boolean isDone() {
        // FIXME
        return false;
    }

    /** @since 0.8.13 */
    public boolean wasUpdateSuccessful() {
        return _updated;
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

    public class PluginUpdateRunner extends UpdateRunner implements Runnable, EepGet.StatusListener {

        public PluginUpdateRunner(String url) {
            super();
        }

        @Override
        protected void update() {
            _updated = false;
            if(_xpi2pURL.startsWith("file://")) {
                updateStatus("<b>" + _("Attempting to copy plugin from {0}", _xpi2pURL) + "</b>");
                // strip off "file://"
                String xpi2pfile = _xpi2pURL.substring(7);
                if(xpi2pfile.length() == 0) { // This is actually what String.isEmpty() does, so it should be safe.
                        statusDone("<b>" + _("No file specified {0}", _xpi2pURL) + "</b>");
                } else {
                    // copy the contents of from to _updateFile
                    long alreadyTransferred = (new File(xpi2pfile)).getAbsoluteFile().length();
                    if(FileUtil.copy((new File(xpi2pfile)).getAbsolutePath(), _updateFile, true, false)) {
                        transferComplete(alreadyTransferred, alreadyTransferred, 0L, _xpi2pURL, _updateFile, false);
                    } else {
                        statusDone("<b>" + _("Failed to copy file {0}", _xpi2pURL) + "</b>");
                    }
                }
            } else {
                updateStatus("<b>" + _("Downloading plugin from {0}", _xpi2pURL) + "</b>");
                // use the same settings as for updater
                boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
                String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
                int proxyPort = ConfigUpdateHandler.proxyPort(_context);
                try {
                    if (shouldProxy)
                        // 10 retries!!
                        _get = new EepGet(_context, proxyHost, proxyPort, 10, _updateFile, _xpi2pURL, false);
                    else
                        _get = new EepGet(_context, 1, _updateFile, _xpi2pURL, false);
                    _get.addStatusListener(PluginUpdateRunner.this);
                    _get.fetch();
                } catch (Throwable t) {
                    _log.error("Error downloading plugin", t);
                }
            }
        }

        @Override
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
            StringBuilder buf = new StringBuilder(64);
            buf.append("<b>").append(_("Downloading plugin")).append(' ');
            double pct = ((double)alreadyTransferred + (double)currentWrite) /
                         ((double)alreadyTransferred + (double)currentWrite + (double)bytesRemaining);
            synchronized (_pct) {
                buf.append(_pct.format(pct));
            }
            buf.append(": ");
            buf.append(_("{0}B transferred", DataHelper.formatSize2(currentWrite + alreadyTransferred)));
            buf.append("</b>");
            updateStatus(buf.toString());
        }

        @Override
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            boolean update = false;
            updateStatus("<b>" + _("Plugin downloaded") + "</b>");
            File f = new File(_updateFile);
            File appDir = new SecureDirectory(_context.getConfigDir(), PLUGIN_DIR);
            if ((!appDir.exists()) && (!appDir.mkdir())) {
                f.delete();
                statusDone("<b>" + _("Cannot create plugin directory {0}", appDir.getAbsolutePath()) + "</b>");
                return;
            }

            TrustedUpdate up = new TrustedUpdate(_context);
            File to = new File(_context.getTempDir(), "tmp" + _context.random().nextInt() + ZIP);
            // extract to a zip file whether the sig is good or not, so we can get the properties file
            String err = up.migrateFile(f, to);
            if (err != null) {
                statusDone("<b>" + err + ' ' + _("from {0}", url) + " </b>");
                f.delete();
                to.delete();
                return;
            }
            File tempDir = new File(_context.getTempDir(), "tmp" + _context.random().nextInt() + "-unzip");
            if (!FileUtil.extractZip(to, tempDir)) {
                f.delete();
                to.delete();
                FileUtil.rmdir(tempDir, false);
                statusDone("<b>" + _("Plugin from {0} is corrupt", url) + "</b>");
                return;
            }
            File installProps = new File(tempDir, "plugin.config");
            Properties props = new OrderedProperties();
            try {
                DataHelper.loadProps(props, installProps);
            } catch (IOException ioe) {
                f.delete();
                to.delete();
                FileUtil.rmdir(tempDir, false);
                statusDone("<b>" + _("Plugin from {0} does not contain the required configuration file", url) + "</b>");
                return;
            }
            // we don't need this anymore, we will unzip again
            FileUtil.rmdir(tempDir, false);

            // ok, now we check sigs and deal with a bad sig
            String pubkey = props.getProperty("key");
            String signer = props.getProperty("signer");
            if (pubkey == null || signer == null || pubkey.length() != 172 || signer.length() <= 0) {
                f.delete();
                to.delete();
                //updateStatus("<b>" + "Plugin contains an invalid key" + ' ' + pubkey + ' ' + signer + "</b>");
                statusDone("<b>" + _("Plugin from {0} contains an invalid key", url) + "</b>");
                return;
            }

            // add all existing plugin keys, so any conflicts with existing keys
            // will be discovered and rejected
            Map<String, String> existingKeys = PluginStarter.getPluginKeys(_context);
            for (Map.Entry<String, String> e : existingKeys.entrySet()) {
                // ignore dups/bad keys
                up.addKey(e.getKey(), e.getValue());
            }

            if (up.haveKey(pubkey)) {
                // the key is already in the TrustedUpdate keyring
                // verify the sig and verify that it is signed by the signer in the plugin.config file
                // Allow "" as the previously-known signer
                String signingKeyName = up.verifyAndGetSigner(f);
                if (!(signer.equals(signingKeyName) || "".equals(signingKeyName))) {
                    f.delete();
                    to.delete();
                    if (signingKeyName == null)
                        _log.error("Failed to verify plugin signature, corrupt plugin or bad signature, signed by: " + signer);
                    else
                        _log.error("Plugin signer \"" + signer + "\" does not match existing signer in plugin.config file \"" + signingKeyName + "\"");
                    statusDone("<b>" + _("Plugin signature verification of {0} failed", url) + "</b>");
                    return;
                }
            } else {
                // add to keyring...
                if(!up.addKey(pubkey, signer)) {
                    // bad or duplicate key
                    f.delete();
                    to.delete();
                    _log.error("Bad key or key mismatch - Failed to add plugin key \"" + pubkey + "\" for plugin signer \"" + signer + "\"");
                    statusDone("<b>" + _("Plugin signature verification of {0} failed", url) + "</b>");
                    return;
                }
                // ...and try the verify again
                // verify the sig and verify that it is signed by the signer in the plugin.config file
                String signingKeyName = up.verifyAndGetSigner(f);
                if (!signer.equals(signingKeyName)) {
                    f.delete();
                    to.delete();
                    if (signingKeyName == null)
                        _log.error("Failed to verify plugin signature, corrupt plugin or bad signature, signed by: " + signer);
                    else
                        // shouldn't happen
                        _log.error("Plugin signer \"" + signer + "\" does not match new signer in plugin.config file \"" + signingKeyName + "\"");
                    statusDone("<b>" + _("Plugin signature verification of {0} failed", url) + "</b>");
                    return;
                }
            }

            String sudVersion = TrustedUpdate.getVersionString(f);
            f.delete();

            String appName = props.getProperty("name");
            String version = props.getProperty("version");
            if (appName == null || version == null || appName.length() <= 0 || version.length() <= 0 ||
                appName.indexOf("<") >= 0 || appName.indexOf(">") >= 0 ||
                version.indexOf("<") >= 0 || version.indexOf(">") >= 0 ||
                appName.startsWith(".") || appName.indexOf("/") >= 0 || appName.indexOf("\\") >= 0) {
                to.delete();
                statusDone("<b>" + _("Plugin from {0} has invalid name or version", url) + "</b>");
                return;
            }
            if (!version.equals(sudVersion)) {
                to.delete();
                statusDone("<b>" + _("Plugin {0} has mismatched versions", appName) + "</b>");
                return;
            }

            String minVersion = ConfigClientsHelper.stripHTML(props, "min-i2p-version");
            if (minVersion != null &&
                (new VersionComparator()).compare(CoreVersion.VERSION, minVersion) < 0) {
                to.delete();
                statusDone("<b>" + _("This plugin requires I2P version {0} or higher", minVersion) + "</b>");
                return;
            }

            minVersion = ConfigClientsHelper.stripHTML(props, "min-java-version");
            if (minVersion != null &&
                (new VersionComparator()).compare(System.getProperty("java.version"), minVersion) < 0) {
                to.delete();
                statusDone("<b>" + _("This plugin requires Java version {0} or higher", minVersion) + "</b>");
                return;
            }

            boolean wasRunning = false;
            File destDir = new SecureDirectory(appDir, appName);
            if (destDir.exists()) {
                if (Boolean.valueOf(props.getProperty("install-only")).booleanValue()) {
                    to.delete();
                    statusDone("<b>" + _("Downloaded plugin is for new installs only, but the plugin is already installed", url) + "</b>");
                    return;
                }
                // compare previous version
                File oldPropFile = new File(destDir, "plugin.config");
                Properties oldProps = new OrderedProperties();
                try {
                    DataHelper.loadProps(oldProps, oldPropFile);
                } catch (IOException ioe) {
                    to.delete();
                    FileUtil.rmdir(tempDir, false);
                    statusDone("<b>" + _("Installed plugin does not contain the required configuration file", url) + "</b>");
                    return;
                }
                String oldPubkey = oldProps.getProperty("key");
                String oldKeyName = oldProps.getProperty("signer");
                String oldAppName = oldProps.getProperty("name");
                if ((!pubkey.equals(oldPubkey)) || (!signer.equals(oldKeyName)) || (!appName.equals(oldAppName))) {
                    to.delete();
                    statusDone("<b>" + _("Signature of downloaded plugin does not match installed plugin") + "</b>");
                    return;
                }
                String oldVersion = oldProps.getProperty("version");
                if (oldVersion == null ||
                    (new VersionComparator()).compare(oldVersion, version) >= 0) {
                    to.delete();
                    statusDone("<b>" + _("Downloaded plugin version {0} is not newer than installed plugin", version) + "</b>");
                    return;
                }
                minVersion = ConfigClientsHelper.stripHTML(props, "min-installed-version");
                if (minVersion != null &&
                    (new VersionComparator()).compare(minVersion, oldVersion) > 0) {
                    to.delete();
                    statusDone("<b>" + _("Plugin update requires installed plugin version {0} or higher", minVersion) + "</b>");
                    return;
                }
                String maxVersion = ConfigClientsHelper.stripHTML(props, "max-installed-version");
                if (maxVersion != null &&
                    (new VersionComparator()).compare(maxVersion, oldVersion) < 0) {
                    to.delete();
                    statusDone("<b>" + _("Plugin update requires installed plugin version {0} or lower", maxVersion) + "</b>");
                    return;
                }
                oldVersion = LogsHelper.jettyVersion();
                minVersion = ConfigClientsHelper.stripHTML(props, "min-jetty-version");
                if (minVersion != null &&
                    (new VersionComparator()).compare(minVersion, oldVersion) > 0) {
                    to.delete();
                    statusDone("<b>" + _("Plugin requires Jetty version {0} or higher", minVersion) + "</b>");
                    return;
                }
                maxVersion = ConfigClientsHelper.stripHTML(props, "max-jetty-version");
                if (maxVersion != null &&
                    (new VersionComparator()).compare(maxVersion, oldVersion) < 0) {
                    to.delete();
                    statusDone("<b>" + _("Plugin requires Jetty version {0} or lower", maxVersion) + "</b>");
                    return;
                }
                // do we defer extraction and installation?
                if (Boolean.valueOf(props.getProperty("router-restart-required")).booleanValue()) {
                    // Yup!
                    try {
                        if(!FileUtil.copy(to, (new SecureFile( new SecureFile(appDir.getCanonicalPath() +"/" + appName +"/"+ ZIP).getCanonicalPath())) , true, true)) {
                            to.delete();
                            statusDone("<b>" + _("Cannot copy plugin to directory {0}", destDir.getAbsolutePath()) + "</b>");
                            return;
                        }
                    } catch (Throwable t) {
                        to.delete();
                        _log.error("Error copying plugin {0}", t);
                        return;
                    }
                    // we don't need the original file anymore.
                    to.delete();
                    statusDone("<b>" + _("Plugin will be installed on next restart.") + "</b>");
                    return;
                }
                if (PluginStarter.isPluginRunning(appName, _context)) {
                    wasRunning = true;
                    try {
                        if (!PluginStarter.stopPlugin(_context, appName)) {
                            // failed, ignore
                        }
                    } catch (Throwable e) {
                        // no updateStatus() for this one
                        _log.error("Error stopping plugin " + appName, e);
                    }
                }
                update = true;
            } else {
                if (Boolean.valueOf(props.getProperty("update-only")).booleanValue()) {
                    to.delete();
                    statusDone("<b>" + _("Plugin is for upgrades only, but the plugin is not installed") + "</b>");
                    return;
                }
                if (!destDir.mkdir()) {
                    to.delete();
                    statusDone("<b>" + _("Cannot create plugin directory {0}", destDir.getAbsolutePath()) + "</b>");
                    return;
                }
            }

            // Finally, extract the zip to the plugin directory
            if (!FileUtil.extractZip(to, destDir)) {
                to.delete();
                statusDone("<b>" + _("Failed to install plugin in {0}", destDir.getAbsolutePath()) + "</b>");
                return;
            }
            _updated = true;
            to.delete();
            // install != update. Changing the user's settings like this is probabbly a bad idea.
            if (Boolean.valueOf( props.getProperty("dont-start-at-install")).booleanValue()) {
                statusDone("<b>" + _("Plugin {0} installed", appName) + "</b>");
                if(!update) {
                    Properties pluginProps = PluginStarter.pluginProperties();
                    pluginProps.setProperty(PluginStarter.PREFIX + appName + PluginStarter.ENABLED, "false");
                    PluginStarter.storePluginProperties(pluginProps);
                }
            } else if (wasRunning || PluginStarter.isPluginEnabled(appName)) {
                // start everything unless it was disabled and not running before
                try {
                    if (PluginStarter.startPlugin(_context, appName)) {
                        String linkName = ConfigClientsHelper.stripHTML(props, "consoleLinkName_" + Messages.getLanguage(_context));
                        if (linkName == null)
                           linkName = ConfigClientsHelper.stripHTML(props, "consoleLinkName");
                        String linkURL = ConfigClientsHelper.stripHTML(props, "consoleLinkURL");
                        String link;
                        if (linkName != null && linkURL != null)
                            link = "<a target=\"_blank\" href=\"" + linkURL + "\"/>" + linkName + "</a>";
                        else
                            link = appName;
                        statusDone("<b>" + _("Plugin {0} installed and started", link) + "</b>");
                    }
                    else
                        statusDone("<b>" + _("Plugin {0} installed but failed to start, check logs", appName) + "</b>");
                } catch (Throwable e) {
                    statusDone("<b>" + _("Plugin {0} installed but failed to start", appName) + ": " + e + "</b>");
                    _log.error("Error starting plugin " + appName, e);
                }
            } else {
                statusDone("<b>" + _("Plugin {0} installed", appName) + "</b>");
            }
        }

        @Override
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            File f = new File(_updateFile);
            f.delete();
            statusDone("<b>" + _("Failed to download plugin from {0}", url) + "</b>");
        }

        private void statusDone(String msg) {
            updateStatus(msg);
            scheduleStatusClean(msg);
        }

    }

    @Override
    protected void updateStatus(String s) {
        super.updateStatus(s);
        _appStatus = s;
    }
}

