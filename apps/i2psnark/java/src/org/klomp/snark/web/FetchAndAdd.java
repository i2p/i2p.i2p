package org.klomp.snark.web;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketEepGet;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.crypto.SHA1;
import net.i2p.data.DataHelper;
import net.i2p.util.EepGet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;

import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.MetaInfo;
import org.klomp.snark.Snark;
import org.klomp.snark.SnarkManager;
import org.klomp.snark.Storage;

/**
 *  A cancellable torrent file downloader.
 *  We extend Snark so its status may be easily listed in the
 *  web table without adding a lot of code there.
 *
 *  Upon successful download, this Snark will be deleted and
 *  a "real" Snark created.
 *
 *  The methods return values similar to a Snark in magnet mode.
 *  A fake info hash, which is the SHA1 of the URL, is returned
 *  to prevent duplicates.
 *
 *  This Snark may be stopped and restarted, although a partially
 *  downloaded file is discarded.
 *
 *  @since 0.9.1 Moved from I2PSnarkUtil
 */
public class FetchAndAdd extends Snark implements EepGet.StatusListener, Runnable {

    private final I2PAppContext _ctx;
    private final Log _log;
    private final SnarkManager _mgr;
    private final String _url;
    private final byte[] _fakeHash;
    private final String _name;
    private final File _dataDir;
    private volatile long _remaining = -1;
    private volatile long _total = -1;
    private volatile long _transferred;
    private volatile boolean _isRunning;
    private volatile boolean _active;
    private volatile long _started;
    private String _failCause;
    private Thread _thread;
    private EepGet _eepGet;

    private static final int RETRIES = 3;

    /**
     *   Caller should call _mgr.addDownloader(this), which
     *   will start things off.
     *
     *   @param dataDir null to default to snark data directory
     */
    public FetchAndAdd(I2PAppContext ctx, SnarkManager mgr, String url, File dataDir) {
        // magnet constructor
        super(mgr.util(), "Torrent download",
              null, null, null, null, null, null);
        _ctx = ctx;
        _log = ctx.logManager().getLog(FetchAndAdd.class);
        _mgr = mgr;
        _url = url;
        _name = _t("Download torrent file from {0}", url);
        _dataDir = dataDir;
        byte[] fake = null;
        try {
            fake = SHA1.getInstance().digest(url.getBytes("ISO-8859-1"));
        } catch (IOException ioe) {}
        _fakeHash = fake;
    }

    /**
     *  Set off by startTorrent()
     */
    public void run() {
        _mgr.addMessageNoEscape(_t("Fetching {0}", urlify(_url)));
        File file = get();
        if (!_isRunning)  // stopped?
            return;
        _isRunning = false;
        if (file != null && file.exists() && file.length() > 0) {
            // remove this in snarks
            _mgr.deleteMagnet(this);
            add(file);
        } else {
            _mgr.addMessageNoEscape(_t("Torrent was not retrieved from {0}", urlify(_url)) +
                            ((_failCause != null) ? (": " + DataHelper.stripHTML(_failCause)) : ""));
        }
        if (file != null)
            file.delete();
    }

    /**
     *  Copied from I2PSnarkUtil so we may add ourselves as a status listener
     *  @return null on failure
     */
    private File get() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Fetching [" + _url + "]");
        File out = null;
        try {
            out = SecureFile.createTempFile("torrentFile", null, _mgr.util().getTempDir());
        } catch (IOException ioe) {
            _log.error("temp file error", ioe);
            _mgr.addMessage("Temp file error: " + ioe);
            if (out != null)
                out.delete();
            return null;
        }
        out.deleteOnExit();

        if (!_mgr.util().connected()) {
            _mgr.addMessage(_t("Opening the I2P tunnel"));
            if (!_mgr.util().connect())
                return null;
        }
        I2PSocketManager manager = _mgr.util().getSocketManager();
        if (manager == null)
            return null;
        _eepGet = new I2PSocketEepGet(_ctx, manager, RETRIES, out.getAbsolutePath(), _url);
        _eepGet.addStatusListener(this);
        _eepGet.addHeader("User-Agent", I2PSnarkUtil.EEPGET_USER_AGENT);
        if (_eepGet.fetch()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Fetch successful [" + _url + "]: size=" + out.length());
            return out;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Fetch failed [" + _url + ']');
            out.delete();
            return null;
        }
    }

    /**
     *  Tell SnarkManager to copy the torrent file over and add it to the Snarks list.
     *  This Snark may then be deleted.
     */
    private void add(File file) {
        _mgr.addMessageNoEscape(_t("Torrent fetched from {0}", urlify(_url)));
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] fileInfoHash = new byte[20];
            String name = MetaInfo.getNameAndInfoHash(in, fileInfoHash);
            try { in.close(); } catch (IOException ioe) {}
            Snark snark = _mgr.getTorrentByInfoHash(fileInfoHash);
            if (snark != null) {
                _mgr.addMessage(_t("Torrent with this info hash is already running: {0}", snark.getBaseName()));
                return;
            }

