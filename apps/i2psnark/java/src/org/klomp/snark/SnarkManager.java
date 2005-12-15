package org.klomp.snark;

import java.io.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Manage multiple snarks
 */
public class SnarkManager {
    private static SnarkManager _instance = new SnarkManager();
    public static SnarkManager instance() { return _instance; }
    
    /** map of (canonical) filename to Snark instance (unsynchronized) */
    private Map _snarks;
    private String _configFile;
    private Properties _config;
    private I2PAppContext _context;
    private Log _log;
    private List _messages;
    
    public static final String PROP_I2CP_HOST = "i2psnark.i2cpHost";
    public static final String PROP_I2CP_PORT = "i2psnark.i2cpPort";
    public static final String PROP_I2CP_OPTS = "i2psnark.i2cpOptions";
    public static final String PROP_EEP_HOST = "i2psnark.eepHost";
    public static final String PROP_EEP_PORT = "i2psnark.eepPort";
    public static final String PROP_DIR = "i2psnark.dir";
    
    private SnarkManager() {
        _snarks = new HashMap();
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(SnarkManager.class);
        _messages = new ArrayList(16);
        loadConfig("i2psnark.config");
        I2PThread monitor = new I2PThread(new DirMonitor(), "Snark DirMonitor");
        monitor.setDaemon(true);
        monitor.start();
    }
    
    private static final int MAX_MESSAGES = 10;
    public void addMessage(String message) {
        synchronized (_messages) {
            _messages.add(message);
            while (_messages.size() > MAX_MESSAGES)
                _messages.remove(0);
        }
        _log.info("MSG: " + message);
    }
    
    private boolean shouldAutoStart() { return true; }
    private int getStartupDelayMinutes() { return 1; }
    private File getDataDir() { 
        String dir = _config.getProperty(PROP_DIR);
        if ( (dir == null) || (dir.trim().length() <= 0) )
            dir = "i2psnark";
        return new File(dir); 
    }
    
    public void loadConfig(String filename) {
        _configFile = filename;
        if (_config == null)
            _config = new Properties();
        File cfg = new File(filename);
        if (cfg.exists()) {
            try {
                DataHelper.loadProps(_config, cfg);
            } catch (IOException ioe) {
                _log.error("Error loading I2PSnark config '" + filename + "'", ioe);
            }
        } 
        // now add sane defaults
        if (!_config.containsKey(PROP_I2CP_HOST))
            _config.setProperty(PROP_I2CP_HOST, "localhost");
        if (!_config.containsKey(PROP_I2CP_PORT))
            _config.setProperty(PROP_I2CP_PORT, "7654");
        if (!_config.containsKey(PROP_EEP_HOST))
            _config.setProperty(PROP_EEP_HOST, "localhost");
        if (!_config.containsKey(PROP_EEP_PORT))
            _config.setProperty(PROP_EEP_PORT, "4444");
        if (!_config.containsKey(PROP_DIR))
            _config.setProperty(PROP_DIR, "i2psnark");
        updateConfig();
    }
    
