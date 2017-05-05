package net.i2p.router.networkdb.reseed;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 *  Moved from RouterConsoleRunner.java
 *
 *  Reseeding is not strictly a router function, it used to be
 *  in the routerconsole app, but this made it impossible to
 *  bootstrap an embedded router lacking a routerconsole,
 *  in iMule or android for example, without additional modifications.
 *
 *  Also, as this is now called from PersistentDataStore, not from the
 *  routerconsole, we can get started as soon as the netdb has read
 *  the netDb/ directory, not when the console starts.
 */
public class ReseedChecker {
    
    private final RouterContext _context;
    private final Log _log;
    private final AtomicBoolean _inProgress = new AtomicBoolean();
    private volatile String _lastStatus = "";
    private volatile String _lastError = "";
    private volatile boolean _networkLogged;

    public static final int MINIMUM = 50;
    private static final long STATUS_CLEAN_TIME = 20*60*1000;

    /**
     *  All reseeding must be done through this instance.
     *  Access through context.netDb().reseedChecker(), others should not instantiate
     *
     *  @since 0.9
     */
    public ReseedChecker(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(ReseedChecker.class);
    }

    /**
     *  Check if a reseed is needed, and start it
     *
     *  @param count current number of known routers, includes us
     *  @return true if a reseed was started
     */
    public boolean checkReseed(int count) {
        if (count >= MINIMUM)
            return false;

        if (_context.getBooleanProperty(Reseeder.PROP_DISABLE)) {
            int x = count - 1;  // us
            // no ngettext, this is rare
            String s;
            if (x > 0)
                s = "Only " + x + " peers remaining but reseed disabled by configuration";
            else
                s = "No peers remaining but reseed disabled by configuration";
            _lastError = s;
            _log.logAlways(Log.WARN, s);
            return false;
        }

        if (_context.router().gracefulShutdownInProgress()) {
            int x = count - 1;
            // no ngettext, this is rare
            String s;
            if (x > 0)
                s = "Only " + x + " peers remaining but reseed disabled by shutdown in progress";
            else
                s = "No peers remaining but reseed disabled by shutdown in progress";
            _lastError = s;
            _log.logAlways(Log.WARN, s);
            return false;
        }

        // we check the i2p installation directory for a flag telling us not to reseed, 
        // but also check the home directory for that flag too, since new users installing i2p
        // don't have an installation directory that they can put the flag in yet.
        File noReseedFile = new File(new File(System.getProperty("user.home")), ".i2pnoreseed");
        File noReseedFileAlt1 = new File(new File(System.getProperty("user.home")), "noreseed.i2p");
        File noReseedFileAlt2 = new File(_context.getConfigDir(), ".i2pnoreseed");
        File noReseedFileAlt3 = new File(_context.getConfigDir(), "noreseed.i2p");
        if (!noReseedFile.exists() && !noReseedFileAlt1.exists() && !noReseedFileAlt2.exists() && !noReseedFileAlt3.exists()) {
            if (!Addresses.isConnected()) {
                if (!_networkLogged) {
                    _log.logAlways(Log.WARN, "Cannot reseed, no network connection");
                    _networkLogged = true;
                }
                return false;
            }
            _networkLogged = false;
            if (count <= 1)
                _log.logAlways(Log.INFO, "Downloading peer router information for a new I2P installation");
            else
                _log.logAlways(Log.WARN, "Very few known peers remaining - reseeding now");
            return requestReseed();
        } else {
            int x = count - 1;  // us
            // no ngettext, this is rare
            String s;
            if (x > 0)
                s = "Only " + x + " peers remaining but reseed disabled by config file";
            else
                s = "No peers remaining but reseed disabled by config file";
            _lastError = s;
            _log.logAlways(Log.WARN, s);
            return false;
        }
    }

    /**
     *  Start a reseed
     *
     *  @return true if a reseed was started, false if already in progress
     *  @since 0.9
     */
    public boolean requestReseed() {
        if (_inProgress.compareAndSet(false, true)) {
            try {
                Reseeder reseeder = new Reseeder(_context, this);
                reseeder.requestReseed();
                return true;
            } catch (Throwable t) {
                _log.error("Reseed failed to start", t);
                done();
                return false;
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Reseed already in progress");
            return false;
        }
    }

    /**
     *  Start a reseed from a zip or su3 URI.
     *
     *  @return true if a reseed was started, false if already in progress
     *  @throws IllegalArgumentException if it doesn't end with zip or su3
     *  @since 0.9.19
     */
    public boolean requestReseed(URI url) throws IllegalArgumentException {
        if (_inProgress.compareAndSet(false, true)) {
            Reseeder reseeder = new Reseeder(_context, this);
            try {
                reseeder.requestReseed(url);
                return true;
            } catch (IllegalArgumentException iae) {
                if (iae.getMessage() != null)
                    setError(DataHelper.escapeHTML(iae.getMessage()));
                done();
                throw iae;
            } catch (Throwable t) {
                _log.error("Reseed failed to start", t);
                done();
                return false;
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Reseed already in progress");
            return false;
        }
    }

    /**
     *  Reseed from a zip or su3 input stream. Blocking.
     *
     *  @return true if a reseed was started, false if already in progress
     *  @throws IOException if already in progress or on most other errors
     *  @since 0.9.19
     */
    public int requestReseed(InputStream in) throws IOException {
        // don't really need to check for in progress here
        if (_inProgress.compareAndSet(false, true)) {
            try {
                Reseeder reseeder = new Reseeder(_context, this);
                return reseeder.requestReseed(in);
            } catch (IOException ioe) {
                if (ioe.getMessage() != null)
                    setError(DataHelper.escapeHTML(ioe.getMessage()));
                throw ioe;
            } finally {
                done();
            }
        } else {
            throw new IOException("Reseed already in progress");
        }
    }

    /**         .
     *  Is a reseed in progress?
     *
     *  @since 0.9
     */
    public boolean inProgress() {
        return _inProgress.get();
    }

    /**
     *  The reseed is complete
     *
     *  @since 0.9
     */
    void done() {
        _inProgress.set(false);
        _context.simpleTimer2().addEvent(new StatusCleaner(_lastStatus, _lastError), STATUS_CLEAN_TIME);
    }

    /**
     *  Status from current reseed attempt,
     *  probably empty if no reseed in progress.
     *  May include HTML.
     *
     *  @return non-null, may be empty
     *  @since 0.9
     */
    public String getStatus() {
        return _lastStatus;
    }

    /**
     *  Status from current reseed attempt
     *
     *  @param s non-null, may be empty
     *  @since 0.9
     */
    void setStatus(String s) {
        _lastStatus = s;
    }

    /**
     *  Error from last or current reseed attempt.
     *  May include HTML.
     *
     *  @return non-null, may be empty
     *  @since 0.9
     */
    public String getError() {
        return _lastError;
    }

    /**
     *  Status from last or current reseed attempt
     *
     *  @param s non-null, may be empty
     *  @since 0.9
     */
    void setError(String s) {
        _lastError = s;
    }

    /**
     *  @since 0.9.19
     */
    private class StatusCleaner implements SimpleTimer.TimedEvent {
        private final String _status, _error;

        public StatusCleaner(String status, String error) {
            _status = status;
            _error = error;
        }

        public void timeReached() {
            if (_status.equals(getStatus()))
                setStatus("");
            if (_error.equals(getError()))
                setError("");
        }
    }
}
