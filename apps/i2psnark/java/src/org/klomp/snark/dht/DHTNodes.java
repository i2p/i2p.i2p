package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1Hash;
import net.i2p.data.DataHelper;
import net.i2p.kademlia.KBucketSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *  All the nodes we know about, stored as a mapping from
 *  node ID to a Destination and Port.
 *
 *  And a real Kademlia routing table, which stores node IDs only.
 *
 * @since 0.8.4
 * @author zzz
 */
class DHTNodes {

    private final I2PAppContext _context;
    private long _expireTime;
    private final Log _log;
    private final ConcurrentHashMap<NID, NodeInfo> _nodeMap;
    private final KBucketSet<NID> _kad;
    private volatile boolean _isRunning;

    /** stagger with other cleaners */
    private static final long CLEAN_TIME = 187*1000;
    /** how long since last heard from do we delete  - BEP 5 says 15 minutes */
    private static final long MAX_EXPIRE_TIME = 30*60*1000;
    private static final long MIN_EXPIRE_TIME = 10*60*1000;
    private static final long DELTA_EXPIRE_TIME = 3*60*1000;
    private static final int MAX_PEERS = 799;

    public DHTNodes(I2PAppContext ctx, NID me) {
        _context = ctx;
        _expireTime = MAX_EXPIRE_TIME;
        _log = _context.logManager().getLog(DHTNodes.class);
        _nodeMap = new ConcurrentHashMap();
        _kad = new KBucketSet(ctx, me, 8, 1);
    }

    public void start() {
        _isRunning = true;
        new Cleaner();
    }

    public void stop() {
        clear();
        _isRunning = false;
    }

    // begin ConcurrentHashMap methods

    public int size() {
        return _nodeMap.size();
    }

    public void clear() {
        _kad.clear();
        _nodeMap.clear();
    }

    public NodeInfo get(NID nid) {
        return _nodeMap.get(nid);
    }

    /**
     *  @return the old value if present, else null
     */
    public NodeInfo putIfAbsent(NodeInfo nInfo) {
        _kad.add(nInfo.getNID());
        return _nodeMap.putIfAbsent(nInfo.getNID(), nInfo);
    }

    public NodeInfo remove(NID nid) {
        _kad.remove(nid);
        return _nodeMap.remove(nid);
    }

    public Collection<NodeInfo> values() {
        return _nodeMap.values();
    }

    // end ConcurrentHashMap methods

    /**
     *  DHT
     *  @param h either a InfoHash or a NID
     */
    public List<NodeInfo> findClosest(SHA1Hash h, int numWant) {
        NID key;
        if (h instanceof NID)
            key = (NID) h;
        else
            key = new NID(h.getData());
        List<NID> keys = _kad.getClosest(key, numWant);
        List<NodeInfo> rv = new ArrayList(keys.size());
        for (NID nid : keys) {
            NodeInfo ninfo = _nodeMap.get(nid);
            if (ninfo != null)
                rv.add(ninfo);
        }
        return rv;
    }

    /**
     *  DHT - get random keys to explore
     */
    public List<NID> getExploreKeys() {
        return _kad.getExploreKeys(15*60*1000);
    }

    /** */
    private class Cleaner extends SimpleTimer2.TimedEvent {

        public Cleaner() {
            super(SimpleTimer2.getInstance(), CLEAN_TIME);
        }

        public void timeReached() {
            if (!_isRunning)
                return;
            long now = _context.clock().now();
            int peerCount = 0;
            for (Iterator<NodeInfo> iter = DHTNodes.this.values().iterator(); iter.hasNext(); ) {
                 NodeInfo peer = iter.next();
                 if (peer.lastSeen() < now - _expireTime) {
                     iter.remove();
                     _kad.remove(peer.getNID());
                 } else {
                     peerCount++;
                }
            }

            if (peerCount > MAX_PEERS)
                _expireTime = Math.max(_expireTime - DELTA_EXPIRE_TIME, MIN_EXPIRE_TIME);
            else
                _expireTime = Math.min(_expireTime + DELTA_EXPIRE_TIME, MAX_EXPIRE_TIME);

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("DHT storage cleaner done, now with " +
                         peerCount + " peers, " +
                         DataHelper.formatDuration(_expireTime) + " expiration");

            schedule(CLEAN_TIME);
        }
    }
}
