package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 *
 */

import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Reduce tunnels or shutdown the session on idle if so configured
 *
 * @author zzz
 */
class SessionIdleTimer implements SimpleTimer.TimedEvent {
    public static final long MINIMUM_TIME = 5*60*1000;
    private static final long DEFAULT_REDUCE_TIME = 20*60*1000;
    private static final long DEFAULT_CLOSE_TIME = 30*60*1000;
    private final static Log _log = new Log(SessionIdleTimer.class);
    private final I2PAppContext _context;
    private final I2PSessionImpl _session;
    private boolean _reduceEnabled;
    private int _reduceQuantity;
    private long _reduceTime;
    private boolean _shutdownEnabled;
    private long _shutdownTime;
    private long _minimumTime;
    private long _lastActive;

    /**
     *  reduce, shutdown, or both must be true
     */
    public SessionIdleTimer(I2PAppContext context, I2PSessionImpl session, boolean reduce, boolean shutdown) {
        _context = context;
        _session = session;
        _reduceEnabled = reduce;
        _shutdownEnabled = shutdown;
        if (! (reduce || shutdown))
            throw new IllegalArgumentException("At least one must be enabled");
        Properties props = session.getOptions();
        _minimumTime = Long.MAX_VALUE;
        _lastActive = 0;
        if (reduce) {
            _reduceQuantity = 1;
            String p = props.getProperty("i2cp.reduceQuantity");
            if (p != null) {
                try {
                    _reduceQuantity = Math.max(Integer.parseInt(p), 1);
                    // also check vs. configured quantities?
                } catch (NumberFormatException nfe) {}
            }
            _reduceTime = DEFAULT_REDUCE_TIME;
            p = props.getProperty("i2cp.reduceIdleTime");
            if (p != null) {
                try {
                    _reduceTime = Math.max(Long.parseLong(p), MINIMUM_TIME);
                } catch (NumberFormatException nfe) {}
            }
            _minimumTime = _reduceTime;
        }
        if (shutdown) {
            _shutdownTime = DEFAULT_CLOSE_TIME;
            String p = props.getProperty("i2cp.closeIdleTime");
            if (p != null) {
                try {
                    _shutdownTime = Math.max(Long.parseLong(p), MINIMUM_TIME);
                } catch (NumberFormatException nfe) {}
            }
            _minimumTime = Math.min(_minimumTime, _shutdownTime);
            if (reduce && _shutdownTime <= _reduceTime)
                reduce = false;
        }
    }

    public void timeReached() {
        if (_session.isClosed())
            return;
        long now = _context.clock().now();
        long lastActivity = _session.lastActivity();
        if (_log.shouldLog(Log.INFO))
            _log.info("Fire idle timer, last activity: " + DataHelper.formatDuration(now - lastActivity) + " ago ");
        long nextDelay = 0;
        if (_shutdownEnabled && now - lastActivity >= _shutdownTime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Closing on idle " + _session);
            _session.destroySession();
            return;
        } else if (lastActivity <= _lastActive && !_shutdownEnabled) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Still idle, sleeping again " + _session);
            nextDelay = _reduceTime;
        } else if (_reduceEnabled && now - lastActivity >= _reduceTime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Reducing quantity on idle " + _session);
            try {
                _session.getProducer().updateTunnels(_session, _reduceQuantity);
            } catch (I2PSessionException ise) {
                _log.error("bork idle reduction " + ise);
            }
            _session.setReduced();
            _lastActive = lastActivity;
            if (_shutdownEnabled)
                nextDelay =  _shutdownTime - (now - lastActivity);
            else
                nextDelay =  _reduceTime;
        } else {
            nextDelay = _minimumTime - (now - lastActivity);
        }
        _context.simpleScheduler().addEvent(this, nextDelay);
    }
}
