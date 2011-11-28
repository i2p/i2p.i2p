package org.klomp.snark.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.Collator;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.MetaInfo;
import org.klomp.snark.Peer;
import org.klomp.snark.Snark;
import org.klomp.snark.SnarkManager;
import org.klomp.snark.Storage;
import org.klomp.snark.TrackerClient;

import org.mortbay.http.HttpResponse;
import org.mortbay.jetty.servlet.Default;
import org.mortbay.util.Resource;
import org.mortbay.util.URI;

/**
 *  We extend Default instead of HTTPServlet so we can handle
 *  i2psnark/ file requests with http:// instead of the flaky and
 *  often-blocked-by-the-browser file://
 */
public class I2PSnarkServlet extends Default {
    private I2PAppContext _context;
    private Log _log;
    private SnarkManager _manager;
    private static long _nonce;
    private Resource _resourceBase;
    private String _themePath;
    private String _imgPath;
    private String _lastAnnounceURL = "";
    
    public static final String PROP_CONFIG_FILE = "i2psnark.configFile";
    /** BEP 9 */
    private static final String MAGNET = "magnet:";
    private static final String MAGNET_FULL = MAGNET + "?xt=urn:btih:";
    /** http://sponge.i2p/files/maggotspec.txt */
    private static final String MAGGOT = "maggot://";
 
    @Override
    public void init(ServletConfig cfg) throws ServletException {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(I2PSnarkServlet.class);
        _nonce = _context.random().nextLong();
        _manager = SnarkManager.instance();
        String configFile = _context.getProperty(PROP_CONFIG_FILE);
        if ( (configFile == null) || (configFile.trim().length() <= 0) )
            configFile = "i2psnark.config";
        _manager.loadConfig(configFile);
        _manager.start();
        try {
            _resourceBase = Resource.newResource(_manager.getDataDir().getAbsolutePath());
        } catch (IOException ioe) {}
        super.init(cfg);
    }
    
    @Override
    public void destroy() {
        _manager.stop();
        super.destroy();
    }

    /**
     *  We override this instead of passing a resource base to super(), because
     *  if a resource base is set, super.getResource() always uses that base,
     *  and we can't get any resources (like icons) out of the .war
     */
    @Override
    protected Resource getResource(String pathInContext) throws IOException
    {
        if (pathInContext == null || pathInContext.equals("/") || pathInContext.equals("/index.jsp") ||
            pathInContext.equals("/index.html") || pathInContext.startsWith("/_icons/"))
            return super.getResource(pathInContext);
        // files in the i2psnark/ directory
        return _resourceBase.addPath(pathInContext);
    }

    /**
     *  Tell the browser to cache the icons
     *  @since 0.8.3
     */
    @Override
    public void handleGet(HttpServletRequest request, HttpServletResponse response, String pathInContext, Resource resource, boolean endsWithSlash) throws ServletException, IOException {
        if (resource.getName().startsWith("jar:file:"))
            response.setHeader("Cache-Control", "max-age=86400");  // cache for a day
        super.handleGet(request, response, pathInContext, resource, endsWithSlash);
    }

    /**
     * Some parts modified from:
     * <pre>
      // ========================================================================
      // $Id: Default.java,v 1.51 2006/10/08 14:13:18 gregwilkins Exp $
      // Copyright 199-2004 Mort Bay Consulting Pty. Ltd.
      // ------------------------------------------------------------------------
      // Licensed under the Apache License, Version 2.0 (the "License");
      // you may not use this file except in compliance with the License.
      // You may obtain a copy of the License at 
      // http://www.apache.org/licenses/LICENSE-2.0
      // Unless required by applicable law or agreed to in writing, software
      // distributed under the License is distributed on an "AS IS" BASIS,
      // WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      // See the License for the specific language governing permissions and
      // limitations under the License.
      // ========================================================================
     * </pre>
     *
     */
    @Override
    public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // since we are not overriding handle*(), do this here
        String method = req.getMethod();
        if (!(method.equals("GET") || method.equals("HEAD") || method.equals("POST"))) {
            resp.sendError(HttpResponse.__405_Method_Not_Allowed);
            return;
        }
        _themePath = "/themes/snark/" + _manager.getTheme() + '/';
        _imgPath = _themePath + "images/";
        // this is the part after /i2psnark
        String path = req.getServletPath();
        boolean isConfigure = "/configure".equals(path);
        // index.jsp doesn't work, it is grabbed by the war handler before here
        if (!(path == null || path.equals("/") || path.equals("/index.jsp") || path.equals("/index.html") || path.equals("/_post") || isConfigure)) {
            if (path.endsWith("/")) {
                // bypass the horrid Resource.getListHTML()
                String pathInfo = req.getPathInfo();
                String pathInContext = URI.addPaths(path, pathInfo);
                req.setCharacterEncoding("UTF-8");
                resp.setCharacterEncoding("UTF-8");
                resp.setContentType("text/html; charset=UTF-8");
                Resource resource = getResource(pathInContext);
                if (resource == null || (!resource.exists())) {
                    resp.sendError(HttpResponse.__404_Not_Found);
                } else {
                    String base = URI.addPaths(req.getRequestURI(), "/");
                    String listing = getListHTML(resource, base, true, method.equals("POST") ? req.getParameterMap() : null);
                    if (listing != null)
                        resp.getWriter().write(listing);
                    else // shouldn't happen
                        resp.sendError(HttpResponse.__404_Not_Found);
                }
            } else {
                super.service(req, resp);
            }
            return;
        }

        req.setCharacterEncoding("UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html; charset=UTF-8");
        
        String nonce = req.getParameter("nonce");
        if (nonce != null) {
            if (nonce.equals(String.valueOf(_nonce)))
                processRequest(req);
            else  // nonce is constant, shouldn't happen
                _manager.addMessage("Please retry form submission (bad nonce)");
        }
        
        String peerParam = req.getParameter("p");
        String peerString;
        if (peerParam == null || !_manager.util().connected()) {
            peerString = "";
        } else {
            peerString = "?p=" + peerParam;
        }

        PrintWriter out = resp.getWriter();
        out.write(DOCTYPE + "<html>\n" +
                  "<head><link rel=\"shortcut icon\" href=\"" + _themePath + "favicon.ico\">\n" +
                  "<title>");
        out.write(_("I2PSnark - Anonymous BitTorrent Client"));
        if ("2".equals(peerParam))
            out.write(" | Debug Mode");
        out.write("</title>\n");
                                         
        // we want it to go to the base URI so we don't refresh with some funky action= value
        if (!isConfigure) {
            int delay = _manager.getRefreshDelaySeconds();
            if (delay > 0)
                out.write("<meta http-equiv=\"refresh\" content=\"" + delay + ";/i2psnark/" + peerString + "\">\n");
        }
        out.write(HEADER_A + _themePath + HEADER_B);
        out.write("</head><body>");
        out.write("<center>");
        if (isConfigure) {
            out.write("<div class=\"snarknavbar\"><a href=\"/i2psnark/\" title=\"");
            out.write(_("Torrents"));
            out.write("\" class=\"snarkRefresh\">");
            out.write("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "arrow_refresh.png\">&nbsp;&nbsp;");
            out.write(_("I2PSnark"));
            out.write("</a>");
        } else {
            out.write("<div class=\"snarknavbar\"><a href=\"/i2psnark/" + peerString + "\" title=\"");
            out.write(_("Refresh page"));
            out.write("\" class=\"snarkRefresh\">");
            out.write("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "arrow_refresh.png\">&nbsp;&nbsp;");
            out.write(_("I2PSnark"));
            out.write("</a> <a href=\"http://forum.i2p/viewforum.php?f=21\" class=\"snarkRefresh\" target=\"_blank\">");
            out.write(_("Forum"));
            out.write("</a>\n");

            Map trackers = _manager.getTrackers();
            for (Iterator iter = trackers.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry entry = (Map.Entry)iter.next();
                String name = (String)entry.getKey();
                String baseURL = (String)entry.getValue();
                int e = baseURL.indexOf('=');
                if (e < 0)
                    continue;
                baseURL = baseURL.substring(e + 1);
                out.write(" <a href=\"" + baseURL + "\" class=\"snarkRefresh\" target=\"_blank\">" + name + "</a>");
            }
        }
        out.write("</div>\n");
        out.write("<div class=\"page\"><div class=\"mainsection\"><div class=\"snarkMessages\"><table><tr><td align=\"left\"><pre>");
        List msgs = _manager.getMessages();
        for (int i = msgs.size()-1; i >= 0; i--) {
            String msg = (String)msgs.get(i);
            out.write(msg + "\n");
        }
        out.write("</pre></td></tr></table></div>");

