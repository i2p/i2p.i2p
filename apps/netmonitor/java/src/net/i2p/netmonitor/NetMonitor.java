package net.i2p.netmonitor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Main driver for the app that harvests data about the performance of the network,
 * building summaries for each peer that change over time. <p />
 *
 * Usage: <code>NetMonitor [configFilename] [--routers filename[,filename]*] [--netDbURL url] </code> <p />
 *
 *
 *
 */
public class NetMonitor {
    private static final Log _log = new Log(NetMonitor.class);
    public static final String CONFIG_LOCATION_DEFAULT = "netmonitor.config";
    public static final String HARVEST_DELAY_PROP = "harvestDelaySeconds";
    public static final int HARVEST_DELAY_DEFAULT = 5*60;
    public static final String EXPORT_DELAY_PROP = "exportDelaySeconds";
    public static final int EXPORT_DELAY_DEFAULT = 120;
    public static final String SUMMARY_DURATION_PROP = "summaryDurationHours";
    public static final int SUMMARY_DURATION_DEFAULT = 72;
    public static final String NETDB_DIR_PROP = "netDbDir";
    public static final String NETDB_DIR_DEFAULT = "netDb";
    public static final String EXPORT_DIR_PROP = "exportDir";
    public static final String EXPORT_DIR_DEFAULT = "monitorData";
    private String _configLocation;
    private int _harvestDelay;
    private int _exportDelay;
    private String _exportDir;
    private String _netDbDir;
    private String _netDbURL;
    private String _explicitRouters;
    private int _summaryDurationHours;
    private boolean _isRunning;
    private Map _peerSummaries;
    
    public NetMonitor() {
        this(CONFIG_LOCATION_DEFAULT, null, null);
    }
    public NetMonitor(String configLocation) {
        this(configLocation, null, null);
    }
    public NetMonitor(String configLocation, String explicitFilenames, String url) {
        _configLocation = configLocation;
        _explicitRouters = explicitFilenames;
        _netDbURL = url;
        _peerSummaries = new HashMap(32);
        loadConfig();
    }
    
    /** read and call parse */
    private void loadConfig() {
        Properties props = new Properties();
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(_configLocation);
            props.load(fis);
        } catch (IOException ioe) {
            _log.warn("Error loading the net monitor config", ioe);
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
        parseConfig(props);
    }
    
    /** interpret the config elements and shove 'em in the vars */
    private void parseConfig(Properties props) {
        String val = props.getProperty(HARVEST_DELAY_PROP, ""+HARVEST_DELAY_DEFAULT);
        try {
            _harvestDelay = Integer.parseInt(val);
        } catch (NumberFormatException nfe) {
            _log.warn("Error parsing the harvest delay [" + val + "]", nfe);
            _harvestDelay = HARVEST_DELAY_DEFAULT;
        }
        
        val = props.getProperty(EXPORT_DELAY_PROP, ""+EXPORT_DELAY_DEFAULT);
        try {
            _exportDelay = Integer.parseInt(val);
        } catch (NumberFormatException nfe) {
            _log.warn("Error parsing the export delay [" + val + "]", nfe);
            _exportDelay = EXPORT_DELAY_DEFAULT;
        }
        
        val = props.getProperty(SUMMARY_DURATION_PROP, ""+SUMMARY_DURATION_DEFAULT);
        try {
            _summaryDurationHours = Integer.parseInt(val);
        } catch (NumberFormatException nfe) {
            _log.warn("Error parsing the summary duration [" + val + "]", nfe);
            _summaryDurationHours = SUMMARY_DURATION_DEFAULT;
        }
        
        _netDbDir = props.getProperty(NETDB_DIR_PROP, NETDB_DIR_DEFAULT);
        _exportDir = props.getProperty(EXPORT_DIR_PROP, EXPORT_DIR_DEFAULT);
    }
    
    public void startMonitor() {
        _isRunning = true;
        I2PThread t = new I2PThread(new NetMonitorRunner(this));
        t.setName("DataHarvester");
        t.setPriority(I2PThread.MIN_PRIORITY);
        t.setDaemon(false);
        t.start();
    }
    
