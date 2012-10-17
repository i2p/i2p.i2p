package net.i2p.router.update;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.util.RFC822Date;
import net.i2p.update.*;
import net.i2p.util.EepGet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.PartialEepGet;
import net.i2p.util.VersionComparator;

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
    
    public UpdateHandler(RouterContext ctx) {
        _context = ctx;
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
        if ((type != UpdateType.ROUTER_SIGNED && type != UpdateType.ROUTER_SIGNED_PACK200) ||
            method != UpdateMethod.HTTP || updateSources.isEmpty())
            return null;
        UpdateRunner update = new UpdateRunner(_context, updateSources);
        update.start();
        return update;
    }
}
