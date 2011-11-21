package net.i2p.router.transport.ntcp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Pool of running threads which will process any read bytes on any of the 
 * NTCPConnections, including the decryption of the data read, connection
 * handshaking, parsing bytes into I2NP messages, etc.
 *
 */
class Reader {
    private final RouterContext _context;
    private final Log _log;
    // TODO change to LBQ ??
    private final List<NTCPConnection> _pendingConnections;
    private final Set<NTCPConnection> _liveReads;
    private final Set<NTCPConnection> _readAfterLive;
    private final List<Runner> _runners;
    
    public Reader(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _pendingConnections = new ArrayList(16);
        _runners = new ArrayList(8);
        _liveReads = new HashSet(8);
        _readAfterLive = new HashSet(8);
    }
    
    public void startReading(int numReaders) {
        for (int i = 1; i <= numReaders; i++) {
            Runner r = new Runner();
            I2PThread t = new I2PThread(r, "NTCP reader " + i + '/' + numReaders, true);
            _runners.add(r);
            t.start();
        }
    }

    public void stopReading() {
        while (!_runners.isEmpty()) {
            Runner r = _runners.remove(0);
            r.stop();
        }
        synchronized (_pendingConnections) {
            _readAfterLive.clear();
            _pendingConnections.notifyAll();
        }
    }
    
    public void wantsRead(NTCPConnection con) {
        boolean already = false;
        synchronized (_pendingConnections) {
            if (_liveReads.contains(con)) {
                _readAfterLive.add(con);
                already = true;
            } else if (!_pendingConnections.contains(con)) {
                _pendingConnections.add(con);
            }
            _pendingConnections.notifyAll();
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("wantsRead: " + con + " already live? " + already);
    }

    public void connectionClosed(NTCPConnection con) {
        synchronized (_pendingConnections) {
            _readAfterLive.remove(con);
            _pendingConnections.remove(con);
            _pendingConnections.notifyAll();
        }
    }
    
    private class Runner implements Runnable {
        private boolean _stop;

        public Runner() {}

        public void stop() { _stop = true; }

        public void run() {
            if (_log.shouldLog(Log.INFO)) _log.info("Starting reader");
            NTCPConnection con = null;
            while (!_stop) {
                try {
                    synchronized (_pendingConnections) {
                        boolean keepReading = (con != null) && _readAfterLive.remove(con);
                        if (keepReading) {
                            // keep on reading the same one
                        } else {
                            _liveReads.remove(con);
                            con = null;
                            if (_pendingConnections.isEmpty()) {
                                _pendingConnections.wait();
                            } else {
                                con = _pendingConnections.remove(0);
                                _liveReads.add(con);
                            }
                        }
                    }
                } catch (InterruptedException ie) {}
                if (!_stop && (con != null) ) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("begin read for " + con);
                    try {
                        processRead(con);
                    } catch (RuntimeException re) {
                        _log.log(Log.CRIT, "Error in the ntcp reader", re);
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("end read for " + con);
                }
            }
            if (_log.shouldLog(Log.INFO)) _log.info("Stopping reader");
        }
    }
    
    /**
     * Process everything read.
     * Return read buffers back to the pool as we process them.
     */
    private void processRead(NTCPConnection con) {
        if (con.isClosed())
            return;
        ByteBuffer buf = null;
        while (!con.isClosed() && !con.isEstablished() && ( (buf = con.getNextReadBuf()) != null) ) {
            EstablishState est = con.getEstablishState();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Processing read buffer as an establishment for " + con + " with [" + est + "]");
            if (est == null) {
                EventPumper.releaseBuf(buf);
                if (!con.isEstablished()) {
                    // establish state is only removed when the connection is fully established,
                    // yet if that happens, con.isEstablished() should return true...
                    throw new RuntimeException("connection was not established, yet the establish state is null for " + con);
                } else {
                    // hmm, there shouldn't be a race here - only one reader should 
                    // be running on a con at a time...
                    _log.error("no establishment state but " + con + " is established... race?");
                    break;
                }
            }
            if (est.isComplete()) {
                // why is it complete yet !con.isEstablished?
                    _log.error("establishment state [" + est + "] is complete, yet the connection isn't established? " 
                               + con.isEstablished() + " (inbound? " + con.isInbound() + " " + con + ")");
                EventPumper.releaseBuf(buf);
                break;
            }
            est.receive(buf);
            EventPumper.releaseBuf(buf);
            if (est.isCorrupt()) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("closing connection on establishment because: " +est.getError(), est.getException());
                if (!est.getFailedBySkew())
                    _context.statManager().addRateData("ntcp.receiveCorruptEstablishment", 1);
                con.close();
                return;
            }
            if (est.isComplete() && est.getExtraBytes() != null)
                con.recvEncryptedI2NP(ByteBuffer.wrap(est.getExtraBytes()));
        }
        // catch race?
        if (!con.isEstablished())
            return;
        while (!con.isClosed() && (buf = con.getNextReadBuf()) != null) {
            // decrypt the data and push it into an i2np message
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Processing read buffer as part of an i2np message (" + buf.remaining() + " bytes)");
            con.recvEncryptedI2NP(buf);
            EventPumper.releaseBuf(buf);
        }
    }
}