    public void stopMonitor() { _isRunning = false; }
    public boolean isRunning() { return _isRunning; }
    /** how many seconds should we wait between harvestings? */
    public int getHarvestDelay() { return _harvestDelay; }
    /** how many seconds should we wait between exporting the data? */
    public int getExportDelay() { return _exportDelay; }
    /** where should we export the data? */
    public String getExportDir() { return _exportDir; }
    public void setExportDir(String dir) { _exportDir = dir; }
    public int getSummaryDurationHours() { return _summaryDurationHours; }
    /** where should we read the data from? */
    public String getNetDbDir() { return _netDbDir; }
    /** if specified, contains a set of filenames we want to harvest routerInfo data from */
    public String getExplicitRouters() { return _explicitRouters; }
    /** if specified, contains a URL to fetch references from */
    public String getNetDbURL() { return _netDbURL; }
    
    /** 
     * what peers are we keeping track of?  
     *
     * @return list of peer names (H(routerIdentity).toBase64())
     */
    public List getPeers() { 
        synchronized (_peerSummaries) {
            return new ArrayList(_peerSummaries.keySet()); 
        } 
    }
    
    /** what data do we have for the peer? */
    public PeerSummary getSummary(String peer) {
        synchronized (_peerSummaries) {
            return (PeerSummary)_peerSummaries.get(peer);
        }
    }
    
    /** keep track of the given stat on the given peer */
    public void addData(String peer, String stat, String descr, String valDescr[], long sampleDate, double val[]) {
        synchronized (_peerSummaries) {
            if (!_peerSummaries.containsKey(peer)) 
                _peerSummaries.put(peer, new PeerSummary(peer));
            PeerSummary summary = (PeerSummary)_peerSummaries.get(peer);
            summary.addData(stat, descr, valDescr, sampleDate, val);
        }
    }

    /** keep track of the given stat on the given peer */
    public void addData(String peer, String stat, String descr, String valDescr[], long sampleDate, long val[]) {
        synchronized (_peerSummaries) {
            if (!_peerSummaries.containsKey(peer)) 
                _peerSummaries.put(peer, new PeerSummary(peer));
            PeerSummary summary = (PeerSummary)_peerSummaries.get(peer);
            summary.addData(stat, descr, valDescr, sampleDate, val);
        }
    }
    
    /** keep track of the loaded summary, overwriting any existing summary for the specified peer */
    public void addSummary(PeerSummary summary) {
        synchronized (_peerSummaries) {
            Object rv = _peerSummaries.put(summary.getPeer(), summary);
            if (rv != summary) _log.error("Updating the peer summary changed objects!  old = " + rv + " new = " + summary);
        }
    }
    
    public void importData() {
        _log.debug("Running import");
        File dataDir = new File(getExportDir());
        if (!dataDir.exists()) return;
        File dataFiles[] = dataDir.listFiles(new FilenameFilter() {
            public boolean accept(File f, String name) {
                return name.endsWith(".txt");
            }
        });
        if (dataFiles == null) return;
        for (int i = 0; i < dataFiles.length; i++) {
            FileInputStream fis = null;
            boolean delete = false;
            try { 
                fis = new FileInputStream(dataFiles[i]);
                PeerSummaryReader.getInstance().read(this, fis);
            } catch (IOException ioe) {
                _log.error("Error reading the data file " + dataFiles[i].getAbsolutePath(), ioe);
                delete = true;
            } finally {
                if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                if (delete) dataFiles[i].delete();
            }
        }
        _log.debug(dataFiles.length + " summaries imported");
    }
    
    /** drop all the old summary data */
    public void coalesceData() {
        synchronized (_peerSummaries) {
            for (Iterator iter = _peerSummaries.values().iterator(); iter.hasNext(); ) {
                PeerSummary summary = (PeerSummary)iter.next();
                summary.coalesceData(_summaryDurationHours * 60*60*1000);
            }
        }
    }
    
    /**
     * main driver for the netMonitor.  the usage is:
     * <code>NetMonitor [configFilename] [--routers filename[,filename]*] [--netDbURL url]</code> 
     */
    public static final void main(String args[]) {
        String cfgLocation = CONFIG_LOCATION_DEFAULT;
        String explicitFilenames = null;
        String explicitURL = null;
        switch (args.length) {
            case 0:
                break;
            case 1:
                cfgLocation = args[0];
                break;
            case 2:
                if ("--routers".equalsIgnoreCase(args[0]))
                    explicitFilenames = args[1];
                else
                    explicitURL = args[1];
                break;
            case 3:
                cfgLocation = args[0];
                if ("--routers".equalsIgnoreCase(args[1]))
                    explicitFilenames = args[2];
                else
                    explicitURL = args[2];
                break;
            default:
                System.err.println("Usage: NetMonitor [configFilename] [--routers filename[,filename]*] [--netDbURL url]");
                return;
        }
        new NetMonitor(cfgLocation, explicitFilenames, explicitURL).startMonitor();
    }
}
