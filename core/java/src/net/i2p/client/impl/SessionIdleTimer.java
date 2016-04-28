package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 *
 */

import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
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
    private final Log _log;
    private final I2PAppContext _context;
    private final I2PSessionImpl _session;
    private final boolean _reduceEnabled;
    private final int _reduceQuantity;
    private final long _reduceTime;
    private final boolean _shutdownEnabled;
    private final long _shutdownTime;
    private final long _minimumTime;
    private long _lastActive;

    /**
     *  reduce, shutdown, or both must be true
     */
    public SessionIdleTimer(I2PAppContext context, I2PSessionImpl session, boolean reduce, boolean shutdown) {
        if (! (reduce || shutdown))
            throw new IllegalArgumentException("At least one must be enabled");
        _context = context;
        _log = context.logManager().getLog(SessionIdleTimer.class);
        _session = session;
        Properties props = session.getOptions();
        long minimumTime = Long.MAX_VALUE;
        long reduceTime = 0;
        long shutdownTime = 0;
        int reduceQuantity = 0;
        if (reduce) {
            reduceQuantity = 1;
            String p = props.getProperty("i2cp.reduceQuantity");
            if (p != null) {
                try {
                    reduceQuantity = Math.max(Integer.parseInt(p), 1);
                    // also check vs. configured quantities?
                } catch (NumberFormatException nfe) {}
            }
            reduceTime = DEFAULT_REDUCE_TIME;
            p = props.getProperty("i2cp.reduceIdleTime");
            if (p != null) {
                try {
                    reduceTime = Math.max(Long.parseLong(p), MINIMUM_TIME);
                } catch (NumberFormatException nfe) {}
            }
            minimumTime = reduceTime;
        }
        if (shutdown) {
            shutdownTime = DEFAULT_CLOSE_TIME;
            String p = props.getProperty("i2cp.closeIdleTime");
            if (p != null) {
                try {
                    shutdownTime = Math.max(Long.parseLong(p), MINIMUM_TIME);
                } catch (NumberFormatException nfe) {}
            }
            minimumTime = Math.min(minimumTime, shutdownTime);
            if (reduce && shutdownTime <= reduceTime)
                reduce = false;
        }
        _reduceEnabled = reduce;
        _reduceQuantity = reduceQuantity;
        _reduceTime = reduceTime;
        _shutdownEnabled = shutdown;
        _shutdownTime = shutdownTime;
        _minimumTime = minimumTime;
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
        _context.simpleTimer2().addEvent(this, nextDelay);
    }
}
