package org.klomp.snark;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Manage multiple snarks
 */
public class SnarkManager implements Snark.CompleteListener {
    private static SnarkManager _instance = new SnarkManager();
    public static SnarkManager instance() { return _instance; }
    
    /** map of (canonical) filename to Snark instance (unsynchronized) */
    private Map _snarks;
    private Object _addSnarkLock;
    private File _configFile;
    private Properties _config;
    private I2PAppContext _context;
    private Log _log;
    private List _messages;
    private I2PSnarkUtil _util;
    private PeerCoordinatorSet _peerCoordinatorSet;
    private ConnectionAcceptor _connectionAcceptor;
    
    public static final String PROP_I2CP_HOST = "i2psnark.i2cpHost";
    public static final String PROP_I2CP_PORT = "i2psnark.i2cpPort";
    public static final String PROP_I2CP_OPTS = "i2psnark.i2cpOptions";
    public static final String PROP_EEP_HOST = "i2psnark.eepHost";
    public static final String PROP_EEP_PORT = "i2psnark.eepPort";
    public static final String PROP_UPLOADERS_TOTAL = "i2psnark.uploaders.total";
    public static final String PROP_UPBW_MAX = "i2psnark.upbw.max";
    public static final String PROP_DIR = "i2psnark.dir";
    public static final String PROP_META_PREFIX = "i2psnark.zmeta.";
    public static final String PROP_META_BITFIELD_SUFFIX = ".bitfield";

    private static final String CONFIG_FILE = "i2psnark.config";
    public static final String PROP_AUTO_START = "i2snark.autoStart";   // oops
    public static final String DEFAULT_AUTO_START = "false";
    public static final String PROP_LINK_PREFIX = "i2psnark.linkPrefix";
    public static final String DEFAULT_LINK_PREFIX = "file:///";
    
    public static final int MIN_UP_BW = 2;
    public static final int DEFAULT_MAX_UP_BW = 10;

    private SnarkManager() {
        _snarks = new HashMap();
        _addSnarkLock = new Object();
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(SnarkManager.class);
        _messages = new ArrayList(16);
        _util = new I2PSnarkUtil(_context);
        _configFile = new File(CONFIG_FILE);
        if (!_configFile.isAbsolute())
            _configFile = new File(_context.getConfigDir(), CONFIG_FILE);
        loadConfig(null);
    }

    /** Caller _must_ call loadConfig(file) before this if setting new values
     *  for i2cp host/port or i2psnark.dir
     */
    public void start() {
        _peerCoordinatorSet = new PeerCoordinatorSet();
        _connectionAcceptor = new ConnectionAcceptor(_util);
        int minutes = getStartupDelayMinutes();
        _messages.add("Adding torrents in " + minutes + (minutes == 1 ? " minute" : " minutes"));
        I2PAppThread monitor = new I2PAppThread(new DirMonitor(), "Snark DirMonitor");
        monitor.setDaemon(true);
        monitor.start();
        _context.addShutdownTask(new SnarkManagerShutdown());
    }
    
    /** hook to I2PSnarkUtil for the servlet */
    public I2PSnarkUtil util() { return _util; }

