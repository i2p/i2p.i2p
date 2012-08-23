package net.i2p.router.transport.ntcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Pool of running threads which will transform the next I2NP message into
 * something ready to be transferred over an NTCP connection, including the
 * encryption of the data read.
 *
 */
class Writer {
    private final Log _log;
    private final Set<NTCPConnection> _pendingConnections;
    private final Set<NTCPConnection> _liveWrites;
    private final Set<NTCPConnection> _writeAfterLive;
    private final List<Runner> _runners;
    
    public Writer(RouterContext ctx) {
        _log = ctx.logManager().getLog(getClass());
        _pendingConnections = new LinkedHashSet(16);
        _runners = new ArrayList(5);
        _liveWrites = new HashSet(5);
        _writeAfterLive = new HashSet(5);
    }
    
    public void startWriting(int numWriters) {
        for (int i = 1; i <=numWriters; i++) {
            Runner r = new Runner();
            I2PThread t = new I2PThread(r, "NTCP writer " + i + '/' + numWriters, true);
            _runners.add(r);
            t.start();
        }
    }
    public void stopWriting() {
        while (!_runners.isEmpty()) {
            Runner r = _runners.remove(0);
            r.stop();
        }
        synchronized (_pendingConnections) {
            _writeAfterLive.clear();
            _pendingConnections.notifyAll();
        }
    }
    
    public void wantsWrite(NTCPConnection con, String source) {
        //if (con.getCurrentOutbound() != null)
        //    throw new RuntimeException("Current outbound message already in play on " + con);
        boolean already = false;
        boolean pending = false;
        synchronized (_pendingConnections) {
            if (_liveWrites.contains(con)) {
                if (!_writeAfterLive.contains(con)) {
                    _writeAfterLive.add(con);
                }
                already = true;
            } else {
                pending = _pendingConnections.add(con);
            }
            _pendingConnections.notifyAll();
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("wantsWrite: " + con + " already live? " + already + " added to pending? " + pending + ": " + source);
    }

    public void connectionClosed(NTCPConnection con) {
        synchronized (_pendingConnections) {
            _writeAfterLive.remove(con);
            _pendingConnections.remove(con);
            _pendingConnections.notifyAll();
        }
    }
    
    private class Runner implements Runnable {
        private boolean _stop;
        public Runner() { _stop = false; }
        public void stop() { _stop = true; }
        public void run() {
            if (_log.shouldLog(Log.INFO)) _log.info("Starting writer");
            NTCPConnection con = null;
            while (!_stop) {
                try {
                    synchronized (_pendingConnections) {
                        boolean keepWriting = (con != null) && _writeAfterLive.remove(con);
                        if (keepWriting) {
                            // keep on writing the same one
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Keep writing on the same connection: " + con);
                        } else {
                            _liveWrites.remove(con);
                            con = null;
                            if (_pendingConnections.isEmpty()) {
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("Done writing, but nothing pending, so wait");
                                _pendingConnections.wait();
                            } else {
                                Iterator<NTCPConnection> iter = _pendingConnections.iterator();
                                con = iter.next();
                                iter.remove();
                                _liveWrites.add(con);
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("Switch to writing on: " + con);
                            }
                        }
                    }
                } catch (InterruptedException ie) {}
                if (!_stop && (con != null)) {
                    try {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Prepare next write on: " + con);
                        con.prepareNextWrite();
                    } catch (RuntimeException re) {
                        _log.log(Log.CRIT, "Error in the ntcp writer", re);
                    }
                }
            }
            if (_log.shouldLog(Log.INFO)) _log.info("Stopping writer");
        }
    }
}
