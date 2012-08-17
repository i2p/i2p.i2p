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
import net.i2p.util.SimpleTimer2;

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
    private volatile boolean _isRunning;
    /** not current, updated by cleaner */
    private int _peerCount;
    /** not current, updated by cleaner */
    private int _torrentCount;

    /** stagger with other cleaners */
    private static final long CLEAN_TIME = 199*1000;
    private static final long MAX_EXPIRE_TIME = 45*60*1000;
    private static final long MIN_EXPIRE_TIME = 15*60*1000;
    private static final long DELTA_EXPIRE_TIME = 3*60*1000;
    private static final int MAX_PEERS = 2000;
    private static final int MAX_PEERS_PER_TORRENT = 150;

    DHTTracker(I2PAppContext ctx) {
        _context = ctx;
        _torrents = new Torrents();
        _expireTime = MAX_EXPIRE_TIME;
        _log = _context.logManager().getLog(DHTTracker.class);
    }

    public void start() {
        _isRunning = true;
        new Cleaner();
    }

    void stop() {
        _torrents.clear();
        _isRunning = false;
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

    /**
     * Debug info, HTML formatted
     */
    public void renderStatusHTML(StringBuilder buf) {
        buf.append("DHT tracker: ").append(_torrentCount).append(" torrents ")
           .append(_peerCount).append(" peers ")
           .append(DataHelper.formatDuration(_expireTime)).append(" expiration<br>");
    }

    private class Cleaner extends SimpleTimer2.TimedEvent {

        public Cleaner() {
            super(SimpleTimer2.getInstance(), CLEAN_TIME);
        }

        public void timeReached() {
            if (!_isRunning)
                return;
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
                if (recent > MAX_PEERS_PER_TORRENT) {
                    // too many, delete at random
                    // TODO per-torrent adjustable expiration?
                    for (Iterator<Peer> iterp = p.values().iterator(); iterp.hasNext() && p.size() > MAX_PEERS_PER_TORRENT; ) {
                         iterp.next();
                         iterp.remove();
                         peerCount--;
                    }
                    torrentCount++;
                } else if (recent <= 0) {
                    iter.remove();
                } else {
                    torrentCount++;
                }
            }

            if (peerCount > MAX_PEERS)
                _expireTime = Math.max(_expireTime - DELTA_EXPIRE_TIME, MIN_EXPIRE_TIME);
            else
                _expireTime = Math.min(_expireTime + DELTA_EXPIRE_TIME, MAX_EXPIRE_TIME);

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("DHT tracker cleaner done, now with " +
                         torrentCount + " torrents, " +
                         peerCount + " peers, " +
                         DataHelper.formatDuration(_expireTime) + " expiration");
            _peerCount = peerCount;
            _torrentCount = torrentCount;
            schedule(CLEAN_TIME);
        }
    }
}
