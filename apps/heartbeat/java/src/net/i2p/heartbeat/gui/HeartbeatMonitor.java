package net.i2p.heartbeat.gui;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * The HeartbeatMonitor, complete with main()!  Act now, and it's only 5 easy
 * payments of $19.95 (plus shipping and handling)!  You heard me, only _5_
 * easy payments of $19.95 (plus shipping and handling)! <p />
 * 
 * (fine print:  something about some states in the US requiring the addition
 * of sales tax... or something) <p />
 * 
 * (finer print:  Satan owns you.  Deal with it.) <p />
 *
 * (even finer print: usage: <code>HeartbeatMonitor [configFilename]</code>)
 */
public class HeartbeatMonitor implements PeerPlotStateFetcher.FetchStateReceptor, PeerPlotConfig.UpdateListener {
    private final static Log _log = new Log(HeartbeatMonitor.class);
    private HeartbeatMonitorState _state;
    private HeartbeatMonitorGUI _gui;
    
    /**
     * Delegating constructor.
     * @see HeartbeatMonitor#HeartbeatMonitor(String)
     */
    public HeartbeatMonitor() { this(null); }
    
    /**
     * Creates a HeartbeatMonitor . . .
     * @param configFilename the configuration file to read from
     */
    public HeartbeatMonitor(String configFilename) {
        _state = new HeartbeatMonitorState(configFilename);
        _gui = new HeartbeatMonitorGUI(this);
    }
    
    /**
     * Starts the game rollin'
     */
    public void runMonitor() {
        loadConfig();
        I2PThread t = new I2PThread(new HeartbeatMonitorRunner(this));
        t.setName("HeartbeatMonitor");
        t.setDaemon(false);
        t.start();
        _log.debug("Monitor started");
    }
    
    /**
     * give us all the data/config available 
     * @return the current state (data/config)
     */
    HeartbeatMonitorState getState() {
        return _state;
    }
    
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
    
    /**
     * Loads config data
     * @param location the name of the location to load data from
     */
    public void load(String location) {
        PeerPlotConfig cfg = new PeerPlotConfig(location);
        cfg.addListener(this);
        PeerPlotState state = new PeerPlotState(cfg);
        PeerPlotStateFetcher.fetchPeerPlotState(this, state);
    }
    
    /* (non-Javadoc)
     * @see PeerPlotStateFetcher.FetchStateReceptor#peerPlotStateFetched
     */
    public synchronized void peerPlotStateFetched(PeerPlotState state) {
        _state.addTest(state);
        _gui.stateUpdated();
    }
    
    /**
     *  store the config defining what peer tests we are monitoring (and how to render) 
     */
    void storeConfig() {}
    
    /**
     * And now, the main function, the one you've all been waiting for! . . .
     * @param args da args.  Should take 1, which is the location to load config data from
     */
    public static void main(String args[]) {
        Thread.currentThread().setName("HeartbeatMonitor.main");
        if (args.length == 1)
            new HeartbeatMonitor(args[0]).runMonitor();
        else
            new HeartbeatMonitor().runMonitor();
    }
    
    /**
     * Called when the config is updated
     * @param config the updated config
     */
    public void configUpdated(PeerPlotConfig config) { 
        _log.debug("Config updated, revamping the gui");
        _gui.stateUpdated(); 
    }
}