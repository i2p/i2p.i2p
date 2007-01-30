package org.klomp.snark.web;

import java.io.*;
import java.util.*;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServlet;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import org.klomp.snark.*;

/**
 *
 */
public class I2PSnarkServlet extends HttpServlet {
    private I2PAppContext _context;
    private Log _log;
    private SnarkManager _manager;
    private static long _nonce;
    
    public static final String PROP_CONFIG_FILE = "i2psnark.configFile";
    
    public void init(ServletConfig cfg) throws ServletException {
        super.init(cfg);
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(I2PSnarkServlet.class);
        _nonce = _context.random().nextLong();
        _manager = SnarkManager.instance();
        String configFile = _context.getProperty(PROP_CONFIG_FILE);
        if ( (configFile == null) || (configFile.trim().length() <= 0) )
            configFile = "i2psnark.config";
        _manager.loadConfig(configFile);
    }
    
    public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html; charset=UTF-8");
        long stats[] = {0,0,0,0};
        
        String nonce = req.getParameter("nonce");
        if ( (nonce != null) && (nonce.equals(String.valueOf(_nonce))) )
            processRequest(req);
        
        PrintWriter out = resp.getWriter();
        out.write(HEADER_BEGIN);
        // we want it to go to the base URI so we don't refresh with some funky action= value
        out.write("<meta http-equiv=\"refresh\" content=\"60;" + req.getRequestURI() + "\">\n");
        out.write(HEADER);
        
        out.write("<table border=\"0\" width=\"100%\">\n");
        out.write("<tr><td width=\"20%\" class=\"snarkTitle\" valign=\"top\" align=\"left\">");
        out.write("I2PSnark<br />\n");
        out.write("<table border=\"0\" width=\"100%\">\n");
        out.write("<tr><td><a href=\"" + req.getRequestURI() + "\" class=\"snarkRefresh\">Refresh</a><br />\n");
        out.write("<td><a href=\"http://forum.i2p/viewforum.php?f=21\" class=\"snarkRefresh\">Forum</a><br />\n");
        out.write("<tr><td><a href=\"http://de-ebook-archiv.i2p/pub/bt/\" class=\"snarkRefresh\">eBook</a><br />\n");
        out.write("<td><a href=\"http://gaytorrents.i2p/\" class=\"snarkRefresh\">GayTorrents</a><br />\n");
        out.write("<tr><td><a href=\"http://nickyb.i2p/bittorrent/\" class=\"snarkRefresh\">NickyB</a><br />\n");
        out.write("<td><a href=\"http://orion.i2p/bt/\" class=\"snarkRefresh\">Orion</a><br />\n");
        out.write("<tr><td><a href=\"http://tracker.postman.i2p/\" class=\"snarkRefresh\">Postman</a><br />\n");
        out.write("<td>&nbsp;\n");
        out.write("</table>\n");
        out.write("</td><td width=\"80%\" class=\"snarkMessages\" valign=\"top\" align=\"left\"><pre>");
        List msgs = _manager.getMessages();
        for (int i = msgs.size()-1; i >= 0; i--) {
            String msg = (String)msgs.get(i);
            out.write(msg + "\n");
        }
        out.write("</pre></td></tr></table>\n");

        List snarks = getSortedSnarks(req);
        String uri = req.getRequestURI();
        out.write(TABLE_HEADER);
        out.write("<th align=\"left\" valign=\"top\">");
        if (I2PSnarkUtil.instance().connected())
            out.write("<a href=\"" + uri + "?action=StopAll&nonce=" + _nonce +
                      "\" title=\"Stop all torrents and the i2p tunnel\">Stop All</a>");
        else
            out.write("&nbsp;");
        out.write("</th></tr></thead>\n");
        for (int i = 0; i < snarks.size(); i++) {
            Snark snark = (Snark)snarks.get(i);
            displaySnark(out, snark, uri, i, stats);
        }
        if (snarks.size() <= 0) {
            out.write(TABLE_EMPTY);
        } else if (snarks.size() > 1) {
            out.write(TABLE_TOTAL);
            out.write("    <th align=\"right\" valign=\"top\">" + formatSize(stats[0]) + "</th>\n" +
                      "    <th align=\"right\" valign=\"top\">" + formatSize(stats[1]) + "</th>\n" +
                      "    <th align=\"right\" valign=\"top\">" + formatSize(stats[2]) + "ps</th>\n" +
                      "    <th align=\"right\" valign=\"top\">" + formatSize(stats[3]) + "ps</th>\n" +
                      "    <th>&nbsp;</th></tr>\n" +
                      "</tfoot>\n");
        }
        
