package net.i2p.heartbeat.gui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.Destination;
import net.i2p.util.Log;
import net.i2p.heartbeat.ClientConfig;

/**
 * Configure how we want to render a particular clientConfig in the GUI
 */
class PeerPlotConfig {
    private final static Log _log = new Log(PeerPlotConfig.class);
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

    /**
     * Delegating constructor . . .
     * @param location the name of the file/URL to get the data from
     */
    public PeerPlotConfig(String location) {
        this(location, null, null, null);
    }
    
    /**
     * Constructs a config =)
     * @param location the location of the file/URL to get the data from
     * @param config the client's configuration
     * @param currentSeriesConfig the series config
     * @param averageSeriesConfigs the average
     */
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
    
    /**
     * 'Rebuilds' the average series stuff from the client configuration
     */
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
    
    /**
     * Adds an average period
     * @param minutes the number of minutes averaged over
     */
    public void addAverage(int minutes) {
        _config.addAveragePeriod(minutes);
        
        TreeMap ordered = new TreeMap();
        for (int i = 0; i < _averageSeriesConfigs.size(); i++) {
            PlotSeriesConfig cfg = (PlotSeriesConfig)_averageSeriesConfigs.get(i);
            ordered.put(new Long(cfg.getPeriod()), cfg);
        }
        Long period = new Long(minutes*60*1000);
        if (!ordered.containsKey(period))
            ordered.put(period, new PlotSeriesConfig(minutes*60*1000));
        
        List cfgs = Collections.synchronizedList(new ArrayList(ordered.size()));
        for (Iterator iter = ordered.values().iterator(); iter.hasNext(); )
            cfgs.add(iter.next());
        
        _averageSeriesConfigs = cfgs;
    }
    
    /** 
     * Where is the current state data supposed to be found?  This must either be a 
     * local file path or a URL
     * @return the current location
     */
    public String getLocation() { return _location; }

    /**
     * The location the current state data is supposed to be found.  This must either be
     * a local file path or a URL
     * @param location the location
     */
    public void setLocation(String location) { 
        _location = location; 
        fireUpdate();
    }
    
    /** 
     * What are we configuring? 
     * @return the client configuration
     */
    public ClientConfig getClientConfig() { return _config; }

    /**
     * Sets what we are currently configuring
     * @param config the new config
     */
    public void setClientConfig(ClientConfig config) { 
        _config = config; 
        fireUpdate();
    }
    
    /** 
     * How do we want to render the current data set?
     * @return the way we currently render the data
     */
    public PlotSeriesConfig getCurrentSeriesConfig() { return _currentSeriesConfig; }

    /**
     * Sets how we want to render the current data set.
     * @param config the new config
     */
    public void setCurrentSeriesConfig(PlotSeriesConfig config) { 
        _currentSeriesConfig = config; 
        fireUpdate();
    }
    
    /** 
     * How do we want to render the averages? 
     * @return the way we currently render the averages
     */
    public List getAverageSeriesConfigs() { return _averageSeriesConfigs; }

    /**
     * Sets how we want to render the averages
     * @param configs the new configs
     */
    public void setAverageSeriesConfigs(List configs) { _averageSeriesConfigs = configs; }
    
    /** 
     * four char description of the peer 
     * @return the name
     */
    public String getPeerName() { 
        Destination peer = getClientConfig().getPeer();
        if (peer == null) 
            return "????";
        else
            return peer.calculateHash().toBase64().substring(0, 4);
    }

    /**
     * title:  name.packetsize.sendfrequency
     * @return the title
     */
    public String getTitle() { 
        return getPeerName() + '.' + getSize() + '.' + getClientConfig().getSendFrequency(); 
    }
    
    /**
     * summary.  includes:name, size, sendfrequency, and # of hops  
     * @return the summary
     */
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

    /** 
     * we've got someone who wants to be notified of changes to the plot config
     * @param lsnr the listener to be added 
     */
    public void addListener(UpdateListener lsnr) { _listeners.add(lsnr); }

    /**
     * remove a listener
     * @param lsnr the listener to remove
     */
    public void removeListener(UpdateListener lsnr) { _listeners.remove(lsnr); }
    