    private static final int MAX_MESSAGES = 5;
    public void addMessage(String message) {
        synchronized (_messages) {
            _messages.add(message);
            while (_messages.size() > MAX_MESSAGES)
                _messages.remove(0);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("MSG: " + message);
    }
    
    /** newest last */
    public List getMessages() {
        synchronized (_messages) {
            return new ArrayList(_messages);
        }
    }
    
    public boolean shouldAutoStart() {
        return Boolean.valueOf(_config.getProperty(PROP_AUTO_START, DEFAULT_AUTO_START+"")).booleanValue();
    }
    public String linkPrefix() {
        return _config.getProperty(PROP_LINK_PREFIX, DEFAULT_LINK_PREFIX + getDataDir().getAbsolutePath() + File.separatorChar);
    }
    private int getStartupDelayMinutes() { return 3; }
    public File getDataDir() { 
        String dir = _config.getProperty(PROP_DIR, "i2psnark");
        File f = new File(dir);
        if (!f.isAbsolute())
            f = new File(_context.getAppDir(), dir);
        return f; 
    }
    
    /** null to set initial defaults */
    public void loadConfig(String filename) {
        if (_config == null)
            _config = new Properties();
        if (filename != null) {
            File cfg = new File(filename);
            if (!cfg.isAbsolute())
                cfg = new File(_context.getConfigDir(), filename);
            _configFile = cfg;
            if (cfg.exists()) {
                try {
                    DataHelper.loadProps(_config, cfg);
                } catch (IOException ioe) {
                   _log.error("Error loading I2PSnark config '" + filename + "'", ioe);
                }
            } 
        } 
        // now add sane defaults
        if (!_config.containsKey(PROP_I2CP_HOST))
            _config.setProperty(PROP_I2CP_HOST, "127.0.0.1");
        if (!_config.containsKey(PROP_I2CP_PORT))
            _config.setProperty(PROP_I2CP_PORT, "7654");
        if (!_config.containsKey(PROP_I2CP_OPTS))
            _config.setProperty(PROP_I2CP_OPTS, "inbound.length=2 inbound.lengthVariance=0 outbound.length=2 outbound.lengthVariance=0 inbound.quantity=3 outbound.quantity=3");
        if (!_config.containsKey(PROP_EEP_HOST))
            _config.setProperty(PROP_EEP_HOST, "127.0.0.1");
        if (!_config.containsKey(PROP_EEP_PORT))
            _config.setProperty(PROP_EEP_PORT, "4444");
        if (!_config.containsKey(PROP_UPLOADERS_TOTAL))
            _config.setProperty(PROP_UPLOADERS_TOTAL, "" + Snark.MAX_TOTAL_UPLOADERS);
        if (!_config.containsKey(PROP_DIR))
            _config.setProperty(PROP_DIR, "i2psnark");
        if (!_config.containsKey(PROP_AUTO_START))
            _config.setProperty(PROP_AUTO_START, DEFAULT_AUTO_START);
        updateConfig();
    }

    /** call from DirMonitor since loadConfig() is called before router I2CP is up */
    private void getBWLimit() {
        if (!_config.containsKey(PROP_UPBW_MAX)) {
            int[] limits = BWLimits.getBWLimits(_util.getI2CPHost(), _util.getI2CPPort());
            if (limits != null && limits[1] > 0)
                _util.setMaxUpBW(limits[1]);
        }
    }
    
    private void updateConfig() {
        String i2cpHost = _config.getProperty(PROP_I2CP_HOST);
        int i2cpPort = getInt(PROP_I2CP_PORT, 7654);
        String opts = _config.getProperty(PROP_I2CP_OPTS);
        Map i2cpOpts = new HashMap();
        if (opts != null) {
            StringTokenizer tok = new StringTokenizer(opts, " ");
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int split = pair.indexOf('=');
                if (split > 0)
                    i2cpOpts.put(pair.substring(0, split), pair.substring(split+1));
            }
        }
        if (i2cpHost != null) {
            _util.setI2CPConfig(i2cpHost, i2cpPort, i2cpOpts);
            _log.debug("Configuring with I2CP options " + i2cpOpts);
        }
        //I2PSnarkUtil.instance().setI2CPConfig("66.111.51.110", 7654, new Properties());
        String eepHost = _config.getProperty(PROP_EEP_HOST);
        int eepPort = getInt(PROP_EEP_PORT, 4444);
        if (eepHost != null)
            _util.setProxy(eepHost, eepPort);
        _util.setMaxUploaders(getInt(PROP_UPLOADERS_TOTAL, Snark.MAX_TOTAL_UPLOADERS));
        _util.setMaxUpBW(getInt(PROP_UPBW_MAX, DEFAULT_MAX_UP_BW));
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
    
    public void updateConfig(String dataDir, boolean autoStart, String seedPct, String eepHost, 
                             String eepPort, String i2cpHost, String i2cpPort, String i2cpOpts,
                             String upLimit, String upBW, boolean useOpenTrackers, String openTrackers) {
        boolean changed = false;
        if (eepHost != null) {
            int port = _util.getEepProxyPort();
            try { port = Integer.parseInt(eepPort); } catch (NumberFormatException nfe) {}
            String host = _util.getEepProxyHost();
            if ( (eepHost.trim().length() > 0) && (port > 0) &&
                 ((!host.equals(eepHost) || (port != _util.getEepProxyPort()) )) ) {
                _util.setProxy(eepHost, port);
                changed = true;
                _config.setProperty(PROP_EEP_HOST, eepHost);
                _config.setProperty(PROP_EEP_PORT, eepPort+"");
                addMessage("EepProxy location changed to " + eepHost + ":" + port);
            }
        }
        if (upLimit != null) {
            int limit = _util.getMaxUploaders();
            try { limit = Integer.parseInt(upLimit); } catch (NumberFormatException nfe) {}
            if ( limit != _util.getMaxUploaders()) {
                if ( limit >= Snark.MIN_TOTAL_UPLOADERS ) {
                    _util.setMaxUploaders(limit);
                    changed = true;
                    _config.setProperty(PROP_UPLOADERS_TOTAL, "" + limit);
                    addMessage("Total uploaders limit changed to " + limit);
                } else {
                    addMessage("Minimum total uploaders limit is " + Snark.MIN_TOTAL_UPLOADERS);
                }
            }
        }
        if (upBW != null) {
            int limit = _util.getMaxUpBW();
            try { limit = Integer.parseInt(upBW); } catch (NumberFormatException nfe) {}
            if ( limit != _util.getMaxUpBW()) {
                if ( limit >= MIN_UP_BW ) {
                    _util.setMaxUpBW(limit);
                    changed = true;
                    _config.setProperty(PROP_UPBW_MAX, "" + limit);
                    addMessage("Up BW limit changed to " + limit + "KBps");
                } else {
                    addMessage("Minimum Up BW limit is " + MIN_UP_BW + "KBps");
                }
            }
        }
        if (i2cpHost != null) {
            int oldI2CPPort = _util.getI2CPPort();
            String oldI2CPHost = _util.getI2CPHost();
            int port = oldI2CPPort;
            try { port = Integer.parseInt(i2cpPort); } catch (NumberFormatException nfe) {}
            String host = oldI2CPHost;
            Map opts = new HashMap();
            if (i2cpOpts == null) i2cpOpts = "";
            StringTokenizer tok = new StringTokenizer(i2cpOpts, " \t\n");
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int split = pair.indexOf('=');
                if (split > 0)
                    opts.put(pair.substring(0, split), pair.substring(split+1));
            }
            Map oldOpts = new HashMap();
            String oldI2CPOpts = _config.getProperty(PROP_I2CP_OPTS);
            if (oldI2CPOpts == null) oldI2CPOpts = "";
            tok = new StringTokenizer(oldI2CPOpts, " \t\n");
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int split = pair.indexOf('=');
                if (split > 0)
                    oldOpts.put(pair.substring(0, split), pair.substring(split+1));
            }
            
            if ( (i2cpHost.trim().length() > 0) && (port > 0) &&
                 ((!host.equals(i2cpHost) || 
                  (port != _util.getI2CPPort()) ||
                  (!oldOpts.equals(opts)))) ) {
                boolean snarksActive = false;
                Set names = listTorrentFiles();
                for (Iterator iter = names.iterator(); iter.hasNext(); ) {
                    Snark snark = getTorrent((String)iter.next());
                    if ( (snark != null) && (!snark.stopped) ) {
                        snarksActive = true;
                        break;
                    }
                }
                if (snarksActive) {
                    addMessage("Cannot change the I2CP settings while torrents are active");
                    _log.debug("i2cp host [" + i2cpHost + "] i2cp port " + port + " opts [" + opts 
                               + "] oldOpts [" + oldOpts + "]");
                } else {
                    if (_util.connected()) {
                        _util.disconnect();
                        addMessage("Disconnecting old I2CP destination");
                    }
                    Properties p = new Properties();
                    p.putAll(opts);
                    addMessage("I2CP settings changed to " + i2cpHost + ":" + port + " (" + i2cpOpts.trim() + ")");
                    _util.setI2CPConfig(i2cpHost, port, p);
                    boolean ok = _util.connect();
                    if (!ok) {
                        addMessage("Unable to connect with the new settings, reverting to the old I2CP settings");
                        _util.setI2CPConfig(oldI2CPHost, oldI2CPPort, oldOpts);
                        ok = _util.connect();
                        if (!ok)
                            addMessage("Unable to reconnect with the old settings!");
                    } else {
                        addMessage("Reconnected on the new I2CP destination");
                        _config.setProperty(PROP_I2CP_HOST, i2cpHost.trim());
                        _config.setProperty(PROP_I2CP_PORT, "" + port);
                        _config.setProperty(PROP_I2CP_OPTS, i2cpOpts.trim());
                        changed = true;
                        // no PeerAcceptors/I2PServerSockets to deal with, since all snarks are inactive
                        for (Iterator iter = names.iterator(); iter.hasNext(); ) {
                            String name = (String)iter.next();
                            Snark snark = getTorrent(name);
                            if ( (snark != null) && (snark.acceptor != null) ) {
                                snark.acceptor.restart();
                                addMessage("I2CP listener restarted for " + snark.meta.getName());
                            }
                        }
                    }
                }
                changed = true;
            }
        }
        if (shouldAutoStart() != autoStart) {
            _config.setProperty(PROP_AUTO_START, autoStart + "");
            addMessage("Adjusted autostart to " + autoStart);
            changed = true;
        }
        if (_util.shouldUseOpenTrackers() != useOpenTrackers) {
            _config.setProperty(I2PSnarkUtil.PROP_USE_OPENTRACKERS, useOpenTrackers + "");
            addMessage((useOpenTrackers ? "En" : "Dis") + "abled open trackers - torrent restart required to take effect.");
            changed = true;
        }
        if (openTrackers != null) {
            if (openTrackers.trim().length() > 0 && !openTrackers.trim().equals(_util.getOpenTrackerString())) {
                _config.setProperty(I2PSnarkUtil.PROP_OPENTRACKERS, openTrackers.trim());
                addMessage("Open Tracker list changed - torrent restart required to take effect.");
                changed = true;
            }
        }
        if (changed) {
            saveConfig();
        } else {
            addMessage("Configuration unchanged.");
        }
    }
    
    public void saveConfig() {
        try {
            synchronized (_configFile) {
                DataHelper.storeProps(_config, _configFile);
            }
        } catch (IOException ioe) {
            addMessage("Unable to save the config to '" + _configFile.getAbsolutePath() + "'.");
        }
    }
    
    public Properties getConfig() { return _config; }
    
    /** hardcoded for sanity.  perhaps this should be customizable, for people who increase their ulimit, etc. */
    private static final int MAX_FILES_PER_TORRENT = 512;
    
    /** set of filenames that we are dealing with */
    public Set listTorrentFiles() { synchronized (_snarks) { return new HashSet(_snarks.keySet()); } }
    /**
     * Grab the torrent given the (canonical) filename
     */
    public Snark getTorrent(String filename) { synchronized (_snarks) { return (Snark)_snarks.get(filename); } }
    public void addTorrent(String filename) { addTorrent(filename, false); }
    public void addTorrent(String filename, boolean dontAutoStart) {
        if ((!dontAutoStart) && !_util.connected()) {
            addMessage("Connecting to I2P");
            boolean ok = _util.connect();
            if (!ok) {
                addMessage("Error connecting to I2P - check your I2CP settings!");
                return;
            }
        }
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
        }
        // don't hold the _snarks lock while verifying the torrent
        if (torrent == null) {
            synchronized (_addSnarkLock) {
                // double-check
                synchronized (_snarks) {
                    if(_snarks.get(filename) != null)
                        return;
                }

                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(sfile);
                    MetaInfo info = new MetaInfo(fis);
                    fis.close();
                    fis = null;
                    
                    String rejectMessage = locked_validateTorrent(info);
                    if (rejectMessage != null) {
                        sfile.delete();
                        addMessage(rejectMessage);
                        return;
                    } else {
                        torrent = new Snark(_util, filename, null, -1, null, null, this,
                                            _peerCoordinatorSet, _connectionAcceptor,
                                            false, dataDir.getPath());
                        torrent.completeListener = this;
                        synchronized (_snarks) {
                            _snarks.put(filename, torrent);
                        }
                    }
                } catch (IOException ioe) {
                    addMessage("Torrent in " + sfile.getName() + " is invalid: " + ioe.getMessage());
                    if (sfile.exists())
                        sfile.delete();
                    return;
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }
            }
        } else {
            return;
        }
        // ok, snark created, now lets start it up or configure it further
        File f = new File(filename);
        if (!dontAutoStart && shouldAutoStart()) {
            torrent.startTorrent();
            addMessage("Torrent added and started: '" + f.getName() + "'.");
        } else {
            addMessage("Torrent added: '" + f.getName() + "'.");
        }
    }
    
    /**
     * Get the timestamp for a torrent from the config file
     */
    public long getSavedTorrentTime(Snark snark) {
        MetaInfo metainfo = snark.meta;
        byte[] ih = metainfo.getInfoHash();
        String infohash = Base64.encode(ih);
        infohash = infohash.replace('=', '$');
        String time = _config.getProperty(PROP_META_PREFIX + infohash + PROP_META_BITFIELD_SUFFIX);
        if (time == null)
            return 0;
        int comma = time.indexOf(',');
        if (comma <= 0)
            return 0;
        time = time.substring(0, comma);
        try { return Long.parseLong(time); } catch (NumberFormatException nfe) {}
        return 0;
    }
    
    /**
     * Get the saved bitfield for a torrent from the config file.
     * Convert "." to a full bitfield.
     */
    public BitField getSavedTorrentBitField(Snark snark) {
        MetaInfo metainfo = snark.meta;
        byte[] ih = metainfo.getInfoHash();
        String infohash = Base64.encode(ih);
        infohash = infohash.replace('=', '$');
        String bf = _config.getProperty(PROP_META_PREFIX + infohash + PROP_META_BITFIELD_SUFFIX);
        if (bf == null)
            return null;
        int comma = bf.indexOf(',');
        if (comma <= 0)
            return null;
        bf = bf.substring(comma + 1).trim();
        int len = metainfo.getPieces();
        if (bf.equals(".")) {
            BitField bitfield = new BitField(len);
            for (int i = 0; i < len; i++)
                 bitfield.set(i);
            return bitfield;
        }
        byte[] bitfield = Base64.decode(bf);
        if (bitfield == null)
            return null;
        if (bitfield.length * 8 < len)
            return null;
        return new BitField(bitfield, len);
    }
    
    /**
     * Save the completion status of a torrent and the current time in the config file
     * in the form "i2psnark.zmeta.$base64infohash=$time,$base64bitfield".
     * The config file property key is appended with the Base64 of the infohash,
     * with the '=' changed to '$' since a key can't contain '='.
     * The time is a standard long converted to string.
     * The status is either a bitfield converted to Base64 or "." for a completed
     * torrent to save space in the config file and in memory.
     */
    public void saveTorrentStatus(MetaInfo metainfo, BitField bitfield) {
        byte[] ih = metainfo.getInfoHash();
        String infohash = Base64.encode(ih);
        infohash = infohash.replace('=', '$');
        String now = "" + System.currentTimeMillis();
        String bfs;
        if (bitfield.complete()) {
          bfs = ".";
        } else {
          byte[] bf = bitfield.getFieldBytes();
          bfs = Base64.encode(bf);
        }
        _config.setProperty(PROP_META_PREFIX + infohash + PROP_META_BITFIELD_SUFFIX, now + "," + bfs);
        saveConfig();
    }
    
    /**
     * Remove the status of a torrent from the config file.
     * This may help the config file from growing too big.
     */
    public void removeTorrentStatus(MetaInfo metainfo) {
        byte[] ih = metainfo.getInfoHash();
        String infohash = Base64.encode(ih);
        infohash = infohash.replace('=', '$');
        _config.remove(PROP_META_PREFIX + infohash + PROP_META_BITFIELD_SUFFIX);
        saveConfig();
    }
    
    private String locked_validateTorrent(MetaInfo info) throws IOException {
        String announce = info.getAnnounce();
        // basic validation of url
        if ((!announce.startsWith("http://")) ||
            (announce.indexOf(".i2p/") < 0)) // need to do better than this
            return "Non-i2p tracker in " + info.getName() + ", deleting it from our list of trackers!";
        List files = info.getFiles();
        if ( (files != null) && (files.size() > MAX_FILES_PER_TORRENT) ) {
            return "Too many files in " + info.getName() + " (" + files.size() + "), deleting it!";
        } else if (info.getPieces() <= 0) {
            return "No pieces in " + info.getName() + "?  deleting it!";
        } else if (info.getPieceLength(0) > Storage.MAX_PIECE_SIZE) {
            return "Pieces are too large in " + info.getName() + " (" + DataHelper.formatSize(info.getPieceLength(0)) +
                   "B), deleting it.";
        } else if (info.getTotalLength() > Storage.MAX_TOTAL_SIZE) {
            System.out.println("torrent info: " + info.toString());
            List lengths = info.getLengths();
            if (lengths != null)
                for (int i = 0; i < lengths.size(); i++)
                    System.out.println("File " + i + " is " + lengths.get(i) + " long.");
            
            return "Torrents larger than " + DataHelper.formatSize(Storage.MAX_TOTAL_SIZE) +
                   "B are not supported yet (because we're paranoid): " + info.getName() + ", deleting it!";
        } else {
            // ok
            return null;
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
            boolean wasStopped = torrent.stopped;
            torrent.stopTorrent();
            if (remaining == 0) {
                // should we disconnect/reconnect here (taking care to deal with the other thread's
                // I2PServerSocket.accept() call properly?)
                ////_util.
            }
            if (!wasStopped)
                addMessage("Torrent stopped: '" + sfile.getName() + "'.");
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
            if (torrent.storage != null)
                removeTorrentStatus(torrent.storage.getMetaInfo());
            addMessage("Torrent removed: '" + torrentFile.getName() + "'.");
        }
    }
    
    private class DirMonitor implements Runnable {
        public void run() {
            try { Thread.sleep(60*1000*getStartupDelayMinutes()); } catch (InterruptedException ie) {}
            // the first message was a "We are starting up in 1m" 
            synchronized (_messages) { 
                if (_messages.size() == 1)
                    _messages.remove(0);
            }

            // here because we need to delay until I2CP is up
            // although the user will see the default until then
            getBWLimit();
            while (true) {
                File dir = getDataDir();
                _log.debug("Directory Monitor loop over " + dir.getAbsolutePath());
                try {
                    monitorTorrents(dir);
                } catch (Exception e) {
                    _log.error("Error in the DirectoryMonitor", e);
                }
                try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
            }
        }
    }
    
    /** two listeners */
    public void torrentComplete(Snark snark) {
        File f = new File(snark.torrent);
        long len = snark.meta.getTotalLength();
        addMessage("Download finished: " + f.getName() + " (size: " + DataHelper.formatSize(len) + "B)");
        updateStatus(snark);
    }
    
    public void updateStatus(Snark snark) {
        saveTorrentStatus(snark.meta, snark.storage.getBitField());
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
                if (shouldAutoStart() && !_util.connect())
                    addMessage("Unable to connect to I2P!");
                addTorrent((String)foundNames.get(i), !shouldAutoStart());
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

    private static final String DEFAULT_TRACKERS[] = { 
//       "Postman", "http://YRgrgTLGnbTq2aZOZDJQ~o6Uk5k6TK-OZtx0St9pb0G-5EGYURZioxqYG8AQt~LgyyI~NCj6aYWpPO-150RcEvsfgXLR~CxkkZcVpgt6pns8SRc3Bi-QSAkXpJtloapRGcQfzTtwllokbdC-aMGpeDOjYLd8b5V9Im8wdCHYy7LRFxhEtGb~RL55DA8aYOgEXcTpr6RPPywbV~Qf3q5UK55el6Kex-6VCxreUnPEe4hmTAbqZNR7Fm0hpCiHKGoToRcygafpFqDw5frLXToYiqs9d4liyVB-BcOb0ihORbo0nS3CLmAwZGvdAP8BZ7cIYE3Z9IU9D1G8JCMxWarfKX1pix~6pIA-sp1gKlL1HhYhPMxwyxvuSqx34o3BqU7vdTYwWiLpGM~zU1~j9rHL7x60pVuYaXcFQDR4-QVy26b6Pt6BlAZoFmHhPcAuWfu-SFhjyZYsqzmEmHeYdAwa~HojSbofg0TMUgESRXMw6YThK1KXWeeJVeztGTz25sL8AAAA.i2p/announce.php=http://tracker.postman.i2p/"
//       , "eBook", "http://E71FRom6PZNEqTN2Lr8P-sr23b7HJVC32KoGnVQjaX6zJiXwhJy2HsXob36Qmj81TYFZdewFZa9mSJ533UZgGyQkXo2ahctg82JKYZfDe5uDxAn1E9YPjxZCWJaFJh0S~UwSs~9AZ7UcauSJIoNtpxrtbmRNVFLqnkEDdLZi26TeucfOmiFmIWnVblLniWv3tG1boE9Abd-6j3FmYVrRucYuepAILYt6katmVNOk6sXmno1Eynrp~~MBuFq0Ko6~jsc2E2CRVYXDhGHEMdt-j6JUz5D7S2RIVzDRqQyAZLKJ7OdQDmI31przzmne1vOqqqLC~1xUumZVIvF~yOeJUGNjJ1Vx0J8i2BQIusn1pQJ6UCB~ZtZZLQtEb8EPVCfpeRi2ri1M5CyOuxN0V5ekmPHrYIBNevuTCRC26NP7ZS5VDgx1~NaC3A-CzJAE6f1QXi0wMI9aywNG5KGzOPifcsih8eyGyytvgLtrZtV7ykzYpPCS-rDfITncpn5hliPUAAAA.i2p/pub/bt/announce.php=http://de-ebook-archiv.i2p/pub/bt/"
//       , "Gaytorrents", "http://uxPWHbK1OIj9HxquaXuhMiIvi21iK0~ZiG9d8G0840ZXIg0r6CbiV71xlsqmdnU6wm0T2LySriM0doW2gUigo-5BNkUquHwOjLROiETnB3ZR0Ml4IGa6QBPn1aAq2d9~g1r1nVjLE~pcFnXB~cNNS7kIhX1d6nLgYVZf0C2cZopEow2iWVUggGGnAA9mHjE86zLEnTvAyhbAMTqDQJhEuLa0ZYSORqzJDMkQt90MV4YMjX1ICY6RfUSFmxEqu0yWTrkHsTtRw48l~dz9wpIgc0a0T9C~eeWvmBFTqlJPtQZwntpNeH~jF7nlYzB58olgV2HHFYpVYD87DYNzTnmNWxCJ5AfDorm6AIUCV2qaE7tZtI1h6fbmGpGlPyW~Kw5GXrRfJwNvr6ajwAVi~bPVnrBwDZezHkfW4slOO8FACPR28EQvaTu9nwhAbqESxV2hCTq6vQSGjuxHeOuzBOEvRWkLKOHWTC09t2DbJ94FSqETmZopTB1ukEmaxRWbKSIaAAAA.i2p/announce.php=http://gaytorrents.i2p/"
//       , "NickyB", "http://9On6d3cZ27JjwYCtyJJbowe054d5tFnfMjv4PHsYs-EQn4Y4mk2zRixatvuAyXz2MmRfXG-NAUfhKr0KCxRNZbvHmlckYfT-WBzwwpiMAl0wDFY~Pl8cqXuhfikSG5WrqdPfDNNIBuuznS0dqaczf~OyVaoEOpvuP3qV6wKqbSSLpjOwwAaQPHjlRtNIW8-EtUZp-I0LT45HSoowp~6b7zYmpIyoATvIP~sT0g0MTrczWhbVTUZnEkZeLhOR0Duw1-IRXI2KHPbA24wLO9LdpKKUXed05RTz0QklW5ROgR6TYv7aXFufX8kC0-DaKvQ5JKG~h8lcoHvm1RCzNqVE-2aiZnO2xH08H-iCWoLNJE-Td2kT-Tsc~3QdQcnEUcL5BF-VT~QYRld2--9r0gfGl-yDrJZrlrihHGr5J7ImahelNn9PpkVp6eIyABRmJHf2iicrk3CtjeG1j9OgTSwaNmEpUpn4aN7Kx0zNLdH7z6uTgCGD9Kmh1MFYrsoNlTp4AAAA.i2p/bittorrent/announce.php=http://nickyb.i2p/bittorrent/"
//       , "Orion", "http://gKik1lMlRmuroXVGTZ~7v4Vez3L3ZSpddrGZBrxVriosCQf7iHu6CIk8t15BKsj~P0JJpxrofeuxtm7SCUAJEr0AIYSYw8XOmp35UfcRPQWyb1LsxUkMT4WqxAT3s1ClIICWlBu5An~q-Mm0VFlrYLIPBWlUFnfPR7jZ9uP5ZMSzTKSMYUWao3ejiykr~mtEmyls6g-ZbgKZawa9II4zjOy-hdxHgP-eXMDseFsrym4Gpxvy~3Fv9TuiSqhpgm~UeTo5YBfxn6~TahKtE~~sdCiSydqmKBhxAQ7uT9lda7xt96SS09OYMsIWxLeQUWhns-C~FjJPp1D~IuTrUpAFcVEGVL-BRMmdWbfOJEcWPZ~CBCQSO~VkuN1ebvIOr9JBerFMZSxZtFl8JwcrjCIBxeKPBmfh~xYh16BJm1BBBmN1fp2DKmZ2jBNkAmnUbjQOqWvUcehrykWk5lZbE7bjJMDFH48v3SXwRuDBiHZmSbsTY6zhGY~GkMQHNGxPMMSIAAAA.i2p/bt/announce.php=http://orion.i2p/bt/"
//       , "anonymity", "http://8EoJZIKrWgGuDrxA3nRJs1jsPfiGwmFWL91hBrf0HA7oKhEvAna4Ocx47VLUR9retVEYBAyWFK-eZTPcvhnz9XffBEiJQQ~kFSCqb1fV6IfPiV3HySqi9U5Caf6~hC46fRd~vYnxmaBLICT3N160cxBETqH3v2rdxdJpvYt8q4nMk9LUeVXq7zqCTFLLG5ig1uKgNzBGe58iNcsvTEYlnbYcE930ABmrzj8G1qQSgSwJ6wx3tUQNl1z~4wSOUMan~raZQD60lRK70GISjoX0-D0Po9WmPveN3ES3g72TIET3zc3WPdK2~lgmKGIs8GgNLES1cXTolvbPhdZK1gxddRMbJl6Y6IPFyQ9o4-6Rt3Lp-RMRWZ2TG7j2OMcNSiOmATUhKEFBDfv-~SODDyopGBmfeLw16F4NnYednvn4qP10dyMHcUASU6Zag4mfc2-WivrOqeWhD16fVAh8MoDpIIT~0r9XmwdaVFyLcjbXObabJczxCAW3fodQUnvuSkwzAAAA.i2p/anonymityTracker/announce.php=http://anonymityweb.i2p/anonymityTracker/"
//       , "The freak's tracker", "http://mHKva9x24E5Ygfey2llR1KyQHv5f8hhMpDMwJDg1U-hABpJ2NrQJd6azirdfaR0OKt4jDlmP2o4Qx0H598~AteyD~RJU~xcWYdcOE0dmJ2e9Y8-HY51ie0B1yD9FtIV72ZI-V3TzFDcs6nkdX9b81DwrAwwFzx0EfNvK1GLVWl59Ow85muoRTBA1q8SsZImxdyZ-TApTVlMYIQbdI4iQRwU9OmmtefrCe~ZOf4UBS9-KvNIqUL0XeBSqm0OU1jq-D10Ykg6KfqvuPnBYT1BYHFDQJXW5DdPKwcaQE4MtAdSGmj1epDoaEBUa9btQlFsM2l9Cyn1hzxqNWXELmx8dRlomQLlV4b586dRzW~fLlOPIGC13ntPXogvYvHVyEyptXkv890jC7DZNHyxZd5cyrKC36r9huKvhQAmNABT2Y~pOGwVrb~RpPwT0tBuPZ3lHYhBFYmD8y~AOhhNHKMLzea1rfwTvovBMByDdFps54gMN1mX4MbCGT4w70vIopS9yAAAA.i2p/bytemonsoon/announce.php"
//       , "mastertracker", "http://VzXD~stRKbL3MOmeTn1iaCQ0CFyTmuFHiKYyo0Rd~dFPZFCYH-22rT8JD7i-C2xzYFa4jT5U2aqHzHI-Jre4HL3Ri5hFtZrLk2ax3ji7Qfb6qPnuYkuiF2E2UDmKUOppI8d9Ye7tjdhQVCy0izn55tBaB-U7UWdcvSK2i85sauyw3G0Gfads1Rvy5-CAe2paqyYATcDmGjpUNLoxbfv9KH1KmwRTNH6k1v4PyWYYnhbT39WfKMbBjSxVQRdi19cyJrULSWhjxaQfJHeWx5Z8Ev4bSPByBeQBFl2~4vqy0S5RypINsRSa3MZdbiAAyn5tr5slWR6QdoqY3qBQgBJFZppy-3iWkFqqKgSxCPundF8gdDLC5ddizl~KYcYKl42y9SGFHIukH-TZs8~em0~iahzsqWVRks3zRG~tlBcX2U3M2~OJs~C33-NKhyfZT7-XFBREvb8Szmd~p66jDxrwOnKaku-G6DyoQipJqIz4VHmY9-y5T8RrUcJcM-5lVoMpAAAA.i2p/announce.php=http://tracker.mastertracker.i2p/"
//       , "Galen", "http://5jpwQMI5FT303YwKa5Rd38PYSX04pbIKgTaKQsWbqoWjIfoancFdWCShXHLI5G5ofOb0Xu11vl2VEMyPsg1jUFYSVnu4-VfMe3y4TKTR6DTpetWrnmEK6m2UXh91J5DZJAKlgmO7UdsFlBkQfR2rY853-DfbJtQIFl91tbsmjcA5CGQi4VxMFyIkBzv-pCsuLQiZqOwWasTlnzey8GcDAPG1LDcvfflGV~6F5no9mnuisZPteZKlrv~~TDoXTj74QjByWc4EOYlwqK8sbU9aOvz~s31XzErbPTfwiawiaZ0RUI-IDrKgyvmj0neuFTWgjRGVTH8bz7cBZIc3viy6ioD-eMQOrXaQL0TCWZUelRwHRvgdPiQrxdYQs7ixkajeHzxi-Pq0EMm5Vbh3j3Q9kfUFW3JjFDA-MLB4g6XnjCbM5J1rC0oOBDCIEfhQkszru5cyLjHiZ5yeA0VThgu~c7xKHybv~OMXION7V8pBKOgET7ZgAkw1xgYe3Kkyq5syAAAA.i2p/tr/announce.php=http://galen.i2p/tr/"
       "POSTMAN", "http://tracker2.postman.i2p/announce.php=http://tracker2.postman.i2p/"
       ,"WELTERDE", "http://BGKmlDOoH3RzFbPRfRpZV2FjpVj8~3moFftw5-dZfDf2070TOe8Tf2~DAVeaM6ZRLdmFEt~9wyFL8YMLMoLoiwGEH6IGW6rc45tstN68KsBDWZqkTohV1q9XFgK9JnCwE~Oi89xLBHsLMTHOabowWM6dkC8nI6QqJC2JODqLPIRfOVrDdkjLwtCrsckzLybNdFmgfoqF05UITDyczPsFVaHtpF1sRggOVmdvCM66otyonlzNcJbn59PA-R808vUrCPMGU~O9Wys0i-NoqtIbtWfOKnjCRFMNw5ex4n9m5Sxm9e20UkpKG6qzEuvKZWi8vTLe1NW~CBrj~vG7I3Ok4wybUFflBFOaBabxYJLlx4xTE1zJIVxlsekmAjckB4v-cQwulFeikR4LxPQ6mCQknW2HZ4JQIq6hL9AMabxjOlYnzh7kjOfRGkck8YgeozcyTvcDUcUsOuSTk06L4kdrv8h2Cozjbloi5zl6KTbj5ZTciKCxi73Pn9grICn-HQqEAAAA.i2p/a=http://tracker.welterde.i2p/stats?mode=top5"
       , "CRSTRACK", "http://b4G9sCdtfvccMAXh~SaZrPqVQNyGQbhbYMbw6supq2XGzbjU4NcOmjFI0vxQ8w1L05twmkOvg5QERcX6Mi8NQrWnR0stLExu2LucUXg1aYjnggxIR8TIOGygZVIMV3STKH4UQXD--wz0BUrqaLxPhrm2Eh9Hwc8TdB6Na4ShQUq5Xm8D4elzNUVdpM~RtChEyJWuQvoGAHY3ppX-EJJLkiSr1t77neS4Lc-KofMVmgI9a2tSSpNAagBiNI6Ak9L1T0F9uxeDfEG9bBSQPNMOSUbAoEcNxtt7xOW~cNOAyMyGydwPMnrQ5kIYPY8Pd3XudEko970vE0D6gO19yoBMJpKx6Dh50DGgybLQ9CpRaynh2zPULTHxm8rneOGRcQo8D3mE7FQ92m54~SvfjXjD2TwAVGI~ae~n9HDxt8uxOecAAvjjJ3TD4XM63Q9TmB38RmGNzNLDBQMEmJFpqQU8YeuhnS54IVdUoVQFqui5SfDeLXlSkh4vYoMU66pvBfWbAAAA.i2p/tracker/announce.php=http://crstrack.i2p/tracker/"

    };
    
    /** comma delimited list of name=announceURL=baseURL for the trackers to be displayed */
    public static final String PROP_TRACKERS = "i2psnark.trackers";
    private static Map trackerMap = null;
    /** sorted map of name to announceURL=baseURL */
    public Map getTrackers() { 
        if (trackerMap != null) // only do this once, can't be updated while running
            return trackerMap;
        Map rv = new TreeMap();
        String trackers = _config.getProperty(PROP_TRACKERS);
        if ( (trackers == null) || (trackers.trim().length() <= 0) )
            trackers = _context.getProperty(PROP_TRACKERS);
        if ( (trackers == null) || (trackers.trim().length() <= 0) ) {
            for (int i = 0; i < DEFAULT_TRACKERS.length; i += 2)
                rv.put(DEFAULT_TRACKERS[i], DEFAULT_TRACKERS[i+1]);
        } else {
            StringTokenizer tok = new StringTokenizer(trackers, ",");
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int split = pair.indexOf('=');
                if (split <= 0)
                    continue;
                String name = pair.substring(0, split).trim();
                String url = pair.substring(split+1).trim();
                if ( (name.length() > 0) && (url.length() > 0) )
                    rv.put(name, url);
            }
        }
        
        trackerMap = rv;
        return trackerMap;
    }
    
    private static class TorrentFilenameFilter implements FilenameFilter {
        private static final TorrentFilenameFilter _filter = new TorrentFilenameFilter();
        public static TorrentFilenameFilter instance() { return _filter; }
        public boolean accept(File dir, String name) {
            return (name != null) && (name.endsWith(".torrent"));
        }
    }

    public class SnarkManagerShutdown extends I2PAppThread {
        public void run() {
            Set names = listTorrentFiles();
            for (Iterator iter = names.iterator(); iter.hasNext(); ) {
                Snark snark = getTorrent((String)iter.next());
                if ( (snark != null) && (!snark.stopped) )
                    snark.stopTorrent();
            }
        }
    }
}
