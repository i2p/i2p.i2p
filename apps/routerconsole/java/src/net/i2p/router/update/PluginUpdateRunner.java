package net.i2p.router.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.IllegalArgumentException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.CoreVersion;
import net.i2p.crypto.SU3File;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.Messages;
import net.i2p.router.web.PluginStarter;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.update.*;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.PortMapper;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFile;
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
 * uri list must not be empty.
 *
 * Moved from web/ and turned into an UpdateTask.
 *
 * @since 0.9.4 moved from PluginUpdateHandler
 */
class PluginUpdateRunner extends UpdateRunner {

    private String _appName;
    private final String _oldVersion;
    private final URI _uri;
    private final String _xpi2pURL;
    private boolean _updated;
    private String _errMsg = "";

    private static final String XPI2P = "app.xpi2p";
    private static final String ZIP = XPI2P + ".zip";
    public static final String PLUGIN_DIR = PluginStarter.PLUGIN_DIR;
    private static final String PROP_ALLOW_NEW_KEYS = "routerconsole.allowUntrustedPlugins";

    public PluginUpdateRunner(RouterContext ctx, ConsoleUpdateManager mgr, List<URI> uris,
                              String appName, String oldVersion ) {
        super(ctx, mgr, UpdateType.PLUGIN, uris);
        if (uris.isEmpty())
            throw new IllegalArgumentException("uri cannot be empty");
        else
            _uri = uris.get(0);
        _xpi2pURL = _uri.toString();
        _appName = appName;
        _oldVersion = oldVersion;
    }

    @Override
    public URI getURI() { return _uri; }

    @Override
    public String getID() { return _appName; }

