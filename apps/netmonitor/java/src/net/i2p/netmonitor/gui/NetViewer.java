package net.i2p.netmonitor.gui;

import net.i2p.netmonitor.NetMonitor;
import net.i2p.netmonitor.PeerSummary;
import net.i2p.netmonitor.PeerStat;
import net.i2p.util.Log;
import net.i2p.util.I2PThread;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Coordinate the visualization of the network monitor. <p />
 *
 * <b>Usage: <code>NetViewer [exportDir]</code></b> <br />
 * (exportDir is where the NetMonitor exports its state, "monitorData" by default)
 */
public class NetViewer {
    private static final Log _log = new Log(NetViewer.class);
    private NetMonitor _monitor;
    private NetViewerGUI _gui;
    private Map _plotConfigs;
    private boolean _isClosed;
    
    public NetViewer() {
        this(NetMonitor.EXPORT_DIR_DEFAULT);
    }
    public NetViewer(String dataDir) {
        _monitor = new NetMonitor();
        _monitor.setExportDir(dataDir);
        _isClosed = false;
        _gui = new NetViewerGUI(this);
        _plotConfigs = new HashMap();
    }
 
    public void runViewer() {
        I2PThread t = new I2PThread(new NetViewerRunner(this));
        t.setName("NetViewer");
        t.setDaemon(false);
        t.start();
    }
    
    void refreshGUI() {
        _gui.stateUpdated();
    }
    
    void reloadData() { 
        _log.debug("Reloading data");
        _monitor.importData(); 
        refreshGUI();
    }
    
    public void addConfig(String peerName, PeerPlotConfig config) {
        synchronized (_plotConfigs) {
            _plotConfigs.put(peerName, config);
        }
    }
    
    public PeerPlotConfig getConfig(String peerName) {
        synchronized (_plotConfigs) {
            return (PeerPlotConfig)_plotConfigs.get(peerName);
        }
    }
    
    public List getConfigNames() {
        synchronized (_plotConfigs) {
            return new ArrayList(_plotConfigs.keySet());
        }
    }
    
    public NetMonitor getMonitor() { return _monitor; }
    
    long getDataLoadDelay() { return _monitor.getExportDelay(); }
    
    /** has the viewer been closed? */
    public boolean getIsClosed() { return _isClosed; }
    public void setIsClosed(boolean closed) { _isClosed = closed; }
    
    public static final void main(String args[]) {
        if (args.length == 1)
            new NetViewer(args[0]).runViewer();
        else
            new NetViewer().runViewer();
    }
}