    private void updateConfig() {
        String i2cpHost = _config.getProperty(PROP_I2CP_HOST);
        int i2cpPort = getInt(PROP_I2CP_PORT, 7654);
        String opts = _config.getProperty(PROP_I2CP_OPTS);
        Properties i2cpOpts = new Properties();
        if (opts != null) {
            StringTokenizer tok = new StringTokenizer(opts, " ");
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int split = pair.indexOf('=');
                if (split > 0) 
                    i2cpOpts.setProperty(pair.substring(0, split), pair.substring(split+1));
            }
        }
        if (i2cpHost != null) {
            I2PSnarkUtil.instance().setI2CPConfig(i2cpHost, i2cpPort, i2cpOpts);
            _log.debug("Configuring with I2CP options " + i2cpOpts);
        }
        //I2PSnarkUtil.instance().setI2CPConfig("66.111.51.110", 7654, new Properties());
        String eepHost = _config.getProperty(PROP_EEP_HOST);
        int eepPort = getInt(PROP_EEP_PORT, 4444);
        if (eepHost != null)
            I2PSnarkUtil.instance().setProxy(eepHost, eepPort);
        getDataDir().mkdirs();
    }
    
    private int getInt(String prop, int defaultVal) {
        String p = _config.getProperty(prop);
        try {
            if ( (p != null) && (p.trim().length() > 0) )
                return  Integer.parseInt(p.trim());
        } catch (NumberFormatException nfe) {
            // ignore
        }
        return defaultVal;
    }
    
    public void saveConfig() {
        try {
            DataHelper.storeProps(_config, new File(_configFile));
        } catch (IOException ioe) {
            addMessage("Unable to save the config to '" + _configFile + "'");
        }
    }
    
    /** set of filenames that we are dealing with */
    public Set listTorrentFiles() { synchronized (_snarks) { return new HashSet(_snarks.keySet()); } }
    /**
     * Grab the torrent given the (canonical) filename
     */
    public Snark getTorrent(String filename) { synchronized (_snarks) { return (Snark)_snarks.get(filename); } }
    public void addTorrent(String filename) {
        File sfile = new File(filename);
        try {
            filename = sfile.getCanonicalPath();
        } catch (IOException ioe) {
            _log.error("Unable to add the torrent " + filename, ioe);
            addMessage("ERR: Could not add the torrent '" + filename + "': " + ioe.getMessage());
            return;
        }
        File dataDir = getDataDir();
        Snark torrent = null;
        synchronized (_snarks) {
            torrent = (Snark)_snarks.get(filename);
            if (torrent == null) {
                torrent = new Snark(filename, null, -1, null, null, false, dataDir.getPath());
                _snarks.put(filename, torrent);
            } else {
                return;
            }
        }
        // ok, snark created, now lets start it up or configure it further
        if (shouldAutoStart()) {
            torrent.startTorrent();
            addMessage("Torrent added and started: '" + filename + "'");
        } else {
            addMessage("Torrent added: '" + filename + "'");
        }
    }
    
    /**
     * Stop the torrent, leaving it on the list of torrents unless told to remove it
     */
    public Snark stopTorrent(String filename, boolean shouldRemove) {
        File sfile = new File(filename);
        try {
            filename = sfile.getCanonicalPath();
        } catch (IOException ioe) {
            _log.error("Unable to remove the torrent " + filename, ioe);
            addMessage("ERR: Could not remove the torrent '" + filename + "': " + ioe.getMessage());
            return null;
        }
        int remaining = 0;
        Snark torrent = null;
        synchronized (_snarks) {
            if (shouldRemove)
                torrent = (Snark)_snarks.remove(filename);
            else
                torrent = (Snark)_snarks.get(filename);
            remaining = _snarks.size();
        }
        if (torrent != null) {
            torrent.stopTorrent();
            if (remaining == 0) {
                // should we disconnect/reconnect here (taking care to deal with the other thread's
                // I2PServerSocket.accept() call properly?)
                ////I2PSnarkUtil.instance().
            }
            addMessage("Torrent stopped: '" + filename + "'");
        }
        return torrent;
    }
    /**
     * Stop the torrent and delete the torrent file itself, but leaving the data
     * behind.
     */
    public void removeTorrent(String filename) {
        Snark torrent = stopTorrent(filename, true);
        if (torrent != null) {
            File torrentFile = new File(filename);
            torrentFile.delete();
            addMessage("Torrent removed: '" + filename + "'");
        }
    }
    
    private class DirMonitor implements Runnable {
        public void run() {
            try { Thread.sleep(60*1000*getStartupDelayMinutes()); } catch (InterruptedException ie) {}
            while (true) {
                File dir = getDataDir();
                _log.debug("Directory Monitor loop over " + dir.getAbsolutePath());
                monitorTorrents(dir);
                try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
            }
        }
    }
    
    private void monitorTorrents(File dir) {
        String fileNames[] = dir.list(TorrentFilenameFilter.instance());
        List foundNames = new ArrayList(0);
        if (fileNames != null) {
            for (int i = 0; i < fileNames.length; i++) {
                try {
                    foundNames.add(new File(dir, fileNames[i]).getCanonicalPath());
                } catch (IOException ioe) {
                    _log.error("Error resolving '" + fileNames[i] + "' in '" + dir, ioe);
                }
            }
        }
        
        Set existingNames = listTorrentFiles();
        // lets find new ones first...
        for (int i = 0; i < foundNames.size(); i++) {
            if (existingNames.contains(foundNames.get(i))) {
                // already known.  noop
            } else {
                addTorrent((String)foundNames.get(i));
            }
        }
        // now lets see which ones have been removed...
        for (Iterator iter = existingNames.iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            if (foundNames.contains(name)) {
                // known and still there.  noop
            } else {
                // known, but removed.  drop it
                stopTorrent(name, true);
            }
        }
    }
    
    private static class TorrentFilenameFilter implements FilenameFilter {
        private static final TorrentFilenameFilter _filter = new TorrentFilenameFilter();
        public static TorrentFilenameFilter instance() { return _filter; }
        public boolean accept(File dir, String name) {
            return (name != null) && (name.endsWith(".torrent"));
        }
    }
}
