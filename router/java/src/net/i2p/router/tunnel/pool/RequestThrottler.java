package net.i2p.router.tunnel.pool;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;

/**
 * Like ParticipatingThrottler, but checked much earlier,
 * cleaned more frequently, and with more than double the min and max limits.
 * This is called before the request is queued or decrypted.
 *
 * @since 0.9.5
 */
class RequestThrottler {
    private final RouterContext context;
    private final ObjectCounter<Hash> counter;
    private final Log _log;

    /** portion of the tunnel lifetime */
    private static final int LIFETIME_PORTION = 6;
    private static final int MIN_LIMIT = 45 / LIFETIME_PORTION;
    private static final int MAX_LIMIT = 165 / LIFETIME_PORTION;
    private static final int PERCENT_LIMIT = 12 / LIFETIME_PORTION;
    private static final long CLEAN_TIME = 11*60*1000 / LIFETIME_PORTION;

    RequestThrottler(RouterContext ctx) {
        this.context = ctx;
        this.counter = new ObjectCounter<Hash>();
        _log = ctx.logManager().getLog(RequestThrottler.class);
        ctx.simpleTimer2().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /** increments before checking */
    boolean shouldThrottle(Hash h) {
        int numTunnels = this.context.tunnelManager().getParticipatingCount();
        int limit = Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, numTunnels * PERCENT_LIMIT / 100));
        int count = counter.increment(h);
        boolean rv = count > limit;
/*
        if (rv && count == 2 * limit) {
            context.banlist().banlistRouter(h, "Excess tunnel requests", null,
                                            context.banlist().BANLIST_CODE_HARD, null,
                                            context.clock().now() + 30*60*1000);
            // drop after any accepted tunnels have expired
            context.simpleTimer2().addEvent(new Disconnector(h), 11*60*1000);
            if (_log.shouldWarn())
                _log.warn("Banning router for excess tunnel requests, limit: " + limit + " count: " + count + ' ' + h.toBase64());
        }
*/
        return rv;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            RequestThrottler.this.counter.clear();
        }
    }

    /**
     *  @since 0.9.52
     */
/*
    private class Disconnector implements SimpleTimer.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) { this.h = h; }
        public void timeReached() {
            context.commSystem().forceDisconnect(h);
        }
    }
*/
}