        out.write(TABLE_FOOTER);
        writeAddForm(out, req);
        if (true) // seeding needs to register the torrent first, so we can't start it automatically (boo, hiss)
            writeSeedForm(out, req);
        writeConfigForm(out, req);
        out.write(FOOTER);
    }
    
    /**
     * Do what they ask, adding messages to _manager.addMessage as necessary
     */
    private void processRequest(HttpServletRequest req) {
        String action = req.getParameter("action");
        if (action == null) {
            // noop
        } else if ("Add torrent".equals(action)) {
            String newFile = req.getParameter("newFile");
            String newURL = req.getParameter("newURL");
            File f = null;
            if ( (newFile != null) && (newFile.trim().length() > 0) )
                f = new File(newFile.trim());
            if ( (f != null) && (!f.exists()) ) {
                _manager.addMessage("Torrent file " + newFile +" does not exist");
            }
            if ( (f != null) && (f.exists()) ) {
                File local = new File(_manager.getDataDir(), f.getName());
                String canonical = null;
                try {
                    canonical = local.getCanonicalPath();
                    
                    if (local.exists()) {
                        if (_manager.getTorrent(canonical) != null)
                            _manager.addMessage("Torrent already running: " + newFile);
                        else
                            _manager.addMessage("Torrent already in the queue: " + newFile);
                    } else {
                        boolean ok = FileUtil.copy(f.getAbsolutePath(), local.getAbsolutePath(), true);
                        if (ok) {
                            _manager.addMessage("Copying torrent to " + local.getAbsolutePath());
                            _manager.addTorrent(canonical);
                        } else {
                            _manager.addMessage("Unable to copy the torrent to " + local.getAbsolutePath() + " from " + f.getAbsolutePath());
                        }
                    }
                } catch (IOException ioe) {
                    _log.warn("hrm: " + local, ioe);
                }
            } else if ( (newURL != null) && (newURL.trim().length() > "http://.i2p/".length()) ) {
                _manager.addMessage("Fetching " + newURL);
                I2PThread fetch = new I2PThread(new FetchAndAdd(_manager, newURL), "Fetch and add");
                fetch.start();
            } else {
                // no file or URL specified
            }
        } else if ("Stop".equals(action)) {
            String torrent = req.getParameter("torrent");
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (Iterator iter = _manager.listTorrentFiles().iterator(); iter.hasNext(); ) {
                        String name = (String)iter.next();
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.meta.getInfoHash())) ) {
                            _manager.stopTorrent(name, false);
                            break;
                        }
                    }
                }
            }
        } else if ("Start".equals(action)) {
            String torrent = req.getParameter("torrent");
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (Iterator iter = _manager.listTorrentFiles().iterator(); iter.hasNext(); ) {
                        String name = (String)iter.next();
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.meta.getInfoHash())) ) {
                            snark.startTorrent();
                            _manager.addMessage("Starting up torrent " + name);
                            break;
                        }
                    }
                }
            }
        } else if ("Remove".equals(action)) {
            String torrent = req.getParameter("torrent");
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (Iterator iter = _manager.listTorrentFiles().iterator(); iter.hasNext(); ) {
                        String name = (String)iter.next();
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.meta.getInfoHash())) ) {
                            _manager.stopTorrent(name, true);
                            // should we delete the torrent file?
                            // yeah, need to, otherwise it'll get autoadded again (at the moment
                            File f = new File(name);
                            f.delete();
                            _manager.addMessage("Torrent file deleted: " + f.getAbsolutePath());
                            break;
                        }
                    }
                }
            }
        } else if ("Delete".equals(action)) {
            String torrent = req.getParameter("torrent");
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (Iterator iter = _manager.listTorrentFiles().iterator(); iter.hasNext(); ) {
                        String name = (String)iter.next();
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.meta.getInfoHash())) ) {
                            _manager.stopTorrent(name, true);
                            File f = new File(name);
                            f.delete();
                            _manager.addMessage("Torrent file deleted: " + f.getAbsolutePath());
                            List files = snark.meta.getFiles();
                            String dataFile = snark.meta.getName();
                            for (int i = 0; files != null && i < files.size(); i++) {
                                // multifile torrents have the getFiles() return lists of lists of filenames, but
                                // each of those lists just contain a single file afaict...
                                File df = new File(_manager.getDataDir(), files.get(i).toString());
                                boolean deleted = FileUtil.rmdir(df, false);
                                if (deleted)
                                    _manager.addMessage("Data dir deleted: " + df.getAbsolutePath());
                                else
                                    _manager.addMessage("Data dir could not be deleted: " + df.getAbsolutePath());
                            }
                            if (dataFile != null) {
                                f = new File(_manager.getDataDir(), dataFile);
                                boolean deleted = f.delete();
                                if (deleted)
                                    _manager.addMessage("Data file deleted: " + f.getAbsolutePath());
                                else
                                    _manager.addMessage("Data file could not be deleted: " + f.getAbsolutePath());
                            }
                            break;
                        }
                    }
                }
            }
        } else if ("Save configuration".equals(action)) {
            String dataDir = req.getParameter("dataDir");
            boolean autoStart = req.getParameter("autoStart") != null;
            String seedPct = req.getParameter("seedPct");
            String eepHost = req.getParameter("eepHost");
            String eepPort = req.getParameter("eepPort");
            String i2cpHost = req.getParameter("i2cpHost");
            String i2cpPort = req.getParameter("i2cpPort");
            String i2cpOpts = req.getParameter("i2cpOpts");
            _manager.updateConfig(dataDir, autoStart, seedPct, eepHost, eepPort, i2cpHost, i2cpPort, i2cpOpts);
        } else if ("Create torrent".equals(action)) {
            String baseData = req.getParameter("baseFile");
            if (baseData != null) {
                File baseFile = new File(_manager.getDataDir(), baseData);
                String announceURL = req.getParameter("announceURL");
                String announceURLOther = req.getParameter("announceURLOther");
                if ( (announceURLOther != null) && (announceURLOther.trim().length() > "http://.i2p/announce".length()) )
                    announceURL = announceURLOther;

                if (announceURL == null || announceURL.length() <= 0)
                    _manager.addMessage("Error creating torrent - you must select a tracker");
                else if (baseFile.exists()) {
                    try {
                        Storage s = new Storage(baseFile, announceURL, null);
                        s.create();
                        MetaInfo info = s.getMetaInfo();
                        File torrentFile = new File(baseFile.getParent(), baseFile.getName() + ".torrent");
                        if (torrentFile.exists())
                            throw new IOException("Cannot overwrite an existing .torrent file: " + torrentFile.getPath());
                        FileOutputStream out = new FileOutputStream(torrentFile);
                        out.write(info.getTorrentData());
                        out.close();
                        _manager.addMessage("Torrent created for " + baseFile.getName() + ": " + torrentFile.getAbsolutePath());
                        // now fire it up, but don't automatically seed it
                        _manager.addTorrent(torrentFile.getCanonicalPath(), false);
                        _manager.addMessage("Many I2P trackers require you to register new torrents before seeding - please do so before starting " + baseFile.getName());
                    } catch (IOException ioe) {
                        _manager.addMessage("Error creating a torrent for " + baseFile.getAbsolutePath() + ": " + ioe.getMessage());
                    }
                } else {
                    _manager.addMessage("Cannot create a torrent for the nonexistent data: " + baseFile.getAbsolutePath());
                }
            }
        } else if ("StopAll".equals(action)) {
            _manager.addMessage("Stopping all torrents and closing the I2P tunnel");
            List snarks = getSortedSnarks(req);
            for (int i = 0; i < snarks.size(); i++) {
                Snark snark = (Snark)snarks.get(i);
                if (!snark.stopped)
                    _manager.stopTorrent(snark.torrent, false);
            }
            if (I2PSnarkUtil.instance().connected()) {
                I2PSnarkUtil.instance().disconnect();
                _manager.addMessage("I2P tunnel closed");
            }
        }
    }
    
    private List getSortedSnarks(HttpServletRequest req) {
        Set files = _manager.listTorrentFiles();
        TreeSet fileNames = new TreeSet(files); // sorts it alphabetically
        ArrayList rv = new ArrayList(fileNames.size());
        for (Iterator iter = fileNames.iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            Snark snark = _manager.getTorrent(name);
            if (snark != null)
                rv.add(snark);
        }
        return rv;
    }

    private static final int MAX_DISPLAYED_FILENAME_LENGTH = 60;
    private static final int MAX_DISPLAYED_ERROR_LENGTH = 30;
    private void displaySnark(PrintWriter out, Snark snark, String uri, int row, long stats[]) throws IOException {
        String filename = snark.torrent;
        File f = new File(filename);
        filename = f.getName(); // the torrent may be the canonical name, so lets just grab the local name
        int i = filename.lastIndexOf(".torrent");
        if (i > 0)
            filename = filename.substring(0, i);
        if (filename.length() > MAX_DISPLAYED_FILENAME_LENGTH)
            filename = filename.substring(0, MAX_DISPLAYED_FILENAME_LENGTH) + "...";
        long total = snark.meta.getTotalLength();
        // Early typecast, avoid possibly overflowing a temp integer
        long remaining = (long) snark.storage.needed() * (long) snark.meta.getPieceLength(0); 
        if (remaining > total)
            remaining = total;
        long downBps = 0;
        long upBps = 0;
        if (snark.coordinator != null) {
            downBps = snark.coordinator.getDownloadRate();
            upBps = snark.coordinator.getUploadRate();
        }
        long remainingSeconds;
        if (downBps > 0)
            remainingSeconds = remaining / downBps;
        else
            remainingSeconds = -1;
        boolean isRunning = !snark.stopped;
        long uploaded = 0;
        if (snark.coordinator != null) {
            uploaded = snark.coordinator.getUploaded();
            stats[0] += snark.coordinator.getDownloaded();
        }
        stats[1] += uploaded;
        if (isRunning) {
            stats[2] += downBps;
            stats[3] += upBps;
        }
        
        boolean isValid = snark.meta != null;
        boolean singleFile = snark.meta.getFiles() == null;
        
        String err = null;
        int curPeers = 0;
        int knownPeers = 0;
        if (snark.coordinator != null) {
            err = snark.coordinator.trackerProblems;
            curPeers = snark.coordinator.getPeerCount();
            knownPeers = snark.coordinator.trackerSeenPeers;
        }
        
        String statusString = "Unknown";
        if (err != null) {
            if (isRunning)
                statusString = "TrackerErr (" + curPeers + "/" + knownPeers + " peers)";
            else {
                if (err.length() > MAX_DISPLAYED_ERROR_LENGTH)
                    err = err.substring(0, MAX_DISPLAYED_ERROR_LENGTH) + "...";
                statusString = "TrackerErr (" + err + ")";
            }
        } else if (remaining <= 0) {
            if (isRunning)
                statusString = "Seeding (" + curPeers + "/" + knownPeers + " peers)";
            else
                statusString = "Complete";
        } else {
            if (isRunning && curPeers > 0 && downBps > 0)
                statusString = "OK (" + curPeers + "/" + knownPeers + " peers)";
            else if (isRunning && curPeers > 0)
                statusString = "Stalled (" + curPeers + "/" + knownPeers + " peers)";
            else if (isRunning)
                statusString = "No Peers (0/" + knownPeers + ")";
            else
                statusString = "Stopped";
        }
        
        String rowClass = (row % 2 == 0 ? "snarkTorrentEven" : "snarkTorrentOdd");
        out.write("<tr class=\"" + rowClass + "\">");
        out.write("<td valign=\"top\" align=\"left\" class=\"snarkTorrentStatus " + rowClass + "\">");
        out.write(statusString + "</td>\n\t");
        out.write("<td valign=\"top\" align=\"left\" class=\"snarkTorrentName " + rowClass + "\">");
        
        if (remaining == 0)
            out.write("<a href=\"file:///" + _manager.getDataDir().getAbsolutePath() + File.separatorChar + snark.meta.getName() 
                      + "\" title=\"Download the completed file\">");
        out.write(filename);
        if (remaining == 0)
            out.write("</a>");
        out.write("</td>\n\t");
        
        out.write("<td valign=\"top\" align=\"right\" class=\"snarkTorrentETA " + rowClass + "\">");
        if(isRunning && remainingSeconds > 0)
            out.write(DataHelper.formatDuration(remainingSeconds*1000)); // (eta 6h)
        out.write("</td>\n\t");
        out.write("<td valign=\"top\" align=\"right\" class=\"snarkTorrentDownloaded " + rowClass + "\">");
        if (remaining > 0)
            out.write(formatSize(total-remaining) + "/" + formatSize(total)); // 18MB/3GB
        else
            out.write(formatSize(total)); // 3GB
        out.write("</td>\n\t");
        out.write("<td valign=\"top\" align=\"right\" class=\"snarkTorrentUploaded " + rowClass 
                  + "\">" + formatSize(uploaded) + "</td>\n\t");
        out.write("<td valign=\"top\" align=\"right\" class=\"snarkTorrentRate\">");
        if(isRunning && remaining > 0)
            out.write(formatSize(downBps) + "ps");
        out.write("</td>\n\t");
        out.write("<td valign=\"top\" align=\"right\" class=\"snarkTorrentRate\">");
        if(isRunning)
            out.write(formatSize(upBps) + "ps");
        out.write("</td>\n\t");
        out.write("<td valign=\"top\" align=\"left\" class=\"snarkTorrentAction " + rowClass + "\">");
        if (isRunning) {
            out.write("<a href=\"" + uri + "?action=Stop&nonce=" + _nonce 
                      + "&torrent=" + Base64.encode(snark.meta.getInfoHash())
                      + "\" title=\"Stop the torrent\">Stop</a>");
        } else {
            if (isValid)
                out.write("<a href=\"" + uri + "?action=Start&nonce=" + _nonce 
                          + "&torrent=" + Base64.encode(snark.meta.getInfoHash())
                          + "\" title=\"Start the torrent\">Start</a> ");
            out.write("<a href=\"" + uri + "?action=Remove&nonce=" + _nonce 
                      + "&torrent=" + Base64.encode(snark.meta.getInfoHash())
                      + "\" title=\"Remove the torrent from the active list, deleting the .torrent file\">Remove</a><br />");
            out.write("<a href=\"" + uri + "?action=Delete&nonce=" + _nonce 
                      + "&torrent=" + Base64.encode(snark.meta.getInfoHash())
                      + "\" title=\"Delete the .torrent file and the associated data file(s)\">Delete</a> ");
        }
        out.write("</td>\n</tr>\n");
    }
    
    private void writeAddForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String uri = req.getRequestURI();
        String newURL = req.getParameter("newURL");
        if ( (newURL == null) || (newURL.trim().length() <= 0) ) newURL = "http://";
        String newFile = req.getParameter("newFile");
        if ( (newFile == null) || (newFile.trim().length() <= 0) ) newFile = "";
        
        out.write("<span class=\"snarkNewTorrent\">\n");
        // *not* enctype="multipart/form-data", so that the input type=file sends the filename, not the file
        out.write("<form action=\"" + uri + "\" method=\"POST\">\n");
        out.write("<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" />\n");
        out.write("<span class=\"snarkConfigTitle\">Add Torrent:</span><br />\n");
        out.write("From URL&nbsp;: <input type=\"text\" name=\"newURL\" size=\"80\" value=\"" + newURL + "\" /> \n");
        // not supporting from file at the moment, since the file name passed isn't always absolute (so it may not resolve)
        //out.write("From file: <input type=\"file\" name=\"newFile\" size=\"50\" value=\"" + newFile + "\" /><br />\n");
        out.write("<input type=\"submit\" value=\"Add torrent\" name=\"action\" /><br />\n");
        out.write("<span class=\"snarkAddInfo\">Alternately, you can copy .torrent files to " + _manager.getDataDir().getAbsolutePath() + "<br />\n");
        out.write("Removing that .torrent file will cause the torrent to stop.<br /></span>\n");
        out.write("</form>\n</span>\n");  
    }
    
    private void writeSeedForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String uri = req.getRequestURI();
        String baseFile = req.getParameter("baseFile");
        if (baseFile == null)
            baseFile = "";
        
        out.write("<span class=\"snarkNewTorrent\"><hr />\n");
        // *not* enctype="multipart/form-data", so that the input type=file sends the filename, not the file
        out.write("<form action=\"" + uri + "\" method=\"POST\">\n");
        out.write("<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" />\n");
        out.write("<span class=\"snarkConfigTitle\">Create Torrent:</span><br />\n");
        //out.write("From file: <input type=\"file\" name=\"newFile\" size=\"50\" value=\"" + newFile + "\" /><br />\n");
        out.write("Data to seed: " + _manager.getDataDir().getAbsolutePath() + File.separatorChar 
                  + "<input type=\"text\" name=\"baseFile\" size=\"20\" value=\"" + baseFile 
                  + "\" title=\"File to seed (must be within the specified path)\" /><br />\n");
        out.write("Tracker: <select name=\"announceURL\"><option value=\"\">Select a tracker</option>\n");
        Map trackers = sort(_manager.getTrackers());
        for (Iterator iter = trackers.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            String announceURL = (String)trackers.get(name);
            // we inject whitespace in sort(...) to guarantee uniqueness, but we can strip it off here
            out.write("\t<option value=\"" + announceURL + "\">" + name.trim() + "</option>\n");
        }
        out.write("</select>\n");
        out.write("or <input type=\"text\" name=\"announceURLOther\" size=\"50\" value=\"http://\" " +
                  "title=\"Custom tracker URL\" /> ");
        out.write("<input type=\"submit\" value=\"Create torrent\" name=\"action\" />\n");
        out.write("</form>\n</span>\n");        
    }
    
    private Map sort(Map trackers) {
        TreeMap rv = new TreeMap();
        for (Iterator iter = trackers.keySet().iterator(); iter.hasNext(); ) {
            String url = (String)iter.next();
            String name = (String)trackers.get(url);
            while (rv.containsKey(name))
                name = name + " ";
            rv.put(name, url);
        }
        return rv;
    }
    
    private void writeConfigForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String uri = req.getRequestURI();
        String dataDir = _manager.getDataDir().getAbsolutePath();
        boolean autoStart = _manager.shouldAutoStart();
        int seedPct = 0;
       
        out.write("<form action=\"" + uri + "\" method=\"POST\">\n");
        out.write("<span class=\"snarkConfig\"><hr />\n");
        out.write("<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" />\n");
        out.write("<span class=\"snarkConfigTitle\">Configuration:</span><br />\n");
        out.write("Data directory: <input type=\"text\" size=\"40\" name=\"dataDir\" value=\"" + dataDir + "\" ");
        out.write("title=\"Directory to store torrents and data\" disabled=\"true\" /><br />\n");
        out.write("Auto start: <input type=\"checkbox\" name=\"autoStart\" value=\"true\" " 
                  + (autoStart ? "checked " : "") 
                  + "title=\"If true, automatically start torrents that are added\" />");
        //Auto add: <input type="checkbox" name="autoAdd" value="true" title="If true, automatically add torrents that are found in the data directory" />
        //Auto stop: <input type="checkbox" name="autoStop" value="true" title="If true, automatically stop torrents that are removed from the data directory" />
        //out.write("<br />\n");
        out.write("Seed percentage: <select name=\"seedPct\" disabled=\"true\" >\n\t");
        if (seedPct <= 0)
            out.write("<option value=\"0\" selected=\"true\">Unlimited</option>\n\t");
        else
            out.write("<option value=\"0\">Unlimited</option>\n\t");
        if (seedPct == 100)
            out.write("<option value=\"100\" selected=\"true\">100%</option>\n\t");
        else
            out.write("<option value=\"100\">100%</option>\n\t");
        if (seedPct == 150)
            out.write("<option value=\"150\" selected=\"true\">150%</option>\n\t");
        else
            out.write("<option value=\"150\">150%</option>\n\t");
        out.write("</select><br />\n");
        
        //out.write("<hr />\n");
        out.write("EepProxy host: <input type=\"text\" name=\"eepHost\" value=\""
                  + I2PSnarkUtil.instance().getEepProxyHost() + "\" size=\"15\" /> ");
        out.write("port: <input type=\"text\" name=\"eepPort\" value=\""
                  + I2PSnarkUtil.instance().getEepProxyPort() + "\" size=\"5\" /><br />\n");
        out.write("I2CP host: <input type=\"text\" name=\"i2cpHost\" value=\"" 
                  + I2PSnarkUtil.instance().getI2CPHost() + "\" size=\"15\" /> ");
        out.write("port: <input type=\"text\" name=\"i2cpPort\" value=\"" +
                  + I2PSnarkUtil.instance().getI2CPPort() + "\" size=\"5\" /> <br />\n");
        StringBuffer opts = new StringBuffer(64);
        Map options = new TreeMap(I2PSnarkUtil.instance().getI2CPOptions());
        for (Iterator iter = options.keySet().iterator(); iter.hasNext(); ) {
            String key = (String)iter.next();
            String val = (String)options.get(key);
            opts.append(key).append('=').append(val).append(' ');
        }
        out.write("I2CP opts: <input type=\"text\" name=\"i2cpOpts\" size=\"80\" value=\""
                  + opts.toString() + "\" /><br />\n");
        out.write("<input type=\"submit\" value=\"Save configuration\" name=\"action\" />\n");
        out.write("</span>\n");
        out.write("</form>\n");
    }
    
    private String formatSize(long bytes) {
        if (bytes < 5*1024)
            return bytes + "B";
        else if (bytes < 5*1024*1024)
            return (bytes/1024) + "KB";
        else if (bytes < 5*1024*1024*1024l)
            return (bytes/(1024*1024)) + "MB";
        else
            return (bytes/(1024*1024*1024)) + "GB";
    }
    
    private static final String HEADER_BEGIN = "<html>\n" +
                                               "<head>\n" +
                                               "<title>I2PSnark - anonymous bittorrent</title>\n";
                                         
    private static final String HEADER = "<style>\n" +
                                         "body {\n" +
                                         "	background-color: #C7CFB4;\n" +
                                         "}\n" +
                                         ".snarkTitle {\n" +
                                         "	text-align: left;\n" +
                                         "	float: left;\n" +
                                         "	margin: 0px 0px 5px 5px;\n" +
                                         "	display: inline;\n" +
                                         "	font-size: 16pt;\n" +
                                         "	font-weight: bold;\n" +
                                         "}\n" +
                                         ".snarkRefresh {\n" +
                                         "                  font-size: 10pt;\n" +
                                         "}\n" +
                                         ".snarkMessages {\n" +
                                         "	border: none;\n" +
                                         "                  background-color: #CECFC6;\n" +
                                         "                  font-family: monospace;\n" +
                                         "                  font-size: 10pt;\n" +
                                         "                  font-weight: 100;\n" +
                                         "                  width: 100%;\n" +
                                         "                  text-align: left;\n" +
                                         "                  margin: 0px 0px 0px 0px;\n" +
                                         "                  border: 0px;\n" +
                                         "                  padding: 5px;\n" +
                                         "                  border-width: 0px;\n" +
                                         "                  border-spacing: 0px;\n" +
                                         "}\n" +
                                         "table {\n" +
                                         "	margin: 0px 0px 0px 0px;\n" +
                                         "	border: 0px;\n" +
                                         "	padding: 0px;\n" +
                                         "	border-width: 0px;\n" +
                                         "	border-spacing: 0px;\n" +
                                         "}\n" +
                                         "th {\n" +
                                         "	background-color: #C7D5D5;\n" +
                                         "	padding: 0px 7px 0px 3px;\n" +
                                         "}\n" +
                                         "td {\n" +
                                         "	padding: 0px 7px 0px 3px;\n" +
                                         "}\n" +
                                         ".snarkTorrentEven {\n" +
                                         "	background-color: #E7E7E7;\n" +
                                         "}\n" +
                                         ".snarkTorrentOdd {\n" +
                                         "	background-color: #DDDDCC;\n" +
                                         "}\n" +
                                         ".snarkNewTorrent {\n" +
                                         "	font-size: 10pt;\n" +
                                         "}\n" +
                                         ".snarkAddInfo {\n" +
                                         "	font-size: 10pt;\n" +
                                         "}\n" +
                                         ".snarkConfigTitle {\n" +
                                         "	font-size: 12pt;\n" +
                                         "                  font-weight: bold;\n" +
                                         "}\n" +
                                         ".snarkConfig {\n" +
                                         "                  font-size: 10pt;\n" +
                                         "}\n" +
                                         "</style>\n" +
                                         "</head>\n" +
                                         "<body>\n";


    private static final String TABLE_HEADER = "<table border=\"0\" class=\"snarkTorrents\" width=\"100%\" cellpadding=\"0 10px\">\n" +
                                               "<thead>\n" +
                                               "<tr><th align=\"left\" valign=\"top\">Status</th>\n" +
                                               "    <th align=\"left\" valign=\"top\">Torrent</th>\n" +
                                               "    <th align=\"right\" valign=\"top\">ETA</th>\n" +
                                               "    <th align=\"right\" valign=\"top\">Downloaded</th>\n" +
                                               "    <th align=\"right\" valign=\"top\">Uploaded</th>\n" +
                                               "    <th align=\"right\" valign=\"top\">Down Rate</th>\n" +
                                               "    <th align=\"right\" valign=\"top\">Up Rate</th>\n";
    
    private static final String TABLE_TOTAL =  "<tfoot>\n" +
                                               "<tr><th align=\"left\" valign=\"top\">Totals</th>\n" +
                                               "    <th>&nbsp;</th>\n" +
                                               "    <th>&nbsp;</th>\n";
    
   private static final String TABLE_EMPTY  = "<tr class=\"snarkTorrentEven\">" +
                                              "<td class=\"snarkTorrentEven\" align=\"left\"" +
                                              "    valign=\"top\" colspan=\"8\">No torrents</td></tr>\n";

    private static final String TABLE_FOOTER = "</table>\n";
    
    private static final String FOOTER = "</body></html>";
}