        if (isConfigure) {
            out.write("<div class=\"logshim\"></div></div>\n");
            writeConfigForm(out, req);
        } else {
            writeTorrents(out, req);
            out.write("</div>\n");
            writeAddForm(out, req);
            writeSeedForm(out, req);
            writeConfigLink(out);
        }
        out.write(FOOTER);
    }

    private void writeTorrents(PrintWriter out, HttpServletRequest req) throws IOException {
        /** dl, ul, down rate, up rate, peers, size */
        final long stats[] = {0,0,0,0,0,0};
        String peerParam = req.getParameter("p");

        List snarks = getSortedSnarks(req);
        String uri = req.getRequestURI();
        boolean isForm = _manager.util().connected() || !snarks.isEmpty();
        if (isForm) {
            out.write("<form action=\"_post\" method=\"POST\">\n");
            out.write("<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" >\n");
            // don't lose peer setting
            if (peerParam != null)
                out.write("<input type=\"hidden\" name=\"p\" value=\"" + peerParam + "\" >\n");
        }
        out.write(TABLE_HEADER);
        out.write("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "status.png\" > ");
        out.write(_("Status"));
        if (_manager.util().connected() && !snarks.isEmpty()) {
            out.write(" <a href=\"/i2psnark/");
            if (peerParam != null) {
                out.write("\">");
                out.write("<img border=\"0\" src=\"" + _imgPath + "hidepeers.png\" title=\"");
                out.write(_("Hide Peers"));
                out.write("\" alt=\"");
                out.write(_("Hide Peers"));
                out.write("\">");
            } else {
                out.write("?p=1\">");
                out.write("<img border=\"0\" src=\"" + _imgPath + "showpeers.png\" title=\"");
                out.write(_("Show Peers"));
                out.write("\" alt=\"");
                out.write(_("Show Peers"));
                out.write("\">");
            }
            out.write("</a><br>\n"); 
        }
        out.write("</th>\n<th colspan=\"3\" align=\"left\">");
        out.write("<img border=\"0\" src=\"" + _imgPath + "torrent.png\" alt=\"\">");
        out.write(_("Torrent"));
        out.write("</th>\n<th align=\"right\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            out.write("<img border=\"0\" src=\"" + _imgPath + "eta.png\" alt=\"\" title=\"");
            out.write(_("Estimated time remaining"));
            out.write("\">");
            // Translators: Please keep short or translate as " "
            out.write(_("ETA"));
        }
        out.write("</th>\n<th align=\"right\">");
        out.write("<img border=\"0\" src=\"" + _imgPath + "head_rx.png\" alt=\"\" title=\"");
        out.write(_("Downloaded"));
        out.write("\">");
        // Translators: Please keep short or translate as " "
        out.write(_("RX"));
        out.write("</th>\n<th align=\"right\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            out.write("<img border=\"0\" src=\"" + _imgPath + "head_tx.png\" alt=\"\" title=\"");
            out.write(_("Uploaded"));
            out.write("\">");
            // Translators: Please keep short or translate as " "
            out.write(_("TX"));
        }
        out.write("</th>\n<th align=\"right\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            out.write("<img border=\"0\" src=\"" + _imgPath + "head_rxspeed.png\" title=\"");
            out.write(_("Down Rate"));
            out.write("\" alt=\"");
            out.write(_("RX"));
            out.write(" \">");
            // Translators: Please keep short or translate as " "
            out.write(_("Rate"));
        }
        out.write("</th>\n<th align=\"right\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            out.write("<img border=\"0\" src=\"" + _imgPath + "head_txspeed.png\" title=\"");
            out.write(_("Up Rate"));
            out.write("\" alt=\"");
            out.write(_("TX"));
            out.write(" \">");
            out.write(_("Rate"));
        }
        out.write("</th>\n<th align=\"center\">");

        // Opera and text-mode browsers: no &thinsp; and no input type=image values submitted
        // Using a unique name fixes Opera, except for the buttons with js confirms, see below
        String ua = req.getHeader("User-Agent");
        boolean isDegraded = ua != null && (ua.startsWith("Lynx") || ua.startsWith("w3m") ||
                                            ua.startsWith("ELinks") || ua.startsWith("Links") ||
                                            ua.startsWith("Dillo"));

        boolean noThinsp = isDegraded || (ua != null && ua.startsWith("Opera"));
        if (_manager.util().connected()) {
            if (isDegraded)
                out.write("<a href=\"/i2psnark/?action=StopAll&amp;nonce=" + _nonce + "\"><img title=\"");
            else {
                // http://www.onenaught.com/posts/382/firefox-4-change-input-type-image-only-submits-x-and-y-not-name
                //out.write("<input type=\"image\" name=\"action\" value=\"StopAll\" title=\"");
                out.write("<input type=\"image\" name=\"action_StopAll\" value=\"foo\" title=\"");
            }
            out.write(_("Stop all torrents and the I2P tunnel"));
            out.write("\" src=\"" + _imgPath + "stop_all.png\" alt=\"");
            out.write(_("Stop All"));
            out.write("\">");
            if (isDegraded)
                out.write("</a>");
        } else if (!snarks.isEmpty()) {
            if (isDegraded)
                out.write("<a href=\"/i2psnark/?action=StartAll&amp;nonce=" + _nonce + "\"><img title=\"");
            else
                out.write("<input type=\"image\" name=\"action_StartAll\" value=\"foo\" title=\"");
            out.write(_("Start all torrents and the I2P tunnel"));
            out.write("\" src=\"" + _imgPath + "start_all.png\" alt=\"");
            out.write(_("Start All"));
            out.write("\">");
            if (isDegraded)
                out.write("</a>");
        } else {
            out.write("&nbsp;");
        }
        out.write("</th></tr></thead>\n");
        for (int i = 0; i < snarks.size(); i++) {
            Snark snark = (Snark)snarks.get(i);
            boolean showDebug = "2".equals(peerParam);
            boolean showPeers = showDebug || "1".equals(peerParam) || Base64.encode(snark.getInfoHash()).equals(peerParam);
            displaySnark(out, snark, uri, i, stats, showPeers, isDegraded, noThinsp, showDebug);
        }

        if (snarks.isEmpty()) {
            out.write("<tr class=\"snarkTorrentNoneLoaded\">" +
                      "<td class=\"snarkTorrentNoneLoaded\"" +
                      " colspan=\"11\"><i>");
            out.write(_("No torrents loaded."));
            out.write("</i></td></tr>\n");
        } else if (snarks.size() > 1) {
            out.write("<tfoot><tr>\n" +
                      "    <th align=\"left\" colspan=\"6\">");
            out.write(_("Totals"));
            out.write(":&nbsp;");
            out.write(ngettext("1 torrent", "{0} torrents", snarks.size()));
            out.write(", ");
            out.write(DataHelper.formatSize2(stats[5]) + "B, ");
            out.write(ngettext("1 connected peer", "{0} connected peers", (int) stats[4]));
            out.write("</th>\n");
            if (_manager.util().connected()) {
                out.write("    <th align=\"right\">" + formatSize(stats[0]) + "</th>\n" +
                      "    <th align=\"right\">" + formatSize(stats[1]) + "</th>\n" +
                      "    <th align=\"right\">" + formatSize(stats[2]) + "ps</th>\n" +
                      "    <th align=\"right\">" + formatSize(stats[3]) + "ps</th>\n" +
                      "    <th></th>");
            } else {
                out.write("<th colspan=\"5\"></th>");
            }
            out.write("</tr></tfoot>\n");
        }
        
        out.write("</table>");
        if (isForm)
            out.write("</form>\n");
    }
    
    /**
     * Do what they ask, adding messages to _manager.addMessage as necessary
     */
    private void processRequest(HttpServletRequest req) {
        String action = req.getParameter("action");
        if (action == null) {
            // http://www.onenaught.com/posts/382/firefox-4-change-input-type-image-only-submits-x-and-y-not-name
            Map params = req.getParameterMap();
            for (Object o : params.keySet()) {
                String key = (String) o;
                if (key.startsWith("action_") && key.endsWith(".x")) {
                    action = key.substring(0, key.length() - 2).substring(7);
                    break;
                }
            }
            if (action == null) {
                _manager.addMessage("No action specified");
                return;
            }
        }
        // sadly, Opera doesn't send value with input type=image, so we have to use GET there
        //if (!"POST".equals(req.getMethod())) {
        //    _manager.addMessage("Action must be with POST");
        //    return;
        //}
        if ("Add".equals(action)) {
            String newFile = req.getParameter("newFile");
            String newURL = req.getParameter("newURL");
         /******
            // NOTE - newFile currently disabled in HTML form - see below
            File f = null;
            if ( (newFile != null) && (newFile.trim().length() > 0) )
                f = new File(newFile.trim());
            if ( (f != null) && (!f.exists()) ) {
                _manager.addMessage(_("Torrent file {0} does not exist", newFile));
            }
            if ( (f != null) && (f.exists()) ) {
                // NOTE - All this is disabled - load from local file disabled
                File local = new File(_manager.getDataDir(), f.getName());
                String canonical = null;
                try {
                    canonical = local.getCanonicalPath();
                    
                    if (local.exists()) {
                        if (_manager.getTorrent(canonical) != null)
                            _manager.addMessage(_("Torrent already running: {0}", newFile));
                        else
                            _manager.addMessage(_("Torrent already in the queue: {0}", newFile));
                    } else {
                        boolean ok = FileUtil.copy(f.getAbsolutePath(), local.getAbsolutePath(), true);
                        if (ok) {
                            _manager.addMessage(_("Copying torrent to {0}", local.getAbsolutePath()));
                            _manager.addTorrent(canonical);
                        } else {
                            _manager.addMessage(_("Unable to copy the torrent to {0}", local.getAbsolutePath()) + ' ' + _("from {0}", f.getAbsolutePath()));
                        }
                    }
                } catch (IOException ioe) {
                    _log.warn("hrm: " + local, ioe);
                }
            } else
          *****/
            if (newURL != null) {
                if (newURL.startsWith("http://")) {
                    _manager.addMessage(_("Fetching {0}", urlify(newURL)));
                    I2PAppThread fetch = new I2PAppThread(new FetchAndAdd(_manager, newURL), "Fetch and add", true);
                    fetch.start();
                } else if (newURL.startsWith(MAGNET) || newURL.startsWith(MAGGOT)) {
                    addMagnet(newURL);
                } else {
                    _manager.addMessage(_("Invalid URL: Must start with \"http://\", \"{0}\", or \"{1}\"", MAGNET, MAGGOT));
                }
            } else {
                // no file or URL specified
            }
        } else if (action.startsWith("Stop_")) {
            String torrent = action.substring(5);
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (Iterator iter = _manager.listTorrentFiles().iterator(); iter.hasNext(); ) {
                        String name = (String)iter.next();
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash())) ) {
                            _manager.stopTorrent(snark, false);
                            break;
                        }
                    }
                }
            }
        } else if (action.startsWith("Start_")) {
            String torrent = action.substring(6);
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (String name : _manager.listTorrentFiles()) {
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash())) ) {
                            snark.startTorrent();
                            _manager.addMessage(_("Starting up torrent {0}", snark.getBaseName()));
                            break;
                        }
                    }
                }
            }
        } else if (action.startsWith("Remove_")) {
            String torrent = action.substring(7);
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (Iterator iter = _manager.listTorrentFiles().iterator(); iter.hasNext(); ) {
                        String name = (String)iter.next();
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash())) ) {
                            MetaInfo meta = snark.getMetaInfo();
                            if (meta == null) {
                                // magnet - remove and delete are the same thing
                                _manager.deleteMagnet(snark);
                                _manager.addMessage(_("Magnet deleted: {0}", name));
                                return;
                            }
                            _manager.stopTorrent(snark, true);
                            // should we delete the torrent file?
                            // yeah, need to, otherwise it'll get autoadded again (at the moment
                            File f = new File(name);
                            f.delete();
                            _manager.addMessage(_("Torrent file deleted: {0}", f.getAbsolutePath()));
                            break;
                        }
                    }
                }
            }
        } else if (action.startsWith("Delete_")) {
            String torrent = action.substring(7);
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (Iterator iter = _manager.listTorrentFiles().iterator(); iter.hasNext(); ) {
                        String name = (String)iter.next();
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash())) ) {
                            MetaInfo meta = snark.getMetaInfo();
                            if (meta == null) {
                                // magnet - remove and delete are the same thing
                                _manager.deleteMagnet(snark);
                                _manager.addMessage(_("Magnet deleted: {0}", name));
                                return;
                            }
                            _manager.stopTorrent(snark, true);
                            File f = new File(name);
                            f.delete();
                            _manager.addMessage(_("Torrent file deleted: {0}", f.getAbsolutePath()));
                            List files = meta.getFiles();
                            String dataFile = snark.getBaseName();
                            f = new File(_manager.getDataDir(), dataFile);
                            if (files == null) { // single file torrent
                                if (f.delete())
                                    _manager.addMessage(_("Data file deleted: {0}", f.getAbsolutePath()));
                                else
                                    _manager.addMessage(_("Data file could not be deleted: {0}", f.getAbsolutePath()));
                                break;
                            }
                            for (int i = 0; i < files.size(); i++) { // pass 1 delete files
                                // multifile torrents have the getFiles() return lists of lists of filenames, but
                                // each of those lists just contain a single file afaict...
                                File df = Storage.getFileFromNames(f, (List) files.get(i));
                                if (df.delete())
                                    _manager.addMessage(_("Data file deleted: {0}", df.getAbsolutePath()));
                                else
                                    _manager.addMessage(_("Data file could not be deleted: {0}", df.getAbsolutePath()));
                            }
                            for (int i = files.size() - 1; i >= 0; i--) { // pass 2 delete dirs - not foolproof,
                                                                          // we could sort and do a strict bottom-up
                                File df = Storage.getFileFromNames(f, (List) files.get(i));
                                df = df.getParentFile();
                                if (df == null || !df.exists())
                                    continue;
                                if(df.delete())
                                    _manager.addMessage(_("Data dir deleted: {0}", df.getAbsolutePath()));
                            }
                            break;
                        }
                    }
                }
            }
        } else if ("Save".equals(action)) {
            String dataDir = req.getParameter("dataDir");
            boolean filesPublic = req.getParameter("filesPublic") != null;
            boolean autoStart = req.getParameter("autoStart") != null;
            String seedPct = req.getParameter("seedPct");
            String eepHost = req.getParameter("eepHost");
            String eepPort = req.getParameter("eepPort");
            String i2cpHost = req.getParameter("i2cpHost");
            String i2cpPort = req.getParameter("i2cpPort");
            String i2cpOpts = buildI2CPOpts(req);
            String upLimit = req.getParameter("upLimit");
            String upBW = req.getParameter("upBW");
            String refreshDel = req.getParameter("refreshDelay");
            String startupDel = req.getParameter("startupDelay");
            boolean useOpenTrackers = req.getParameter("useOpenTrackers") != null;
            String openTrackers = req.getParameter("openTrackers");
            String theme = req.getParameter("theme");
            _manager.updateConfig(dataDir, filesPublic, autoStart, refreshDel, startupDel,
                                  seedPct, eepHost, eepPort, i2cpHost, i2cpPort, i2cpOpts,
                                  upLimit, upBW, useOpenTrackers, openTrackers, theme);
        } else if ("Create".equals(action)) {
            String baseData = req.getParameter("baseFile");
            if (baseData != null && baseData.trim().length() > 0) {
                File baseFile = new File(_manager.getDataDir(), baseData);
                String announceURL = req.getParameter("announceURL");
                String announceURLOther = req.getParameter("announceURLOther");
                if ( (announceURLOther != null) && (announceURLOther.trim().length() > "http://.i2p/announce".length()) )
                    announceURL = announceURLOther;

                if (announceURL == null || announceURL.length() <= 0)
                    _manager.addMessage(_("Error creating torrent - you must select a tracker"));
                else if (baseFile.exists()) {
                    _lastAnnounceURL = announceURL;
                    if (announceURL.equals("none"))
                        announceURL = null;
                    try {
                        // This may take a long time to check the storage, but since it already exists,
                        // it shouldn't be THAT bad, so keep it in this thread.
                        Storage s = new Storage(_manager.util(), baseFile, announceURL, null);
                        s.close(); // close the files... maybe need a way to pass this Storage to addTorrent rather than starting over
                        MetaInfo info = s.getMetaInfo();
                        File torrentFile = new File(_manager.getDataDir(), s.getBaseName() + ".torrent");
                        // FIXME is the storage going to stay around thanks to the info reference?
                        // now add it, but don't automatically start it
                        _manager.addTorrent(info, s.getBitField(), torrentFile.getAbsolutePath(), true);
                        _manager.addMessage(_("Torrent created for \"{0}\"", baseFile.getName()) + ": " + torrentFile.getAbsolutePath());
                        if (announceURL != null)
                            _manager.addMessage(_("Many I2P trackers require you to register new torrents before seeding - please do so before starting \"{0}\"", baseFile.getName()));
                    } catch (IOException ioe) {
                        _manager.addMessage(_("Error creating a torrent for \"{0}\"", baseFile.getAbsolutePath()) + ": " + ioe.getMessage());
                    }
                } else {
                    _manager.addMessage(_("Cannot create a torrent for the nonexistent data: {0}", baseFile.getAbsolutePath()));
                }
            } else {
                _manager.addMessage(_("Error creating torrent - you must enter a file or directory"));
            }
        } else if ("StopAll".equals(action)) {
            _manager.addMessage(_("Stopping all torrents and closing the I2P tunnel."));
            List snarks = getSortedSnarks(req);
            for (int i = 0; i < snarks.size(); i++) {
                Snark snark = (Snark)snarks.get(i);
                if (!snark.isStopped())
                    _manager.stopTorrent(snark, false);
            }
            if (_manager.util().connected()) {
                // Give the stopped announces time to get out
                try { Thread.sleep(2000); } catch (InterruptedException ie) {}
                _manager.util().disconnect();
                _manager.addMessage(_("I2P tunnel closed."));
            }
        } else if ("StartAll".equals(action)) {
            _manager.addMessage(_("Opening the I2P tunnel and starting all torrents."));
            List snarks = getSortedSnarks(req);
            for (int i = 0; i < snarks.size(); i++) {
                Snark snark = (Snark)snarks.get(i);
                if (snark.isStopped())
                    snark.startTorrent();
            }
        } else {
            _manager.addMessage("Unknown POST action: \"" + action + '\"');
        }
    }
    
    private static final String iopts[] = {"inbound.length", "inbound.quantity",
                                           "outbound.length", "outbound.quantity" };

    /** put the individual i2cp selections into the option string */
    private static String buildI2CPOpts(HttpServletRequest req) {
        StringBuilder buf = new StringBuilder(128);
        String p = req.getParameter("i2cpOpts");
        if (p != null)
            buf.append(p);
        for (int i = 0; i < iopts.length; i++) {
            p = req.getParameter(iopts[i]);
            if (p != null)
                buf.append(' ').append(iopts[i]).append('=').append(p);
        }
        return buf.toString();
    }

    /**
     *  Sort alphabetically in current locale, ignore case, ignore leading "the "
     *  (I guess this is worth it, a lot of torrents start with "The "
     *  These are full path names which makes it harder
     *  @since 0.7.14
     */
    private class TorrentNameComparator implements Comparator<String> {
        private final Comparator collator = Collator.getInstance();
        private final String skip = _manager.getDataDir().getAbsolutePath() + File.separator;

        public int compare(String l, String r) {
            if (l.startsWith(skip))
                l = l.substring(skip.length());
            if (r.startsWith(skip))
                r = r.substring(skip.length());
            String llc = l.toLowerCase(Locale.US);
            if (llc.startsWith("the ") || llc.startsWith("the.") || llc.startsWith("the_"))
                l = l.substring(4);
            String rlc = r.toLowerCase(Locale.US);
            if (rlc.startsWith("the ") || rlc.startsWith("the.") || rlc.startsWith("the_"))
                r = r.substring(4);
            return collator.compare(l, r);
        }
    }

    private List<Snark> getSortedSnarks(HttpServletRequest req) {
        Set<String> files = _manager.listTorrentFiles();
        TreeSet<String> fileNames = new TreeSet(new TorrentNameComparator());
        fileNames.addAll(files);
        ArrayList<Snark> rv = new ArrayList(fileNames.size());
        for (Iterator iter = fileNames.iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            Snark snark = _manager.getTorrent(name);
            if (snark != null)
                rv.add(snark);
        }
        return rv;
    }

    private static final int MAX_DISPLAYED_FILENAME_LENGTH = 50;
    private static final int MAX_DISPLAYED_ERROR_LENGTH = 43;
    private void displaySnark(PrintWriter out, Snark snark, String uri, int row, long stats[], boolean showPeers,
                              boolean isDegraded, boolean noThinsp, boolean showDebug) throws IOException {
        String filename = snark.getName();
        File f = new File(filename);
        filename = f.getName(); // the torrent may be the canonical name, so lets just grab the local name
        int i = filename.lastIndexOf(".torrent");
        if (i > 0)
            filename = filename.substring(0, i);
        String fullFilename = filename;
        if (filename.length() > MAX_DISPLAYED_FILENAME_LENGTH) {
            String start = filename.substring(0, MAX_DISPLAYED_FILENAME_LENGTH);
            if (start.indexOf(" ") < 0 && start.indexOf("-") < 0) {
                // browser has nowhere to break it
                fullFilename = filename;
                filename = start + "&hellip;";
            }
        }
        long total = snark.getTotalLength();
        long remaining = snark.getRemainingLength(); 
        if (remaining > total)
            remaining = total;
        long downBps = snark.getDownloadRate();
        long upBps = snark.getUploadRate();
        long remainingSeconds;
        if (downBps > 0)
            remainingSeconds = remaining / downBps;
        else
            remainingSeconds = -1;
        boolean isRunning = !snark.isStopped();
        long uploaded = snark.getUploaded();
        stats[0] += snark.getDownloaded();
        stats[1] += uploaded;
        if (isRunning) {
            stats[2] += downBps;
            stats[3] += upBps;
        }
        stats[5] += total;
        
        MetaInfo meta = snark.getMetaInfo();
        // isValid means isNotMagnet
        boolean isValid = meta != null;
        boolean isMultiFile = isValid && meta.getFiles() != null;
        
        String err = snark.getTrackerProblems();
        int curPeers = snark.getPeerCount();
        stats[4] += curPeers;
        int knownPeers = Math.max(curPeers, snark.getTrackerSeenPeers());
        
        String rowClass = (row % 2 == 0 ? "snarkTorrentEven" : "snarkTorrentOdd");
        String statusString;
        if (err != null) {
            if (isRunning && curPeers > 0 && !showPeers)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "trackererror.png\" title=\"" + err + "\"></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("Tracker Error") +
                               ": <a href=\"" + uri + "?p=" + Base64.encode(snark.getInfoHash()) + "\">" +
                               curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
            else if (isRunning)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "trackererror.png\" title=\"" + err + "\"></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("Tracker Error") +
                               ": " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
            else {
                if (err.length() > MAX_DISPLAYED_ERROR_LENGTH)
                    err = err.substring(0, MAX_DISPLAYED_ERROR_LENGTH) + "&hellip;";
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "trackererror.png\" title=\"" + err + "\"></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("Tracker Error") +
                "<br>" + err;
            }
        } else if (remaining == 0) {  // < 0 means no meta size yet
            if (isRunning && curPeers > 0 && !showPeers)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "seeding.png\" ></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("Seeding") +
                               ": <a href=\"" + uri + "?p=" + Base64.encode(snark.getInfoHash()) + "\">" +
                               curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
            else if (isRunning)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "seeding.png\" ></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("Seeding") +
                               ": " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
            else
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "complete.png\" ></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("Complete");
        } else {
            if (isRunning && curPeers > 0 && downBps > 0 && !showPeers)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "downloading.png\" ></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("OK") +
                               ": <a href=\"" + uri + "?p=" + Base64.encode(snark.getInfoHash()) + "\">" +
                               curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
            else if (isRunning && curPeers > 0 && downBps > 0)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "downloading.png\" ></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("OK") +
                               ": " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
            else if (isRunning && curPeers > 0 && !showPeers)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "stalled.png\" ></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("Stalled") +
                               ": <a href=\"" + uri + "?p=" + Base64.encode(snark.getInfoHash()) + "\">" +
                               curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
            else if (isRunning && curPeers > 0)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "stalled.png\" ></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("Stalled") +
                               ": " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
            else if (isRunning && knownPeers > 0)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "nopeers.png\" ></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("No Peers") +
                               ": 0" + thinsp(noThinsp) + knownPeers ;
            else if (isRunning)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "nopeers.png\" ></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("No Peers");
            else
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "stopped.png\" ></td><td class=\"snarkTorrentStatus " + rowClass + "\">" + _("Stopped");
        }
        
        out.write("<tr class=\"" + rowClass + "\">");
        out.write("<td class=\"center " + rowClass + "\">");
        out.write(statusString + "</td>\n\t");

        // (i) icon column
        out.write("<td class=\"" + rowClass + "\">");
        if (isValid && meta.getAnnounce() != null) {
            // Link to local details page - note that trailing slash on a single-file torrent
            // gets us to the details page instead of the file.
            //StringBuilder buf = new StringBuilder(128);
            //buf.append("<a href=\"").append(snark.getBaseName())
            //   .append("/\" title=\"").append(_("Torrent details"))
            //   .append("\"><img alt=\"").append(_("Info")).append("\" border=\"0\" src=\"")
            //   .append(_imgPath).append("details.png\"></a>");
            //out.write(buf.toString());

            // Link to tracker details page
            String trackerLink = getTrackerLink(meta.getAnnounce(), snark.getInfoHash());
            if (trackerLink != null)
                out.write(trackerLink);
        }

        // File type icon column
        out.write("</td>\n<td class=\"" + rowClass + "\">");
        if (isValid) {
            // Link to local details page - note that trailing slash on a single-file torrent
            // gets us to the details page instead of the file.
            StringBuilder buf = new StringBuilder(128);
            buf.append("<a href=\"").append(snark.getBaseName())
               .append("/\" title=\"").append(_("Torrent details"))
               .append("\">");
            out.write(buf.toString());
        }
        String icon;
        if (isMultiFile)
            icon = "folder";
        else if (isValid)
            icon = toIcon(meta.getName());
        else
            icon = "magnet";
        if (isValid) {
            out.write(toImg(icon, _("Info")));
            out.write("</a>");
        } else {
            out.write(toImg(icon));
        }

        // Torrent name column
        out.write("</td><td class=\"snarkTorrentName " + rowClass + "\">");
        if (remaining == 0 || isMultiFile) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("<a href=\"").append(snark.getBaseName());
            if (isMultiFile)
                buf.append('/');
            buf.append("\" title=\"");
            if (isMultiFile)
                buf.append(_("View files"));
            else
                buf.append(_("Open file"));
            buf.append("\">");
            out.write(buf.toString());
        }
        out.write(filename);
        if (remaining == 0 || isMultiFile)
            out.write("</a>");

        out.write("<td align=\"right\" class=\"snarkTorrentETA " + rowClass + "\">");
        if(isRunning && remainingSeconds > 0)
            out.write(DataHelper.formatDuration2(remainingSeconds*1000)); // (eta 6h)
        out.write("</td>\n\t");
        out.write("<td align=\"right\" class=\"snarkTorrentDownloaded " + rowClass + "\">");
        if (remaining > 0)
            out.write(formatSize(total-remaining) + thinsp(noThinsp) + formatSize(total));
        else if (remaining == 0)
            out.write(formatSize(total)); // 3GB
        //else
        //    out.write("??");  // no meta size yet
        out.write("</td>\n\t");
        out.write("<td align=\"right\" class=\"snarkTorrentUploaded " + rowClass + "\">");
        if(isRunning && isValid)
           out.write(formatSize(uploaded));
        out.write("</td>\n\t");
        out.write("<td align=\"right\" class=\"snarkTorrentRateDown\">");
        if(isRunning && remaining != 0)
            out.write(formatSize(downBps) + "ps");
        out.write("</td>\n\t");
        out.write("<td align=\"right\" class=\"snarkTorrentRateUp\">");
        if(isRunning && isValid)
            out.write(formatSize(upBps) + "ps");
        out.write("</td>\n\t");
        out.write("<td align=\"center\" class=\"snarkTorrentAction " + rowClass + "\">");
        String parameters = "&nonce=" + _nonce + "&torrent=" + Base64.encode(snark.getInfoHash());
        String b64 = Base64.encode(snark.getInfoHash());
        if (showPeers)
            parameters = parameters + "&p=1";
        if (isRunning) {
            if (isDegraded)
                out.write("<a href=\"/i2psnark/?action=Stop_" + b64 + "&amp;nonce=" + _nonce + "\"><img title=\"");
            else
                out.write("<input type=\"image\" name=\"action_Stop_" + b64 + "\" value=\"foo\" title=\"");
            out.write(_("Stop the torrent"));
            out.write("\" src=\"" + _imgPath + "stop.png\" alt=\"");
            out.write(_("Stop"));
            out.write("\">");
            if (isDegraded)
                out.write("</a>");
        } else {
                // This works in Opera but it's displayed a little differently, so use noThinsp here too so all 3 icons are consistent
                if (noThinsp)
                    out.write("<a href=\"/i2psnark/?action=Start_" + b64 + "&amp;nonce=" + _nonce + "\"><img title=\"");
                else
                    out.write("<input type=\"image\" name=\"action_Start_" + b64 + "\" value=\"foo\" title=\"");
                out.write(_("Start the torrent"));
                out.write("\" src=\"" + _imgPath + "start.png\" alt=\"");
                out.write(_("Start"));
                out.write("\">");
                if (isDegraded)
                    out.write("</a>");

            if (isValid) {
                // Doesnt work with Opera so use noThinsp instead of isDegraded
                if (noThinsp)
                    out.write("<a href=\"/i2psnark/?action=Remove_" + b64 + "&amp;nonce=" + _nonce + "\"><img title=\"");
                else
                    out.write("<input type=\"image\" name=\"action_Remove_" + b64 + "\" value=\"foo\" title=\"");
                out.write(_("Remove the torrent from the active list, deleting the .torrent file"));
                out.write("\" onclick=\"if (!confirm('");
                // Can't figure out how to escape double quotes inside the onclick string.
                // Single quotes in translate strings with parameters must be doubled.
                // Then the remaining single quite must be escaped
                out.write(_("Are you sure you want to delete the file \\''{0}.torrent\\'' (downloaded data will not be deleted) ?", fullFilename));
                out.write("')) { return false; }\"");
                out.write(" src=\"" + _imgPath + "remove.png\" alt=\"");
                out.write(_("Remove"));
                out.write("\">");
                if (isDegraded)
                    out.write("</a>");
            }

            // Doesnt work with Opera so use noThinsp instead of isDegraded
            if (noThinsp)
                out.write("<a href=\"/i2psnark/?action=Delete_" + b64 + "&amp;nonce=" + _nonce + "\"><img title=\"");
            else
                out.write("<input type=\"image\" name=\"action_Delete_" + b64 + "\" value=\"foo\" title=\"");
            out.write(_("Delete the .torrent file and the associated data file(s)"));
            out.write("\" onclick=\"if (!confirm('");
            // Can't figure out how to escape double quotes inside the onclick string.
            // Single quotes in translate strings with parameters must be doubled.
            // Then the remaining single quite must be escaped
            out.write(_("Are you sure you want to delete the torrent \\''{0}\\'' and all downloaded data?", fullFilename));
            out.write("')) { return false; }\"");
            out.write(" src=\"" + _imgPath + "delete.png\" alt=\"");
            out.write(_("Delete"));
            out.write("\">");
            if (isDegraded)
                out.write("</a>");
        }
        out.write("</td>\n</tr>\n");

        if(showPeers && isRunning && curPeers > 0) {
            List<Peer> peers = snark.getPeerList();
            if (!showDebug)
                Collections.sort(peers, new PeerComparator());
            for (Peer peer : peers) {
                if (!peer.isConnected())
                    continue;
                out.write("<tr class=\"" + rowClass + "\"><td></td>");
                out.write("<td colspan=\"4\" align=\"right\" class=\"" + rowClass + "\">");
                String ch = peer.toString().substring(0, 4);
                String client;
                if ("AwMD".equals(ch))
                    client = _("I2PSnark");
                else if ("BFJT".equals(ch))
                    client = "I2PRufus";
                else if ("TTMt".equals(ch))
                    client = "I2P-BT";
                else if ("LUFa".equals(ch))
                    client = "Azureus";
                else if ("CwsL".equals(ch))
                    client = "I2PSnarkXL";
                else if ("ZV".equals(ch.substring(2,4)) || "VUZP".equals(ch))
                    client = "Robert";
                else if (ch.startsWith("LV")) // LVCS 1.0.2?; LVRS 1.0.4
                    client = "Transmission";
                else if ("LUtU".equals(ch))
                    client = "KTorrent";
                else
                    client = _("Unknown") + " (" + ch + ')';
                out.write(client + "&nbsp;&nbsp;<tt>" + peer.toString().substring(5, 9)+ "</tt>");
                if (showDebug)
                    out.write(" inactive " + (peer.getInactiveTime() / 1000) + "s");
                out.write("</td>\n\t");
                out.write("<td class=\"snarkTorrentStatus " + rowClass + "\">");
                out.write("</td>\n\t");
                out.write("<td align=\"right\" class=\"snarkTorrentStatus " + rowClass + "\">");
                float pct;
                if (isValid) {
                    pct = (float) (100.0 * (float) peer.completed() / meta.getPieces());
                    if (pct == 100.0)
                        out.write(_("Seed"));
                    else {
                        String ps = String.valueOf(pct);
                        if (ps.length() > 5)
                            ps = ps.substring(0, 5);
                        out.write(ps + "%");
                    }
                } else {
                    pct = (float) 101.0;
                    // until we get the metainfo we don't know how many pieces there are
                    //out.write("??");
                }
                out.write("</td>\n\t");
                out.write("<td class=\"snarkTorrentStatus " + rowClass + "\">");
                out.write("</td>\n\t");
                out.write("<td align=\"right\" class=\"snarkTorrentStatus " + rowClass + "\">");
                if (remaining > 0) {
                    if (peer.isInteresting() && !peer.isChoked()) {
                        out.write("<span class=\"unchoked\">");
                        out.write(formatSize(peer.getDownloadRate()) + "ps</span>");
                    } else {
                        out.write("<span class=\"choked\"><a title=\"");
                        if (!peer.isInteresting())
                            out.write(_("Uninteresting (The peer has no pieces we need)"));
                        else
                            out.write(_("Choked (The peer is not allowing us to request pieces)"));
                        out.write("\">");
                        out.write(formatSize(peer.getDownloadRate()) + "ps</a></span>");
                    }
                } else if (!isValid) {
                    //if (peer supports metadata extension) {
                        out.write("<span class=\"unchoked\">");
                        out.write(formatSize(peer.getDownloadRate()) + "ps</span>");
                    //} else {
                    //}
                }
                out.write("</td>\n\t");
                out.write("<td align=\"right\" class=\"snarkTorrentStatus " + rowClass + "\">");
                if (isValid && pct < 100.0) {
                    if (peer.isInterested() && !peer.isChoking()) {
                        out.write("<span class=\"unchoked\">");
                        out.write(formatSize(peer.getUploadRate()) + "ps</span>");
                    } else {
                        out.write("<span class=\"choked\"><a title=\"");
                        if (!peer.isInterested())
                            out.write(_("Uninterested (We have no pieces the peer needs)"));
                        else
                            out.write(_("Choking (We are not allowing the peer to request pieces)"));
                        out.write("\">");
                        out.write(formatSize(peer.getUploadRate()) + "ps</a></span>");
                    }
                }
                out.write("</td>\n\t");
                out.write("<td class=\"snarkTorrentStatus " + rowClass + "\">");
                out.write("</td></tr>\n\t");
                if (showDebug)
                    out.write("<tr class=\"" + rowClass + "\"><td></td><td colspan=\"10\" align=\"right\" class=\"" + rowClass + "\">" + peer.getSocket() + "</td></tr>");
            }
        }
    }
    
    /** @since 0.8.2 */
    private static String thinsp(boolean disable) {
        if (disable)
            return " / ";
        return ("&thinsp;/&thinsp;");
    }

    /**
     *  Sort by completeness (seeds first), then by ID
     *  @since 0.8.1
     */
    private static class PeerComparator implements Comparator<Peer> {
        public int compare(Peer l, Peer r) {
            int diff = r.completed() - l.completed();      // reverse
            if (diff != 0)
                return diff;
            return l.toString().substring(5, 9).compareTo(r.toString().substring(5, 9));
        }
    }

    /**
     *  @return string or null
     *  @since 0.8.4
     */
    private String getTrackerLink(String announce, byte[] infohash) {
        // temporarily hardcoded for postman* and anonymity, requires bytemonsoon patch for lookup by info_hash
        if (announce != null && (announce.startsWith("http://YRgrgTLG") || announce.startsWith("http://8EoJZIKr") ||
              announce.startsWith("http://lnQ6yoBT") || announce.startsWith("http://tracker2.postman.i2p/") ||
              announce.startsWith("http://ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p/"))) {
            Map<String, String> trackers = _manager.getTrackers();
            for (Map.Entry<String, String> entry : trackers.entrySet()) {
                String baseURL = entry.getValue();
                if (!(baseURL.startsWith(announce) || // vvv hack for non-b64 announce in list vvv
                      (announce.startsWith("http://lnQ6yoBT") && baseURL.startsWith("http://tracker2.postman.i2p/")) ||
                      (announce.startsWith("http://ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p/") && baseURL.startsWith("http://tracker2.postman.i2p/"))))
                    continue;
                int e = baseURL.indexOf('=');
                if (e < 0)
                    continue;
                baseURL = baseURL.substring(e + 1);
                String name = entry.getKey();
                StringBuilder buf = new StringBuilder(128);
                buf.append("<a href=\"").append(baseURL).append("details.php?dllist=1&amp;filelist=1&amp;info_hash=")
                   .append(TrackerClient.urlencode(infohash))
                   .append("\" title=\"").append(_("Details at {0} tracker", name)).append("\" target=\"_blank\">" +
                          "<img alt=\"").append(_("Info")).append("\" border=\"0\" src=\"")
                   .append(_imgPath).append("details.png\"></a>");
                return buf.toString();
            }
        }
        return null;
    }

    private void writeAddForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String newURL = req.getParameter("newURL");
        if ( (newURL == null) || (newURL.trim().length() <= 0) )
            newURL = "";
        else
            newURL = DataHelper.stripHTML(newURL);    // XSS
        //String newFile = req.getParameter("newFile");
        //if ( (newFile == null) || (newFile.trim().length() <= 0) ) newFile = "";
        
        out.write("<div class=\"snarkNewTorrent\">\n");
        // *not* enctype="multipart/form-data", so that the input type=file sends the filename, not the file
        out.write("<form action=\"_post\" method=\"POST\">\n");
        out.write("<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" >\n");
        out.write("<input type=\"hidden\" name=\"action\" value=\"Add\" >\n");
        // don't lose peer setting
        String peerParam = req.getParameter("p");
        if (peerParam != null)
            out.write("<input type=\"hidden\" name=\"p\" value=\"" + peerParam + "\" >\n");
        out.write("<div class=\"addtorrentsection\"><span class=\"snarkConfigTitle\">");
        out.write("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "add.png\"> ");
        out.write(_("Add Torrent"));
        out.write("</span><hr>\n<table border=\"0\"><tr><td>");
        out.write(_("From URL"));
        out.write(":<td><input type=\"text\" name=\"newURL\" size=\"85\" value=\"" + newURL + "\"");
        out.write(" title=\"");
        out.write(_("Enter the torrent file download URL (I2P only), magnet link, or maggot link"));
        out.write("\"> \n");
        // not supporting from file at the moment, since the file name passed isn't always absolute (so it may not resolve)
        //out.write("From file: <input type=\"file\" name=\"newFile\" size=\"50\" value=\"" + newFile + "\" /><br>");
        out.write("<input type=\"submit\" value=\"");
        out.write(_("Add torrent"));
        out.write("\" name=\"foo\" ><br>\n");
        out.write("<tr><td>&nbsp;<td><span class=\"snarkAddInfo\">");
        out.write(_("You can also copy .torrent files to: {0}.", "<code>" + _manager.getDataDir().getAbsolutePath () + "</code>"));
        out.write("\n");
        out.write(_("Removing a .torrent will cause it to stop."));
        out.write("<br></span></table>\n");
        out.write("</div></form></div>");  
    }
    
    private void writeSeedForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String baseFile = req.getParameter("baseFile");
        if (baseFile == null || baseFile.trim().length() <= 0)
            baseFile = "";
        else
            baseFile = DataHelper.stripHTML(baseFile);    // XSS
        
        out.write("<div class=\"newtorrentsection\"><div class=\"snarkNewTorrent\">\n");
        // *not* enctype="multipart/form-data", so that the input type=file sends the filename, not the file
        out.write("<form action=\"_post\" method=\"POST\">\n");
        out.write("<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" >\n");
        out.write("<input type=\"hidden\" name=\"action\" value=\"Create\" >\n");
        // don't lose peer setting
        String peerParam = req.getParameter("p");
        if (peerParam != null)
            out.write("<input type=\"hidden\" name=\"p\" value=\"" + peerParam + "\" >\n");
        out.write("<span class=\"snarkConfigTitle\">");
        out.write("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "create.png\"> ");
        out.write(_("Create Torrent"));
        out.write("</span><hr>\n<table border=\"0\"><tr><td>");
        //out.write("From file: <input type=\"file\" name=\"newFile\" size=\"50\" value=\"" + newFile + "\" /><br>\n");
        out.write(_("Data to seed"));
        out.write(":<td><code>" + _manager.getDataDir().getAbsolutePath() + File.separatorChar 
                  + "</code><input type=\"text\" name=\"baseFile\" size=\"58\" value=\"" + baseFile 
                  + "\" title=\"");
        out.write(_("File or directory to seed (must be within the specified path)"));
        out.write("\" ><tr><td>\n");
        out.write(_("Tracker"));
        out.write(":<td><select name=\"announceURL\"><option value=\"\">");
        out.write(_("Select a tracker"));
        out.write("</option>\n");
        // todo remember this one with _lastAnnounceURL also
        out.write("<option value=\"none\">");
        //out.write(_("Open trackers and DHT only"));
        out.write(_("Open trackers only"));
        out.write("</option>\n");
        Map trackers = _manager.getTrackers();
        for (Iterator iter = trackers.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry entry = (Map.Entry)iter.next();
            String name = (String)entry.getKey();
            String announceURL = (String)entry.getValue();
            int e = announceURL.indexOf('=');
            if (e > 0)
                announceURL = announceURL.substring(0, e);
            if (announceURL.equals(_lastAnnounceURL))
                announceURL += "\" selected=\"selected";
            out.write("\t<option value=\"" + announceURL + "\">" + name + "</option>\n");
        }
        out.write("</select>\n");
        out.write(_("or"));
        out.write("&nbsp;<input type=\"text\" name=\"announceURLOther\" size=\"57\" value=\"http://\" " +
                  "title=\"");
        out.write(_("Specify custom tracker announce URL"));
        out.write("\" > " +
                  "<input type=\"submit\" value=\"");
        out.write(_("Create torrent"));
        out.write("\" name=\"foo\" ></table>\n" +
                  "</form></div></div>");        
    }
    
    private static final int[] times = { 30, 60, 2*60, 5*60, 10*60, 30*60, -1 };

    private void writeConfigForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String dataDir = _manager.getDataDir().getAbsolutePath();
        boolean filesPublic = _manager.areFilesPublic();
        boolean autoStart = _manager.shouldAutoStart();
        boolean useOpenTrackers = _manager.util().shouldUseOpenTrackers();
        String openTrackers = _manager.util().getOpenTrackerString();
        //int seedPct = 0;
       
        out.write("<form action=\"/i2psnark/configure\" method=\"POST\">\n" +
                  "<div class=\"configsectionpanel\"><div class=\"snarkConfig\">\n" +
                  "<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" >\n" +
                  "<input type=\"hidden\" name=\"action\" value=\"Save\" >\n" +
                  "<span class=\"snarkConfigTitle\">" +
                  "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "config.png\"> ");
        out.write(_("Configuration"));
        out.write("</span><hr>\n"   +
                  "<table border=\"0\"><tr><td>");

        out.write(_("Data directory"));
        out.write(": <td><code>" + dataDir + "</code> <i>(");
        out.write(_("Edit i2psnark.config and restart to change"));
        out.write(")</i><br>\n" +

                  "<tr><td>");
        out.write(_("Files readable by all"));
        out.write(": <td><input type=\"checkbox\" class=\"optbox\" name=\"filesPublic\" value=\"true\" " 
                  + (filesPublic ? "checked " : "") 
                  + "title=\"");
        out.write(_("If checked, other users may access the downloaded files"));
        out.write("\" >" +

                  "<tr><td>");
        out.write(_("Auto start"));
        out.write(": <td><input type=\"checkbox\" class=\"optbox\" name=\"autoStart\" value=\"true\" " 
                  + (autoStart ? "checked " : "") 
                  + "title=\"");
        out.write(_("If checked, automatically start torrents that are added"));
        out.write("\" >" +

                  "<tr><td>");
        out.write(_("Theme"));
        out.write(": <td><select name='theme'>");
        String theme = _manager.getTheme();
        String[] themes = _manager.getThemes();
        for(int i = 0; i < themes.length; i++) {
            if(themes[i].equals(theme))
                out.write("\n<OPTION value=\"" + themes[i] + "\" SELECTED>" + themes[i]);
            else
                out.write("\n<OPTION value=\"" + themes[i] + "\">" + themes[i]);
        }
        out.write("</select>\n" +

                  "<tr><td>");
        out.write(_("Refresh time"));
        out.write(": <td><select name=\"refreshDelay\">");
        int delay = _manager.getRefreshDelaySeconds();
        for (int i = 0; i < times.length; i++) {
            out.write("<option value=\"");
            out.write(Integer.toString(times[i]));
            out.write("\"");
            if (times[i] == delay)
                out.write(" selected=\"true\"");
            out.write(">");
            if (times[i] > 0)
                out.write(DataHelper.formatDuration2(times[i] * 1000));
            else
                out.write(_("Never"));
            out.write("</option>\n");
        }
        out.write("</select><br>" +

                  "<tr><td>");
        out.write(_("Startup delay"));
        out.write(": <td><input name=\"startupDelay\" size=\"3\" class=\"r\" value=\"" + _manager.util().getStartupDelay() + "\"> ");
        out.write(_("minutes"));
        out.write("<br>\n"); 


        //Auto add: <input type="checkbox" name="autoAdd" value="true" title="If true, automatically add torrents that are found in the data directory" />
        //Auto stop: <input type="checkbox" name="autoStop" value="true" title="If true, automatically stop torrents that are removed from the data directory" />
        //out.write("<br>\n");
