/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package org.klomp.snark;

import java.util.Map;
import java.util.Properties;

import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *  Periodically check for idle condition based on connected peers,
 *  and reduce/restore tunnel count as necessary.
 *  We can't use the I2CP idle detector because it's based on traffic,
 *  so DHT and announces would keep it non-idle.
 *
 *  @since 0.9.7
 */
class IdleChecker extends SimpleTimer2.TimedEvent {

    private final SnarkManager _mgr;
    private final I2PSnarkUtil _util;
    private final PeerCoordinatorSet _pcs;
    private final Log _log;
    private int _consec;
    private int _consecNotRunning;
    private boolean _isIdle;
    private String _lastIn = "3";
    private String _lastOut = "3";
    private final Object _lock = new Object();

    private static final long CHECK_TIME = 63*1000;
    private static final int MAX_CONSEC_IDLE = 4;
    private static final int MAX_CONSEC_NOT_RUNNING = 20;

    /**
     *  Caller must schedule
     */
    public IdleChecker(SnarkManager mgr, PeerCoordinatorSet pcs) {
        super(mgr.util().getContext().simpleTimer2());
        _util = mgr.util();
        _log = _util.getContext().logManager().getLog(IdleChecker.class);
        _mgr = mgr;
        _pcs = pcs;
    }

    public void timeReached() {
        synchronized (_lock) {
            locked_timeReached();
        }
    }

    private void locked_timeReached() {
        if (_util.connected()) {
            boolean torrentRunning = false;
            int peerCount = 0;
            for (PeerCoordinator pc : _pcs) {
                if (!pc.halted()) {
                    torrentRunning = true;
                    peerCount += pc.getPeers();
                }
            }

            if (torrentRunning) {
                _consecNotRunning = 0;
            } else {
                if (_consecNotRunning++ >= MAX_CONSEC_NOT_RUNNING) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Closing tunnels on idle");
                    _util.disconnect();
                    _mgr.addMessage(_util.getString("No more torrents running.") + ' ' +
                                    _util.getString("I2P tunnel closed."));
                    schedule(3 * CHECK_TIME);
                    return;
                }
            }

            if (peerCount > 0) {
                restoreTunnels(peerCount);
            } else {
                if (!_isIdle) {
                    if (_consec++ >= MAX_CONSEC_IDLE)
                        reduceTunnels();
                    else
                        restoreTunnels(1);  // pretend we have one peer for now
                }
            }
        } else {
            _isIdle = false;
            _consec = 0;
            _consecNotRunning = 0;
            _lastIn = "3";
            _lastOut = "3";
        }
        schedule(CHECK_TIME);
    }

    /**
     *  Reduce to 1 in / 1 out tunnel
     */
    private void reduceTunnels() {
        _isIdle = true;
        if (_log.shouldLog(Log.INFO))
            _log.info("Reducing tunnels on idle");
        setTunnels("1", "1", "0", "0");
    }
    
    /**
     *  Restore or adjust tunnel count based on current peer count
     *  @param peerCount greater than zero
     */
    private void restoreTunnels(int peerCount) {
        if (_isIdle && _log.shouldLog(Log.INFO))
            _log.info("Restoring tunnels on activity");
        _isIdle = false;
        Map<String, String> opts = _util.getI2CPOptions();
        String i = opts.get("inbound.quantity");
        if (i == null)
            i = Integer.toString(SnarkManager.DEFAULT_TUNNEL_QUANTITY);
        String o = opts.get("outbound.quantity");
        if (o == null)
            o = Integer.toString(SnarkManager.DEFAULT_TUNNEL_QUANTITY);
        String ib = opts.get("inbound.backupQuantity");
        if (ib == null)
            ib = "0";
        String ob= opts.get("outbound.backupQuantity");
        if (ob == null)
            ob = "0";
        // we don't need more tunnels than we have peers, reduce if so
        // reduce to max(peerCount / 2, 2)
        int in, out;
        try {
            in = Integer.parseInt(i);
        } catch (NumberFormatException nfe) {
            in = 3;
        }
        try {
            out = Integer.parseInt(o);
        } catch (NumberFormatException nfe) {
            out = 3;
        }
        int target = Math.max(peerCount / 2, 2);
        if (target < in && in > 2) {
            in = target;
            i = Integer.toString(in);
        }
        if (target < out && out > 2) {
            out = target;
            o = Integer.toString(out);
        }
        if (!(_lastIn.equals(i) && _lastOut.equals(o)))
            setTunnels(i, o, ib, ob);
    }
    
    /**
     *  Set in / out / in backup / out backup tunnel counts
     */
    private void setTunnels(String i, String o, String ib, String ob) {
        _consec = 0;
        I2PSocketManager mgr = _util.getSocketManager();
        if (mgr != null) {
            I2PSession sess = mgr.getSession();
            if (sess != null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("New tunnel settings " + i + " / " + o + " / " + ib + " / " + ob);
                Properties newProps = new Properties();
                newProps.setProperty("inbound.quantity", i);
                newProps.setProperty("outbound.quantity", o);
                newProps.setProperty("inbound.backupQuantity", ib);
                newProps.setProperty("outbound.backupQuantity", ob);
                sess.updateOptions(newProps);
                _lastIn = i;
                _lastOut = o;
            }
        }
    }
}
