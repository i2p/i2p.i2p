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
public class SnarkManager implements Snark.CompleteListener {
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

    public static final String PROP_AUTO_START = "i2snark.autoStart";
    public static final String DEFAULT_AUTO_START = "false";
    
    private SnarkManager() {
        _snarks = new HashMap();
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(SnarkManager.class);
        _messages = new ArrayList(16);
        loadConfig("i2psnark.config");
        int minutes = getStartupDelayMinutes();
        _messages.add("Starting up torrents in " + minutes + (minutes == 1 ? " minute" : " minutes"));
        I2PThread monitor = new I2PThread(new DirMonitor(), "Snark DirMonitor");
        monitor.setDaemon(true);
        monitor.start();
    }
    
    private static final int MAX_MESSAGES = 5;
    public void addMessage(String message) {
        synchronized (_messages) {
            _messages.add(message);
            while (_messages.size() > MAX_MESSAGES)
                _messages.remove(0);
        }
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
    private int getStartupDelayMinutes() { return 1; }
    public File getDataDir() { 
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
        if (!_config.containsKey(PROP_AUTO_START))
            _config.setProperty(PROP_AUTO_START, DEFAULT_AUTO_START);
        updateConfig();
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
    
    public void updateConfig(String dataDir, boolean autoStart, String seedPct, String eepHost, 
                             String eepPort, String i2cpHost, String i2cpPort, String i2cpOpts) {
        boolean changed = false;
        if (eepHost != null) {
            int port = I2PSnarkUtil.instance().getEepProxyPort();
            try { port = Integer.parseInt(eepPort); } catch (NumberFormatException nfe) {}
            String host = I2PSnarkUtil.instance().getEepProxyHost();
            if ( (eepHost.trim().length() > 0) && (port > 0) &&
                 ((!host.equals(eepHost) || (port != I2PSnarkUtil.instance().getEepProxyPort()) )) ) {
                I2PSnarkUtil.instance().setProxy(eepHost, port);
                changed = true;
                _config.setProperty(PROP_EEP_HOST, eepHost);
                _config.setProperty(PROP_EEP_PORT, eepPort+"");
                addMessage("EepProxy location changed to " + eepHost + ":" + port);
            }
        }
        if (i2cpHost != null) {
            int oldI2CPPort = I2PSnarkUtil.instance().getI2CPPort();
            String oldI2CPHost = I2PSnarkUtil.instance().getI2CPHost();
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
                  (port != I2PSnarkUtil.instance().getI2CPPort()) ||
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
                    if (I2PSnarkUtil.instance().connected()) {
                        I2PSnarkUtil.instance().disconnect();
                        addMessage("Disconnecting old I2CP destination");
                    }
                    Properties p = new Properties();
                    p.putAll(opts);
                    addMessage("I2CP settings changed to " + i2cpHost + ":" + port + " (" + i2cpOpts.trim() + ")");
                    I2PSnarkUtil.instance().setI2CPConfig(i2cpHost, port, p);
                    boolean ok = I2PSnarkUtil.instance().connect();
                    if (!ok) {
                        addMessage("Unable to connect with the new settings, reverting to the old I2CP settings");
                        I2PSnarkUtil.instance().setI2CPConfig(oldI2CPHost, oldI2CPPort, oldOpts);
                        ok = I2PSnarkUtil.instance().connect();
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
        if (changed) {
            saveConfig();
        } else {
            addMessage("Configuration unchanged");
        }
    }
    
    public void saveConfig() {
        try {
            DataHelper.storeProps(_config, new File(_configFile));
        } catch (IOException ioe) {
            addMessage("Unable to save the config to '" + _configFile + "'");
        }
    }
    
    public Properties getConfig() { return _config; }
    
    /** hardcoded for sanity.  perhaps this should be customizable, for people who increase their ulimit, etc. */
    private static final int MAX_FILES_PER_TORRENT = 128;
    
    /** set of filenames that we are dealing with */
    public Set listTorrentFiles() { synchronized (_snarks) { return new HashSet(_snarks.keySet()); } }
    /**
     * Grab the torrent given the (canonical) filename
     */
    public Snark getTorrent(String filename) { synchronized (_snarks) { return (Snark)_snarks.get(filename); } }
    public void addTorrent(String filename) { addTorrent(filename, false); }
    public void addTorrent(String filename, boolean dontAutoStart) {
        if (!I2PSnarkUtil.instance().connected()) {
            addMessage("Connecting to I2P");
            boolean ok = I2PSnarkUtil.instance().connect();
            if (!ok) {
                addMessage("Error connecting to I2P - check your I2CP settings");
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
            if (torrent == null) {
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
                        torrent = new Snark(filename, null, -1, null, null, false, dataDir.getPath());
                        torrent.completeListener = this;
                        _snarks.put(filename, torrent);
                    }
                } catch (IOException ioe) {
                    addMessage("Torrent in " + sfile.getName() + " is invalid: " + ioe.getMessage());
                    if (sfile.exists())
                        sfile.delete();
                    return;
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }
            } else {
                return;
            }
        }
        // ok, snark created, now lets start it up or configure it further
        File f = new File(filename);
        if (!dontAutoStart && shouldAutoStart()) {
            torrent.startTorrent();
            addMessage("Torrent added and started: '" + f.getName() + "'");
        } else {
            addMessage("Torrent added: '" + f.getName() + "'");
        }
    }
    
    private String locked_validateTorrent(MetaInfo info) throws IOException {
        List files = info.getFiles();
        if ( (files != null) && (files.size() > MAX_FILES_PER_TORRENT) ) {
            return "Too many files in " + info.getName() + " (" + files.size() + "), deleting it";
        } else if (info.getPieces() <= 0) {
            return "No pieces in " + info.getName() + "?  deleting it";
        } else if (info.getPieceLength(0) > 10*1024*1024) {
            return "Pieces are too large in " + info.getName() + " (" + info.getPieceLength(0)/1024 + "KB, deleting it";
        } else if (info.getTotalLength() > 10*1024*1024*1024l) {
            System.out.println("torrent info: " + info.toString());
            List lengths = info.getLengths();
            if (lengths != null)
                for (int i = 0; i < lengths.size(); i++)
                    System.out.println("File " + i + " is " + lengths.get(i) + " long");
            
            return "Torrents larger than 10GB are not supported yet (because we're paranoid): " + info.getName() + ", deleting it";
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
                ////I2PSnarkUtil.instance().
            }
            if (!wasStopped)
                addMessage("Torrent stopped: '" + sfile.getName() + "'");
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
            addMessage("Torrent removed: '" + torrentFile.getName() + "'");
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
    
    public void torrentComplete(Snark snark) {
        File f = new File(snark.torrent);
        long len = snark.meta.getTotalLength();
        addMessage("Download complete of " + f.getName() 
                   + (len < 5*1024*1024 ? " (size: " + (len/1024) + "KB)" : " (size: " + (len/(1024*1024l)) + "MB)"));
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
                if (I2PSnarkUtil.instance().connect())
                    addTorrent((String)foundNames.get(i));
                else
                    addMessage("Unable to connect to I2P");
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
       "Postman's tracker", "http://YRgrgTLGnbTq2aZOZDJQ~o6Uk5k6TK-OZtx0St9pb0G-5EGYURZioxqYG8AQt~LgyyI~NCj6aYWpPO-150RcEvsfgXLR~CxkkZcVpgt6pns8SRc3Bi-QSAkXpJtloapRGcQfzTtwllokbdC-aMGpeDOjYLd8b5V9Im8wdCHYy7LRFxhEtGb~RL55DA8aYOgEXcTpr6RPPywbV~Qf3q5UK55el6Kex-6VCxreUnPEe4hmTAbqZNR7Fm0hpCiHKGoToRcygafpFqDw5frLXToYiqs9d4liyVB-BcOb0ihORbo0nS3CLmAwZGvdAP8BZ7cIYE3Z9IU9D1G8JCMxWarfKX1pix~6pIA-sp1gKlL1HhYhPMxwyxvuSqx34o3BqU7vdTYwWiLpGM~zU1~j9rHL7x60pVuYaXcFQDR4-QVy26b6Pt6BlAZoFmHhPcAuWfu-SFhjyZYsqzmEmHeYdAwa~HojSbofg0TMUgESRXMw6YThK1KXWeeJVeztGTz25sL8AAAA.i2p/announce.php"
       , "Orion's tracker", "http://gKik1lMlRmuroXVGTZ~7v4Vez3L3ZSpddrGZBrxVriosCQf7iHu6CIk8t15BKsj~P0JJpxrofeuxtm7SCUAJEr0AIYSYw8XOmp35UfcRPQWyb1LsxUkMT4WqxAT3s1ClIICWlBu5An~q-Mm0VFlrYLIPBWlUFnfPR7jZ9uP5ZMSzTKSMYUWao3ejiykr~mtEmyls6g-ZbgKZawa9II4zjOy-hdxHgP-eXMDseFsrym4Gpxvy~3Fv9TuiSqhpgm~UeTo5YBfxn6~TahKtE~~sdCiSydqmKBhxAQ7uT9lda7xt96SS09OYMsIWxLeQUWhns-C~FjJPp1D~IuTrUpAFcVEGVL-BRMmdWbfOJEcWPZ~CBCQSO~VkuN1ebvIOr9JBerFMZSxZtFl8JwcrjCIBxeKPBmfh~xYh16BJm1BBBmN1fp2DKmZ2jBNkAmnUbjQOqWvUcehrykWk5lZbE7bjJMDFH48v3SXwRuDBiHZmSbsTY6zhGY~GkMQHNGxPMMSIAAAA.i2p/bt"
//       , "The freak's tracker", "http://mHKva9x24E5Ygfey2llR1KyQHv5f8hhMpDMwJDg1U-hABpJ2NrQJd6azirdfaR0OKt4jDlmP2o4Qx0H598~AteyD~RJU~xcWYdcOE0dmJ2e9Y8-HY51ie0B1yD9FtIV72ZI-V3TzFDcs6nkdX9b81DwrAwwFzx0EfNvK1GLVWl59Ow85muoRTBA1q8SsZImxdyZ-TApTVlMYIQbdI4iQRwU9OmmtefrCe~ZOf4UBS9-KvNIqUL0XeBSqm0OU1jq-D10Ykg6KfqvuPnBYT1BYHFDQJXW5DdPKwcaQE4MtAdSGmj1epDoaEBUa9btQlFsM2l9Cyn1hzxqNWXELmx8dRlomQLlV4b586dRzW~fLlOPIGC13ntPXogvYvHVyEyptXkv890jC7DZNHyxZd5cyrKC36r9huKvhQAmNABT2Y~pOGwVrb~RpPwT0tBuPZ3lHYhBFYmD8y~AOhhNHKMLzea1rfwTvovBMByDdFps54gMN1mX4MbCGT4w70vIopS9yAAAA.i2p/bytemonsoon/announce.php"
    };
    
    /** comma delimited list of name=announceURL for the trackers to be displayed */
    public static final String PROP_TRACKERS = "i2psnark.trackers";
    /** unordered map of announceURL to name */
    public Map getTrackers() { 
        HashMap rv = new HashMap();
        String trackers = _config.getProperty(PROP_TRACKERS);
        if ( (trackers == null) || (trackers.trim().length() <= 0) )
            trackers = _context.getProperty(PROP_TRACKERS);
        if ( (trackers == null) || (trackers.trim().length() <= 0) ) {
            for (int i = 0; i < DEFAULT_TRACKERS.length; i += 2)
                rv.put(DEFAULT_TRACKERS[i+1], DEFAULT_TRACKERS[i]);
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
                    rv.put(url, name);
            }
        }
        
        return rv;
    }
    
    private static class TorrentFilenameFilter implements FilenameFilter {
        private static final TorrentFilenameFilter _filter = new TorrentFilenameFilter();
        public static TorrentFilenameFilter instance() { return _filter; }
        public boolean accept(File dir, String name) {
            return (name != null) && (name.endsWith(".torrent"));
        }
    }
}
