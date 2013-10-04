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
        if (_util.connected()) {
            boolean torrentRunning = false;
            boolean hasPeers = false;
            for (PeerCoordinator pc : _pcs) {
                if (!pc.halted()) {
                    torrentRunning = true;
                    if (pc.getPeers() > 0) {
                        hasPeers = true;
                        break;
                    }
                }
            }

            if (torrentRunning) {
                _consecNotRunning = 0;
            } else {
                if (_consecNotRunning++ >= MAX_CONSEC_NOT_RUNNING) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Closing tunnels on idle");
                    _util.disconnect();
                    _mgr.addMessage(_util.getString("I2P tunnel closed."));
                    schedule(3 * CHECK_TIME);
                    return;
                }
            }

            if (hasPeers) {
                if (_isIdle)
                    restoreTunnels();
            } else {
                if (!_isIdle) {
                    if (_consec++ >= MAX_CONSEC_IDLE)
                        reduceTunnels();
                }
            }
        } else {
            _isIdle = false;
            _consec = 0;
            _consecNotRunning = 0;
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
     *  Restore tunnel count
     */
    private void restoreTunnels() {
        _isIdle = false;
        if (_log.shouldLog(Log.INFO))
            _log.info("Restoring tunnels on activity");
        Map<String, String> opts = _util.getI2CPOptions();
        String i = opts.get("inbound.quantity");
        if (i == null)
            i = "3";
        String o = opts.get("outbound.quantity");
        if (o == null)
            o = "3";
        String ib = opts.get("inbound.backupQuantity");
        if (ib == null)
            ib = "0";
        String ob= opts.get("outbound.backupQuantity");
        if (ob == null)
            ob = "0";
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
                Properties newProps = new Properties();
                newProps.setProperty("inbound.quantity", i);
                newProps.setProperty("outbound.quantity", o);
                newProps.setProperty("inbound.backupQuantity", ib);
                newProps.setProperty("outbound.backupQuantity", ob);
                sess.updateOptions(newProps);
            }
        }
    }
}
