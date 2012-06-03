package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1Hash;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 *  All the nodes we know about, stored as a mapping from
 *  node ID to a Destination and Port.
 *  Also uses the keySet as a subsitute for kbuckets.
 *
 *  Swap this out for a real DHT later.
 *
 * @since 0.8.4
 * @author zzz
 */
class DHTNodes extends ConcurrentHashMap<NID, NodeInfo> {

    private final I2PAppContext _context;
    private long _expireTime;
    private final Log _log;

    /** stagger with other cleaners */
    private static final long CLEAN_TIME = 237*1000;
    private static final long MAX_EXPIRE_TIME = 60*60*1000;
    private static final long MIN_EXPIRE_TIME = 5*60*1000;
    private static final long DELTA_EXPIRE_TIME = 7*60*1000;
    private static final int MAX_PEERS = 9999;

    public DHTNodes(I2PAppContext ctx) {
        super();
        _context = ctx;
        _expireTime = MAX_EXPIRE_TIME;
        _log = _context.logManager().getLog(DHTNodes.class);
        SimpleScheduler.getInstance().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /**
     *  Fake DHT
     *  @param sha1 either a InfoHash or a NID
     */
    List<NodeInfo> findClosest(SHA1Hash h, int numWant) {
        // sort the whole thing
        Set<NID> all = new TreeSet(new SHA1Comparator(h));
        all.addAll(keySet());
        int sz = all.size();
        int max = Math.min(numWant, sz);

        // return the first ones
        List<NodeInfo> rv = new ArrayList(max);
        int count = 0;
        for (NID nid : all) {
            if (count++ >= max)
                break;
            NodeInfo nInfo = get(nid);
            if (nInfo == null)
                continue;
            rv.add(nInfo);
        }
        return rv;
    }

  /**** used CHM methods to be replaced:
    public Collection<NodeInfo> values() {}
    public NodeInfo get(NID nID) {}
    public NodeInfo putIfAbssent(NID nID, NodeInfo nInfo) {}
    public int size() {}
  ****/

    /** */
    private class Cleaner implements SimpleTimer.TimedEvent {

        public void timeReached() {
            long now = _context.clock().now();
            int peerCount = 0;
            for (Iterator<NodeInfo> iter = DHTNodes.this.values().iterator(); iter.hasNext(); ) {
                 NodeInfo peer = iter.next();
                 if (peer.lastSeen() < now - _expireTime)
                     iter.remove();
                 else
                     peerCount++;
            }

            if (peerCount > MAX_PEERS)
                _expireTime = Math.max(_expireTime - DELTA_EXPIRE_TIME, MIN_EXPIRE_TIME);
            else
                _expireTime = Math.min(_expireTime + DELTA_EXPIRE_TIME, MAX_EXPIRE_TIME);

            if (_log.shouldLog(Log.INFO))
                _log.info("DHT storage cleaner done, now with " +
                         peerCount + " peers, " +
                         DataHelper.formatDuration(_expireTime) + " expiration");

        }
    }
}
