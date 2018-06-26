package net.i2p.router.transport.ntcp;

import java.nio.ByteBuffer;
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
 * Pool of running threads which will process any read bytes on any of the 
 * NTCPConnections, including the decryption of the data read, connection
 * handshaking, parsing bytes into I2NP messages, etc.
 *
 */
class Reader {
    private final RouterContext _context;
    private final Log _log;
    // TODO change to LBQ ??
    private final Set<NTCPConnection> _pendingConnections;
    private final Set<NTCPConnection> _liveReads;
    private final Set<NTCPConnection> _readAfterLive;
    private final List<Runner> _runners;
    
    public Reader(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _pendingConnections = new LinkedHashSet<NTCPConnection>(16);
        _runners = new ArrayList<Runner>(8);
        _liveReads = new HashSet<NTCPConnection>(8);
        _readAfterLive = new HashSet<NTCPConnection>(8);
    }
    
    public synchronized void startReading(int numReaders) {
        for (int i = 1; i <= numReaders; i++) {
            Runner r = new Runner();
            I2PThread t = new I2PThread(r, "NTCP reader " + i + '/' + numReaders, true);
            _runners.add(r);
            t.start();
        }
    }

    public synchronized void stopReading() {
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
            } else {
                _pendingConnections.add(con);
                // only notify here if added?
            }
            _pendingConnections.notify();
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("wantsRead: " + con + " already live? " + already);
    }

    public void connectionClosed(NTCPConnection con) {
        synchronized (_pendingConnections) {
            _readAfterLive.remove(con);
            _pendingConnections.remove(con);
            // necessary?
            _pendingConnections.notify();
        }
    }
    
    private class Runner implements Runnable {
        private volatile boolean _stop;

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
                            if (con != null) {
                                _liveReads.remove(con);
                                con = null;
                            }
                            if (_pendingConnections.isEmpty()) {
                                _pendingConnections.wait();
                            } else {
                                Iterator<NTCPConnection> iter = _pendingConnections.iterator();
                                con = iter.next();
                                iter.remove();
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
                    } catch (IllegalStateException ise) {
                        // FailedEstablishState.receive() (race - see below)
                        if (_log.shouldWarn())
                            _log.warn("Error in the ntcp reader", ise);
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
        ByteBuffer buf = null;
        while(true) {
            synchronized(con) {
                if (con.isClosed())
                    return;
                if (con.isEstablished())
                    break;
            }
            if ((buf = con.getNextReadBuf()) == null)
                return;
            EstablishState est = con.getEstablishState();
            
            if (est.isComplete()) {
                // why is it complete yet !con.isEstablished?
                _log.error("establishment state [" + est + "] is complete, yet the connection isn't established? " 
                        + con.isEstablished() + " (inbound? " + con.isInbound() + " " + con + ")");
                EventPumper.releaseBuf(buf);
                break;
            }
            // FIXME call est.isCorrupt() before also? throws ISE here... see above
            est.receive(buf);
            EventPumper.releaseBuf(buf);
            if (est.isCorrupt()) {
                con.close();
                return;
            }
            // EstablishState is responsible for passing "extra" data to the con
        }
        while (!con.isClosed() && (buf = con.getNextReadBuf()) != null) {
            // decrypt the data and push it into an i2np message
            con.recvEncryptedI2NP(buf);
            EventPumper.releaseBuf(buf);
        }
    }
}