            String originalName = Storage.filterName(name);
            name = originalName + ".torrent";
            File torrentFile = new File(_mgr.getDataDir(), name);

            String canonical = torrentFile.getCanonicalPath();

            if (torrentFile.exists()) {
                if (_mgr.getTorrent(canonical) != null)
                    _mgr.addMessage(_t("Torrent already running: {0}", name));
                else
                    _mgr.addMessage(_t("Torrent already in the queue: {0}", name));
            } else {
                // This may take a LONG time to create the storage.
                _mgr.copyAndAddTorrent(file, canonical, _dataDir);
                snark = _mgr.getTorrentByBaseName(originalName);
                if (snark != null)
                    snark.startTorrent();
                else
                    throw new IOException("Unknown error - check logs");
            }
        } catch (IOException ioe) {
            _mgr.addMessageNoEscape(_t("Torrent at {0} was not valid", urlify(_url)) + ": " + DataHelper.stripHTML(ioe.getMessage()));
        } catch (OutOfMemoryError oom) {
            _mgr.addMessageNoEscape(_t("ERROR - Out of memory, cannot create torrent from {0}", urlify(_url)) + ": " + DataHelper.stripHTML(oom.getMessage()));
        } finally {
            try { if (in != null) in.close(); } catch (IOException ioe) {}
        }
    }

    // Snark overrides so all the buttons and stats on the web page work

    @Override
    public synchronized void startTorrent() {
        if (_isRunning)
            return;
         // reset counters in case starting a second time
         _remaining = -1;
         // leave the total if we knew it before
         //_total = -1;
         _transferred = 0;
         _failCause = null;
        _started = _util.getContext().clock().now();
        _isRunning = true;
        _active = false;
        _thread = new I2PAppThread(this, "Torrent File EepGet", true);
        _thread.start();
    }

    @Override
    public synchronized void stopTorrent() {
        if (_thread != null && _isRunning) {
            if (_eepGet != null)
                _eepGet.stopFetching();
            _thread.interrupt();
        }
        _isRunning = false;
        _active = false;
    }

    @Override
    public boolean isStopped() {
        return !_isRunning;
    }

    @Override
    public String getName() {
        return _name;
    }

    @Override
    public String getBaseName() {
        return _name;
    }

    @Override
    public byte[] getInfoHash() {
        return _fakeHash;
    }

    /**
     *  @return torrent file size or -1
     */
    @Override
    public long getTotalLength() {
        return _total;
    }

    /**
     *  @return -1 when done so the web will list us as "complete" instead of "seeding"
     */
    @Override
    public long getRemainingLength() {
        long rv = _remaining;
        return rv > 0 ? rv : -1;
    }

    /**
     *  @return torrent file bytes remaining or -1
     */
    @Override
    public long getNeededLength() {
        return _remaining;
    }

    @Override
    public long getDownloadRate() {
        if (_isRunning && _active) {
            long time = _ctx.clock().now() - _started;
            if (time > 1000) {
                long rv = (_transferred * 1000) / time;
                if (rv >= 100)
                    return rv;
            }
        }
        return 0;
    }

    @Override
    public long getDownloaded() {
        return _total - _remaining;
    }

    @Override
    public int getPeerCount() {
        return (_isRunning && _active && _transferred > 0) ? 1 : 0;
    }

    @Override
    public int getTrackerSeenPeers() {
        return (_transferred > 0) ? 1 : 0;
    }

    // End Snark overrides

    // EepGet status listeners to maintain the state for the web page

    public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
        if (bytesRemaining >= 0) {
            _remaining = bytesRemaining;
        }
        _transferred = bytesTransferred;
        if (cause != null)
            _failCause = cause.toString();
        _active = false;
    }

    public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
        if (bytesRemaining >= 0) {
            _remaining = bytesRemaining;
            _total = bytesRemaining + currentWrite + alreadyTransferred;
        }
        _transferred = bytesTransferred;
        _active = true;
    }

    public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
        if (bytesRemaining >= 0) {
            _remaining = bytesRemaining;
            _total = bytesRemaining + alreadyTransferred;
        }
        _transferred = bytesTransferred;
        _active = false;
    }

    public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
        if (bytesRemaining >= 0) {
            _remaining = bytesRemaining;
        }
        _transferred = bytesTransferred;
        _active = false;
    }

    public void headerReceived(String url, int attemptNum, String key, String val) {}

    public void attempting(String url) {}

    // End of EepGet status listeners

    private String _t(String s) {
        return _mgr.util().getString(s);
    }

    private String _t(String s, String o) {
        return _mgr.util().getString(s, o);
    }

    private static String urlify(String s) {
        return I2PSnarkServlet.urlify(s);
    }
}