    void fireUpdate() {
        if (_disabled) return;
        for (Iterator iter = _listeners.iterator(); iter.hasNext(); ) {
            ((UpdateListener)iter.next()).configUpdated(this);
        }
    }
    
    /**
     * Disables notification of events listeners
     * @see PeerPlotConfig#fireUpdate()
     */
    public void disableEvents() { _disabled = true; }

    /**
     * Enables notification of events listeners
     * @see PeerPlotConfig#fireUpdate()
     */
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
        
        /**
         * Delegating constructor . . .
         * @param period the period for the config 
         *        (0 for current, otherwise # of milliseconds being averaged over)
         */
        public PlotSeriesConfig(long period) {
            this(period, false, false, false, null);
            if (period <= 0) {
                _plotSendTime = true;
                _plotReceiveTime = true;
                _plotLostMessages = true;
            }
        }
        
        
        /**
         * Creates a config for the rendering of a particular dataset)
         * @param period the period for the config
         *        (0 for current, otherwise # of milliseconds being averaged over)
         * @param plotSend do we plot send times?
         * @param plotReceive do we plot receive times?
         * @param plotLost do we plot lost packets?
         * @param plotColor in what color?
         */
        public PlotSeriesConfig(long period, boolean plotSend, boolean plotReceive, boolean plotLost, Color plotColor) {
            _period = period;
            _plotSendTime = plotSend;
            _plotReceiveTime = plotReceive;
            _plotLostMessages = plotLost;
            _plotLineColor = plotColor;
        }
        
        /**
         * Retrieves the plot config this plot series config is a part of
         * @return the plot config
         */
        public PeerPlotConfig getPlotConfig() { return PeerPlotConfig.this; }
        
        /** 
         * What period is this series config describing?
         * @return 0 for current, otherwise # milliseconds that are being averaged over
         */
        public long getPeriod() { return _period; }

        /**
         * Sets the period this series config is describing
         * @param period the period
         *        (0 for current, otherwise # milliseconds that are being averaged over)
         */
        public void setPeriod(long period) { 
            _period = period; 
            fireUpdate();
        }
        
        /**
         * Should we render the time to send (ping to peer)?
         * @return true or false . . .
         */
        public boolean getPlotSendTime() { return _plotSendTime; }

        /**
         * Sets whether we render the time to send (ping to peer) or not
         * @param shouldPlot true or false 
         */
        public void setPlotSendTime(boolean shouldPlot) { 
            _plotSendTime = shouldPlot; 
            fireUpdate();
        }

        /** 
         * Should we render the time to receive (peer pong to us)?
         * @return true or false . . . 
         */
        public boolean getPlotReceiveTime() { return _plotReceiveTime; }

        /**
         * Sets whether we render the time to receive (peer pong to us)
         * @param shouldPlot true or false
         */
        public void setPlotReceiveTime(boolean shouldPlot) { 
            _plotReceiveTime = shouldPlot; 
            fireUpdate();
        }
        /**
         * Should we render the number of messages lost (ping sent, no pong received in time)? 
         * @return true or false . . .
         */
        public boolean getPlotLostMessages() { return _plotLostMessages; }

        /**
         * Sets whether we render the number of messages lost (ping sent, no pong received in time) or not
         * @param shouldPlot true or false
         */
        public void setPlotLostMessages(boolean shouldPlot) { 
            _plotLostMessages = shouldPlot; 
            fireUpdate();
        }
        /**
         * What color should we plot the data with? 
         * @return the color
         */
        public Color getPlotLineColor() { return _plotLineColor; }

        /**
         * Sets the color we should plot the data with
         * @param color the color to use
         */
        public void setPlotLineColor(Color color) { 
            _plotLineColor = color; 
            fireUpdate();
        }
    }
    
    /**
     * An interface for listening to updates . . .
     */
    public interface UpdateListener {
        /**
         * @param config the peer plot config that changes
         * @see PeerPlotConfig#fireUpdate()
         */
        void configUpdated(PeerPlotConfig config);
    }
}