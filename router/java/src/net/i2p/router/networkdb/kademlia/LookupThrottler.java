package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Count how often we have recently received a lookup request with
 * the reply specified to go to a peer/TunnelId pair.
 * This offers basic DOS protection but is not a complete solution.
 * The reply peer/tunnel could be spoofed, for example.
 * And a requestor could have up to 6 reply tunnels.
 *
 * @since 0.7.11
 */
class LookupThrottler {
    private ObjectCounter<ReplyTunnel> counter;
    /** the id of this is -1 */
    private static final TunnelId DUMMY_ID = new TunnelId();
    /** this seems like plenty */
    private static final int MAX_LOOKUPS = 30;
    private static final long CLEAN_TIME = 60*1000;

    LookupThrottler() {
        this.counter = new ObjectCounter();
        SimpleScheduler.getInstance().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /**
     * increments before checking
     * @param key non-null
     * @param id null if for direct lookups
     */
    boolean shouldThrottle(Hash key, TunnelId id) {
        return this.counter.increment(new ReplyTunnel(key, id)) > MAX_LOOKUPS;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            LookupThrottler.this.counter.clear();
        }
    }

    /** yes, we could have a two-level lookup, or just do h.tostring() + id.tostring() */
    private static class ReplyTunnel {
        public Hash h;
        public TunnelId id;

        ReplyTunnel(Hash h, TunnelId id) {
            this.h = h;
            if (id != null)
                this.id = id;
            else
                this.id = DUMMY_ID;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || !(obj instanceof ReplyTunnel))
                return false;
            return this.h.equals(((ReplyTunnel)obj).h) &&
                   this.id.equals(((ReplyTunnel)obj).id);
        }
    
        @Override
        public int hashCode() {
            return this.h.hashCode() ^ this.id.hashCode(); 
        }
    }
}
