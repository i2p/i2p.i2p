package net.i2p.heartbeat.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

/**
 * manage the current state of the GUI - all data points, as well as any
 * rendering or configuration options.
 *
 */
class HeartbeatMonitorState {
    private String _configFile;
    private List _peerPlotState;
    private int _currentPeerPlotConfig;
    private int _refreshRateMs;
    private boolean _killed;
    
    /** by default, refresh every 30 seconds */
    private final static int DEFAULT_REFRESH_RATE = 30*1000;
    /** where do we load/store config info from? */
    private final static String DEFAULT_CONFIG_FILE = "heartbeatMonitor.config";
    
    public HeartbeatMonitorState() { this(DEFAULT_CONFIG_FILE); }
    public HeartbeatMonitorState(String configFile) {
        _peerPlotState = Collections.synchronizedList(new ArrayList());
        _refreshRateMs = DEFAULT_REFRESH_RATE;
        _configFile = configFile;
        _killed = false;
        _currentPeerPlotConfig = 0;
    }
    
    /** how many tests are we monitoring? */
    public int getTestCount() { return _peerPlotState.size(); }
    public PeerPlotState getTest(int peer) { return (PeerPlotState)_peerPlotState.get(peer); }
    public void addTest(PeerPlotState peerState) { 
        if (!_peerPlotState.contains(peerState))
            _peerPlotState.add(peerState); 
    }
    public void removeTest(PeerPlotState peerState) { _peerPlotState.remove(peerState); }
    
    public void removeTest(PeerPlotConfig peerConfig) {
        for (int i = 0; i < getTestCount(); i++) {
            PeerPlotState state = getTest(i);
            if (state.getPlotConfig() == peerConfig) {
                removeTest(state);
                return;
            }
        }
    }
    
    /** which of the tests are we currently editing/viewing? */
    public int getPeerPlotConfig() { return _currentPeerPlotConfig; }
    public void setPeerPlotConfig(int whichTest) { _currentPeerPlotConfig = whichTest; }
    
    /** how frequently should we update the data? */
    public int getRefreshRateMs() { return _refreshRateMs; }
    public void setRefreshRateMs(int ms) { _refreshRateMs = ms; }
    
    /** where is our config stored? */
    public String getConfigFile() { return _configFile; }
    public void setConfigFile(String filename) { _configFile = filename; }
    
    /** have we been shut down? */
    public boolean getWasKilled() { return _killed; }
    public void setWasKilled(boolean killed) { _killed = killed; }
}