        @Override
        protected void update() {

            _updated = false;
            if (_xpi2pURL.startsWith("file:") || _method == UpdateMethod.FILE) {
                // strip off file:// or just file:
                String xpi2pfile = _uri.getPath();
                if(xpi2pfile == null || xpi2pfile.length() == 0) {
                        statusDone("<b>" + _t("Bad URL {0}", _xpi2pURL) + "</b>");
                } else {
                    // copy the contents of from to _updateFile
                    long alreadyTransferred = (new File(xpi2pfile)).getAbsoluteFile().length();
                    if(FileUtil.copy((new File(xpi2pfile)).getAbsolutePath(), _updateFile, true, false)) {
                        updateStatus("<b>" + _t("Attempting to install from file {0}", _xpi2pURL) + "</b>");
                        transferComplete(alreadyTransferred, alreadyTransferred, 0L, _xpi2pURL, _updateFile, false);
                    } else {
                        statusDone("<b>" + _t("Failed to install from file {0}, copy failed.", _xpi2pURL) + "</b>");
                    }
                }
            } else {
                // use the same settings as for updater
                //boolean shouldProxy = _context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY);
                // always proxy, or else FIXME
                boolean shouldProxy = true;
                String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
                int proxyPort = ConfigUpdateHandler.proxyPort(_context);
                if (shouldProxy && proxyPort == ConfigUpdateHandler.DEFAULT_PROXY_PORT_INT &&
                    proxyHost.equals(ConfigUpdateHandler.DEFAULT_PROXY_HOST) &&
                    _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY) < 0) {
                    String msg = _t("HTTP client proxy tunnel must be running");
                    if (_log.shouldWarn())
                        _log.warn(msg);
                    statusDone("<b>" + msg + "</b>");
                    _mgr.notifyTaskFailed(this, msg, null);
                    return;
                }
                updateStatus("<b>" + _t("Downloading plugin from {0}", _xpi2pURL) + "</b>");
                try {
                    if (shouldProxy)
                        // 10 retries!!
                        _get = new EepGet(_context, proxyHost, proxyPort, 10, _updateFile, _xpi2pURL, false);
                    else
                        _get = new EepGet(_context, 1, _updateFile, _xpi2pURL, false);
                    _get.addStatusListener(PluginUpdateRunner.this);
                    _get.fetch(CONNECT_TIMEOUT, -1, shouldProxy ? INACTIVITY_TIMEOUT : NOPROXY_INACTIVITY_TIMEOUT);
                } catch (Throwable t) {
                    _log.error("Error downloading plugin", t);
                }
            }
            if (_updated)
                _mgr.notifyComplete(this, _newVersion, null);
            else
                _mgr.notifyTaskFailed(this, _errMsg, null);
        }

    /**
     *  Overridden to change the "Updating I2P" text in super
     *  @since 0.9.35
     */
    @Override
    public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
        long d = currentWrite + bytesTransferred;
        String status = "<b>" + _t("Downloading plugin") + ": " + _appName + "</b>";
        _mgr.notifyProgress(this, status, d, d + bytesRemaining);
    }

        @Override
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            if (!(_xpi2pURL.startsWith("file:") || _method == UpdateMethod.FILE))
                updateStatus("<b>" + _t("Plugin downloaded") + "</b>");
            File f = new File(_updateFile);
            File appDir = new SecureDirectory(_context.getConfigDir(), PLUGIN_DIR);
            if ((!appDir.exists()) && (!appDir.mkdir())) {
                f.delete();
                statusDone("<b>" + _t("Cannot create plugin directory {0}", appDir.getAbsolutePath()) + "</b>");
                return;
            }
            boolean isSU3;
            try {
                isSU3 = isSU3File(f);
            } catch (IOException ioe) {
                f.delete();
                statusDone("<b>" + ioe + "</b>");
                return;
            }
            if (isSU3)
                processSU3(f, appDir, url);
            else
                processSUD(f, appDir, url);
        }

        /**
         *  @since 0.9.15
         *  @return if SU3
         */
        private static boolean isSU3File(File f) throws IOException {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(f);
                for (int i = 0; i < SU3File.MAGIC.length(); i++) {
                    if (fis.read() != SU3File.MAGIC.charAt(i))
                        return false;
                }
                return true;
            } finally {
                if (fis != null) try { fis.close(); } catch (IOException ioe) {}
            }
        }

        /**
         *  @since 0.9.15
         *  @return success
         */
        private void processSUD(File f, File appDir, String url) {
            TrustedUpdate up = new TrustedUpdate(_context);
            File to = new File(_context.getTempDir(), "tmp" + _context.random().nextInt() + ZIP);
            // extract to a zip file whether the sig is good or not, so we can get the properties file
            String err = up.migrateFile(f, to);
            if (err != null) {
                statusDone("<b>" + err + ' ' + _t("from {0}", url) + " </b>");
                f.delete();
                to.delete();
                return;
            }
            Properties props = getPluginConfig(f, to, url);
            if (props == null)
                return;

            // ok, now we check sigs and deal with a bad sig
            String pubkey = props.getProperty("key");
            String signer = DataHelper.stripHTML(props.getProperty("signer"));
            if (pubkey == null || signer == null || pubkey.length() != 172 || signer.length() <= 0) {
                f.delete();
                to.delete();
                //updateStatus("<b>" + "Plugin contains an invalid key" + ' ' + pubkey + ' ' + signer + "</b>");
                statusDone("<b>" + _t("Plugin from {0} contains an invalid key", url) + "</b>");
                return;
            }
            SigningPublicKey spk;
            try {
                spk = new SigningPublicKey(pubkey);
            } catch (DataFormatException dfe) {
                f.delete();
                to.delete();
                statusDone("<b>" + _t("Plugin from {0} contains an invalid key", url) + "</b>");
                return;
            }

            // add all existing plugin keys, so any conflicts with existing keys
            // will be discovered and rejected
            Map<String, String> existingKeys = PluginStarter.getPluginKeys(_context);
            for (Map.Entry<String, String> e : existingKeys.entrySet()) {
                // ignore dups/bad keys
                up.addKey(e.getKey(), e.getValue());
            }

            // add all trusted plugin keys, so any conflicts with trusted keys
            // will be discovered and rejected
            Map<String, String> trustedKeys = TrustedPluginKeys.getKeys();
            for (Map.Entry<String, String> e : trustedKeys.entrySet()) {
                // ignore dups/bad keys
                up.addKey(e.getKey(), e.getValue());
            }

            if (up.haveKey(pubkey)) {
                // the key is already in the TrustedUpdate keyring
                // verify the sig and verify that it is signed by the signer in the plugin.config file
                // Allow "" as the previously-known signer
                boolean ok = up.verify(f, spk);
                String signingKeyName = up.getKeys().get(spk);
                if ((!ok) || !(signer.equals(signingKeyName) || "".equals(signingKeyName))) {
                    f.delete();
                    to.delete();
                    if (signingKeyName == null)
                        _log.error("Failed to verify plugin signature, corrupt plugin or bad signature, signed by: " + signer);
                    else
                        _log.error("Plugin signer \"" + signer + "\" does not match existing signer in plugin.config file \"" + signingKeyName + "\"");
                    statusDone("<b>" + _t("Plugin signature verification of {0} failed", url) + "</b>");
                    return;
                }
            } else if (_context.getBooleanProperty(PROP_ALLOW_NEW_KEYS)) {
                // add to keyring...
                if(!up.addKey(pubkey, signer)) {
                    // bad or duplicate key
                    f.delete();
                    to.delete();
                    _log.error("Bad key or key mismatch - Failed to add plugin key \"" + pubkey + "\" for plugin signer \"" + signer + "\"");
                    statusDone("<b>" + _t("Plugin signature verification of {0} failed", url) + "</b>");
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
                    statusDone("<b>" + _t("Plugin signature verification of {0} failed", url) + "</b>");
                    return;
                }
            } else {
                // unknown key
                f.delete();
                to.delete();
                _log.error("Untrusted plugin key \"" + pubkey + "\" for plugin signer \"" + signer + "\"");
                // don't display signer, we're really checking the key not the signer name
                statusDone("<b>" + _t("Plugin not installed - signer is untrusted") + "</b>");
                return;
            }

            String sudVersion = TrustedUpdate.getVersionString(f);
            f.delete();
            processFinal(to, appDir, url, props, sudVersion, pubkey, signer);
        }

        /**
         *  @since 0.9.15
         */
        private void processSU3(File f, File appDir, String url) {
            SU3File su3 = new SU3File(_context, f);
            File to = new File(_context.getTempDir(), "tmp" + _context.random().nextInt() + ZIP);
            String sudVersion;
            String signingKeyName;
            try {
                su3.verifyAndMigrate(to);
                if (su3.getFileType() != SU3File.TYPE_ZIP)
                    throw new IOException("bad file type");
                if (su3.getContentType() != SU3File.CONTENT_PLUGIN)
                    throw new IOException("bad content type");
                sudVersion = su3.getVersionString();
                signingKeyName = su3.getSignerString();
            } catch (IOException ioe) {
                statusDone("<b>" + ioe + ' ' + _t("from {0}", url) + " </b>");
                f.delete();
                to.delete();
                return;
            }
            Properties props = getPluginConfig(f, to, url);
            if (props == null)
                return;
            String signer = props.getProperty("signer");
            if (signer == null || signer.length() <= 0) {
                f.delete();
                to.delete();
                statusDone("<b>" + _t("Plugin from {0} contains an invalid key", url) + "</b>");
                return;
            }
            if (!signer.equals(signingKeyName)) {
                f.delete();
                to.delete();
                if (signingKeyName == null)
                    _log.error("Failed to verify plugin signature, corrupt plugin or bad signature, signed by: " + signer);
                else
                    // shouldn't happen
                    _log.error("Plugin signer \"" + signer + "\" does not match new signer in plugin.config file \"" + signingKeyName + "\"");
                statusDone("<b>" + _t("Plugin signature verification of {0} failed", url) + "</b>");
                return;
            }
            processFinal(to, appDir, url, props, sudVersion, null, signer);
        }

        /**
         *  @since 0.9.15
         *  @return null on error
         */
        private Properties getPluginConfig(File f, File to, String url) {
            File tempDir = new File(_context.getTempDir(), "tmp" + _context.random().nextInt() + "-unzip");
            if (!FileUtil.extractZip(to, tempDir, Log.ERROR)) {
                f.delete();
                to.delete();
                FileUtil.rmdir(tempDir, false);
                statusDone("<b>" + _t("Plugin from {0} is corrupt", url) + "</b>");
                return null;
            }
            File installProps = new File(tempDir, "plugin.config");
            Properties props = new OrderedProperties();
            try {
                DataHelper.loadProps(props, installProps);
            } catch (IOException ioe) {
                f.delete();
                to.delete();
                statusDone("<b>" + _t("Plugin from {0} does not contain the required configuration file", url) + "</b>");
                return null;
            } finally {
                // we don't need this anymore, we will unzip again
                FileUtil.rmdir(tempDir, false);
            }
            return props;
        }

        /**
         *  @param pubkey null OK for su3
         *  @since 0.9.15
         */
        private void processFinal(File to, File appDir, String url, Properties props, String sudVersion, String pubkey, String signer) {
            boolean update = false;
            String appName = props.getProperty("name");
            String version = props.getProperty("version");
            if (appName == null || version == null || appName.length() <= 0 || version.length() <= 0 ||
                appName.indexOf('<') >= 0 || appName.indexOf('>') >= 0 ||
                version.indexOf('<') >= 0 || version.indexOf('>') >= 0 ||
                appName.startsWith(".") || appName.indexOf('/') >= 0 || appName.indexOf('\\') >= 0) {
                to.delete();
                statusDone("<b>" + _t("Plugin from {0} has invalid name or version", url) + "</b>");
                return;
            }
            if (!version.equals(sudVersion)) {
                to.delete();
                statusDone("<b>" + _t("Plugin {0} has mismatched versions", appName) + "</b>");
                return;
            }
            // set so notifyComplete() will work
            _appName = appName;
            _newVersion = version;

            String minVersion = PluginStarter.stripHTML(props, "min-i2p-version");
            if (minVersion != null &&
                VersionComparator.comp(CoreVersion.VERSION, minVersion) < 0) {
                to.delete();
                statusDone("<b>" + _t("This plugin requires I2P version {0} or higher", minVersion) + "</b>");
                return;
            }

            minVersion = PluginStarter.stripHTML(props, "min-java-version");
            if (minVersion != null &&
                VersionComparator.comp(System.getProperty("java.version"), minVersion) < 0) {
                to.delete();
                statusDone("<b>" + _t("This plugin requires Java version {0} or higher", minVersion) + "</b>");
                return;
            }

            boolean wasRunning = false;
            File destDir = new SecureDirectory(appDir, appName);
            if (destDir.exists()) {
                if (Boolean.valueOf(props.getProperty("install-only")).booleanValue()) {
                    to.delete();
                    statusDone("<b>" + _t("Downloaded plugin is for new installs only, but the plugin is already installed", url) + "</b>");
                    return;
                }
                // compare previous version
                File oldPropFile = new File(destDir, "plugin.config");
                Properties oldProps = new OrderedProperties();
                try {
                    DataHelper.loadProps(oldProps, oldPropFile);
                } catch (IOException ioe) {
                    to.delete();
                    statusDone("<b>" + _t("Installed plugin does not contain the required configuration file", url) + "</b>");
                    return;
                }
                String oldPubkey = oldProps.getProperty("key");
                String oldKeyName = oldProps.getProperty("signer");
                String oldAppName = oldProps.getProperty("name");
                if ((pubkey != null && !pubkey.equals(oldPubkey)) || (!signer.equals(oldKeyName)) || (!appName.equals(oldAppName))) {
                    to.delete();
                    statusDone("<b>" + _t("Signature of downloaded plugin does not match installed plugin") + "</b>");
                    return;
                }
                String oldVersion = oldProps.getProperty("version");
                if (oldVersion == null ||
                    VersionComparator.comp(oldVersion, version) >= 0) {
                    to.delete();
                    statusDone("<b>" + _t("Downloaded plugin version {0} is not newer than installed plugin", version) + "</b>");
                    return;
                }
                minVersion = PluginStarter.stripHTML(props, "min-installed-version");
                if (minVersion != null &&
                    VersionComparator.comp(minVersion, oldVersion) > 0) {
                    to.delete();
                    statusDone("<b>" + _t("Plugin update requires installed plugin version {0} or higher", minVersion) + "</b>");
                    return;
                }
                String maxVersion = PluginStarter.stripHTML(props, "max-installed-version");
                if (maxVersion != null &&
                    VersionComparator.comp(maxVersion, oldVersion) < 0) {
                    to.delete();
                    statusDone("<b>" + _t("Plugin update requires installed plugin version {0} or lower", maxVersion) + "</b>");
                    return;
                }
                oldVersion = RouterConsoleRunner.jettyVersion();
                minVersion = PluginStarter.stripHTML(props, "min-jetty-version");
                if (minVersion != null &&
                    VersionComparator.comp(minVersion, oldVersion) > 0) {
                    to.delete();
                    statusDone("<b>" + _t("Plugin requires Jetty version {0} or higher", minVersion) + "</b>");
                    return;
                }
                String blacklistVersion = PluginStarter.jetty9Blacklist.get(appName);
                if (blacklistVersion != null &&
                    VersionComparator.comp(version, blacklistVersion) <= 0) {
                    to.delete();
                    statusDone("<b>" + _t("Plugin requires Jetty version {0} or lower", "8.9999") + "</b>");
                    return;
                }
                maxVersion = PluginStarter.stripHTML(props, "max-jetty-version");
                if (maxVersion != null &&
                    VersionComparator.comp(maxVersion, oldVersion) < 0) {
                    to.delete();
                    statusDone("<b>" + _t("Plugin requires Jetty version {0} or lower", maxVersion) + "</b>");
                    return;
                }
                // do we defer extraction and installation?
                if (Boolean.valueOf(props.getProperty("router-restart-required")).booleanValue()) {
                    // Yup!
                    try {
                        if(!FileUtil.copy(to, (new SecureFile( new SecureFile(appDir.getCanonicalPath() +"/" + appName +"/"+ ZIP).getCanonicalPath())) , true, true)) {
                            to.delete();
                            statusDone("<b>" + _t("Cannot copy plugin to directory {0}", destDir.getAbsolutePath()) + "</b>");
                            return;
                        }
                    } catch (Throwable t) {
                        to.delete();
                        _log.error("Error copying plugin {0}", t);
                        return;
                    }
                    // we don't need the original file anymore.
                    to.delete();
                    statusDone("<b>" + _t("Plugin will be installed on next restart.") + ' ' + appName + ' ' + version + "</b>");
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
                    statusDone("<b>" + _t("Plugin is for upgrades only, but the plugin is not installed") + ". " + appName + ' ' + version + "</b>");
                    return;
                }
                if (!destDir.mkdir()) {
                    to.delete();
                    statusDone("<b>" + _t("Cannot create plugin directory {0}", destDir.getAbsolutePath()) + "</b>");
                    return;
                }
            }

            // Finally, extract the zip to the plugin directory
            if (!FileUtil.extractZip(to, destDir, Log.WARN)) {
                to.delete();
                statusDone("<b>" + _t("Failed to install plugin in {0}", destDir.getAbsolutePath()) + "</b>");
                return;
            }
            _updated = true;
            to.delete();
            // install != update. Changing the user's settings like this is probabbly a bad idea.
            if (Boolean.valueOf( props.getProperty("dont-start-at-install")).booleanValue()) {
                statusDone("<b>" + _t("Plugin {0} installed", appName + ' ' + version) + "</b>");
                if(!update) {
                    Properties pluginProps = PluginStarter.pluginProperties();
                    pluginProps.setProperty(PluginStarter.PREFIX + appName + PluginStarter.ENABLED, "false");
                    PluginStarter.storePluginProperties(pluginProps);
                }
            } else if (wasRunning || PluginStarter.isPluginEnabled(appName)) {
                // start everything unless it was disabled and not running before
                try {
                    if (PluginStarter.startPlugin(_context, appName)) {
                        String linkName = PluginStarter.stripHTML(props, "consoleLinkName_" + Messages.getLanguage(_context));
                        if (linkName == null)
                           linkName = PluginStarter.stripHTML(props, "consoleLinkName");
                        String linkURL = PluginStarter.stripHTML(props, "consoleLinkURL");
                        String link;
                        if (linkName != null && linkURL != null)
                            link = "<a target=\"_blank\" href=\"" + linkURL + "\"/>" + linkName + ' ' + version + "</a>";
                        else
                            link = appName + ' ' + version;
                        statusDone("<b>" + _t("Plugin {0} installed and started", link) + "</b>");
                    }
                    else
                        statusDone("<b>" + _t("Plugin {0} installed but failed to start, check logs", appName + ' ' + version) + "</b>");
                } catch (Throwable e) {
                    statusDone("<b>" + _t("Plugin {0} installed but failed to start", appName + ' ' + version) + ": " + e + "</b>");
                    _log.error("Error starting plugin " + appName + ' ' + version, e);
                }
            } else {
                statusDone("<b>" + _t("Plugin {0} installed", appName + ' ' + version) + "</b>");
            }
        }

        @Override
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            File f = new File(_updateFile);
            f.delete();
            statusDone("<b>" + _t("Failed to download plugin from {0}", url) + "</b>");
        }

        private void statusDone(String msg) {
            // if we fail, we will pass this back in notifyTaskFailed()
            _errMsg = msg;
            updateStatus(msg);
        }

}

