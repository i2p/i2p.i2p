package net.i2p.heartbeat.gui;

import net.i2p.util.Log;

/** 
 * Periodically fire off necessary events (instructing the heartbeat monitor when
 * to refetch the data, etc).  This is the only active thread in the heartbeat 
 * monitor (outside the swing/jvm threads) 
 *
 */
class HeartbeatMonitorRunner implements Runnable {
    private final static Log _log = new Log(HeartbeatMonitorRunner.class);
    private HeartbeatMonitor _monitor;
    
    public HeartbeatMonitorRunner(HeartbeatMonitor monitor) {
        _monitor = monitor;
    }
    
    public void run() {
        while (!_monitor.getState().getWasKilled()) {
            _monitor.refetchData();
            try { Thread.sleep(_monitor.getState().getRefreshRateMs()); } catch (InterruptedException ie) {}
        }
        _log.info("Stopping the heartbeat monitor runner");
    }
}