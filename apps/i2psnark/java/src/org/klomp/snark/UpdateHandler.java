package org.klomp.snark;

import java.net.URI;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.update.*;

/**
 * <p>Handles the request to update the router by firing up a magnet.
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
 *
 * @since 0.9.4
 */
class UpdateHandler implements Updater {
    private final I2PAppContext _context;
    private final UpdateManager _umgr;
    private final SnarkManager _smgr;
    
    public UpdateHandler(I2PAppContext ctx, UpdateManager umgr, SnarkManager smgr) {
        _context = ctx;
        _umgr = umgr;
        _smgr = smgr;
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
        if ((type != UpdateType.ROUTER_SIGNED && type != UpdateType.ROUTER_SIGNED_SU3) ||
            method != UpdateMethod.TORRENT || updateSources.isEmpty())
            return null;
        UpdateRunner update = new UpdateRunner(_context, _umgr, _smgr, type, updateSources, newVersion);
        _umgr.notifyProgress(update, "<b>" + _smgr.util().getString("Updating") + "</b>");
        return update;
    }
}
