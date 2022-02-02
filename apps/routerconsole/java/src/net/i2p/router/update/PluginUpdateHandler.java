package net.i2p.router.update;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import net.i2p.router.RouterContext;
import net.i2p.router.web.PluginStarter;
import net.i2p.update.*;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Check for or download an updated version of a plugin.
 * A plugin is a standard .sud file with a 40-byte signature,
 * a 16-byte version, and a .zip file.
 *
 * So we get the current version and update URL for the installed plugin,
 * then fetch the first 56 bytes of the URL, extract the version,
 * and compare.
 *
 * Moved from web/ and turned into an Updater.
 *
 * @since 0.7.12
 * @author zzz
 */
class PluginUpdateHandler implements Checker, Updater {
    private final RouterContext _context;
    private final ConsoleUpdateManager _mgr;
    private final Log _log;

    public PluginUpdateHandler(RouterContext ctx, ConsoleUpdateManager mgr) {
        _context = ctx;
        _log = _context.logManager().getLog(PluginUpdateHandler.class);
        _mgr = mgr;
    }

    /** check a single plugin */
    @Override
    public UpdateTask check(UpdateType type, UpdateMethod method,
                            String appName, String currentVersion, long maxTime) {
        if ((type != UpdateType.PLUGIN) ||
            method != UpdateMethod.HTTP || appName.length() <= 0)
            return null;

        Properties props = PluginStarter.pluginProperties(_context, appName);
        String oldVersion = props.getProperty("version");
        String xpi2pURL = props.getProperty("updateURL.su3");
        if (xpi2pURL == null)
            xpi2pURL = props.getProperty("updateURL");
        List<URI> updateSources = null;
        if (xpi2pURL != null) {
            xpi2pURL = xpi2pURL.replace("$OS", SystemVersion.getOS());
            xpi2pURL = xpi2pURL.replace("$ARCH", SystemVersion.getArch());
            if (_log.shouldLog(Log.INFO))
                _log.info("Checking for updates for " + appName + ": " + xpi2pURL);
            try {
                updateSources = Collections.singletonList(new URI(xpi2pURL));
            } catch (URISyntaxException use) {}
        }

        if (oldVersion == null || updateSources == null) {
            //updateStatus("<b>" + _t("Cannot check, plugin {0} is not installed", appName) + "</b>");
            return null;
        }

        UpdateRunner update = new PluginUpdateChecker(_context, _mgr, updateSources, appName, oldVersion);
        return update;
    }

    /** download a single plugin */
    @Override
    public UpdateTask update(UpdateType type, UpdateMethod method, List<URI> updateSources,
                               String appName, String newVersion, long maxTime) {
        if (type != UpdateType.PLUGIN ||
            (method != UpdateMethod.HTTP && method != UpdateMethod.FILE) ||
            updateSources.isEmpty())
            return null;
        Properties props = PluginStarter.pluginProperties(_context, appName);
        String oldVersion = props.getProperty("version");
        if (oldVersion == null) {
            // assume new install
            oldVersion = "0";
        }

        UpdateRunner update = new PluginUpdateRunner(_context, _mgr, updateSources, appName, oldVersion);
        // set status before thread to ensure UI feedback
        _mgr.notifyProgress(update, "<b>" + _mgr._t("Updating") + "</b>");
        return update;
    }
}

