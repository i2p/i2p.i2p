package net.i2p.heartbeat.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * manage the current state of the GUI - all data points, as well as any
 * rendering or configuration options.
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
    
    /**
     * A delegating constructor.
     * @see HeartbeatMonitorState#HeartbeatMonitorState(String)
     */
    public HeartbeatMonitorState() { this(DEFAULT_CONFIG_FILE); }

    /**
     * Constructs the state, loading from the specified location
     * @param configFile the name of the file to load info from
     */
    public HeartbeatMonitorState(String configFile) {
        _peerPlotState = Collections.synchronizedList(new ArrayList());
        _refreshRateMs = DEFAULT_REFRESH_RATE;
        _configFile = configFile;
        _killed = false;
        _currentPeerPlotConfig = 0;
    }
    
    /**
     * how many tests are we monitoring?
     * @return the number of tests
     */
    public int getTestCount() { return _peerPlotState.size(); }

    /**
     * Retrieves the current info of a test for a certain peer . . . 
     * @param peer a number associated with a certain peer
     * @return the test data
     */
    public PeerPlotState getTest(int peer) { return (PeerPlotState)_peerPlotState.get(peer); }

    /**
     * Adds a test . . .
     * @param peerState the test (by state) to add . . .
     */
    public void addTest(PeerPlotState peerState) { 
        if (!_peerPlotState.contains(peerState))
            _peerPlotState.add(peerState); 
    }
    /**
     * Removes a test . . .
     * @param peerState the test (by state) to remove . . .
     */
    public void removeTest(PeerPlotState peerState) { _peerPlotState.remove(peerState); }
    
    /**
     * Removes a test . . .
     * @param peerConfig the test (by config) to remove . . .
     */
    public void removeTest(PeerPlotConfig peerConfig) {
        for (int i = 0; i < getTestCount(); i++) {
            PeerPlotState state = getTest(i);
            if (state.getPlotConfig() == peerConfig) {
                removeTest(state);
                return;
            }
        }
    }
    
    /** 
     * which of the tests are we currently editing/viewing?
     * @return the number associated with the test
     */
    public int getPeerPlotConfig() { return _currentPeerPlotConfig; }

    /**
     * Sets the test we are currently editting/viewing
     * @param whichTest the number associated with the test
     */
    public void setPeerPlotConfig(int whichTest) { _currentPeerPlotConfig = whichTest; }
    
    /**
     * how frequently should we update the data?
     * @return the current frequency (in milliseconds) 
     */
    public int getRefreshRateMs() { return _refreshRateMs; }

    /**
     * Sets how frequently we should update data
     * @param ms the frequency (in milliseconds)
     */
    public void setRefreshRateMs(int ms) { _refreshRateMs = ms; }
    
    /** 
     * where is our config stored? 
     * @return the name of the config file
     */
    public String getConfigFile() { return _configFile; }

    /**
     * Sets where our config is stored
     * @param filename the name of the config file
     */
    public void setConfigFile(String filename) { _configFile = filename; }
    
    /** 
     * have we been shut down?
     * @return true if we have, false otherwise 
     */
    public boolean getWasKilled() { return _killed; }

    /**
     * Sets if we have been shutdown or not
     * @param killed true if we've been shutdown, false otherwise
     */
    public void setWasKilled(boolean killed) { _killed = killed; }
}