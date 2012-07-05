package net.i2p.client.streaming;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *  Share important TCP Control Block parameters across Connections
 *  to the same remote peer.
 *  This is intended for "temporal" sharing at connection open/close time,
 *  not "ensemble" sharing during a connection. Ref. RFC 2140.
 *  
 *  There is a TCB share per ConnectionManager (i.e. per local Destination)
 *  so that there is no information leakage to other Destinations on the
 *  same router.
 *
 */
class TCBShare {
    private final I2PAppContext _context;
    private final Log _log;
    private final Map<Destination, Entry> _cache;
    private final CleanEvent _cleaner;

    private static final long EXPIRE_TIME = 30*60*1000;
    private static final long CLEAN_TIME = 10*60*1000;
    private static final double RTT_DAMPENING = 0.75;
    private static final double WDW_DAMPENING = 0.75;
    private static final int MAX_RTT = ((int) Connection.MAX_RESEND_DELAY) / 2;
    private static final int MAX_WINDOW_SIZE = Connection.MAX_WINDOW_SIZE / 4;
    
    public TCBShare(I2PAppContext ctx, SimpleTimer2 timer) {
        _context = ctx;
        _log = ctx.logManager().getLog(TCBShare.class);
        _cache = new ConcurrentHashMap(4);
        _cleaner = new CleanEvent(timer);
        _cleaner.schedule(CLEAN_TIME);
    }

    public void stop() {
        _cleaner.cancel();
    }

    public void updateOptsFromShare(Connection con) {
        Destination dest = con.getRemotePeer();
        if (dest == null)
            return;
        ConnectionOptions opts = con.getOptions();
        if (opts == null)
            return;
        Entry e = _cache.get(dest);
        if (e == null || e.isExpired())
            return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("From cache: " +
                       con.getSession().getMyDestination().calculateHash().toBase64().substring(0, 4) +
                       '-' +
                       dest.calculateHash().toBase64().substring(0, 4) +
                       " RTT: " + e.getRTT() + " wdw: " + e.getWindowSize());
        opts.setRTT(e.getRTT());
        opts.setWindowSize(e.getWindowSize());
    }

    public void updateShareOpts(Connection con) {
        Destination dest = con.getRemotePeer();
        if (dest == null)
            return;
        if (con.getAckedPackets() <= 0)
            return;
        ConnectionOptions opts = con.getOptions();
        if (opts == null)
            return;
        int old = -1;
        int oldw = -1;
        Entry e = _cache.get(dest);
        if (e == null || e.isExpired()) {
            e = new Entry(opts.getRTT(), opts.getWindowSize());
            _cache.put(dest, e);
        } else {
            old = e.getRTT();
            oldw = e.getWindowSize();
            e.setRTT(opts.getRTT());
            e.setWindowSize(opts.getWindowSize());
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("To cache: " +
                       con.getSession().getMyDestination().calculateHash().toBase64().substring(0, 4) +
                       '-' +
                       dest.calculateHash().toBase64().substring(0, 4) +
                       " old: " + old + " con: " + opts.getRTT() + " new: " + e.getRTT() +
                       " oldw: " + oldw + " conw: " + opts.getWindowSize() + " neww: " + e.getWindowSize());
    }

    private class Entry {
        int _rtt;
        int _wdw;
        long _updated;

        public Entry(int ms, int wdw) {
            _rtt = ms;
            _wdw = wdw;
            _updated = _context.clock().now();
        }
        public int getRTT() { return _rtt; }
        public void setRTT(int ms) {
            _rtt = (int)(RTT_DAMPENING*_rtt + (1-RTT_DAMPENING)*ms);        
            if (_rtt > MAX_RTT)
                _rtt = MAX_RTT;
            _updated = _context.clock().now();
        }
        public int getWindowSize() { return _wdw; }
        public void setWindowSize(int wdw) {
            _wdw = (int)(0.5 + WDW_DAMPENING*_wdw + (1-WDW_DAMPENING)*wdw);       
            if (_wdw > MAX_WINDOW_SIZE)
                _wdw = MAX_WINDOW_SIZE;
            _updated = _context.clock().now();
        }
        public boolean isExpired() {
            return _updated < _context.clock().now() - EXPIRE_TIME;
        }
    }

    private class CleanEvent extends SimpleTimer2.TimedEvent {
        public CleanEvent(SimpleTimer2 timer) {
            // Use router's SimpleTimer2
            super(timer);
        }
        public void timeReached() {
            for (Iterator<Entry> iter = _cache.values().iterator(); iter.hasNext(); ) {
                if (iter.next().isExpired())
                    iter.remove();
            }
            schedule(CLEAN_TIME);
        }
    }
}
