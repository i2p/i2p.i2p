package net.i2p.heartbeat.gui;


/**
 * Current data + plot config for a particular test
 * 
 */
class PeerPlotState {
    private StaticPeerData _currentData;
    private PeerPlotConfig _plotConfig;
    
    /**
     * Delegating constructor . . .
     * @see PeerPlotState#PeerPlotState(PeerPlotConfig, StaticPeerData)
     */
    public PeerPlotState() {
        this(null, null);
    }
    
    /**
     * Delegating constructor . . .
     * @param config plot config
     * @see PeerPlotState#PeerPlotState(PeerPlotConfig, StaticPeerData)
     */
    public PeerPlotState(PeerPlotConfig config) {
        this(config, new StaticPeerData(config.getClientConfig()));
    }
    /**
     * Creates a PeerPlotState
     * @param config plot config
     * @param data peer data
     */
    public PeerPlotState(PeerPlotConfig config, StaticPeerData data) {
        _plotConfig = config;
        _currentData = data;
    }
    
    /**
     * Add an average
     * @param minutes mins averaged over
     * @param sendMs how much later did the peer receieve
     * @param recvMs how much later did we receieve
     * @param lost how many were lost
     */
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
    
    /**
     * data set to render 
     * @return the data set
     */
    public StaticPeerData getCurrentData() { return _currentData; }

    /**
     * Sets the data set to render
     * @param data the data set
     */
    public void setCurrentData(StaticPeerData data) { _currentData = data; }
    
    /**
     * configuration options on how to render the data set 
     * @return the config options
     */
    public PeerPlotConfig getPlotConfig() { return _plotConfig; }

    /**
     * Sets the configuration options on how to render the data
     * @param config the config options
     */
    public void setPlotConfig(PeerPlotConfig config) { _plotConfig = config; }
}