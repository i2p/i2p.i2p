package net.i2p.heartbeat.gui;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeMap;

import java.awt.Color;

import net.i2p.data.Destination;
import net.i2p.heartbeat.ClientConfig;

/**
 * Configure how we want to render a particular clientConfig in the GUI
 *
 */
class PeerPlotConfig {
    /** where can we find the current state/data (either as a filename or a URL)? */
    private String _location;
    /** what test are we defining the plot data for? */
    private ClientConfig _config;
    /** how should we render the current data set? */
    private PlotSeriesConfig _currentSeriesConfig;
    /** how should we render the various averages available? */
    private List _averageSeriesConfigs;
    private Set _listeners;
    private boolean _disabled;

    public PeerPlotConfig(String location) {
        this(location, null, null, null);
    }
    
    public PeerPlotConfig(String location, ClientConfig config, PlotSeriesConfig currentSeriesConfig, List averageSeriesConfigs) {
        _location = location;
        if (config == null) 
            config = new ClientConfig(location);
        _config = config;
        if (currentSeriesConfig != null)
            _currentSeriesConfig = currentSeriesConfig;
        else
            _currentSeriesConfig = new PlotSeriesConfig(0);
        
        if (averageSeriesConfigs != null) {
            _averageSeriesConfigs = averageSeriesConfigs;
        } else {
            rebuildAverageSeriesConfigs();
        }
        _listeners = Collections.synchronizedSet(new HashSet(2));
        _disabled = false;
    }
    
    public void rebuildAverageSeriesConfigs() {
        int periods[] = _config.getAveragePeriods();
        if (periods == null) {
            _averageSeriesConfigs = Collections.synchronizedList(new ArrayList(0));
        } else {
            Arrays.sort(periods);
            _averageSeriesConfigs = Collections.synchronizedList(new ArrayList(periods.length));
            for (int i = 0; i < periods.length; i++) {
                _averageSeriesConfigs.add(new PlotSeriesConfig(periods[i]*60*1000));
            }
        }
    }
    
    public void addAverage(int minutes) {
        _config.addAveragePeriod(minutes);
        
        TreeMap ordered = new TreeMap();
        for (int i = 0; i < _averageSeriesConfigs.size(); i++) {
            PlotSeriesConfig cfg = (PlotSeriesConfig)_averageSeriesConfigs.get(i);
            ordered.put(new Long(cfg.getPeriod()), cfg);
        }
        ordered.put(new Long(minutes*60*1000), new PlotSeriesConfig(minutes*60*1000));
        
        List cfgs = Collections.synchronizedList(new ArrayList(ordered.size()));
        for (Iterator iter = ordered.values().iterator(); iter.hasNext(); )
            cfgs.add(iter.next());
        
        _averageSeriesConfigs = cfgs;
    }
    
    /** 
     * Where is the current state data supposed to be found?  This must either be a 
     * local file path or a URL
     *
     */
    public String getLocation() { return _location; }
    public void setLocation(String location) { 
        _location = location; 
        fireUpdate();
    }
    
    /** What are we configuring? */
    public ClientConfig getClientConfig() { return _config; }
    public void setClientConfig(ClientConfig config) { 
        _config = config; 
        fireUpdate();
    }
    
    /** How do we want to render the current data set? */
    public PlotSeriesConfig getCurrentSeriesConfig() { return _currentSeriesConfig; }
    public void setCurrentSeriesConfig(PlotSeriesConfig config) { 
        _currentSeriesConfig = config; 
        fireUpdate();
    }
    
    /** How do we want to render the averages? */
    public List getAverageSeriesConfigs() { return _averageSeriesConfigs; }
    public void setAverageSeriesConfigs(List configs) { _averageSeriesConfigs = configs; }
    
    /** four char description of the peer */
    public String getPeerName() { 
        Destination peer = getClientConfig().getPeer();
        if (peer == null) 
            return "????";
        else
            return peer.calculateHash().toBase64().substring(0, 4);
    }

    public String getTitle() { return getPeerName() + '.' + getSize() + '.' + getClientConfig().getSendFrequency(); }
    public String getSummary() { 
        return "Send peer " + getPeerName() + ' ' + getSize() + " every " + 
               getClientConfig().getSendFrequency() + " seconds through " +
               getClientConfig().getNumHops() + "-hop tunnels";
    }
    
    private String getSize() {
        int bytes = getClientConfig().getSendSize();
        if (bytes < 1024)
            return bytes + "b";
        else 
            return bytes/1024 + "kb";
    }

    /** we've got someone who wants to be notified of changes to the plot config */
    public void addListener(UpdateListener lsnr) { _listeners.add(lsnr); }
    public void removeListener(UpdateListener lsnr) { _listeners.remove(lsnr); }
    
    void fireUpdate() {
        if (_disabled) return;
        for (Iterator iter = _listeners.iterator(); iter.hasNext(); ) {
            ((UpdateListener)iter.next()).configUpdated(this);
        }
    }
    
    public void disableEvents() { _disabled = true; }
    public void enableEvents() { _disabled = false; }
    
    /** 
     * How do we want to render a particular dataset (either the current or the averaged values)?
     */
    public class PlotSeriesConfig {
        private long _period;
        private boolean _plotSendTime;
        private boolean _plotReceiveTime;
        private boolean _plotLostMessages;
        private Color _plotLineColor;
        
        public PlotSeriesConfig(long period) {
            this(period, false, false, false, null);
        }
        public PlotSeriesConfig(long period, boolean plotSend, boolean plotReceive, boolean plotLost, Color plotColor) {
            _period = period;
            _plotSendTime = plotSend;
            _plotReceiveTime = plotReceive;
            _plotLostMessages = plotLost;
            _plotLineColor = plotColor;
        }
        
        public PeerPlotConfig getPlotConfig() { return PeerPlotConfig.this; }
        
        /** 
         * What period is this series config describing?
         *
         * @return 0 for current, otherwise # milliseconds that are being averaged over
         */
        public long getPeriod() { return _period; }
        public void setPeriod(long period) { 
            _period = period; 
            fireUpdate();
        }
        /** Should we render the time to send (ping to peer)? */
        public boolean getPlotSendTime() { return _plotSendTime; }
        public void setPlotSendTime(boolean shouldPlot) { 
            _plotSendTime = shouldPlot; 
            fireUpdate();
        }
        /** Should we render the time to receive (peer pong to us)? */
        public boolean getPlotReceiveTime() { return _plotReceiveTime; }
        public void setPlotReceiveTime(boolean shouldPlot) { 
            _plotReceiveTime = shouldPlot; 
            fireUpdate();
        }
        /** Should we render the number of messages lost (ping sent, no pong received in time)? */
        public boolean getPlotLostMessages() { return _plotLostMessages; }
        public void setPlotLostMessages(boolean shouldPlot) { 
            _plotLostMessages = shouldPlot; 
            fireUpdate();
        }
        /** What color should we plot the data with? */
        public Color getPlotLineColor() { return _plotLineColor; }
        public void setPlotLineColor(Color color) { 
            _plotLineColor = color; 
            fireUpdate();
        }
    }
    
    public interface UpdateListener {
        void configUpdated(PeerPlotConfig config);
    }
}