class FetchAndAdd implements Runnable {
    private SnarkManager _manager;
    private String _url;
    public FetchAndAdd(SnarkManager mgr, String url) {
        _manager = mgr;
        _url = url;
    }
    public void run() {
        _url = _url.trim();
        File file = I2PSnarkUtil.instance().get(_url, false);
        try {
            if ( (file != null) && (file.exists()) && (file.length() > 0) ) {
                _manager.addMessage("Torrent fetched from " + _url);
                FileInputStream in = null;
                try {
                    in = new FileInputStream(file);
                    MetaInfo info = new MetaInfo(in);
                    String name = info.getName();
                    name = name.replace('/', '_');
                    name = name.replace('\\', '_');
                    name = name.replace('&', '+');
                    name = name.replace('\'', '_');
                    name = name.replace('"', '_');
                    name = name.replace('`', '_');
                    name = name + ".torrent";
                    File torrentFile = new File(_manager.getDataDir(), name);

                    String canonical = torrentFile.getCanonicalPath();

                    if (torrentFile.exists()) {
                        if (_manager.getTorrent(canonical) != null)
                            _manager.addMessage("Torrent already running: " + name);
                        else
                            _manager.addMessage("Torrent already in the queue: " + name);
                    } else {
                        FileUtil.copy(file.getAbsolutePath(), canonical, true);
                        _manager.addTorrent(canonical);
                    }
                } catch (IOException ioe) {
                    _manager.addMessage("Torrent at " + _url + " was not valid: " + ioe.getMessage());
                } finally {
                    try { in.close(); } catch (IOException ioe) {}
                }
            } else {
                _manager.addMessage("Torrent was not retrieved from " + _url);
            }
        } finally {
            if (file != null) file.delete();
        }
    }
}
