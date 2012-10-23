package net.i2p.router.update;

import java.net.URI;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.update.*;

/**
 * <p>Handles the request to update the router by firing one or more
 * {@link net.i2p.util.EepGet} calls to download the latest signed update file
 * and displaying the status to anyone who asks.
 * </p>
 * <p>After the download completes the signed update file is verified with
 * {@link net.i2p.crypto.TrustedUpdate}, and if it's authentic the payload
 * of the signed update file is unpacked and the router is restarted to complete
 * the update process.
 * </p>
 *
 * This does not do any checking, that is handled by the NewsFetcher.
 */
class UpdateHandler implements Updater {
    protected final RouterContext _context;
    protected final ConsoleUpdateManager _mgr;
    
    public UpdateHandler(RouterContext ctx, ConsoleUpdateManager mgr) {
        _context = ctx;
        _mgr = mgr;
    }
    
    /**
     *  Start a download and return a handle to the download task.
     *  Should not block.
     *
     *  @param id plugin name or ignored
     *  @param maxTime how long you have
     *  @return active task or null if unable to download
     */
    public UpdateTask update(UpdateType type, UpdateMethod method, List<URI> updateSources,
                             String id, String newVersion, long maxTime) {
        if (type != UpdateType.ROUTER_SIGNED ||
            method != UpdateMethod.HTTP || updateSources.isEmpty())
            return null;
        UpdateRunner update = new UpdateRunner(_context, _mgr, updateSources);
        // set status before thread to ensure UI feedback
        _mgr.notifyProgress(update, "<b>" + _mgr._("Updating") + "</b>");
        return update;
    }
}
