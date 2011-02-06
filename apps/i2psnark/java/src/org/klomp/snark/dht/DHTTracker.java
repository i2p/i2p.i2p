package org.klomp.snark.dht;
/*
 *  From zzzot, relicensed to GPLv2
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * The tracker stores peers, i.e. Dest hashes (not nodes).
 *
 * @since 0.8.4
 * @author zzz
 */
class DHTTracker {

    private final I2PAppContext _context;
    private final Torrents _torrents;
    private long _expireTime;
    private final Log _log;

    /** stagger with other cleaners */
    private static final long CLEAN_TIME = 199*1000;
    /** make this longer than postman's tracker */
    private static final long MAX_EXPIRE_TIME = 95*60*1000;
    private static final long MIN_EXPIRE_TIME = 5*60*1000;
    private static final long DELTA_EXPIRE_TIME = 7*60*1000;
    private static final int MAX_PEERS = 9999;

    DHTTracker(I2PAppContext ctx) {
        _context = ctx;
        _torrents = new Torrents();
        _expireTime = MAX_EXPIRE_TIME;
        _log = _context.logManager().getLog(DHTTracker.class);
        SimpleScheduler.getInstance().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    void stop() {
        _torrents.clear();
        // no way to stop the cleaner
    }

    void announce(InfoHash ih, Hash hash) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Announce " + hash + " for " + ih);
        Peers peers = _torrents.get(ih);
        if (peers == null) {
            peers = new Peers();
            Peers peers2 = _torrents.putIfAbsent(ih, peers);
            if (peers2 != null)
                peers = peers2;
        }

        Peer peer = new Peer(hash.getData());
        Peer peer2 = peers.putIfAbsent(peer, peer);
        if (peer2 != null)
            peer = peer2;
        peer.setLastSeen(_context.clock().now());
    }

    void unannounce(InfoHash ih, Hash hash) {
        Peers peers = _torrents.get(ih);
        if (peers == null)
            return;
        Peer peer = new Peer(hash.getData());
        peers.remove(peer);
    }

    /**
     *  Caller's responsibility to remove himself from the list
     *  @return list or empty list (never null)
     */
    List<Hash> getPeers(InfoHash ih, int max) {
        Peers peers = _torrents.get(ih);
        if (peers == null)
            return Collections.EMPTY_LIST;

        int size = peers.size();
        List<Hash> rv = new ArrayList(peers.values());
        if (max < size) {
                Collections.shuffle(rv, _context.random());
                rv = rv.subList(0, max);
        }
        return rv;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {

        public void timeReached() {
            long now = _context.clock().now();
            int torrentCount = 0;
            int peerCount = 0;
            for (Iterator<Peers> iter = _torrents.values().iterator(); iter.hasNext(); ) {
                Peers p = iter.next();
                int recent = 0;
                for (Iterator<Peer> iterp = p.values().iterator(); iterp.hasNext(); ) {
                     Peer peer = iterp.next();
                     if (peer.lastSeen() < now - _expireTime)
                         iterp.remove();
                     else {
                         recent++;
                         peerCount++;
                     }
                }
                if (recent <= 0)
                    iter.remove();
                else
                    torrentCount++;
            }

            if (peerCount > MAX_PEERS)
                _expireTime = Math.max(_expireTime - DELTA_EXPIRE_TIME, MIN_EXPIRE_TIME);
            else
                _expireTime = Math.min(_expireTime + DELTA_EXPIRE_TIME, MAX_EXPIRE_TIME);

            if (_log.shouldLog(Log.INFO))
                _log.info("DHT tracker cleaner done, now with " +
                         torrentCount + " torrents, " +
                         peerCount + " peers, " +
                         DataHelper.formatDuration(_expireTime) + " expiration");
        }
    }
}
