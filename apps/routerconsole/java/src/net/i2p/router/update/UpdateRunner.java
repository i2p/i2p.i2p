package net.i2p.router.update;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.update.*;
import net.i2p.util.EepGet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.PartialEepGet;
import net.i2p.util.VersionComparator;

/**
 *  The downloader for router signed updates,
 *  and the base class for all the other Checkers and Runners.
 *
 *  @since 0.9.4 moved from UpdateHandler
 *
 */
class UpdateRunner extends I2PAppThread implements UpdateTask, EepGet.StatusListener {
    protected final RouterContext _context;
    protected final Log _log;
    protected final ConsoleUpdateManager _mgr;
    protected final List<URI> _urls;
    protected final String _updateFile;
    protected volatile boolean _isRunning;
    protected boolean done;
    protected EepGet _get;
    /** tells the listeners what mode we are in - set to true in extending classes for checks */
    protected boolean _isPartial;
    /** set by the listeners on completion */
    protected String _newVersion;
    // 56 byte header, only used for suds
    private final ByteArrayOutputStream _baos;
    protected URI _currentURI;

    private static final String SIGNED_UPDATE_FILE = "i2pupdate.sud";

    protected static final long CONNECT_TIMEOUT = 55*1000;
    protected static final long INACTIVITY_TIMEOUT = 5*60*1000;
    protected static final long NOPROXY_INACTIVITY_TIMEOUT = 60*1000;

    public UpdateRunner(RouterContext ctx, ConsoleUpdateManager mgr, List<URI> uris) { 
        super("Update Runner");
        setDaemon(true);
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _mgr = mgr;
        _urls = uris;
        _baos = new ByteArrayOutputStream(TrustedUpdate.HEADER_BYTES);
        _updateFile = (new File(ctx.getTempDir(), "update" + ctx.random().nextInt() + ".tmp")).getAbsolutePath();
    }

    //////// begin UpdateTask methods

    public boolean isRunning() { return _isRunning; }

    public void shutdown() {
        _isRunning = false;
        interrupt();
    }

    public UpdateType getType() { return UpdateType.ROUTER_SIGNED; }

    public UpdateMethod getMethod() { return UpdateMethod.HTTP; }

    public URI getURI() { return _currentURI; }

    public String getID() { return ""; }

    //////// end UpdateTask methods

    @Override
    public void run() {
        _isRunning = true;
        try {
            update();
        } catch (Throwable t) {
            _mgr.notifyTaskFailed(this, "", t);
        } finally {
            _isRunning = false;
        }
    }

    /**
     *  Loop through the entire list of update URLs.
     *  For each one, first get the version from the first 56 bytes and see if
     *  it is newer than what we are running now.
     *  If it is, get the whole thing.
     */
    protected void update() {
        // Do a PartialEepGet on the selected URL, check for version we expect,
        // and loop if it isn't what we want.
        // This will allows us to do a release without waiting for the last host to install the update.
        // Alternative: In bytesTransferred(), Check the data in the output file after
        // we've received at least 56 bytes. Need a cancel() method in EepGet ?

        boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
        String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
        int proxyPort = ConfigUpdateHandler.proxyPort(_context);

        if (_urls.isEmpty()) {
            // not likely, don't bother translating
            updateStatus("<b>Update source list is empty, cannot download update</b>");
            _log.error("Update source list is empty - cannot download update");
            _mgr.notifyTaskFailed(this, "", null);
            return;
        }

        for (URI uri : _urls) {
            _currentURI = uri;
            String updateURL = uri.toString();
            updateStatus("<b>" + _("Updating from {0}", linkify(updateURL)) + "</b>");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Selected update URL: " + updateURL);

            // Check the first 56 bytes for the version
            if (shouldProxy) {
                _isPartial = true;
                _baos.reset();
                try {
                    // no retries
                    _get = new PartialEepGet(_context, proxyHost, proxyPort, _baos, updateURL, TrustedUpdate.HEADER_BYTES);
                    _get.addStatusListener(UpdateRunner.this);
                    _get.fetch(CONNECT_TIMEOUT);
                } catch (Throwable t) {
                }
                _isPartial = false;
                if (_newVersion == null)
                    continue;
            }

            // Now get the whole thing
            try {
                if (shouldProxy)
                    // 40 retries!!
                    _get = new EepGet(_context, proxyHost, proxyPort, 40, _updateFile, updateURL, false);
                else
                    _get = new EepGet(_context, 1, _updateFile, updateURL, false);
                _get.addStatusListener(UpdateRunner.this);
                _get.fetch(CONNECT_TIMEOUT, -1, shouldProxy ? INACTIVITY_TIMEOUT : NOPROXY_INACTIVITY_TIMEOUT);
            } catch (Throwable t) {
                _log.error("Error updating", t);
            }
            if (this.done)
                break;
        }
        (new File(_updateFile)).delete();
        if (!this.done)
            _mgr.notifyTaskFailed(this, "", null);
    }
    
    // EepGet Listeners below.
    // We use the same for both the partial and the full EepGet,
    // with a couple of adjustments depending on which mode.

    public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Attempt failed on " + url, cause);
        // ignored
        _mgr.notifyAttemptFailed(this, url, null);
    }

    /** subclasses should override */
    public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
        if (_isPartial)
            return;
        long d = currentWrite + bytesTransferred;
        String status = "<b>" + _("Updating") + "</b>";
        _mgr.notifyProgress(this, status, d, d + bytesRemaining);
    }

    /** subclasses should override */
    public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
        if (_isPartial) {
            // Compare version with what we have now
            String newVersion = TrustedUpdate.getVersionString(new ByteArrayInputStream(_baos.toByteArray()));
            boolean newer = (new VersionComparator()).compare(newVersion, RouterVersion.VERSION) > 0;
            if (newer) {
                _newVersion = newVersion;
            } else {
                updateStatus("<b>" + _("No new version found at {0}", linkify(url)) + "</b>");
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Found old version \"" + newVersion + "\" at " + url);
            }
            return;
        }

        File tmp = new File(_updateFile);
        if (_mgr.notifyComplete(this, _newVersion, tmp))
            this.done = true;
        else
            tmp.delete();  // corrupt
    }

    /** subclasses should override */
    public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
        // don't display bytesTransferred as it is meaningless
        if (_log.shouldLog(Log.WARN))
            _log.warn("Update from " + url + " did not download completely (" +
                           bytesRemaining + " remaining after " + currentAttempt + " tries)");
        updateStatus("<b>" + _("Transfer failed from {0}", linkify(url)) + "</b>");
        _mgr.notifyAttemptFailed(this, url, null);
        // update() will call notifyTaskFailed() after last URL
    }

    public void headerReceived(String url, int attemptNum, String key, String val) {}

    public void attempting(String url) {}

    protected void updateStatus(String s) {
        _mgr.notifyProgress(this, s);
    }

    protected static String linkify(String url) {
        return ConsoleUpdateManager.linkify(url);
    }

    /** translate a string */
    protected String _(String s) {
        return _mgr._(s);
    }

    /**
     *  translate a string with a parameter
     */
    protected String _(String s, Object o) {
        return _mgr._(s, o);
    }

    @Override
    public String toString() {
        return getClass().getName() + ' ' + getType() + ' ' + getID() + ' ' + getMethod() + ' ' + getURI();
    }
}