/*
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
        out.write("</select><br>\n");
*/
        out.write("<tr><td>");
        out.write(_("Total uploader limit"));
        out.write(": <td><input type=\"text\" name=\"upLimit\" class=\"r\" value=\""
                  + _manager.util().getMaxUploaders() + "\" size=\"3\" maxlength=\"3\" > ");
        out.write(_("peers"));
        out.write("<br>\n" +

                  "<tr><td>");
        out.write(_("Up bandwidth limit"));
        out.write(": <td><input type=\"text\" name=\"upBW\" class=\"r\" value=\""
                  + _manager.util().getMaxUpBW() + "\" size=\"3\" maxlength=\"3\" > KBps <i>(");
        out.write(_("Half available bandwidth recommended."));
        out.write(" <a href=\"/config.jsp\" target=\"blank\">");
        out.write(_("View or change router bandwidth"));
        out.write("</a>)</i><br>\n" +
        
                  "<tr><td>");
        out.write(_("Use open trackers also"));
        out.write(": <td><input type=\"checkbox\" class=\"optbox\" name=\"useOpenTrackers\" value=\"true\" " 
                  + (useOpenTrackers ? "checked " : "") 
                  + "title=\"");
        out.write(_("If checked, announce torrents to open trackers as well as the tracker listed in the torrent file"));
        out.write("\" > " +

                  "<tr><td>");
        out.write(_("Open tracker announce URLs"));
        out.write(": <td><input type=\"text\" name=\"openTrackers\" value=\""
                  + openTrackers + "\" size=\"50\" ><br>\n");

        //out.write("\n");
        //out.write("EepProxy host: <input type=\"text\" name=\"eepHost\" value=\""
        //          + _manager.util().getEepProxyHost() + "\" size=\"15\" /> ");
        //out.write("port: <input type=\"text\" name=\"eepPort\" value=\""
        //          + _manager.util().getEepProxyPort() + "\" size=\"5\" maxlength=\"5\" /><br>\n");

        Map<String, String> options = new TreeMap(_manager.util().getI2CPOptions());
        out.write("<tr><td>");
        out.write(_("Inbound Settings"));
        out.write(":<td>");
        out.write(renderOptions(1, 6, options.remove("inbound.quantity"), "inbound.quantity", TUNNEL));
        out.write("&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;");
        out.write(renderOptions(0, 4, options.remove("inbound.length"), "inbound.length", HOP));
        out.write("<tr><td>");
        out.write(_("Outbound Settings"));
        out.write(":<td>");
        out.write(renderOptions(1, 6, options.remove("outbound.quantity"), "outbound.quantity", TUNNEL));
        out.write("&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;");
        out.write(renderOptions(0, 4, options.remove("outbound.length"), "outbound.length", HOP));

        if (!_context.isRouterContext()) {
            out.write("<tr><td>");
            out.write(_("I2CP host"));
            out.write(": <td><input type=\"text\" name=\"i2cpHost\" value=\"" 
                      + _manager.util().getI2CPHost() + "\" size=\"15\" > " +

                      "<tr><td>");
            out.write(_("I2CP port"));
            out.write(": <td><input type=\"text\" name=\"i2cpPort\" class=\"r\" value=\"" +
                      + _manager.util().getI2CPPort() + "\" size=\"5\" maxlength=\"5\" > <br>\n");
        }

        options.remove(I2PSnarkUtil.PROP_MAX_BW);
        // was accidentally in the I2CP options prior to 0.8.9 so it will be in old config files
        options.remove(SnarkManager.PROP_OPENTRACKERS);
        StringBuilder opts = new StringBuilder(64);
        for (Map.Entry<String, String> e : options.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            opts.append(key).append('=').append(val).append(' ');
        }
        out.write("<tr><td>");
        out.write(_("I2CP options"));
        out.write(": <td><textarea name=\"i2cpOpts\" cols=\"60\" rows=\"1\" wrap=\"off\" spellcheck=\"false\" >"
                  + opts.toString() + "</textarea><br>\n" +

                  "<tr><td>&nbsp;<td><input type=\"submit\" value=\"");
        out.write(_("Save configuration"));
        out.write("\" name=\"foo\" >\n" +
                  "</table></div></div></form>");
    }
    
    private void writeConfigLink(PrintWriter out) throws IOException {
        out.write("<div class=\"configsection\"><span class=\"snarkConfig\">\n" +
                  "<span class=\"snarkConfigTitle\"><a href=\"configure\">" +
                  "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "config.png\"> ");
        out.write(_("Configuration"));
        out.write("</a></span></span></div>\n");
    }

    /**
     *  @param url in base32 or hex, xt must be first magnet param
     *  @since 0.8.4
     */
    private void addMagnet(String url) {
        String ihash;
        String name;
        String trackerURL = null;
        if (url.startsWith(MAGNET)) {
            // magnet:?xt=urn:btih:0691e40aae02e552cfcb57af1dca56214680c0c5&tr=http://tracker2.postman.i2p/announce.php
            String xt = getParam("xt", url);
            if (xt == null || !xt.startsWith("urn:btih:")) {
                _manager.addMessage(_("Invalid magnet URL {0}", url));
                return;
            }
            ihash = xt.substring("urn:btih:".length());
            trackerURL = getParam("tr", url);
            name = "Magnet " + ihash;
            String dn = getParam("dn", url);
            if (dn != null)
                name += " (" + Storage.filterName(dn) + ')';
        } else if (url.startsWith(MAGGOT)) {
            // maggot://0691e40aae02e552cfcb57af1dca56214680c0c5:0b557bbdf8718e95d352fbe994dec3a383e2ede7
            ihash = url.substring(MAGGOT.length()).trim();
            int col = ihash.indexOf(':');
            if (col >= 0)
                ihash = ihash.substring(0, col);
            name = "Maggot " + ihash;
        } else {
            return;
        }
        byte[] ih = null;
        if (ihash.length() == 32) {
            ih = Base32.decode(ihash);
        } else if (ihash.length() == 40) {
            //  Like DataHelper.fromHexString() but ensures no loss of leading zero bytes
            ih = new byte[20];
            try {
                for (int i = 0; i < 20; i++) {
                    ih[i] = (byte) (Integer.parseInt(ihash.substring(i*2, (i*2) + 2), 16) & 0xff);
                }
            } catch (NumberFormatException nfe) {
                ih = null;
            }
        }
        if (ih == null || ih.length != 20) {
            _manager.addMessage(_("Invalid info hash in magnet URL {0}", url));
            return;
        }
        _manager.addMagnet(name, ih, trackerURL, true);
    }

    private static String getParam(String key, String uri) {
        int idx = uri.indexOf('?' + key + '=');
        if (idx >= 0) {
            idx += key.length() + 2;
        } else {
            idx = uri.indexOf('&' + key + '=');
            if (idx >= 0)
                idx += key.length() + 2;
        }
        if (idx < 0 || idx > uri.length())
            return null;
        String rv = uri.substring(idx);
        idx = rv.indexOf('&');
        if (idx >= 0)
            rv = rv.substring(0, idx);
        else
            rv = rv.trim();
        return rv;
    }

    /** copied from ConfigTunnelsHelper */
    private static final String HOP = "hop";
    private static final String TUNNEL = "tunnel";
    /** dummies for translation */
    private static final String HOPS = ngettext("1 hop", "{0} hops");
    private static final String TUNNELS = ngettext("1 tunnel", "{0} tunnels");
    /** prevents the ngettext line below from getting tagged */
    private static final String DUMMY0 = "{0} ";
    private static final String DUMMY1 = "1 ";

    /** modded from ConfigTunnelsHelper @since 0.7.14 */
    private String renderOptions(int min, int max, String strNow, String selName, String name) {
        int now = 2;
        try {
            now = Integer.parseInt(strNow);
        } catch (Throwable t) {}
        StringBuilder buf = new StringBuilder(128);
        buf.append("<select name=\"").append(selName).append("\">\n");
        for (int i = min; i <= max; i++) {
            buf.append("<option value=\"").append(i).append("\" ");
            if (i == now)
                buf.append("selected=\"true\" ");
            // constants to prevent tagging
            buf.append(">").append(ngettext(DUMMY1 + name, DUMMY0 + name + 's', i));
            buf.append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }

    /** translate */
    private String _(String s) {
        return _manager.util().getString(s);
    }

    /** translate */
    private String _(String s, Object o) {
        return _manager.util().getString(s, o);
    }

    /** translate */
    private String _(String s, Object o, Object o2) {
        return _manager.util().getString(s, o, o2);
    }

    /** translate (ngettext) @since 0.7.14 */
    private String ngettext(String s, String p, int n) {
        return _manager.util().getString(n, s, p);
    }

    /** dummy for tagging */
    private static String ngettext(String s, String p) {
        return null;
    }

    // rounding makes us look faster :)
    private static String formatSize(long bytes) {
        if (bytes < 5000)
            return bytes + "&nbsp;B";
        else if (bytes < 5*1024*1024)
            return ((bytes + 512)/1024) + "&nbsp;KB";
        else if (bytes < 10*1024*1024*1024l)
            return ((bytes + 512*1024)/(1024*1024)) + "&nbsp;MB";
        else
            return ((bytes + 512*1024*1024)/(1024*1024*1024)) + "&nbsp;GB";
    }
    
    /** @since 0.7.14 */
    private static String urlify(String s) {
        StringBuilder buf = new StringBuilder(256);
        // browsers seem to work without doing this but let's be strict
        String link = s.replace("&", "&amp;").replace(" ", "%20");
        buf.append("<a href=\"").append(link).append("\">").append(link).append("</a>");
        return buf.toString();
    }

    private static final String DOCTYPE = "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\">\n";
    private static final String HEADER_A = "<link href=\"";
    private static final String HEADER_B = "snark.css\" rel=\"stylesheet\" type=\"text/css\" >";


    private static final String TABLE_HEADER = "<table border=\"0\" class=\"snarkTorrents\" width=\"100%\" >\n" +
                                               "<thead>\n" +
                                               "<tr><th colspan=\"2\">";

    private static final String FOOTER = "</div></center></body></html>";

    /**
     * Modded heavily from the Jetty version in Resource.java,
     * pass Resource as 1st param
     * All the xxxResource constructors are package local so we can't extend them.
     *
     * <pre>
      // ========================================================================
      // $Id: Resource.java,v 1.32 2009/05/16 01:53:36 gregwilkins Exp $
      // Copyright 1996-2004 Mort Bay Consulting Pty. Ltd.
      // ------------------------------------------------------------------------
      // Licensed under the Apache License, Version 2.0 (the "License");
      // you may not use this file except in compliance with the License.
      // You may obtain a copy of the License at 
      // http://www.apache.org/licenses/LICENSE-2.0
      // Unless required by applicable law or agreed to in writing, software
      // distributed under the License is distributed on an "AS IS" BASIS,
      // WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      // See the License for the specific language governing permissions and
      // limitations under the License.
      // ========================================================================
     * </pre>
     *
     * Get the resource list as a HTML directory listing.
     * @param r The Resource
     * @param base The base URL
     * @param parent True if the parent directory should be included
     * @param postParams map of POST parameters or null if not a POST
     * @return String of HTML
     * @since 0.7.14
     */
    private String getListHTML(Resource r, String base, boolean parent, Map postParams)
        throws IOException
    {
        String[] ls = null;
        if (r.isDirectory()) {
            ls = r.list();
            Arrays.sort(ls, Collator.getInstance());
        }  // if r is not a directory, we are only showing torrent info section
        
        StringBuilder buf=new StringBuilder(4096);
        buf.append(DOCTYPE + "<HTML><HEAD><TITLE>");
        String title = URI.decodePath(base);
        if (title.startsWith("/i2psnark/"))
            title = title.substring("/i2psnark/".length());

        // Get the snark associated with this directory
        String torrentName;
        int slash = title.indexOf('/');
        if (slash > 0)
            torrentName = title.substring(0, slash);
        else
            torrentName = title;
        Snark snark = _manager.getTorrentByBaseName(torrentName);

        if (snark != null && postParams != null)
            savePriorities(snark, postParams);

        if (title.endsWith("/"))
            title = title.substring(0, title.length() - 1);
        String directory = title;
        title = _("Torrent") + ": " + title;
        buf.append(title);
        buf.append("</TITLE>").append(HEADER_A).append(_themePath).append(HEADER_B).append("<link rel=\"shortcut icon\" href=\"" + _themePath + "favicon.ico\">" +
             "</HEAD><BODY>\n<center><div class=\"snarknavbar\"><a href=\"/i2psnark/\" title=\"Torrents\"");
        buf.append(" class=\"snarkRefresh\"><img alt=\"\" border=\"0\" src=\"" + _imgPath + "arrow_refresh.png\">&nbsp;&nbsp;I2PSnark</a></div></center>\n");
        
        if (parent)  // always true
            buf.append("<div class=\"page\"><div class=\"mainsection\">");
        boolean showPriority = ls != null && snark != null && snark.getStorage() != null && !snark.getStorage().complete();
        if (showPriority)
            buf.append("<form action=\"").append(base).append("\" method=\"POST\">\n");
        buf.append("<TABLE BORDER=0 class=\"snarkTorrents\" ><thead>");
        if (snark != null) {
            // first row - torrent info
            // FIXME center
            buf.append("<tr><th colspan=\"" + (showPriority ? '4' : '3') + "\"><div>")
                .append(_("Torrent")).append(": ").append(snark.getBaseName());
            int pieces = snark.getPieces();
            double completion = (pieces - snark.getNeeded()) / (double) pieces;
            if (completion < 1.0)
                buf.append("<br>").append(_("Completion")).append(": ").append((new DecimalFormat("0.00%")).format(completion));
            else
                buf.append("<br>").append(_("Complete"));
            // else unknown
            buf.append("<br>").append(_("Size")).append(": ").append(formatSize(snark.getTotalLength()));
            MetaInfo meta = snark.getMetaInfo();
            if (meta != null) {
                List files = meta.getFiles();
                int fileCount = files != null ? files.size() : 1;
                buf.append("<br>").append(_("Files")).append(": ").append(fileCount);
            }
            buf.append("<br>").append(_("Pieces")).append(": ").append(pieces);
            buf.append("<br>").append(_("Piece size")).append(": ").append(formatSize(snark.getPieceLength(0)));

            if (meta != null) {
                String announce = meta.getAnnounce();
                if (announce != null) {
                    buf.append("<br>");
                    String trackerLink = getTrackerLink(announce, snark.getInfoHash());
                    if (trackerLink != null)
                        buf.append(trackerLink).append(' ');
                    buf.append(_("Tracker")).append(": ");
                    if (announce.startsWith("http://"))
                        announce = announce.substring(7);
                    int slsh = announce.indexOf('/');
                    if (slsh > 0)
                        announce = announce.substring(0, slsh);
                    if (announce.length() > 67)
                        announce = announce.substring(0, 40) + "&hellip;" + announce.substring(announce.length() - 8);
                    buf.append(announce);
                }
            }

            String hex = I2PSnarkUtil.toHex(snark.getInfoHash());
            buf.append("<br>").append(toImg("magnet", _("Magnet link"))).append(" <a href=\"")
               .append(MAGNET_FULL).append(hex).append("\">")
               .append(MAGNET_FULL).append(hex).append("</a>");
            // We don't have the hash of the torrent file
            //buf.append("<br>").append(_("Maggot link")).append(": <a href=\"").append(MAGGOT).append(hex).append(':').append(hex).append("\">")
            //   .append(MAGGOT).append(hex).append(':').append(hex).append("</a>");
            buf.append("</div></th></tr>");
        }
        if (ls == null) {
            // We are only showing the torrent info section
            buf.append("</thead></table></div></div></BODY></HTML>");
            return buf.toString();
        }

        // second row - dir info
        buf.append("<tr><th>")
            .append("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "file.png\" >&nbsp;")
            .append(_("Directory")).append(": ").append(directory).append("</th><th align=\"right\">")
            .append("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "size.png\" >&nbsp;")
            .append(_("Size"));
        buf.append("</th><th class=\"headerstatus\">")
            .append("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "status.png\" >&nbsp;")
            .append(_("Status")).append("</th>");
        if (showPriority)
            buf.append("<th class=\"headerpriority\">")
            .append("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "priority.png\" >&nbsp;")
            .append(_("Priority")).append("</th>");
        buf.append("</tr></thead>\n");
        buf.append("<tr><td colspan=\"" + (showPriority ? '4' : '3') + "\" class=\"ParentDir\"><A HREF=\"");
        buf.append(URI.addPaths(base,"../"));
        buf.append("\"><img alt=\"\" border=\"0\" src=\"" + _imgPath + "up.png\"> ")
            .append(_("Up to higher level directory")).append("</A></td></tr>\n");


        //DateFormat dfmt=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
        //                                               DateFormat.MEDIUM);
        boolean showSaveButton = false;
        for (int i=0 ; i< ls.length ; i++)
        {   
            String encoded=URI.encodePath(ls[i]);
            // bugfix for I2P - Backport from Jetty 6 (zero file lengths and last-modified times)
            // http://jira.codehaus.org/browse/JETTY-361?page=com.atlassian.jira.plugin.system.issuetabpanels%3Achangehistory-tabpanel#issue-tabs
            // See resource.diff attachment
            //Resource item = addPath(encoded);
            Resource item = r.addPath(ls[i]);
            
            String rowClass = (i % 2 == 0 ? "snarkTorrentEven" : "snarkTorrentOdd");
            buf.append("<TR class=\"").append(rowClass).append("\"><TD class=\"snarkFileName ")
               .append(rowClass).append("\">");
            
            // Get completeness and status string
            boolean complete = false;
            String status = "";
            long length = item.length();
            if (item.isDirectory()) {
                complete = true;
                status = toImg("tick") + ' ' + _("Directory");
            } else {
                if (snark == null || snark.getStorage() == null) {
                    // Assume complete, perhaps he removed a completed torrent but kept a bookmark
                    complete = true;
                    status = toImg("cancel") + ' ' + _("Torrent not found?");
                } else {
                    Storage storage = snark.getStorage();
                    try {
                        File f = item.getFile();
                        if (f != null) {
                            long remaining = storage.remaining(f.getCanonicalPath());
                            if (remaining < 0) {
                                complete = true;
                                status = toImg("cancel") + ' ' + _("File not found in torrent?");
                            } else if (remaining == 0 || length <= 0) {
                                complete = true;
                                status = toImg("tick") + ' ' + _("Complete");
                            } else {
                                int priority = storage.getPriority(f.getCanonicalPath());
                                if (priority < 0)
                                    status = toImg("cancel");
                                else if (priority == 0)
                                    status = toImg("clock");
                                else
                                    status = toImg("clock_red");
                                status += " " +
                                         (100 * (length - remaining) / length) + "% " + _("complete") +
                                         " (" + DataHelper.formatSize2(remaining) + _("bytes remaining") + ")";
                            }
                        } else {
                            status = "Not a file?";
                        }
                    } catch (IOException ioe) {
                        status = "Not a file? " + ioe;
                    }
                }
            }

            String path=URI.addPaths(base,encoded);
            if (item.isDirectory() && !path.endsWith("/"))
                path=URI.addPaths(path,"/");
            String icon = toIcon(item);

            if (complete) {
                buf.append("<a href=\"").append(path).append("\">");
                // thumbnail ?
                String plc = item.toString().toLowerCase(Locale.US);
                if (plc.endsWith(".jpg") || plc.endsWith(".jpeg") || plc.endsWith(".png") ||
                    plc.endsWith(".gif") || plc.endsWith(".ico")) {
                    buf.append("<img alt=\"\" border=\"0\" class=\"thumb\" src=\"")
                       .append(path).append("\"></a> ");
                } else {
                    buf.append(toImg(icon, _("Open"))).append("</a> ");
                }
                buf.append("<A HREF=\"");
                buf.append(path);
                buf.append("\">");
            } else {
                buf.append(toImg(icon)).append(' ');
            }
            buf.append(ls[i]);
            if (complete)
                buf.append("</a>");
            buf.append("</TD><TD ALIGN=right class=\"").append(rowClass).append(" snarkFileSize\">");
            if (!item.isDirectory())
                buf.append(DataHelper.formatSize2(length)).append('B');
            buf.append("</TD><TD class=\"").append(rowClass).append(" snarkFileStatus\">");
            //buf.append(dfmt.format(new Date(item.lastModified())));
            buf.append(status);
            buf.append("</TD>");
            if (showPriority) {
                buf.append("<td class=\"priority\">");
                File f = item.getFile();
                if ((!complete) && (!item.isDirectory()) && f != null) {
                    int pri = snark.getStorage().getPriority(f.getCanonicalPath());
                    buf.append("<input type=\"radio\" value=\"5\" name=\"pri.").append(f.getCanonicalPath()).append("\" ");
                    if (pri > 0)
                        buf.append("checked=\"true\"");
                    buf.append('>').append(_("High"));

                    buf.append("<input type=\"radio\" value=\"0\" name=\"pri.").append(f.getCanonicalPath()).append("\" ");
                    if (pri == 0)
                        buf.append("checked=\"true\"");
                    buf.append('>').append(_("Normal"));

                    buf.append("<input type=\"radio\" value=\"-9\" name=\"pri.").append(f.getCanonicalPath()).append("\" ");
                    if (pri < 0)
                        buf.append("checked=\"true\"");
                    buf.append('>').append(_("Skip"));
                    showSaveButton = true;
                }
                buf.append("</td>");
            }
            buf.append("</TR>\n");
        }
        if (showSaveButton) {
            buf.append("<thead><tr><th colspan=\"3\">&nbsp;</th><th class=\"headerpriority\"><input type=\"submit\" value=\"");
            buf.append(_("Save priorities"));
            buf.append("\" name=\"foo\" ></th></tr></thead>\n");
        }
        buf.append("</TABLE>\n");
        if (showPriority)
	    buf.append("</form>");
	buf.append("</div></div></BODY></HTML>\n");
        
        return buf.toString();
    }

    /** @since 0.7.14 */
    private String toIcon(Resource item) {
        if (item.isDirectory())
            return "folder";
        return toIcon(item.toString());
    }

    /**
     *  Pick an icon; try to catch the common types in an i2p environment
     *  @return file name not including ".png"
     *  @since 0.7.14
     */
    private String toIcon(String path) {
        String icon;
        // Should really just add to the mime.properties file in org.mortbay.jetty.jar
        // instead of this mishmash. We can't get to HttpContext.setMimeMapping()
        // from here? We could do it from a web.xml perhaps.
        // Or could we put our own org/mortbay/http/mime.properties file in the war?
        String plc = path.toLowerCase(Locale.US);
        String mime = getServletContext().getMimeType(path);
        if (mime == null)
            mime = "";
        if (mime.equals("text/html"))
            icon = "html";
        else if (mime.equals("text/plain") || plc.endsWith(".nfo") ||
                 mime.equals("application/rtf"))
            icon = "page";
        else if (mime.equals("application/java-archive") || plc.endsWith(".war") ||
                 plc.endsWith(".deb"))
            icon = "package";
        else if (plc.endsWith(".xpi2p"))
            icon = "plugin";
        else if (mime.equals("application/pdf"))
            icon = "page_white_acrobat";
        else if (mime.startsWith("image/") || plc.endsWith(".ico"))
            icon = "photo";
        else if (mime.startsWith("audio/") || mime.equals("application/ogg") ||
                 plc.endsWith(".flac") || plc.endsWith(".m4a") || plc.endsWith(".wma") ||
                 plc.endsWith(".ape") || plc.endsWith(".oga"))
            icon = "music";
        else if (mime.startsWith("video/") || plc.endsWith(".mkv") || plc.endsWith(".m4v") ||
                 plc.endsWith(".mp4") || plc.endsWith(".wmv") || plc.endsWith(".flv") ||
                 plc.endsWith(".ogm") || plc.endsWith(".ogv"))
            icon = "film";
        else if (mime.equals("application/zip") || mime.equals("application/x-gtar") ||
                 mime.equals("application/compress") || mime.equals("application/gzip") ||
                 mime.equals("application/x-tar") ||
                 plc.endsWith(".rar") || plc.endsWith(".bz2") || plc.endsWith(".7z"))
            icon = "compress";
        else if (plc.endsWith(".exe"))
            icon = "application";
        else if (plc.endsWith(".iso"))
            icon = "cd";
        else
            icon = "page_white";
        return icon;
    }
    
    /** @since 0.7.14 */
    private static String toImg(String icon) {
        return "<img alt=\"\" height=\"16\" width=\"16\" src=\"/i2psnark/_icons/" + icon + ".png\">";
    }

    /** @since 0.8.2 */
    private static String toImg(String icon, String altText) {
        return "<img alt=\"" + altText + "\" height=\"16\" width=\"16\" src=\"/i2psnark/_icons/" + icon + ".png\">";
    }

    /** @since 0.8.1 */
    private void savePriorities(Snark snark, Map postParams) {
        Storage storage = snark.getStorage();
        if (storage == null)
            return;
        Set<Map.Entry> entries = postParams.entrySet();
        for (Map.Entry entry : entries) {
            String key = (String)entry.getKey();
            if (key.startsWith("pri.")) {
                try {
                    String file = key.substring(4);
                    String val = ((String[])entry.getValue())[0];   // jetty arrays
                    int pri = Integer.parseInt(val);
                    storage.setPriority(file, pri);
                    //System.err.println("Priority now " + pri + " for " + file);
                } catch (Throwable t) { t.printStackTrace(); }
            }
        }
         snark.updatePiecePriorities();
        _manager.saveTorrentStatus(snark.getMetaInfo(), storage.getBitField(), storage.getFilePriorities());
    }


/** inner class, don't bother reindenting */
private static class FetchAndAdd implements Runnable {
    private SnarkManager _manager;
    private String _url;
    public FetchAndAdd(SnarkManager mgr, String url) {
        _manager = mgr;
        _url = url;
    }
    public void run() {
        _url = _url.trim();
        // 3 retries
        File file = _manager.util().get(_url, false, 3);
        try {
            if ( (file != null) && (file.exists()) && (file.length() > 0) ) {
                _manager.addMessage(_("Torrent fetched from {0}", urlify(_url)));
                FileInputStream in = null;
                try {
                    in = new FileInputStream(file);
                    byte[] fileInfoHash = new byte[20];
                    String name = MetaInfo.getNameAndInfoHash(in, fileInfoHash);
                    try { in.close(); } catch (IOException ioe) {}
                    Snark snark = _manager.getTorrentByInfoHash(fileInfoHash);
                    if (snark != null) {
                        _manager.addMessage(_("Torrent with this info hash is already running: {0}", snark.getBaseName()));
                        return;
                    }

                    name = Storage.filterName(name);
                    name = name + ".torrent";
                    File torrentFile = new File(_manager.getDataDir(), name);

                    String canonical = torrentFile.getCanonicalPath();

                    if (torrentFile.exists()) {
                        if (_manager.getTorrent(canonical) != null)
                            _manager.addMessage(_("Torrent already running: {0}", name));
                        else
                            _manager.addMessage(_("Torrent already in the queue: {0}", name));
                    } else {
                        // This may take a LONG time to create the storage.
                        _manager.copyAndAddTorrent(file, canonical);
                    }
                } catch (IOException ioe) {
                    _manager.addMessage(_("Torrent at {0} was not valid", urlify(_url)) + ": " + ioe.getMessage());
                } catch (OutOfMemoryError oom) {
                    _manager.addMessage(_("ERROR - Out of memory, cannot create torrent from {0}", urlify(_url)) + ": " + oom.getMessage());
                } finally {
                    try { if (in != null) in.close(); } catch (IOException ioe) {}
                }
            } else {
                _manager.addMessage(_("Torrent was not retrieved from {0}", urlify(_url)));
            }
        } finally {
            if (file != null) file.delete();
        }
    }

    private String _(String s, String o) {
        return _manager.util().getString(s, o);
    }

}

}
