package net.i2p.heartbeat.gui;

import net.i2p.heartbeat.PeerData;

/**
 * Current data + plot config for a particular test
 * 
 */
class PeerPlotState {
    private StaticPeerData _currentData;
    private PeerPlotConfig _plotConfig;
    
    public PeerPlotState() {
        this(null, null);
    }
    public PeerPlotState(PeerPlotConfig config) {
        this(config, new StaticPeerData(config.getClientConfig()));
    }
    public PeerPlotState(PeerPlotConfig config, StaticPeerData data) {
        _plotConfig = config;
        _currentData = data;
    }
    
    public void addAverage(int minutes, int sendMs, int recvMs, int lost) {
        // make sure we've got the config entry for the average
        _plotConfig.addAverage(minutes);
        // add the data point...
        _currentData.addAverage(minutes, sendMs, recvMs, lost);
    }
    
    /**
     * we successfully got a ping/pong through
     *
     * @param sendTime when did the ping get sent?
     * @param sendMs how much later did the peer receive the ping?
     * @param recvMs how much later than that did we receive the pong? 
     */
    public void addSuccess(long sendTime, int sendMs, int recvMs) {
        _currentData.addData(sendTime, sendMs, recvMs);
    }
    
    /**
     * we lost a ping/pong
     *
     * @param sendTime when did we send the ping?
     */
    public void addLost(long sendTime) {
        _currentData.addData(sendTime);
    }
    
    /** data set to render */
    public StaticPeerData getCurrentData() { return _currentData; }
    public void setCurrentData(StaticPeerData data) { _currentData = data; }
    
    /** configuration options on how to render the data set */
    public PeerPlotConfig getPlotConfig() { return _plotConfig; }
    public void setPlotConfig(PeerPlotConfig config) { _plotConfig = config; }
}