package net.i2p.netmonitor.gui;

import java.awt.Color;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.i2p.netmonitor.PeerSummary;
import net.i2p.util.Log;

/**
 * Configure how we want to render a particular peerSummary in the GUI
 */
class PeerPlotConfig {
    private final static Log _log = new Log(PeerPlotConfig.class);
    /** where can we find the current state/data (either as a filename or a URL)? */
    private String _location;
    /** what test are we defining the plot data for? */
    private PeerSummary _summary;
    /** how should we render the current data set? */
    private PlotSeriesConfig _seriesConfig;
    private Set _listeners;
    private boolean _disabled;

    /**
     * Delegating constructor . . .
     * @param location the name of the file/URL to get the data from
     */
    public PeerPlotConfig(String location) {
        this(location, null, null);
    }
    
    /**
     * Delegating constructor . . .
     * @param location the name of the file/URL to get the data from
     */
    public PeerPlotConfig(PeerSummary summary) {
        this(null, summary, null);
    }
    
    /**
     * Constructs a config =)
     * @param location the location of the file/URL to get the data from
     * @param config the client's configuration
     * @param seriesConfig the series config
     */
    public PeerPlotConfig(String location, PeerSummary summary, PlotSeriesConfig seriesConfig) {
        _location = location;
        _summary = summary;
        if (seriesConfig != null)
            _seriesConfig = seriesConfig;
        else
            _seriesConfig = new PlotSeriesConfig();
        
        _listeners = Collections.synchronizedSet(new HashSet(2));
        _disabled = false;
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
    public PeerSummary getSummary() { return _summary; }

    /**
     * Sets what we are currently configuring
     * @param config the new config
     */
    public void setPeerSummary(PeerSummary summary) { 
        _summary = summary;
        fireUpdate();
    }
    
    /** 
     * How do we want to render the current data set?
     * @return the way we currently render the data
     */
    public PlotSeriesConfig getSeriesConfig() { return _seriesConfig; }

    /**
     * Sets how we want to render the current data set.
     * @param config the new config
     */
    public void setSeriesConfig(PlotSeriesConfig config) { 
        _seriesConfig = config; 
        fireUpdate();
    }
    
    /** 
     * four char description of the peer 
     * @return the name
     */
    public String getPeerName() { return _summary.getPeer(); }
    
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
        private Map _shouldPlot;
        private Map _plotColors;
        
        /**
         * Creates a config for the rendering of a particular dataset)
         * @param plotLost do we plot lost packets?
         * @param plotColor in what color?
         */
        public PlotSeriesConfig() {
            _shouldPlot = new HashMap(16);
            _plotColors = new HashMap(16);
        }
        
        /**
         * Retrieves the plot config this plot series config is a part of
         * @return the plot config
         */
        public PeerPlotConfig getPlotConfig() { return PeerPlotConfig.this; }
        
        public boolean getShouldPlotValue(String statName, String argName, boolean defaultVal) { 
            Boolean val = (Boolean)_shouldPlot.get(statName + argName);
            if (val == null) 
                return defaultVal;
            else
                return val.booleanValue();
        }
        
        public void setShouldPlotValue(String statName, String argName, boolean shouldPlot) { 
            _shouldPlot.put(statName + argName, new Boolean(shouldPlot));
            fireUpdate();
        }

        /**
         * What color should we plot the data with? 
         * @return the color
         */
        public Color getPlotLineColor(String statName, String argName) { 
            return (Color)_plotColors.get(statName + argName);
        }

        /**
         * Sets the color we should plot the data with
         * @param color the color to use
         */
        public void setPlotLineColor(String statName, String argName, Color color) { 
            if (color == null)
                _plotColors.remove(statName + argName);
            else
                _plotColors.put(statName + argName, color);
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