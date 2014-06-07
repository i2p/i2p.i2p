package net.i2p.client.streaming.impl;

import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import static net.i2p.client.streaming.impl.I2PSocketOptionsImpl.getDouble;
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
    private final double _rttDampening, _wdwDampening, _rttDevDampening;

    private static final long EXPIRE_TIME = 15*60*1000;
    private static final long CLEAN_TIME = 5*60*1000;
    ///// constants defined in rfc 2140
    ///// do not change unless you know what you're doing
    private static final double RTT_DAMPENING = 0.75;
    private static final double RTTDEV_DAMPENING = 0.75;
    private static final double WDW_DAMPENING = 0.75;
    private static final String RTT_DAMP_PROP="i2p.streaming.tcbcache.rttDampening";
    private static final String WDW_DAMP_PROP="i2p.streaming.tcbcache.wdwDampening";
    private static final String RTTDEV_DAMP_PROP="i2p.streaming.tcbcache.rttdevDampening";
    /////
    private static final int MAX_RTT = ((int) Connection.MAX_RESEND_DELAY) / 2;
    private static final int MAX_RTT_DEV = (int) (MAX_RTT * 1.5);
    private static final int MAX_WINDOW_SIZE = ConnectionPacketHandler.MAX_SLOW_START_WINDOW;
    
    public TCBShare(I2PAppContext ctx, SimpleTimer2 timer) {
        _context = ctx;
        _log = ctx.logManager().getLog(TCBShare.class);
        
        final Properties props = ctx.getProperties();
        _rttDampening = getDouble(props, RTT_DAMP_PROP, RTT_DAMPENING);
        _wdwDampening = getDouble(props, WDW_DAMP_PROP, WDW_DAMPENING);
        _rttDevDampening = getDouble(props, RTTDEV_DAMP_PROP, RTTDEV_DAMPENING);
        
        _cache = new ConcurrentHashMap<Destination,Entry>(4);
        _cleaner = new CleanEvent(timer);
        _cleaner.schedule(CLEAN_TIME);
        
        if (_log.shouldLog(Log.DEBUG)) {
            String log = "Creating TCBCache with rttDamp=%s, rttDevDamp=%s, wdwDamp=%s, "+
                    "expire=%d, clean=%d";
            log = String.format(log,_rttDampening,_rttDevDampening,_wdwDampening,
                    EXPIRE_TIME,CLEAN_TIME);
             _log.debug(log);
        }
    }

    /**
     *  Cannot be restarted.
     */
    public void stop() {
        _cleaner.cancel();
        _cache.clear();
    }

    /** retrieve from cache */
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
        final int rtt, rttDev, wdw;
        synchronized(e) {
            rtt = e.getRTT();
            rttDev = e.getRTTDev();
            wdw = e.getWindowSize();
        }
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("From cache: " +
                       con.getSession().getMyDestination().calculateHash().toBase64().substring(0, 4) +
                       '-' +
                       dest.calculateHash().toBase64().substring(0, 4) +
                       " RTT: " + rtt + 
                       " RTTDev: "+ rttDev +
                       " wdw: " + wdw );
        }
        opts.loadFromCache(rtt,rttDev,wdw);
    }

    /** store to cache */
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
        int oldDev = -1;
        Entry e = _cache.get(dest);
        if (e == null || e.isExpired()) {
            e = new Entry(opts.getRTT(), opts.getWindowSize(), opts.getRTTDev());
            _cache.put(dest, e);
        } else {
            synchronized(e) {
                old = e.getRTT();
                oldw = e.getWindowSize();
                oldDev = e.getRTTDev();
                e.setRTT(opts.getRTT());
                e.setWindowSize(opts.getWindowSize());
                e.setRTTDev(opts.getRTTDev());
            }
        }
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("To cache: " +
                       con.getSession().getMyDestination().calculateHash().toBase64().substring(0, 4) +
                       '-' +
                       dest.calculateHash().toBase64().substring(0, 4) +
                       " old: " + old + " con: " + opts.getRTT() + " new: " + e.getRTT() +
                       " oldDev: " + oldDev + " conDev: " + opts.getRTTDev() + " newDev: " + e.getRTTDev() +
                       " oldw: " + oldw + " conw: " + opts.getWindowSize() + " neww: " + e.getWindowSize());
        }
    }

    private class Entry {
        int _rtt;
        int _wdw;
        int _rttDev;
        long _updated;

        public Entry(int ms, int wdw, int rttDev) {
            _rtt = ms;
            _wdw = wdw;
            _rttDev = rttDev;
            _updated = _context.clock().now();
        }
        public synchronized int getRTT() { return _rtt; }
        public synchronized void setRTT(int ms) {
            _rtt = (int)(_rttDampening*_rtt + (1-_rttDampening)*ms);        
            if (_rtt > MAX_RTT)
                _rtt = MAX_RTT;
            _updated = _context.clock().now();
        }
        public synchronized int getRTTDev() { return _rttDev; }
        public synchronized void setRTTDev(int count) {
            _rttDev = (int)(_rttDevDampening*_rttDev + (1-_rttDevDampening)*count);        
            if (_rttDev > MAX_RTT_DEV)
                _rttDev = MAX_RTT_DEV;
            _updated = _context.clock().now();
        }
        public synchronized int getWindowSize() { return _wdw; }
        public synchronized void setWindowSize(int wdw) {
            _wdw = (int)(0.5 + _wdwDampening*_wdw + (1-_wdwDampening)*wdw);       
            if (_wdw > MAX_WINDOW_SIZE)
                _wdw = MAX_WINDOW_SIZE;
            _updated = _context.clock().now();
        }
        public synchronized boolean isExpired() {
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
