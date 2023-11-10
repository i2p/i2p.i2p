package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;

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
    private final ObjectCounter<ReplyTunnel> counter;
    /** the id of this is -1 */
    private static final TunnelId DUMMY_ID = new TunnelId();
    private static final int DEFAULT_MAX_LOOKUPS = 14;
    private static final int DEFAULT_MAX_NON_FF_LOOKUPS = 3;
    private static final long DEFAULT_CLEAN_TIME = 2*60*1000;
    private final int MAX_LOOKUPS;
    private final int MAX_NON_FF_LOOKUPS;
    private final long CLEAN_TIME;
    private final FloodfillNetworkDatabaseFacade _facade;
    private int _max;

    LookupThrottler(FloodfillNetworkDatabaseFacade facade) {
        this(facade, DEFAULT_MAX_LOOKUPS, DEFAULT_MAX_NON_FF_LOOKUPS, DEFAULT_CLEAN_TIME);
    }

    /**
     *  @param maxlookups when floodfill
     *  @param maxnonfflookups when not floodfill
     *  @since 0.9.60
     */
    LookupThrottler(FloodfillNetworkDatabaseFacade facade, int maxlookups, int maxnonfflookups, long cleanTime) {
        _facade = facade;
        MAX_LOOKUPS = maxlookups;
        MAX_NON_FF_LOOKUPS = maxnonfflookups;
        CLEAN_TIME = cleanTime;
        this.counter = new ObjectCounter<ReplyTunnel>();
        SimpleTimer2.getInstance().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /**
     * increments before checking
     * @param key non-null
     * @param id null if for direct lookups
     */
    boolean shouldThrottle(Hash key, TunnelId id) {
        return this.counter.increment(new ReplyTunnel(key, id)) > _max;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            LookupThrottler.this.counter.clear();
            _max = _facade.floodfillEnabled() ? MAX_LOOKUPS : MAX_NON_FF_LOOKUPS;
        }
    }

    /** yes, we could have a two-level lookup, or just do h.tostring() + id.tostring() */
    private static class ReplyTunnel {
        public final Hash h;
        public final TunnelId id;

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
