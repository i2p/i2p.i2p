package net.i2p.heartbeat.gui;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;

public class HeartbeatMonitor implements PeerPlotStateFetcher.FetchStateReceptor {
    private final static Log _log = new Log(HeartbeatMonitor.class);
    private HeartbeatMonitorState _state;
    private HeartbeatMonitorGUI _gui;
    
    public HeartbeatMonitor() { this(null); }
    public HeartbeatMonitor(String configFilename) {
        _state = new HeartbeatMonitorState(configFilename);
        _gui = new HeartbeatMonitorGUI(this);
    }
    
    public void runMonitor() {
        loadConfig();
        I2PThread t = new I2PThread(new HeartbeatMonitorRunner(this));
        t.setName("HeartbeatMonitor");
        t.setDaemon(false);
        t.start();
        _log.debug("Monitor started");
    }
    
    /** give us all the data/config available */
    HeartbeatMonitorState getState() { return _state; }
    
    /** for all of the peer tests being monitored, refetch the data and rerender */
    void refetchData() {
        _log.debug("Refetching data");
        for (int i = 0; i < _state.getTestCount(); i++)
            PeerPlotStateFetcher.fetchPeerPlotState(this, _state.getTest(i));
    }
    
    /** (re)load the config defining what peer tests we are monitoring (and how to render) */
    void loadConfig() {
        //for (int i = 0; i < 10; i++) {
        //    load("fake" + i);
        //}
    }
    
    public void load(String location) {
        PeerPlotConfig cfg = new PeerPlotConfig(location);
        PeerPlotState state = new PeerPlotState(cfg);
        PeerPlotStateFetcher.fetchPeerPlotState(this, state);
    }
    
    public synchronized void peerPlotStateFetched(PeerPlotState state) {
        _state.addTest(state);
        _gui.stateUpdated();
    }
    
    /** store the config defining what peer tests we are monitoring (and how to render) */
    void storeConfig() {}
    
    public static void main(String args[]) {
        Thread.currentThread().setName("HeartbeatMonitor.main");
        if (args.length == 1)
            new HeartbeatMonitor(args[0]).runMonitor();
        else
            new HeartbeatMonitor().runMonitor();
    }
}