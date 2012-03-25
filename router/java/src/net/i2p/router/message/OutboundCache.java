package net.i2p.router.message;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 *  Helper for OCMOSJ
 *
 * This is the place where we make I2P go fast.
 *
 * We have five static caches.
 * - The LeaseSet cache is used to decide whether to bundle our own leaseset,
 *   which minimizes overhead.
 * - The Lease cache is used to persistently send to the same lease for the destination,
 *   which keeps the streaming lib happy by minimizing out-of-order delivery.
 * - The Tunnel and BackloggedTunnel caches are used to persistently use the same outbound tunnel
 *   for the same destination,
 *   which keeps the streaming lib happy by minimizing out-of-order delivery.
 * - The last reply requested cache ensures that a reply is requested every so often,
 *   so that failed tunnels are recognized.
 *
 *  @since 0.9 moved out of OCMOSJ
 */
public class OutboundCache {

    /**
     * Use the same outbound tunnel as we did for the same destination previously,
     * if possible, to keep the streaming lib happy
     * Use two caches - although a cache of a list of tunnels per dest might be
     * more elegant.
     * Key the caches on the source+dest pair.
     *
     * NOT concurrent.
     */
    final Map<HashPair, TunnelInfo> tunnelCache = new HashMap(64);

    /*
     * NOT concurrent.
     */
    final Map<HashPair, TunnelInfo> backloggedTunnelCache = new HashMap(64);

    /**
      * Returns the reply lease set if forced to do so,
      * or if configured to do so,
      * or if a certain percentage of the time if configured to do so,
      * or if our lease set has changed since we last talked to them,
      * or 10% of the time anyway so they don't forget us (disabled for now),
      * or null otherwise.
      *
      * Note that wantACK randomly forces us another 5% of the time.
      *
      * We don't want to do this too often as a typical 2-lease leaseset
      * in a DatabaseStoreMessage is 861+37=898 bytes -
      * when added to garlic it's a 1056-byte addition total, which is huge.
      *
      * Key the cache on the source+dest pair.
      *
      * Concurrent.
      */
    final Map<HashPair, LeaseSet> leaseSetCache = new ConcurrentHashMap(64);

    /**
     * Use the same inbound tunnel (i.e. lease) as we did for the same destination previously,
     * if possible, to keep the streaming lib happy
     * Key the caches on the source+dest pair.
     *
     * We're going to use the lease until it expires, as long as it remains in the current leaseSet.
     *
     * If not found,
     * fetch the next lease that we should try sending through, randomly chosen
     * from within the sorted leaseSet (NOT sorted by # of failures through each 
     * lease).
     *
     * Concurrent.
     */
    final ConcurrentHashMap<HashPair, Lease> leaseCache = new ConcurrentHashMap(64);

    /**
     * This cache is used to ensure that we request a reply every so often.
     * Hopefully this allows the router to recognize a failed tunnel and switch,
     * before upper layers like streaming lib fail, even for low-bandwidth
     * connections like IRC.
     *
     * Concurrent.
     */
    final Map<HashPair, Long> lastReplyRequestCache = new ConcurrentHashMap(64);

    private final RouterContext _context;

    private static final int CLEAN_INTERVAL = 5*60*1000;
    
    public OutboundCache(RouterContext ctx) {
        _context = ctx;
        SimpleScheduler.getInstance().addPeriodicEvent(new OCMOSJCacheCleaner(), CLEAN_INTERVAL, CLEAN_INTERVAL);
    }

    /**
     * Key used to cache things with based on source + dest
     * @since 0.8.3
     */
    static class HashPair {
        private final Hash sh, dh;

        public HashPair(Hash s, Hash d) {
            sh = s;
            dh = d;
        }

        public int hashCode() {
            return sh.hashCode() ^ dh.hashCode();
        }

        public boolean equals(Object o) {
            if (o == null || !(o instanceof HashPair))
                return false;
            HashPair hp = (HashPair) o;
            return sh.equals(hp.sh) && dh.equals(hp.dh);
        }
    }

    /**
     * Called on failure to give us a better chance of success next time.
     * Of course this is probably 60s too late.
     * And we could pick the bad ones at random again.
     * Or remove entries that were sent and succeeded after this was sent but before this failed.
     * But it's a start.
     *
     * @param lease may be null
     * @param inTunnel may be null
     * @param outTunnel may be null
     */
    void clearCaches(HashPair hashPair, Lease lease, TunnelInfo inTunnel, TunnelInfo outTunnel) {
        if (inTunnel != null) {   // if we wanted an ack, we sent our lease too
                leaseSetCache.remove(hashPair);
        }
        if (lease != null) {
            // remove only if still equal to lease (concurrent)
            leaseCache.remove(hashPair, lease);
        }
        if (outTunnel != null) {
            synchronized(tunnelCache) {
                TunnelInfo t = backloggedTunnelCache.get(hashPair);
                if (t != null && t.equals(outTunnel))
                    backloggedTunnelCache.remove(hashPair);
                t = tunnelCache.get(hashPair);
                if (t != null && t.equals(outTunnel))
                    tunnelCache.remove(hashPair);
            }
        }
    }

    /**
     *  @since 0.8.8
     */
    public void clearAllCaches() {
        leaseSetCache.clear();
        leaseCache.clear();
        synchronized(tunnelCache) {
            backloggedTunnelCache.clear();
            tunnelCache.clear();
        }
        lastReplyRequestCache.clear();
    }

    /**
     * Clean out old leaseSets
     */
    private static void cleanLeaseSetCache(RouterContext ctx, Map<HashPair, LeaseSet> tc) {
        long now = ctx.clock().now();
        for (Iterator<LeaseSet> iter = tc.values().iterator(); iter.hasNext(); ) {
            LeaseSet l = iter.next();
            if (l.getEarliestLeaseDate() < now)
                iter.remove();
        }
    }

    /**
     * Clean out old leases
     */
    private static void cleanLeaseCache(Map<HashPair, Lease> tc) {
        for (Iterator<Lease> iter = tc.values().iterator(); iter.hasNext(); ) {
            Lease l = iter.next();
            if (l.isExpired(Router.CLOCK_FUDGE_FACTOR))
                iter.remove();
        }
    }

    /**
     * Clean out old tunnels
     * Caller must synchronize on tc.
     */
    private static void cleanTunnelCache(RouterContext ctx, Map<HashPair, TunnelInfo> tc) {
        for (Iterator<Map.Entry<HashPair, TunnelInfo>> iter = tc.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<HashPair, TunnelInfo> entry = iter.next();
            HashPair k = entry.getKey();
            TunnelInfo tunnel = entry.getValue();
            // This is a little sneaky, but get the _from back out of the "opaque" hash key
            if (!ctx.tunnelManager().isValidTunnel(k.sh, tunnel))
                iter.remove();
        }
    }

    /**
     * Clean out old reply times
     */
    private static void cleanReplyCache(RouterContext ctx, Map<HashPair, Long> tc) {
        long now = ctx.clock().now();
        for (Iterator<Long> iter = tc.values().iterator(); iter.hasNext(); ) {
            Long l = iter.next();
            if (l.longValue() < now - CLEAN_INTERVAL)
                iter.remove();
        }
    }

    private class OCMOSJCacheCleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            cleanLeaseSetCache(_context, leaseSetCache);
            cleanLeaseCache(leaseCache);
            synchronized(tunnelCache) {
                cleanTunnelCache(_context, tunnelCache);
                cleanTunnelCache(_context, backloggedTunnelCache);
            }
            cleanReplyCache(_context, lastReplyRequestCache);
        }
    }
}
