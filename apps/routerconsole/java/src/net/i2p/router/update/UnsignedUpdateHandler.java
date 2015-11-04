package net.i2p.router.update;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.NewsHelper;
import net.i2p.update.*;
import static net.i2p.update.UpdateType.*;
import static net.i2p.update.UpdateMethod.*;

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
class UnsignedUpdateHandler implements Checker, Updater {
    private final RouterContext _context;
    private final ConsoleUpdateManager _mgr;

    public UnsignedUpdateHandler(RouterContext ctx, ConsoleUpdateManager mgr) {
        _context = ctx;
        _mgr = mgr;
    }

    /**
     *  @return null if none
     *  @since 0.9.4
     */
    public List<URI> getUpdateSources() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_ZIP_URL);
        if (url == null)
            return null;

        try {
            return Collections.singletonList(new URI(url));
        } catch (URISyntaxException use) {
            return null;
        }
    }

    /**
     *  @param currentVersion ignored, we use time stored in a property
     */
    @Override
    public UpdateTask check(UpdateType type, UpdateMethod method,
                            String id, String currentVersion, long maxTime) {
        if (type != UpdateType.ROUTER_UNSIGNED || method != UpdateMethod.HTTP)
            return null;

        List<URI> updateSources = getUpdateSources();
        if (updateSources == null)
            return null;

        long ms = _context.getProperty(NewsHelper.PROP_LAST_UPDATE_TIME, 0L);
        if (ms <= 0) {
            // we don't know what version you have, so stamp it with the current time,
            // and we'll look for something newer next time around.
            _context.router().saveConfig(NewsHelper.PROP_LAST_UPDATE_TIME,
                                               Long.toString(_context.clock().now()));
            return null;
        }

        UpdateRunner update = new UnsignedUpdateChecker(_context, _mgr, updateSources, ms);
        return update;
    }

    /**
     *  Start a download and return a handle to the download task.
     *  Should not block.
     *
     *  @param id plugin name or ignored
     *  @param maxTime how long you have
     *  @return active task or null if unable to download
     */
    @Override
    public UpdateTask update(UpdateType type, UpdateMethod method, List<URI> updateSources,
                             String id, String newVersion, long maxTime) {
        if (type != ROUTER_UNSIGNED || method != HTTP || updateSources.isEmpty())
            return null;
        UpdateRunner update = new UnsignedUpdateRunner(_context, _mgr, updateSources);
        // set status before thread to ensure UI feedback
        _mgr.notifyProgress(update, "<b>" + _mgr._t("Updating") + "</b>");
        return update;
    }
}
