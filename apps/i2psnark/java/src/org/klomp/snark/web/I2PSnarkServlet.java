package org.klomp.snark.web;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.text.Collator;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Log;

import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.MagnetURI;
import org.klomp.snark.MetaInfo;
import org.klomp.snark.Peer;
import org.klomp.snark.PeerID;
import org.klomp.snark.Snark;
import org.klomp.snark.SnarkManager;
import org.klomp.snark.Storage;
import org.klomp.snark.Tracker;
import org.klomp.snark.TrackerClient;
import org.klomp.snark.dht.DHT;

/**
 *  Refactored to eliminate Jetty dependencies.
 */
public class I2PSnarkServlet extends BasicServlet {

    private static final long serialVersionUID = 1L;
    /** generally "/i2psnark" */
    private String _contextPath;
    /** generally "i2psnark" */
    private String _contextName;
    private transient SnarkManager _manager;
    private long _nonce;
    private String _themePath;
    private String _imgPath;
    private String _lastAnnounceURL;
    
    private static final String DEFAULT_NAME = "i2psnark";
    public static final String PROP_CONFIG_FILE = "i2psnark.configFile";
    private static final String WARBASE = "/.icons/";
    private static final char HELLIP = '\u2026';
 
    public I2PSnarkServlet() {
        super();
    }

    @Override
    public void init(ServletConfig cfg) throws ServletException {
        super.init(cfg);
        String cpath = getServletContext().getContextPath();
        _contextPath = cpath == "" ? "/" : cpath;
        _contextName = cpath == "" ? DEFAULT_NAME : cpath.substring(1).replace("/", "_");
        _nonce = _context.random().nextLong();
        // limited protection against overwriting other config files or directories
        // in case you named your war "router.war"
        // We don't handle bad characters in the context path. Don't do that.
        String configName = _contextName;
        if (!configName.equals(DEFAULT_NAME))
            configName = DEFAULT_NAME + '_' + _contextName;
        _manager = new SnarkManager(_context, _contextPath, configName);
        String configFile = _context.getProperty(PROP_CONFIG_FILE);
        if ( (configFile == null) || (configFile.trim().length() <= 0) )
            configFile = configName + ".config";
        _manager.loadConfig(configFile);
        _manager.start();
        loadMimeMap("org/klomp/snark/web/mime");
        setResourceBase(_manager.getDataDir());
        setWarBase(WARBASE);
    }
    
    @Override
    public void destroy() {
        if (_manager != null)
            _manager.stop();
        super.destroy();
    }

    /**
     *  We override this to set the file relative to the storage dirctory
     *  for the torrent.
     *
     *  @param pathInContext should always start with /
     */
    @Override
    public File getResource(String pathInContext)
    {
        if (pathInContext == null || pathInContext.equals("/") || pathInContext.equals("/index.jsp") ||
            !pathInContext.startsWith("/") || pathInContext.length() == 0 ||
            pathInContext.equals("/index.html") || pathInContext.startsWith(WARBASE))
            return super.getResource(pathInContext);
        // files in the i2psnark/ directory
        // get top level
        pathInContext = pathInContext.substring(1);
        File top = new File(pathInContext);
        File parent;
        while ((parent = top.getParentFile()) != null) {
            top = parent;
        }
        Snark snark = _manager.getTorrentByBaseName(top.getPath());
        if (snark != null) {
            Storage storage = snark.getStorage();
            if (storage != null) {
                File sbase = storage.getBase();
                String child = pathInContext.substring(top.getPath().length());
                return new File(sbase, child);
            }
        }
        return new File(_resourceBase, pathInContext);
    }

    /**
     *  Handle what we can here, calling super.doGet() for the rest.
     *  @since 0.8.3
     */
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGetAndPost(request, response);
    }

    /**
     *  Handle what we can here, calling super.doPost() for the rest.
     *  @since Jetty 7
     */
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGetAndPost(request, response);
    }

    /**
     * Handle what we can here, calling super.doGet() or super.doPost() for the rest.
     *
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
    private void doGetAndPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Service " + req.getMethod() + " \"" + req.getContextPath() + "\" \"" + req.getServletPath() + "\" \"" + req.getPathInfo() + '"');
        // since we are not overriding handle*(), do this here
        String method = req.getMethod();
        // this is the part after /i2psnark
        String path = req.getServletPath();

        // in-war icons etc.
        if (path != null && path.startsWith(WARBASE)) {
            if (method.equals("GET") || method.equals("HEAD"))
                super.doGet(req, resp);
            else  // no POST either
                resp.sendError(405);
        }

        _themePath = "/themes/snark/" + _manager.getTheme() + '/';
        _imgPath = _themePath + "images/";
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        resp.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'");
        resp.setHeader("X-XSS-Protection", "1; mode=block");

        String peerParam = req.getParameter("p");
        String stParam = req.getParameter("st");
        String peerString;
        if (peerParam == null || (!_manager.util().connected()) ||
            peerParam.replaceAll("[a-zA-Z0-9~=-]", "").length() > 0) {  // XSS
            peerString = "";
        } else {
            peerString = "?p=" + DataHelper.stripHTML(peerParam);
        }
        if (stParam != null && !stParam.equals("0")) {
            stParam = DataHelper.stripHTML(stParam);
            if (peerString.length() > 0)
                peerString += "&amp;st=" + stParam;
            else
                peerString =  "?st="+ stParam;
        }

        // AJAX for mainsection
        if ("/.ajax/xhr1.html".equals(path)) {
            resp.setCharacterEncoding("UTF-8");
            resp.setContentType("text/html; charset=UTF-8");
            PrintWriter out = resp.getWriter();
            //if (_log.shouldLog(Log.DEBUG))
            //    _manager.addMessage((_context.clock().now() / 1000) + " xhr1 p=" + req.getParameter("p"));
            writeMessages(out, false, peerString);
            writeTorrents(out, req);
            return;
        }

        boolean isConfigure = "/configure".equals(path);
        // index.jsp doesn't work, it is grabbed by the war handler before here
        if (!(path == null || path.equals("/") || path.equals("/index.jsp") ||
              path.equals("/index.html") || path.equals("/_post") || isConfigure)) {
            if (path.endsWith("/")) {
                // Listing of a torrent (torrent detail page)
                // bypass the horrid Resource.getListHTML()
                String pathInfo = req.getPathInfo();
                String pathInContext = addPaths(path, pathInfo);
                req.setCharacterEncoding("UTF-8");
                resp.setCharacterEncoding("UTF-8");
                resp.setContentType("text/html; charset=UTF-8");
                File resource = getResource(pathInContext);
                if (resource == null) {
                    resp.sendError(404);
                } else {
                    String base = addPaths(req.getRequestURI(), "/");
                    String listing = getListHTML(resource, base, true, method.equals("POST") ? req.getParameterMap() : null);
                    if (method.equals("POST")) {
                        // P-R-G
                        sendRedirect(req, resp, "");
                    } else if (listing != null) {
                        resp.getWriter().write(listing);
                    } else { // shouldn't happen
                        resp.sendError(404);
                    }
                }
            } else {
                // local completed files in torrent directories
                if (method.equals("GET") || method.equals("HEAD"))
                    super.doGet(req, resp);
                else if (method.equals("POST"))
                    super.doPost(req, resp);
                else
                    resp.sendError(405);
            }
            return;
        }

        // Either the main page or /configure

        req.setCharacterEncoding("UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html; charset=UTF-8");
        
        String nonce = req.getParameter("nonce");
        if (nonce != null) {
            if (nonce.equals(String.valueOf(_nonce)))
                processRequest(req);
            else  // nonce is constant, shouldn't happen
                _manager.addMessage("Please retry form submission (bad nonce)");
            // P-R-G (or G-R-G to hide the params from the address bar)
            sendRedirect(req, resp, peerString);
            return;	
        }
        
        PrintWriter out = resp.getWriter();
        out.write(DOCTYPE + "<html>\n" +
                  "<head><link rel=\"shortcut icon\" href=\"" + _themePath + "favicon.ico\">\n" +
                  "<title>");
        if (_contextName.equals(DEFAULT_NAME))
            out.write(_("I2PSnark"));
        else
            out.write(_contextName);
        out.write(" - ");
        if (isConfigure)
            out.write(_("Configuration"));
        else
            out.write(_("Anonymous BitTorrent Client"));
        if ("2".equals(peerParam))
            out.write(" | Debug Mode");
        out.write("</title>\n");
                                         
        // we want it to go to the base URI so we don't refresh with some funky action= value
        int delay = 0;
        if (!isConfigure) {
            delay = _manager.getRefreshDelaySeconds();
            if (delay > 0) {
                //out.write("<meta http-equiv=\"refresh\" content=\"" + delay + ";/i2psnark/" + peerString + "\">\n");
                out.write("<script src=\"/js/ajax.js\" type=\"text/javascript\"></script>\n" +
                          "<script type=\"text/javascript\">\n"  +
                          "var failMessage = \"<div class=\\\"routerdown\\\"><b>" + _("Router is down") + "<\\/b><\\/div>\";\n" +
                          "function requestAjax1() { ajax(\"" + _contextPath + "/.ajax/xhr1.html" +
                          peerString.replace("&amp;", "&") +  // don't html escape in js
                          "\", \"mainsection\", " + (delay*1000) + "); }\n" +
                          "function initAjax() { setTimeout(requestAjax1, " + (delay*1000) +");  }\n"  +
                          "</script>\n");
            }
        }
        out.write(HEADER_A + _themePath + HEADER_B + "</head>\n");
        if (isConfigure || delay <= 0)
            out.write("<body>");
        else
            out.write("<body onload=\"initAjax()\">");
        out.write("<center>");
        List<Tracker> sortedTrackers = null;
        if (isConfigure) {
            out.write("<div class=\"snarknavbar\"><a href=\"" + _contextPath + "/\" title=\"");
            out.write(_("Torrents"));
            out.write("\" class=\"snarkRefresh\">");
            out.write("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "arrow_refresh.png\">&nbsp;&nbsp;");
            if (_contextName.equals(DEFAULT_NAME))
                out.write(_("I2PSnark"));
            else
                out.write(_contextName);
            out.write("</a>");
        } else {
            out.write("<div class=\"snarknavbar\"><a href=\"" + _contextPath + '/' + peerString + "\" title=\"");
            out.write(_("Refresh page"));
            out.write("\" class=\"snarkRefresh\">");
            out.write("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "arrow_refresh.png\">&nbsp;&nbsp;");
            if (_contextName.equals(DEFAULT_NAME))
                out.write(_("I2PSnark"));
            else
                out.write(_contextName);
            out.write("</a> <a href=\"http://forum.i2p/viewforum.php?f=21\" class=\"snarkRefresh\" target=\"_blank\">");
            out.write(_("Forum"));
            out.write("</a>\n");

            sortedTrackers = _manager.getSortedTrackers();
            for (Tracker t : sortedTrackers) {
                if (t.baseURL == null || !t.baseURL.startsWith("http"))
                    continue;
                out.write(" <a href=\"" + t.baseURL + "\" class=\"snarkRefresh\" target=\"_blank\">" + t.name + "</a>");
            }
        }
        out.write("</div>\n");
        String newURL = req.getParameter("newURL");
        if (newURL != null && newURL.trim().length() > 0 && req.getMethod().equals("GET"))
            _manager.addMessage(_("Click \"Add torrent\" button to fetch torrent"));
        out.write("<div class=\"page\"><div id=\"mainsection\" class=\"mainsection\">");

        writeMessages(out, isConfigure, peerString);

        if (isConfigure) {
            // end of mainsection div
            out.write("<div class=\"logshim\"></div></div>\n");
            writeConfigForm(out, req);
            writeTrackerForm(out, req);
        } else {
            boolean pageOne = writeTorrents(out, req);
            // end of mainsection div
            if (pageOne) {
                out.write("</div><div id=\"lowersection\">\n");
                writeAddForm(out, req);
                writeSeedForm(out, req, sortedTrackers);
                writeConfigLink(out);
                // end of lowersection div
            }
            out.write("</div>\n");
        }
        out.write(FOOTER);
    }

    private void writeMessages(PrintWriter out, boolean isConfigure, String peerString) throws IOException {
        List<String> msgs = _manager.getMessages();
        if (!msgs.isEmpty()) {
            out.write("<div class=\"snarkMessages\">");
            out.write("<a href=\"" + _contextPath + '/');
            if (isConfigure)
                out.write("configure");
            if (peerString.length() > 0)
                out.write(peerString + "&amp;");
            else
                out.write("?");
            out.write("action=Clear&amp;nonce=" + _nonce + "\">" +
                      "<img src=\"" + _imgPath + "delete.png\" title=\"" + _("clear messages") +
                      "\" alt=\"" + _("clear messages") + "\"></a>" +
                      "<ul>");
            for (int i = msgs.size()-1; i >= 0; i--) {
                String msg = msgs.get(i);
                out.write("<li>" + msg + "</li>\n");
            }
            out.write("</ul></div>");
        }
    }

    /**
     *  @return true if on first page
     */
    private boolean writeTorrents(PrintWriter out, HttpServletRequest req) throws IOException {
        /** dl, ul, down rate, up rate, peers, size */
        final long stats[] = {0,0,0,0,0,0};
        String peerParam = req.getParameter("p");
        String stParam = req.getParameter("st");

        List<Snark> snarks = getSortedSnarks(req);
        boolean isForm = _manager.util().connected() || !snarks.isEmpty();
        if (isForm) {
            out.write("<form action=\"_post\" method=\"POST\">\n");
            out.write("<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" >\n");
            // don't lose peer setting
            if (peerParam != null)
                out.write("<input type=\"hidden\" name=\"p\" value=\"" + peerParam + "\" >\n");
            // ...or st setting
            if (stParam != null)
                out.write("<input type=\"hidden\" name=\"st\" value=\"" + stParam + "\" >\n");
        }
        out.write(TABLE_HEADER);

        // Opera and text-mode browsers: no &thinsp; and no input type=image values submitted
        // Using a unique name fixes Opera, except for the buttons with js confirms, see below
        String ua = req.getHeader("User-Agent");
        boolean isDegraded = ua != null && (ua.startsWith("Lynx") || ua.startsWith("w3m") ||
                                            ua.startsWith("ELinks") || ua.startsWith("Links") ||
                                            ua.startsWith("Dillo") || ua.startsWith("Emacs-w3m"));
        boolean noThinsp = isDegraded || (ua != null && ua.startsWith("Opera"));

        // pages
        int start = 0;
        int total = snarks.size();
        if (stParam != null) {
            try {
                start = Math.max(0, Math.min(total - 1, Integer.parseInt(stParam)));
            } catch (NumberFormatException nfe) {}
        }
        int pageSize = Math.max(_manager.getPageSize(), 5);

        out.write("<tr><th><img border=\"0\" src=\"" + _imgPath + "status.png\" title=\"");
        out.write(_("Status"));
        out.write("\" alt=\"");
        out.write(_("Status"));
        out.write("\"></th>\n<th>");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            out.write(" <a href=\"" + _contextPath + '/');
            if (peerParam != null) {
                if (stParam != null) {
                    out.write("?st=");
                    out.write(stParam);
                }
                out.write("\">");
                out.write("<img border=\"0\" src=\"" + _imgPath + "hidepeers.png\" title=\"");
                out.write(_("Hide Peers"));
                out.write("\" alt=\"");
                out.write(_("Hide Peers"));
                out.write("\">");
            } else {
                out.write("?p=1");
                if (stParam != null) {
                    out.write("&amp;st=");
                    out.write(stParam);
                }
                out.write("\">");
                out.write("<img border=\"0\" src=\"" + _imgPath + "showpeers.png\" title=\"");
                out.write(_("Show Peers"));
                out.write("\" alt=\"");
                out.write(_("Show Peers"));
                out.write("\">");
            }
            out.write("</a><br>\n"); 
        }
        out.write("</th>\n<th colspan=\"2\" align=\"left\">");
        out.write("<img border=\"0\" src=\"" + _imgPath + "torrent.png\" title=\"");
        out.write(_("Torrent"));
        out.write("\" alt=\"");
        out.write(_("Torrent"));
        out.write("\"></th>\n<th align=\"center\">");
        if (total > 0 && (start > 0 || total > pageSize)) {
            writePageNav(out, start, pageSize, total, peerParam, noThinsp);
        }
        out.write("</th>\n<th align=\"right\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            out.write("<img border=\"0\" src=\"" + _imgPath + "eta.png\" title=\"");
            out.write(_("Estimated time remaining"));
            out.write("\" alt=\"");
            // Translators: Please keep short or translate as " "
            out.write(_("ETA"));
            out.write("\">");
        }
        out.write("</th>\n<th align=\"right\">");
        out.write("<img border=\"0\" src=\"" + _imgPath + "head_rx.png\" title=\"");
        out.write(_("Downloaded"));
        out.write("\" alt=\"");
        // Translators: Please keep short or translate as " "
        out.write(_("RX"));
        out.write("\">");
        out.write("</th>\n<th align=\"right\">");
        if (!snarks.isEmpty()) {
            out.write("<img border=\"0\" src=\"" + _imgPath + "head_tx.png\" title=\"");
            out.write(_("Uploaded"));
            out.write("\" alt=\"");
            // Translators: Please keep short or translate as " "
            out.write(_("TX"));
            out.write("\">");
        }
        out.write("</th>\n<th align=\"right\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            out.write("<img border=\"0\" src=\"" + _imgPath + "head_rxspeed.png\" title=\"");
            out.write(_("Down Rate"));
            out.write("\" alt=\"");
            // Translators: Please keep short or translate as " "
            out.write(_("RX Rate"));
            out.write(" \">");
        }
        out.write("</th>\n<th align=\"right\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            out.write("<img border=\"0\" src=\"" + _imgPath + "head_txspeed.png\" title=\"");
            out.write(_("Up Rate"));
            out.write("\" alt=\"");
            // Translators: Please keep short or translate as " "
            out.write(_("TX Rate"));
            out.write(" \">");
        }
        out.write("</th>\n<th align=\"center\">");

        if (_manager.isStopping()) {
            out.write("&nbsp;");
        } else if (_manager.util().connected()) {
            if (isDegraded)
                out.write("<a href=\"" + _contextPath + "/?action=StopAll&amp;nonce=" + _nonce + "\"><img title=\"");
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
            for (Snark s : snarks) {
                if (s.isStopped()) {
                    // show startall too
                    out.write("<br>");
                    if (isDegraded)
                        out.write("<a href=\"" + _contextPath + "/?action=StartAll&amp;nonce=" + _nonce + "\"><img title=\"");
                    else
                        out.write("<input type=\"image\" name=\"action_StartAll\" value=\"foo\" title=\"");
                    out.write(_("Start all stopped torrents"));
                    out.write("\" src=\"" + _imgPath + "start_all.png\" alt=\"");
                    out.write(_("Start All"));
                    out.write("\">");
                    if (isDegraded)
                        out.write("</a>");
                    break;
                }
            }
        } else if ((!_manager.util().isConnecting()) && !snarks.isEmpty()) {
            if (isDegraded)
                out.write("<a href=\"" + _contextPath + "/?action=StartAll&amp;nonce=" + _nonce + "\"><img title=\"");
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
        out.write("</th></tr>\n");
        out.write("</thead>\n");
        String uri = _contextPath + '/';
        boolean showDebug = "2".equals(peerParam);

        String stParamStr = stParam == null ? "" : "&amp;st=" + stParam;
        for (int i = 0; i < total; i++) {
            Snark snark = snarks.get(i);
            boolean showPeers = showDebug || "1".equals(peerParam) || Base64.encode(snark.getInfoHash()).equals(peerParam);
            boolean hide = i < start || i >= start + pageSize;
            displaySnark(out, snark, uri, i, stats, showPeers, isDegraded, noThinsp, showDebug, hide, stParamStr);
        }

        if (total == 0) {
            out.write("<tr class=\"snarkTorrentNoneLoaded\">" +
                      "<td class=\"snarkTorrentNoneLoaded\"" +
                      " colspan=\"11\"><i>");
            out.write(_("No torrents loaded."));
            out.write("</i></td></tr>\n");
        } else /** if (snarks.size() > 1) */ {
            out.write("<tfoot><tr>\n" +
                      "    <th align=\"left\" colspan=\"6\">");
            out.write("&nbsp;");
            out.write(_("Totals"));
            out.write(":&nbsp;");
            out.write(ngettext("1 torrent", "{0} torrents", total));
            out.write(", ");
            out.write(DataHelper.formatSize2(stats[5]) + "B");
            if (_manager.util().connected() && total > 0) {
                out.write(", ");
                out.write(ngettext("1 connected peer", "{0} connected peers", (int) stats[4]));
            }
            DHT dht = _manager.util().getDHT();
            if (dht != null) {
                int dhts = dht.size();
                if (dhts > 0) {
                    out.write(", ");
                    out.write(ngettext("1 DHT peer", "{0} DHT peers", dhts));
                }
                if (showDebug)
                    out.write(dht.renderStatusHTML());
            }
            out.write("</th>\n");
            if (_manager.util().connected() && total > 0) {
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
        return start == 0;
    }
    
    /**
     *  @since 0.9.6
     */
    private void writePageNav(PrintWriter out, int start, int pageSize, int total,
                              String peerParam, boolean noThinsp) {
            // Page nav
            if (start > 0) {
                // First
                out.write("<a href=\"" + _contextPath);
                if (peerParam != null)
                    out.write("?p=" + peerParam);
                out.write("\">" +
                          "<img alt=\"" + _("First") + "\" title=\"" + _("First page") + "\" border=\"0\" src=\"" +
                          _imgPath + "control_rewind_blue.png\">" +
                          "</a>&nbsp;");
                int prev = Math.max(0, start - pageSize);
                //if (prev > 0) {
                if (true) {
                    // Back
                    out.write("&nbsp;<a href=\"" + _contextPath +  "?st=" + prev);
                    if (peerParam != null)
                        out.write("&amp;p=" + peerParam);
                    out.write("\">" +
                          "<img alt=\"" + _("Prev") + "\" title=\"" + _("Previous page") + "\" border=\"0\" src=\"" +
                          _imgPath + "control_back_blue.png\">" +
                          "</a>&nbsp;");
                }
            } else {
                out.write(
                          "<img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "control_rewind_blue.png\">" +
                          "&nbsp;" +
                          "<img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "control_back_blue.png\">" +
                          "&nbsp;");
            }
            // Page count
            int pages = 1 + ((total - 1) / pageSize);
            if (pages == 1 && start > 0)
                pages = 2;
            if (pages > 1) {
                int page;
                if (start + pageSize >= total)
                    page = pages;
                else
                    page = 1 + (start / pageSize);
                //out.write("&nbsp;" + _("Page {0}", page) + thinsp(noThinsp) + pages + "&nbsp;");
                out.write("&nbsp;&nbsp;" + page + thinsp(noThinsp) + pages + "&nbsp;&nbsp;");
            }
            if (start + pageSize < total) {
                int next = start + pageSize;
                //if (next + pageSize < total) {
                if (true) {
                    // Next
                    out.write("&nbsp;<a href=\"" + _contextPath +  "?st=" + next);
                    if (peerParam != null)
                        out.write("&amp;p=" + peerParam);
                    out.write("\">" +
                          "<img alt=\"" + _("Next") + "\" title=\"" + _("Next page") + "\" border=\"0\" src=\"" +
                          _imgPath + "control_play_blue.png\">" +
                          "</a>&nbsp;");
                }
                // Last
                int last = ((total - 1) / pageSize) * pageSize;
                out.write("&nbsp;<a href=\"" + _contextPath +  "?st=" + last);
                if (peerParam != null)
                    out.write("&amp;p=" + peerParam);
                out.write("\">" +
                          "<img alt=\"" + _("Last") + "\" title=\"" + _("Last page") + "\" border=\"0\" src=\"" +
                          _imgPath + "control_fastforward_blue.png\">" +
                          "</a>&nbsp;");
            } else {
                out.write("&nbsp;" +
                          "<img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "control_play_blue.png\">" +
                          "&nbsp;" +
                          "<img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "control_fastforward_blue.png\">");
            }
    }
    
    /**
     * Do what they ask, adding messages to _manager.addMessage as necessary
     */
    private void processRequest(HttpServletRequest req) {
        String action = req.getParameter("action");
        if (action == null) {
            // http://www.onenaught.com/posts/382/firefox-4-change-input-type-image-only-submits-x-and-y-not-name
            @SuppressWarnings("unchecked") // TODO-Java6: Remove cast, return type is correct
            Map<String, String[]> params = req.getParameterMap();
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
            String newURL = req.getParameter("nofilter_newURL");
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
                if (newURL.contains("<") || newURL.contains(">") ||
                    newURL.contains("%3C") || newURL.contains("%3E") ||
                    newURL.contains("%3c") || newURL.contains("%3e") ||
                    newURL.contains("\"") || newURL.contains("'") ||
                    newURL.contains("%22") || newURL.contains("%27")) {
                    _manager.addMessage("Invalid URL");
                } else if (newURL.startsWith("http://")) {
                    FetchAndAdd fetch = new FetchAndAdd(_context, _manager, newURL);
                    _manager.addDownloader(fetch);
                } else if (newURL.startsWith(MagnetURI.MAGNET) || newURL.startsWith(MagnetURI.MAGGOT)) {
                    addMagnet(newURL);
                } else if (newURL.length() == 40 && newURL.replaceAll("[a-fA-F0-9]", "").length() == 0) {
                    addMagnet(MagnetURI.MAGNET_FULL + newURL);
                } else {
                    _manager.addMessage(_("Invalid URL: Must start with \"http://\", \"{0}\", or \"{1}\"",
                                          MagnetURI.MAGNET, MagnetURI.MAGGOT));
                }
            } else {
                // no file or URL specified
            }
        } else if (action.startsWith("Stop_")) {
            String torrent = action.substring(5);
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (String name : _manager.listTorrentFiles() ) {
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
                    _manager.startTorrent(infoHash);
                }
            }
        } else if (action.startsWith("Remove_")) {
            String torrent = action.substring(7);
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (String name : _manager.listTorrentFiles() ) {
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash())) ) {
                            MetaInfo meta = snark.getMetaInfo();
                            if (meta == null) {
                                // magnet - remove and delete are the same thing
                                // Remove not shown on UI so we shouldn't get here
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
                    for (String name : _manager.listTorrentFiles() ) {
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash())) ) {
                            MetaInfo meta = snark.getMetaInfo();
                            if (meta == null) {
                                // magnet - remove and delete are the same thing
                                _manager.deleteMagnet(snark);
                                if (snark instanceof FetchAndAdd)
                                    _manager.addMessage(_("Download deleted: {0}", name));
                                else
                                    _manager.addMessage(_("Magnet deleted: {0}", name));
                                return;
                            }
                            _manager.stopTorrent(snark, true);
                            File f = new File(name);
                            f.delete();
                            _manager.addMessage(_("Torrent file deleted: {0}", f.getAbsolutePath()));
                            List<List<String>> files = meta.getFiles();
                            String dataFile = snark.getBaseName();
                            f = new File(_manager.getDataDir(), dataFile);
                            if (files == null) { // single file torrent
                                if (f.delete())
                                    _manager.addMessage(_("Data file deleted: {0}", f.getAbsolutePath()));
                                else
                                    _manager.addMessage(_("Data file could not be deleted: {0}", f.getAbsolutePath()));
                                break;
                            }
                            Storage storage = snark.getStorage();
                            if (storage == null)
                                break;
                            // step 1 delete files
                            for (File df : storage.getFiles()) {
                                if (df.delete()) {
                                    //_manager.addMessage(_("Data file deleted: {0}", df.getAbsolutePath()));
                                } else {
                                    _manager.addMessage(_("Data file could not be deleted: {0}", df.getAbsolutePath()));
                                }
                            }
                            // step 2 delete dirs bottom-up
                            Set<File> dirs = storage.getDirectories();
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Dirs to delete: " + DataHelper.toString(dirs));
                            boolean ok = false;
                            for (File df : dirs) {
                                if (df.delete()) {
                                    ok = true;
                                    //_manager.addMessage(_("Data dir deleted: {0}", df.getAbsolutePath()));
                                } else {
                                    ok = false;
                                    _manager.addMessage(_("Directory could not be deleted: {0}", df.getAbsolutePath()));
                                    if (_log.shouldLog(Log.WARN))
                                        _log.warn("Could not delete dir " + df);
                                }
                            }
                            // step 3 message for base (last one)
                            if (ok)
                                _manager.addMessage(_("Directory deleted: {0}", storage.getBase()));
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
            String pageSize = req.getParameter("pageSize");
            boolean useOpenTrackers = req.getParameter("useOpenTrackers") != null;
            boolean useDHT = req.getParameter("useDHT") != null;
            //String openTrackers = req.getParameter("openTrackers");
            String theme = req.getParameter("theme");
            _manager.updateConfig(dataDir, filesPublic, autoStart, refreshDel, startupDel, pageSize,
                                  seedPct, eepHost, eepPort, i2cpHost, i2cpPort, i2cpOpts,
                                  upLimit, upBW, useOpenTrackers, useDHT, theme);
            // update servlet
            try {
                setResourceBase(_manager.getDataDir());
            } catch (ServletException se) {}
        } else if ("Save2".equals(action)) {
            String taction = req.getParameter("taction");
            if (taction != null)
                processTrackerForm(taction, req);
        } else if ("Create".equals(action)) {
            String baseData = req.getParameter("nofilter_baseFile");
            if (baseData != null && baseData.trim().length() > 0) {
                File baseFile = new File(baseData.trim());
                if (!baseFile.isAbsolute())
                    baseFile = new File(_manager.getDataDir(), baseData);
                String announceURL = req.getParameter("announceURL");
                // make the user add a tracker on the config form now
                //String announceURLOther = req.getParameter("announceURLOther");
                //if ( (announceURLOther != null) && (announceURLOther.trim().length() > "http://.i2p/announce".length()) )
                //    announceURL = announceURLOther;

                if (baseFile.exists()) {
                    String torrentName = baseFile.getName();
                    if (torrentName.toLowerCase(Locale.US).endsWith(".torrent")) {
                        _manager.addMessage(_("Cannot add a torrent ending in \".torrent\": {0}", baseFile.getAbsolutePath()));
                        return;
                    }
                    Snark snark = _manager.getTorrentByBaseName(torrentName);
                    if (snark != null) {
                        _manager.addMessage(_("Torrent with this name is already running: {0}", torrentName));
                        return;
                    }
                    if (isParentOf(baseFile,_manager.getDataDir()) ||
                        isParentOf(baseFile, _manager.util().getContext().getBaseDir()) ||
                        isParentOf(baseFile, _manager.util().getContext().getConfigDir())) {
                        _manager.addMessage(_("Cannot add a torrent including an I2P directory: {0}", baseFile.getAbsolutePath()));
                        return;
                    }
                    Collection<Snark> snarks = _manager.getTorrents();
                    for (Snark s : snarks) {
                        Storage storage = s.getStorage();
                        if (storage == null)
                            continue;
                        File sbase = storage.getBase();
                        if (isParentOf(sbase, baseFile)) {
                            _manager.addMessage(_("Cannot add torrent {0} inside another torrent: {1}",
                                                  baseFile.getAbsolutePath(), sbase));
                            return;
                        }
                        if (isParentOf(baseFile, sbase)) {
                            _manager.addMessage(_("Cannot add torrent {0} including another torrent: {1}",
                                                  baseFile.getAbsolutePath(), sbase));
                            return;
                        }
                    }

                    if (announceURL.equals("none"))
                        announceURL = null;
                    _lastAnnounceURL = announceURL;
                    List<String> backupURLs = new ArrayList<String>();
                    Enumeration<?> e = req.getParameterNames();
                    while (e.hasMoreElements()) {
                         Object o = e.nextElement();
                         if (!(o instanceof String))
                             continue;
                         String k = (String) o;
                        if (k.startsWith("backup_")) {
                            String url = k.substring(7);
                            if (!url.equals(announceURL))
                                backupURLs.add(DataHelper.stripHTML(url));
                        }
                    }
                    List<List<String>> announceList = null;
                    if (!backupURLs.isEmpty()) {
                        // BEP 12 - Put primary first, then the others, each as the sole entry in their own list
                        if (announceURL == null) {
                            _manager.addMessage(_("Error - Cannot include alternate trackers without a primary tracker"));
                            return;
                        }
                        backupURLs.add(0, announceURL);
                        boolean hasPrivate = false;
                        boolean hasPublic = false;
                        for (String url : backupURLs) {
                            if (_manager.getPrivateTrackers().contains(url))
                                hasPrivate = true;
                            else
                                hasPublic = true;
                        }
                        if (hasPrivate && hasPublic) {
                            _manager.addMessage(_("Error - Cannot mix private and public trackers in a torrent"));
                            return;
                        }
                        announceList = new ArrayList<List<String>>(backupURLs.size());
                        for (String url : backupURLs) {
                            announceList.add(Collections.singletonList(url));
                        }
                    }
                    try {
                        // This may take a long time to check the storage, but since it already exists,
                        // it shouldn't be THAT bad, so keep it in this thread.
                        // TODO thread it for big torrents, perhaps a la FetchAndAdd
                        boolean isPrivate = _manager.getPrivateTrackers().contains(announceURL);
                        Storage s = new Storage(_manager.util(), baseFile, announceURL, announceList, isPrivate, null);
                        s.close(); // close the files... maybe need a way to pass this Storage to addTorrent rather than starting over
                        MetaInfo info = s.getMetaInfo();
                        File torrentFile = new File(_manager.getDataDir(), s.getBaseName() + ".torrent");
                        // FIXME is the storage going to stay around thanks to the info reference?
                        // now add it, but don't automatically start it
                        boolean ok = _manager.addTorrent(info, s.getBitField(), torrentFile.getAbsolutePath(), baseFile, true);
                        if (!ok)
                            return;
                        _manager.addMessage(_("Torrent created for \"{0}\"", baseFile.getName()) + ": " + torrentFile.getAbsolutePath());
                        if (announceURL != null && !_manager.util().getOpenTrackers().contains(announceURL))
                            _manager.addMessage(_("Many I2P trackers require you to register new torrents before seeding - please do so before starting \"{0}\"", baseFile.getName()));
                    } catch (IOException ioe) {
                        _manager.addMessage(_("Error creating a torrent for \"{0}\"", baseFile.getAbsolutePath()) + ": " + ioe);
                        _log.error("Error creating a torrent", ioe);
                    }
                } else {
                    _manager.addMessage(_("Cannot create a torrent for the nonexistent data: {0}", baseFile.getAbsolutePath()));
                }
            } else {
                _manager.addMessage(_("Error creating torrent - you must enter a file or directory"));
            }
        } else if ("StopAll".equals(action)) {
            _manager.stopAllTorrents(false);
        } else if ("StartAll".equals(action)) {
            _manager.startAllTorrents();
        } else if ("Clear".equals(action)) {
            _manager.clearMessages();
        } else {
            _manager.addMessage("Unknown POST action: \"" + action + '\"');
        }
    }

    /**
     *  Redirect a POST to a GET (P-R-G), preserving the peer string
     *  @since 0.9.5
     */
    private void sendRedirect(HttpServletRequest req, HttpServletResponse resp, String p) throws IOException {
        String url = req.getRequestURL().toString();
        StringBuilder buf = new StringBuilder(128);
        if (url.endsWith("_post"))
            url = url.substring(0, url.length() - 5);
        buf.append(url);
        if (p.length() > 0)
            buf.append(p.replace("&amp;", "&"));  // no you don't html escape the redirect header
        resp.setHeader("Location", buf.toString());
        resp.sendError(302, "Moved");
    }

    /** @since 0.9 */
    private void processTrackerForm(String action, HttpServletRequest req) {
        if (action.equals(_("Delete selected")) || action.equals(_("Save tracker configuration"))) {
            boolean changed = false;
            Map<String, Tracker> trackers = _manager.getTrackerMap();
            List<String> removed = new ArrayList<String>();
            List<String> open = new ArrayList<String>();
            List<String> priv = new ArrayList<String>();
            Enumeration<?> e = req.getParameterNames();
            while (e.hasMoreElements()) {
                 Object o = e.nextElement();
                 if (!(o instanceof String))
                     continue;
                 String k = (String) o;
                 if (k.startsWith("delete_")) {
                     k = k.substring(7);
                     Tracker t;
                     if ((t = trackers.remove(k)) != null) {
                        removed.add(t.announceURL);
                        _manager.addMessage(_("Removed") + ": " + DataHelper.stripHTML(k));
                        changed = true;
                     }
                } else if (k.startsWith("open_")) {
                     open.add(k.substring(5));
                } else if (k.startsWith("private_")) {
                     priv.add(k.substring(8));
                }
            }
            if (changed) {
                _manager.saveTrackerMap();
            }

            open.removeAll(removed);
            List<String> oldOpen = new ArrayList<String>(_manager.util().getOpenTrackers());
            Collections.sort(oldOpen);
            Collections.sort(open);
            if (!open.equals(oldOpen))
                _manager.saveOpenTrackers(open);

            priv.removeAll(removed);
            // open trumps private
            priv.removeAll(open);
            List<String> oldPriv = new ArrayList<String>(_manager.getPrivateTrackers());
            Collections.sort(oldPriv);
            Collections.sort(priv);
            if (!priv.equals(oldPriv))
                _manager.savePrivateTrackers(priv);

        } else if (action.equals(_("Add tracker"))) {
            String name = req.getParameter("tname");
            String hurl = req.getParameter("thurl");
            String aurl = req.getParameter("taurl");
            if (name != null && hurl != null && aurl != null) {
                name = DataHelper.stripHTML(name.trim());
                hurl = DataHelper.stripHTML(hurl.trim());
                aurl = DataHelper.stripHTML(aurl.trim()).replace("=", "&#61;");
                if (name.length() > 0 && hurl.startsWith("http://") && TrackerClient.isValidAnnounce(aurl)) {
                    Map<String, Tracker> trackers = _manager.getTrackerMap();
                    trackers.put(name, new Tracker(name, aurl, hurl));
                    _manager.saveTrackerMap();
                    // open trumps private
                    if (req.getParameter("_add_open_") != null) {
                        List<String> newOpen = new ArrayList<String>(_manager.util().getOpenTrackers());
                        newOpen.add(aurl);
                        _manager.saveOpenTrackers(newOpen);
                    } else if (req.getParameter("_add_private_") != null) {
                        List<String> newPriv = new ArrayList<String>(_manager.getPrivateTrackers());
                        newPriv.add(aurl);
                        _manager.savePrivateTrackers(newPriv);
                    }
                } else {
                    _manager.addMessage(_("Enter valid tracker name and URLs"));
                }
            } else {
                _manager.addMessage(_("Enter valid tracker name and URLs"));
            }
        } else if (action.equals(_("Restore defaults"))) {
            _manager.setDefaultTrackerMap();
            _manager.saveOpenTrackers(null);
            _manager.addMessage(_("Restored default trackers"));
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
     *  @since 0.7.14
     */
    private static class TorrentNameComparator implements Comparator<Snark>, Serializable {

        public int compare(Snark l, Snark r) {
            // put downloads and magnets first
            if (l.getStorage() == null && r.getStorage() != null)
                return -1;
            if (l.getStorage() != null && r.getStorage() == null)
                return 1;
            String ls = l.getBaseName();
            String llc = ls.toLowerCase(Locale.US);
            if (llc.startsWith("the ") || llc.startsWith("the.") || llc.startsWith("the_"))
                ls = ls.substring(4);
            String rs = r.getBaseName();
            String rlc = rs.toLowerCase(Locale.US);
            if (rlc.startsWith("the ") || rlc.startsWith("the.") || rlc.startsWith("the_"))
                rs = rs.substring(4);
            return Collator.getInstance().compare(ls, rs);
        }
    }

    private List<Snark> getSortedSnarks(HttpServletRequest req) {
        ArrayList<Snark> rv = new ArrayList<Snark>(_manager.getTorrents());
        Collections.sort(rv, new TorrentNameComparator());
        return rv;
    }

    private static final int MAX_DISPLAYED_FILENAME_LENGTH = 50;
    private static final int MAX_DISPLAYED_ERROR_LENGTH = 43;

    /**
     *  Display one snark (one line in table, unless showPeers is true)
     *
     *  @param stats in/out param (totals)
     *  @param statsOnly if true, output nothing, update stats only
     *  @param stParam non null; empty or e.g. &amp;st=10
     */
    private void displaySnark(PrintWriter out, Snark snark, String uri, int row, long stats[], boolean showPeers,
                              boolean isDegraded, boolean noThinsp, boolean showDebug, boolean statsOnly,
                              String stParam) throws IOException {
        // stats
        long uploaded = snark.getUploaded();
        stats[0] += snark.getDownloaded();
        stats[1] += uploaded;
        long downBps = snark.getDownloadRate();
        long upBps = snark.getUploadRate();
        boolean isRunning = !snark.isStopped();
        if (isRunning) {
            stats[2] += downBps;
            stats[3] += upBps;
        }
        int curPeers = snark.getPeerCount();
        stats[4] += curPeers;
        long total = snark.getTotalLength();
        if (total > 0)
            stats[5] += total;
        if (statsOnly)
            return;

        String basename = snark.getBaseName();
        String fullBasename = basename;
        if (basename.length() > MAX_DISPLAYED_FILENAME_LENGTH) {
            String start = basename.substring(0, MAX_DISPLAYED_FILENAME_LENGTH);
            if (start.indexOf(" ") < 0 && start.indexOf("-") < 0) {
                // browser has nowhere to break it
                basename = start + HELLIP;
            }
        }
        // includes skipped files, -1 for magnet mode
        long remaining = snark.getRemainingLength(); 
        if (remaining > total)
            remaining = total;
        // does not include skipped files, -1 for magnet mode or when not running.
        long needed = snark.getNeededLength(); 
        if (needed > total)
            needed = total;
        long remainingSeconds;
        if (downBps > 0 && needed > 0)
            remainingSeconds = needed / downBps;
        else
            remainingSeconds = -1;
        
        MetaInfo meta = snark.getMetaInfo();
        // isValid means isNotMagnet
        boolean isValid = meta != null;
        boolean isMultiFile = isValid && meta.getFiles() != null;
        
        String err = snark.getTrackerProblems();
        int knownPeers = Math.max(curPeers, snark.getTrackerSeenPeers());
        
        String rowClass = (row % 2 == 0 ? "snarkTorrentEven" : "snarkTorrentOdd");
        String statusString;
        if (snark.isChecking()) {
            statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "stalled.png\" title=\"" + _("Checking") + "\"></td>" +
                           "<td class=\"snarkTorrentStatus\">" + _("Checking");
        } else if (snark.isAllocating()) {
            statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "stalled.png\" title=\"" + _("Allocating") + "\"></td>" +
                           "<td class=\"snarkTorrentStatus\">" + _("Allocating");
        } else if (err != null && curPeers == 0) {
            // Also don't show if seeding... but then we won't see the not-registered error
            //       && remaining != 0 && needed != 0) {
            // let's only show this if we have no peers, otherwise PEX and DHT should bail us out, user doesn't care
            //if (isRunning && curPeers > 0 && !showPeers)
            //    statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "trackererror.png\" title=\"" + err + "\"></td>" +
            //                   "<td class=\"snarkTorrentStatus " + rowClass + "\">" + _("Tracker Error") +
            //                   ": <a href=\"" + uri + "?p=" + Base64.encode(snark.getInfoHash()) + "\">" +
            //                   curPeers + thinsp(noThinsp) +
            //                   ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
            //else if (isRunning)
            if (isRunning)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "trackererror.png\" title=\"" + err + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + _("Tracker Error") +
                               ": " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
            else {
                if (err.length() > MAX_DISPLAYED_ERROR_LENGTH)
                    err = DataHelper.escapeHTML(err.substring(0, MAX_DISPLAYED_ERROR_LENGTH)) + "&hellip;";
                else
                    err = DataHelper.escapeHTML(err);
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "trackererror.png\" title=\"" + err + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + _("Tracker Error");
            }
        } else if (snark.isStarting()) {
            statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "stalled.png\" title=\"" + _("Starting") + "\"></td>" +
                           "<td class=\"snarkTorrentStatus\">" + _("Starting");
        } else if (remaining == 0 || needed == 0) {  // < 0 means no meta size yet
            // partial complete or seeding
            if (isRunning) {
                String img;
                String txt;
                if (remaining == 0) {
                    img = "seeding";
                    txt = _("Seeding");
                } else {
                    // partial
                    img = "complete";
                    txt = _("Complete");
                }
                if (curPeers > 0 && !showPeers)
                    statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + img + ".png\" title=\"" + txt + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + txt +
                               ": <a href=\"" + uri + "?p=" + Base64.encode(snark.getInfoHash()) + stParam + "\">" +
                               curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
                else
                    statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + img + ".png\" title=\"" + txt + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + txt +
                               ": " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
            } else {
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "complete.png\" title=\"" + _("Complete") + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + _("Complete");
            }
        } else {
            if (isRunning && curPeers > 0 && downBps > 0 && !showPeers)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "downloading.png\" title=\"" + _("OK") + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + _("OK") +
                               ": <a href=\"" + uri + "?p=" + Base64.encode(snark.getInfoHash()) + stParam + "\">" +
                               curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
            else if (isRunning && curPeers > 0 && downBps > 0)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "downloading.png\" title=\"" + _("OK") + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + _("OK") +
                               ": " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
            else if (isRunning && curPeers > 0 && !showPeers)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "stalled.png\" title=\"" + _("Stalled") + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + _("Stalled") +
                               ": <a href=\"" + uri + "?p=" + Base64.encode(snark.getInfoHash()) + stParam + "\">" +
                               curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
            else if (isRunning && curPeers > 0)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "stalled.png\" title=\"" + _("Stalled") + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + _("Stalled") +
                               ": " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
            else if (isRunning && knownPeers > 0)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "nopeers.png\" title=\"" + _("No Peers") + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + _("No Peers") +
                               ": 0" + thinsp(noThinsp) + knownPeers ;
            else if (isRunning)
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "nopeers.png\" title=\"" + _("No Peers") + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + _("No Peers");
            else
                statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "stopped.png\" title=\"" + _("Stopped") + "\"></td>" +
                               "<td class=\"snarkTorrentStatus\">" + _("Stopped");
        }
        
        out.write("<tr class=\"" + rowClass + "\">");
        out.write("<td class=\"center\">");
        out.write(statusString + "</td>\n\t");

        // (i) icon column
        out.write("<td>");
        if (isValid) {
            String announce = meta.getAnnounce();
            if (announce == null)
                announce = snark.getTrackerURL();
            if (announce != null) {
                // Link to tracker details page
                String trackerLink = getTrackerLink(announce, snark.getInfoHash());
                if (trackerLink != null)
                    out.write(trackerLink);
            }
        }

        String encodedBaseName = encodePath(fullBasename);
        // File type icon column
        out.write("</td>\n<td>");
        if (isValid) {
            // Link to local details page - note that trailing slash on a single-file torrent
            // gets us to the details page instead of the file.
            StringBuilder buf = new StringBuilder(128);
            buf.append("<a href=\"").append(encodedBaseName)
               .append("/\" title=\"").append(_("Torrent details"))
               .append("\">");
            out.write(buf.toString());
        }
        String icon;
        if (isMultiFile)
            icon = "folder";
        else if (isValid)
            icon = toIcon(meta.getName());
        else if (snark instanceof FetchAndAdd)
            icon = "basket_put";
        else
            icon = "magnet";
        if (isValid) {
            out.write(toImg(icon));
            out.write("</a>");
        } else {
            out.write(toImg(icon));
        }

        // Torrent name column
        out.write("</td><td class=\"snarkTorrentName\"");
        if (isMultiFile) {
            // link on the whole td
            out.write(" onclick=\"document.location='" + encodedBaseName + "/';\">");
        } else {
            out.write('>');
        }
        if (remaining == 0 || isMultiFile) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("<a href=\"").append(encodedBaseName);
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
        out.write(DataHelper.escapeHTML(basename));
        if (remaining == 0 || isMultiFile)
            out.write("</a>");

        out.write("<td align=\"right\" class=\"snarkTorrentETA\">");
        if(isRunning && remainingSeconds > 0 && !snark.isChecking())
            out.write(DataHelper.formatDuration2(Math.max(remainingSeconds, 10) * 1000)); // (eta 6h)
        out.write("</td>\n\t");
        out.write("<td align=\"right\" class=\"snarkTorrentDownloaded\">");
        if (remaining > 0)
            out.write(formatSize(total-remaining) + thinsp(noThinsp) + formatSize(total));
        else if (remaining == 0)
            out.write(formatSize(total)); // 3GB
        //else
        //    out.write("??");  // no meta size yet
        out.write("</td>\n\t");
        out.write("<td align=\"right\" class=\"snarkTorrentUploaded\">");
        if (isValid && uploaded > 0)
           out.write(formatSize(uploaded));
        out.write("</td>\n\t");
        out.write("<td align=\"right\" class=\"snarkTorrentRateDown\">");
        if (isRunning && needed > 0)
            out.write(formatSize(downBps) + "ps");
        out.write("</td>\n\t");
        out.write("<td align=\"right\" class=\"snarkTorrentRateUp\">");
        if (isRunning && isValid)
            out.write(formatSize(upBps) + "ps");
        out.write("</td>\n\t");
        out.write("<td align=\"center\" class=\"snarkTorrentAction\">");
        String b64 = Base64.encode(snark.getInfoHash());
        if (snark.isChecking()) {
            // show no buttons
        } else if (isRunning) {
            // Stop Button
            if (isDegraded)
                out.write("<a href=\"" + _contextPath + "/?action=Stop_" + b64 + "&amp;nonce=" + _nonce + stParam + "\"><img title=\"");
            else
                out.write("<input type=\"image\" name=\"action_Stop_" + b64 + "\" value=\"foo\" title=\"");
            out.write(_("Stop the torrent"));
            out.write("\" src=\"" + _imgPath + "stop.png\" alt=\"");
            out.write(_("Stop"));
            out.write("\">");
            if (isDegraded)
                out.write("</a>");
        } else if (!snark.isStarting()) {
            if (!_manager.isStopping()) {
                // Start Button
                // This works in Opera but it's displayed a little differently, so use noThinsp here too so all 3 icons are consistent
                if (noThinsp)
                    out.write("<a href=\"" + _contextPath + "/?action=Start_" + b64 + "&amp;nonce=" + _nonce + stParam + "\"><img title=\"");
                else
                    out.write("<input type=\"image\" name=\"action_Start_" + b64 + "\" value=\"foo\" title=\"");
                out.write(_("Start the torrent"));
                out.write("\" src=\"" + _imgPath + "start.png\" alt=\"");
                out.write(_("Start"));
                out.write("\">");
                if (isDegraded)
                    out.write("</a>");
            }
            if (isValid) {
                // Remove Button
                // Doesnt work with Opera so use noThinsp instead of isDegraded
                if (noThinsp)
                    out.write("<a href=\"" + _contextPath + "/?action=Remove_" + b64 + "&amp;nonce=" + _nonce + stParam + "\"><img title=\"");
                else
                    out.write("<input type=\"image\" name=\"action_Remove_" + b64 + "\" value=\"foo\" title=\"");
                out.write(_("Remove the torrent from the active list, deleting the .torrent file"));
                out.write("\" onclick=\"if (!confirm('");
                // Can't figure out how to escape double quotes inside the onclick string.
                // Single quotes in translate strings with parameters must be doubled.
                // Then the remaining single quote must be escaped
                out.write(_("Are you sure you want to delete the file \\''{0}\\'' (downloaded data will not be deleted) ?",
                            escapeJSString(snark.getName())));
                out.write("')) { return false; }\"");
                out.write(" src=\"" + _imgPath + "remove.png\" alt=\"");
                out.write(_("Remove"));
                out.write("\">");
                if (isDegraded)
                    out.write("</a>");
            }

            // Delete Button
            // Doesnt work with Opera so use noThinsp instead of isDegraded
            if (noThinsp)
                out.write("<a href=\"" + _contextPath + "/?action=Delete_" + b64 + "&amp;nonce=" + _nonce + stParam + "\"><img title=\"");
            else
                out.write("<input type=\"image\" name=\"action_Delete_" + b64 + "\" value=\"foo\" title=\"");
            out.write(_("Delete the .torrent file and the associated data file(s)"));
            out.write("\" onclick=\"if (!confirm('");
            // Can't figure out how to escape double quotes inside the onclick string.
            // Single quotes in translate strings with parameters must be doubled.
            // Then the remaining single quote must be escaped
            out.write(_("Are you sure you want to delete the torrent \\''{0}\\'' and all downloaded data?",
                        escapeJSString(fullBasename)));
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
                out.write("<td colspan=\"4\" align=\"right\">");
                PeerID pid = peer.getPeerID();
                String ch = pid != null ? pid.toString().substring(0, 4) : "????";
                String client;
                if ("AwMD".equals(ch))
                    client = _("I2PSnark");
                else if ("BFJT".equals(ch))
                    client = "I2PRufus";
                else if ("TTMt".equals(ch))
                    client = "I2P-BT";
                else if ("LUFa".equals(ch))
                    client = "Vuze" + getAzVersion(pid.getID());
                else if ("CwsL".equals(ch))
                    client = "I2PSnarkXL";
                else if ("ZV".equals(ch.substring(2,4)) || "VUZP".equals(ch))
                    client = "Robert" + getRobtVersion(pid.getID());
                else if (ch.startsWith("LV")) // LVCS 1.0.2?; LVRS 1.0.4
                    client = "Transmission" + getAzVersion(pid.getID());
                else if ("LUtU".equals(ch))
                    client = "KTorrent" + getAzVersion(pid.getID());
                else
                    client = _("Unknown") + " (" + ch + ')';
                out.write(client + "&nbsp;&nbsp;<tt>" + peer.toString().substring(5, 9)+ "</tt>");
                if (showDebug)
                    out.write(" inactive " + (peer.getInactiveTime() / 1000) + "s");
                out.write("</td>\n\t");
                out.write("<td class=\"snarkTorrentStatus\">");
                out.write("</td>\n\t");
                out.write("<td align=\"right\" class=\"snarkTorrentStatus\">");
                float pct;
                if (isValid) {
                    pct = (float) (100.0 * peer.completed() / meta.getPieces());
                    if (pct >= 100.0)
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
                out.write("<td class=\"snarkTorrentStatus\">");
                out.write("</td>\n\t");
                out.write("<td align=\"right\" class=\"snarkTorrentStatus\">");
                if (needed > 0) {
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
                out.write("<td align=\"right\" class=\"snarkTorrentStatus\">");
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
                out.write("<td class=\"snarkTorrentStatus\">");
                out.write("</td></tr>\n\t");
                if (showDebug)
                    out.write("<tr class=\"" + rowClass + "\"><td></td><td colspan=\"10\" align=\"right\">" + peer.getSocket() + "</td></tr>");
            }
        }
    }

    /**
     *  Make it JS and HTML-safe
     *  @since 0.9.15
     *  http://stackoverflow.com/questions/8749001/escaping-html-entities-in-javascript-string-literals-within-the-script-block
     */
    private static String escapeJSString(String s) {
        return s.replace("\\", "\\u005c")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("\"", "\\u0022")
                .replace("'", "\\u0027")
                .replace("&", "\\u0026");
    }

    /**
     *  Get version from bytes 3-6
     *  @return " w.x.y.z" or ""
     *  @since 0.9.14
     */
    private static String getAzVersion(byte[] id) {
        if (id[7] != '-')
            return "";
        StringBuilder buf = new StringBuilder(16);
        buf.append(' ');
        for (int i = 3; i <= 6; i++) {
            int val = id[i] - '0';
            if (val < 0)
                return "";
            if (val > 9)
                val = id[i] - 'A';
            if (i != 6 || val != 0) {
                if (i != 3)
                    buf.append('.');
                buf.append(val);
            }
        }
        return buf.toString();
    }

    /**
     *  Get version from bytes 3-5
     *  @return " w.x.y" or ""
     *  @since 0.9.14
     */
    private static String getRobtVersion(byte[] id) {
        StringBuilder buf = new StringBuilder(8);
        buf.append(' ');
        for (int i = 3; i <= 5; i++) {
            int val = id[i];
            if (val < 0)
                return "";
            if (i != 3)
                buf.append('.');
            buf.append(val);
        }
        return buf.toString();
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
    private static class PeerComparator implements Comparator<Peer>, Serializable {

        public int compare(Peer l, Peer r) {
            int diff = r.completed() - l.completed();      // reverse
            if (diff != 0)
                return diff;
            return l.toString().substring(5, 9).compareTo(r.toString().substring(5, 9));
        }
    }

    /**
     *  Generate link to details page if we know it supports it.
     *  Start of anchor only, caller must add anchor text or img and close anchor.
     *
     *  @return string or null if unknown tracker
     *  @since 0.8.4
     */
    private String getTrackerLinkUrl(String announce, byte[] infohash) {
        // temporarily hardcoded for postman* and anonymity, requires bytemonsoon patch for lookup by info_hash
        if (announce != null && (announce.startsWith("http://YRgrgTLG") || announce.startsWith("http://8EoJZIKr") ||
              announce.startsWith("http://lnQ6yoBT") || announce.startsWith("http://tracker2.postman.i2p/") ||
              announce.startsWith("http://ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p/"))) {
            for (Tracker t : _manager.getTrackers()) {
                String aURL = t.announceURL;
                if (!(aURL.startsWith(announce) || // vvv hack for non-b64 announce in list vvv
                      (announce.startsWith("http://lnQ6yoBT") && aURL.startsWith("http://tracker2.postman.i2p/")) ||
                      (announce.startsWith("http://ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p/") && aURL.startsWith("http://tracker2.postman.i2p/"))))
                    continue;
                String baseURL = urlEncode(t.baseURL);
                String name = DataHelper.escapeHTML(t.name);
                StringBuilder buf = new StringBuilder(128);
                buf.append("<a href=\"").append(baseURL).append("details.php?dllist=1&amp;filelist=1&amp;info_hash=")
                   .append(TrackerClient.urlencode(infohash))
                   .append("\" title=\"").append(_("Details at {0} tracker", name)).append("\" target=\"_blank\">");
                return buf.toString();
            }
        }
        return null;
    }

    /**
     *  Full link to details page with img
     *  @return string or null if details page unsupported
     *  @since 0.8.4
     */
    private String getTrackerLink(String announce, byte[] infohash) {
        String linkUrl = getTrackerLinkUrl(announce, infohash);
        if (linkUrl != null) {
            StringBuilder buf = new StringBuilder(128);
            buf.append(linkUrl)
               .append("<img alt=\"").append(_("Info")).append("\" border=\"0\" src=\"")
               .append(_imgPath).append("details.png\"></a>");
            return buf.toString();
        }
        return null;
    }

    /**
     *  Full anchor to home page or details page with shortened host name as anchor text
     *  @return string, non-null
     *  @since 0.9.5
     */
    private String getShortTrackerLink(String announce, byte[] infohash) {
        StringBuilder buf = new StringBuilder(128);
        String trackerLinkUrl = getTrackerLinkUrl(announce, infohash);
        if (announce.startsWith("http://"))
            announce = announce.substring(7);
        // strip path
        int slsh = announce.indexOf('/');
        if (slsh > 0)
            announce = announce.substring(0, slsh);
        if (trackerLinkUrl != null) {
            buf.append(trackerLinkUrl);
        } else {
            // browsers don't like a full b64 dest, so convert it to b32
            String host = announce;
            if (host.length() >= 516) {
                int colon = announce.indexOf(':');
                String port = "";
                if (colon > 0) {
                    port = host.substring(colon);
                    host = host.substring(0, colon);
                }
                if (host.endsWith(".i2p"))
                    host = host.substring(0, host.length() - 4);
                byte[] b = Base64.decode(host);
                if (b != null) {
                    Hash h = _context.sha().calculateHash(b);
                    // should we add the port back or strip it?
                    host = Base32.encode(h.getData()) + ".b32.i2p" + port;
                }
            }
            buf.append("<a href=\"http://").append(urlEncode(host)).append("/\">");
        }
        // strip port
        int colon = announce.indexOf(':');
        if (colon > 0)
            announce = announce.substring(0, colon);
        if (announce.length() > 67)
            announce = DataHelper.escapeHTML(announce.substring(0, 40)) + "&hellip;" +
                       DataHelper.escapeHTML(announce.substring(announce.length() - 8));
        buf.append(announce);
        buf.append("</a>");
        return buf.toString();
    }

    private void writeAddForm(PrintWriter out, HttpServletRequest req) throws IOException {
        // display incoming parameter if a GET so links will work
        String newURL = req.getParameter("nofilter_newURL");
        if (newURL == null || newURL.trim().length() <= 0 || req.getMethod().equals("POST"))
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
            out.write("<input type=\"hidden\" name=\"p\" value=\"" + DataHelper.stripHTML(peerParam) + "\" >\n");
        out.write("<div class=\"addtorrentsection\"><span class=\"snarkConfigTitle\">");
        out.write("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "add.png\"> ");
        out.write(_("Add Torrent"));
        out.write("</span><hr>\n<table border=\"0\"><tr><td>");
        out.write(_("From URL"));
        out.write(":<td><input type=\"text\" name=\"nofilter_newURL\" size=\"85\" value=\"" + newURL + "\" spellcheck=\"false\"");
        out.write(" title=\"");
        out.write(_("Enter the torrent file download URL (I2P only), magnet link, maggot link, or info hash"));
        out.write("\"> \n");
        // not supporting from file at the moment, since the file name passed isn't always absolute (so it may not resolve)
        //out.write("From file: <input type=\"file\" name=\"newFile\" size=\"50\" value=\"" + newFile + "\" /><br>");
        out.write("<input type=\"submit\" class=\"add\" value=\"");
        out.write(_("Add torrent"));
        out.write("\" name=\"foo\" ><br>\n");
        out.write("<tr><td>&nbsp;<td><span class=\"snarkAddInfo\">");
        out.write(_("You can also copy .torrent files to: {0}.", "<code>" + _manager.getDataDir().getAbsolutePath () + "</code>"));
        out.write("\n");
        out.write(_("Removing a .torrent will cause it to stop."));
        out.write("<br></span></table>\n");
        out.write("</div></form></div>");  
    }
    
    private void writeSeedForm(PrintWriter out, HttpServletRequest req, List<Tracker> sortedTrackers) throws IOException {
        out.write("<a name=\"add\"></a><div class=\"newtorrentsection\"><div class=\"snarkNewTorrent\">\n");
        // *not* enctype="multipart/form-data", so that the input type=file sends the filename, not the file
        out.write("<form action=\"_post\" method=\"POST\">\n");
        out.write("<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" >\n");
        out.write("<input type=\"hidden\" name=\"action\" value=\"Create\" >\n");
        // don't lose peer setting
        String peerParam = req.getParameter("p");
        if (peerParam != null)
            out.write("<input type=\"hidden\" name=\"p\" value=\"" + DataHelper.stripHTML(peerParam) + "\" >\n");
        out.write("<span class=\"snarkConfigTitle\">");
        out.write("<img alt=\"\" border=\"0\" src=\"" + _imgPath + "create.png\"> ");
        out.write(_("Create Torrent"));
        out.write("</span><hr>\n<table border=\"0\"><tr><td>");
        //out.write("From file: <input type=\"file\" name=\"newFile\" size=\"50\" value=\"" + newFile + "\" /><br>\n");
        out.write(_("Data to seed"));
        out.write(":<td>"
                  + "<input type=\"text\" name=\"nofilter_baseFile\" size=\"58\" value=\""
                  + "\" spellcheck=\"false\" title=\"");
        out.write(_("File or directory to seed (full path or within the directory {0} )",
                    _manager.getDataDir().getAbsolutePath() + File.separatorChar));
        out.write("\" ><tr><td>\n");
        out.write(_("Trackers"));
        out.write(":<td><table style=\"width: 30%;\"><tr><td></td><td align=\"center\">");
        out.write(_("Primary"));
        out.write("</td><td align=\"center\">");
        out.write(_("Alternates"));
        out.write("</td><td rowspan=\"0\">" +
                  " <input type=\"submit\" class=\"create\" value=\"");
        out.write(_("Create torrent"));
        out.write("\" name=\"foo\" >" +
                  "</td></tr>\n");
        for (Tracker t : sortedTrackers) {
            String name = t.name;
            String announceURL = t.announceURL.replace("&#61;", "=");
            out.write("<tr><td>");
            out.write(name);
            out.write("</td><td align=\"center\"><input type=\"radio\" name=\"announceURL\" value=\"");
            out.write(announceURL);
            out.write("\"");
            if (announceURL.equals(_lastAnnounceURL))
                out.write(" checked");
            out.write("></td><td align=\"center\"><input type=\"checkbox\" name=\"backup_");
            out.write(announceURL);
            out.write("\" value=\"foo\"></td></tr>\n");
        }
        out.write("<tr><td><i>");
        out.write(_("none"));
        out.write("</i></td><td align=\"center\"><input type=\"radio\" name=\"announceURL\" value=\"none\"");
        if (_lastAnnounceURL == null)
            out.write(" checked");
        out.write("></td><td></td></tr></table>\n");
        // make the user add a tracker on the config form now
        //out.write(_("or"));
        //out.write("&nbsp;<input type=\"text\" name=\"announceURLOther\" size=\"57\" value=\"http://\" " +
        //          "title=\"");
        //out.write(_("Specify custom tracker announce URL"));
        //out.write("\" > " +
        out.write("</td></tr>" +
                  "</table>\n" +
                  "</form></div></div>");        
    }
    
    private static final int[] times = { 5, 15, 30, 60, 2*60, 5*60, 10*60, 30*60, -1 };

    private void writeConfigForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String dataDir = _manager.getDataDir().getAbsolutePath();
        boolean filesPublic = _manager.areFilesPublic();
        boolean autoStart = _manager.shouldAutoStart();
        boolean useOpenTrackers = _manager.util().shouldUseOpenTrackers();
        //String openTrackers = _manager.util().getOpenTrackerString();
        boolean useDHT = _manager.util().shouldUseDHT();
        //int seedPct = 0;
       
        out.write("<form action=\"" + _contextPath + "/configure\" method=\"POST\">\n" +
                  "<div class=\"configsectionpanel\"><div class=\"snarkConfig\">\n" +
                  "<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" >\n" +
                  "<input type=\"hidden\" name=\"action\" value=\"Save\" >\n" +
                  "<span class=\"snarkConfigTitle\">" +
                  "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "config.png\"> ");
        out.write(_("Configuration"));
        out.write("</span><hr>\n"   +
                  "<table border=\"0\"><tr><td>");

        out.write(_("Data directory"));
        out.write(": <td><input name=\"dataDir\" size=\"80\" value=\"" + dataDir + "\" spellcheck=\"false\"></td>\n" +

                  "<tr><td>");
        out.write(_("Files readable by all"));
        out.write(": <td><input type=\"checkbox\" class=\"optbox\" name=\"filesPublic\" value=\"true\" " 
                  + (filesPublic ? "checked " : "") 
                  + "title=\"");
        out.write(_("If checked, other users may access the downloaded files"));
        out.write("\" >" +

                  "<tr><td>");
        out.write(_("Auto start torrents"));
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
                out.write(" selected=\"selected\"");
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
        out.write(": <td><input name=\"startupDelay\" size=\"4\" class=\"r\" value=\"" + _manager.util().getStartupDelay() + "\"> ");
        out.write(_("minutes"));
        out.write("<br>\n" +

                  "<tr><td>");
        out.write(_("Page size"));
        out.write(": <td><input name=\"pageSize\" size=\"4\" maxlength=\"6\" class=\"r\" value=\"" + _manager.getPageSize() + "\"> ");
        out.write(_("torrents"));
        out.write("<br>\n"); 


        //Auto add: <input type="checkbox" name="autoAdd" value="true" title="If true, automatically add torrents that are found in the data directory" />
        //Auto stop: <input type="checkbox" name="autoStop" value="true" title="If true, automatically stop torrents that are removed from the data directory" />
        //out.write("<br>\n");
/*
        out.write("Seed percentage: <select name=\"seedPct\" disabled=\"true\" >\n\t");
        if (seedPct <= 0)
            out.write("<option value=\"0\" selected=\"selected\">Unlimited</option>\n\t");
        else
            out.write("<option value=\"0\">Unlimited</option>\n\t");
        if (seedPct == 100)
            out.write("<option value=\"100\" selected=\"selected\">100%</option>\n\t");
        else
            out.write("<option value=\"100\">100%</option>\n\t");
        if (seedPct == 150)
            out.write("<option value=\"150\" selected=\"selected\">150%</option>\n\t");
        else
            out.write("<option value=\"150\">150%</option>\n\t");
        out.write("</select><br>\n");
*/
        out.write("<tr><td>");
        out.write(_("Total uploader limit"));
        out.write(": <td><input type=\"text\" name=\"upLimit\" class=\"r\" value=\""
                  + _manager.util().getMaxUploaders() + "\" size=\"4\" maxlength=\"3\" > ");
        out.write(_("peers"));
        out.write("<br>\n" +

                  "<tr><td>");
        out.write(_("Up bandwidth limit"));
        out.write(": <td><input type=\"text\" name=\"upBW\" class=\"r\" value=\""
                  + _manager.util().getMaxUpBW() + "\" size=\"4\" maxlength=\"4\" > KBps <i>");
        out.write(_("Half available bandwidth recommended."));
        out.write(" [<a href=\"/config.jsp\" target=\"blank\">");
        out.write(_("View or change router bandwidth"));
        out.write("</a>]</i><br>\n" +
        
                  "<tr><td>");
        out.write(_("Use open trackers also"));
        out.write(": <td><input type=\"checkbox\" class=\"optbox\" name=\"useOpenTrackers\" value=\"true\" " 
                  + (useOpenTrackers ? "checked " : "") 
                  + "title=\"");
        out.write(_("If checked, announce torrents to open trackers as well as the tracker listed in the torrent file"));
        out.write("\" ></td></tr>\n" +
        
                  "<tr><td>");
        out.write(_("Enable DHT"));
        out.write(": <td><input type=\"checkbox\" class=\"optbox\" name=\"useDHT\" value=\"true\" " 
                  + (useDHT ? "checked " : "") 
                  + "title=\"");
        out.write(_("If checked, use DHT"));
        out.write("\" ></td></tr>\n");

        //          "<tr><td>");
        //out.write(_("Open tracker announce URLs"));
        //out.write(": <td><input type=\"text\" name=\"openTrackers\" value=\""
        //          + openTrackers + "\" size=\"50\" ><br>\n");

        //out.write("\n");
        //out.write("EepProxy host: <input type=\"text\" name=\"eepHost\" value=\""
        //          + _manager.util().getEepProxyHost() + "\" size=\"15\" /> ");
        //out.write("port: <input type=\"text\" name=\"eepPort\" value=\""
        //          + _manager.util().getEepProxyPort() + "\" size=\"5\" maxlength=\"5\" /><br>\n");

        Map<String, String> options = new TreeMap<String, String>(_manager.util().getI2CPOptions());
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
                  "<tr><td colspan=\"2\">&nbsp;\n" +  // spacer
                  "<tr><td>&nbsp;<td><input type=\"submit\" class=\"accept\" value=\"");
        out.write(_("Save configuration"));
        out.write("\" name=\"foo\" >\n" +
                  "<tr><td colspan=\"2\">&nbsp;\n" +  // spacer
                  "</table></div></div></form>");
    }
    
    /** @since 0.9 */
    private void writeTrackerForm(PrintWriter out, HttpServletRequest req) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<form action=\"" + _contextPath + "/configure\" method=\"POST\">\n" +
                  "<div class=\"configsectionpanel\"><div class=\"snarkConfig\">\n" +
                  "<input type=\"hidden\" name=\"nonce\" value=\"" + _nonce + "\" >\n" +
                  "<input type=\"hidden\" name=\"action\" value=\"Save2\" >\n" +
                  "<span class=\"snarkConfigTitle\">" +
                  "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "config.png\"> ");
        buf.append(_("Trackers"));
        buf.append("</span><hr>\n"   +
                   "<table class=\"trackerconfig\"><tr><th>")
           //.append(_("Remove"))
           .append("</th><th>")
           .append(_("Name"))
           .append("</th><th>")
           .append(_("Website URL"))
           .append("</th><th>")
           .append(_("Open"))
           .append("</th><th>")
           .append(_("Private"))
           .append("</th><th>")
           .append(_("Announce URL"))
           .append("</th></tr>\n");
        List<String> openTrackers = _manager.util().getOpenTrackers();
        List<String> privateTrackers = _manager.getPrivateTrackers();
        for (Tracker t : _manager.getSortedTrackers()) {
            String name = t.name;
            String homeURL = t.baseURL;
            String announceURL = t.announceURL.replace("&#61;", "=");
            buf.append("<tr><td><input type=\"checkbox\" class=\"optbox\" name=\"delete_")
               .append(name).append("\" title=\"").append(_("Delete")).append("\">" +
                       "</td><td>").append(name)
               .append("</td><td>").append(urlify(homeURL, 35))
               .append("</td><td><input type=\"checkbox\" class=\"optbox\" name=\"open_")
               .append(announceURL).append("\"");
            if (openTrackers.contains(t.announceURL))
                buf.append(" checked=\"checked\"");
            buf.append(">" +
                       "</td><td><input type=\"checkbox\" class=\"optbox\" name=\"private_")
               .append(announceURL).append("\"");
            if (privateTrackers.contains(t.announceURL)) {
                buf.append(" checked=\"checked\"");
            } else {
                if (SnarkManager.DEFAULT_TRACKER_ANNOUNCES.contains(t.announceURL))
                    buf.append(" disabled=\"disabled\"");
            }
            buf.append(">" +
                       "</td><td>").append(urlify(announceURL, 35))
               .append("</td></tr>\n");
        }
        buf.append("<tr><td><b>")
           .append(_("Add")).append(":</b></td>" +
                   "<td><input type=\"text\" class=\"trackername\" name=\"tname\" spellcheck=\"false\"></td>" +
                   "<td><input type=\"text\" class=\"trackerhome\" name=\"thurl\" spellcheck=\"false\"></td>" +
                   "<td><input type=\"checkbox\" class=\"optbox\" name=\"_add_open_\"></td>" +
                   "<td><input type=\"checkbox\" class=\"optbox\" name=\"_add_private_\"></td>" +
                   "<td><input type=\"text\" class=\"trackerannounce\" name=\"taurl\" spellcheck=\"false\"></td></tr>\n" +
                   "<tr><td colspan=\"6\">&nbsp;</td></tr>\n" +  // spacer
                   "<tr><td colspan=\"2\"></td><td colspan=\"4\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"default\" value=\"").append(_("Add tracker")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"delete\" value=\"").append(_("Delete selected")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"accept\" value=\"").append(_("Save tracker configuration")).append("\">\n" +
                   // "<input type=\"reset\" class=\"cancel\" value=\"").append(_("Cancel")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"reload\" value=\"").append(_("Restore defaults")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"add\" value=\"").append(_("Add tracker")).append("\">\n" +
                   "</td></tr>" +
                   "<tr><td colspan=\"6\">&nbsp;</td></tr>\n" +  // spacer
                   "</table></div></div></form>\n");
        out.write(buf.toString());
    }

    private void writeConfigLink(PrintWriter out) throws IOException {
        out.write("<div class=\"configsection\"><span class=\"snarkConfig\">\n" +
                  "<span class=\"snarkConfigTitle\"><a href=\"configure\">" +
                  "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "config.png\"> ");
        out.write(_("Configuration"));
        out.write("</a></span></span></div>\n");
    }

    /**
     *  @param url in base32 or hex
     *  @since 0.8.4
     */
    private void addMagnet(String url) {
        try {
            MagnetURI magnet = new MagnetURI(_manager.util(), url);
            String name = magnet.getName();
            byte[] ih = magnet.getInfoHash();
            String trackerURL = magnet.getTrackerURL();
            _manager.addMagnet(name, ih, trackerURL, true);
        } catch (IllegalArgumentException iae) {
            _manager.addMessage(_("Invalid magnet URL {0}", url));
        }
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
                buf.append("selected=\"selected\" ");
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
    
    /**
     * This is for a full URL. For a path only, use encodePath().
     * @since 0.7.14
     */
    static String urlify(String s) {
        return urlify(s, 100);
    }
    
    /**
     * This is for a full URL. For a path only, use encodePath().
     * @since 0.9
     */
    private static String urlify(String s, int max) {
        StringBuilder buf = new StringBuilder(256);
        // browsers seem to work without doing this but let's be strict
        String link = urlEncode(s);
        String display;
        if (s.length() <= max)
            display = DataHelper.escapeHTML(link);
        else
            display = DataHelper.escapeHTML(s.substring(0, max)) + "&hellip;";
        buf.append("<a href=\"").append(link).append("\">").append(display).append("</a>");
        return buf.toString();
    }
    
    /**
     * This is for a full URL. For a path only, use encodePath().
     * @since 0.8.13
     */
    private static String urlEncode(String s) {
        return s.replace(";", "%3B").replace("&", "&amp;").replace(" ", "%20")
                .replace("<", "%3C").replace(">", "%3E")
                .replace("[", "%5B").replace("]", "%5D");
    }

    private static final String DOCTYPE = "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\">\n";
    private static final String HEADER_A = "<link href=\"";
    private static final String HEADER_B = "snark.css\" rel=\"stylesheet\" type=\"text/css\" >";


    private static final String TABLE_HEADER = "<table border=\"0\" class=\"snarkTorrents\" width=\"100%\" >\n" +
                                               "<thead>\n";

    private static final String FOOTER = "</div></center></body></html>";

    /**
     *  Sort alphabetically in current locale, ignore case,
     *  directories first
     *  @since 0.9.6
     */
    private static class ListingComparator implements Comparator<File>, Serializable {

        public int compare(File l, File r) {
            boolean ld = l.isDirectory();
            boolean rd = r.isDirectory();
            if (ld && !rd)
                return -1;
            if (rd && !ld)
                return 1;
            return Collator.getInstance().compare(l.getName(), r.getName());
        }
    }

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
     * @param xxxr The Resource unused
     * @param base The encoded base URL
     * @param parent True if the parent directory should be included
     * @param postParams map of POST parameters or null if not a POST
     * @return String of HTML or null if postParams != null
     * @since 0.7.14
     */
    private String getListHTML(File xxxr, String base, boolean parent, Map<String, String[]> postParams)
        throws IOException
    {
        String decodedBase = decodePath(base);
        String title = decodedBase;
        String cpath = _contextPath + '/';
        if (title.startsWith(cpath))
            title = title.substring(cpath.length());

        // Get the snark associated with this directory
        String torrentName;
        String pathInTorrent;
        int slash = title.indexOf('/');
        if (slash > 0) {
            torrentName = title.substring(0, slash);
            pathInTorrent = title.substring(slash);
        } else {
            torrentName = title;
            pathInTorrent = "/";
        }
        Snark snark = _manager.getTorrentByBaseName(torrentName);

        if (snark != null && postParams != null) {
            // caller must P-R-G
            String[] val = postParams.get("nonce");
            if (val != null) {
                String nonce = val[0];
                if (String.valueOf(_nonce).equals(nonce))
                    savePriorities(snark, postParams);
                else
                    _manager.addMessage("Please retry form submission (bad nonce)");
            }
            return null;
        }

        File r;
        if (snark != null) {
            Storage storage = snark.getStorage();
            if (storage != null) {
                File sbase = storage.getBase();
                if (pathInTorrent.equals("/"))
                    r = sbase;
                else
                    r = new File(sbase, pathInTorrent);
            } else {
                // magnet, dummy
                r = new File("");
            }
        } else {
            // dummy
            r = new File("");
        }
        StringBuilder buf=new StringBuilder(4096);
        buf.append(DOCTYPE).append("<HTML><HEAD><TITLE>");
        if (title.endsWith("/"))
            title = title.substring(0, title.length() - 1);
        String directory = title;
        title = _("Torrent") + ": " + DataHelper.escapeHTML(title);
        buf.append(title);
        buf.append("</TITLE>").append(HEADER_A).append(_themePath).append(HEADER_B).append("<link rel=\"shortcut icon\" href=\"" + _themePath + "favicon.ico\">" +
             "</HEAD><BODY>\n<center><div class=\"snarknavbar\"><a href=\"").append(_contextPath).append("/\" title=\"Torrents\"");
        buf.append(" class=\"snarkRefresh\"><img alt=\"\" border=\"0\" src=\"").append(_imgPath).append("arrow_refresh.png\">&nbsp;&nbsp;");
        if (_contextName.equals(DEFAULT_NAME))
            buf.append(_("I2PSnark"));
        else
            buf.append(_contextName);
        buf.append("</a></div></center>\n");
        
        if (parent)  // always true
            buf.append("<div class=\"page\"><div class=\"mainsection\">");
        boolean showPriority = snark != null && snark.getStorage() != null && !snark.getStorage().complete() &&
                               r.isDirectory();
        if (showPriority) {
            buf.append("<form action=\"").append(base).append("\" method=\"POST\">\n");
            buf.append("<input type=\"hidden\" name=\"nonce\" value=\"").append(_nonce).append("\" >\n");
        }
        if (snark != null) {
            // first table - torrent info
            buf.append("<table class=\"snarkTorrentInfo\">\n");
            buf.append("<tr><th><b>")
               .append(_("Torrent"))
               .append(":</b> ")
               .append(DataHelper.escapeHTML(snark.getBaseName()))
               .append("</th></tr>\n");

            String fullPath = snark.getName();
            String baseName = encodePath((new File(fullPath)).getName());
            buf.append("<tr><td>")
               .append("<img alt=\"\" border=\"0\" src=\"").append(_imgPath).append("file.png\" >&nbsp;<b>")
               .append(_("Torrent file"))
               .append(":</b> <a href=\"").append(_contextPath).append('/').append(baseName).append("\">")
               .append(DataHelper.escapeHTML(fullPath))
               .append("</a></td></tr>\n");
            buf.append("<tr><td>")
               .append("<img alt=\"\" border=\"0\" src=\"").append(_imgPath).append("file.png\" >&nbsp;<b>")
               .append(_("Data location"))
               .append(":</b> ")
               .append(DataHelper.escapeHTML(snark.getStorage().getBase().getPath()))
               .append("</td></tr>\n");

            String announce = null;
            MetaInfo meta = snark.getMetaInfo();
            if (meta != null) {
                announce = meta.getAnnounce();
                if (announce == null)
                    announce = snark.getTrackerURL();
                if (announce != null) {
                    announce = DataHelper.stripHTML(announce);
                    buf.append("<tr><td>");
                    String trackerLink = getTrackerLink(announce, snark.getInfoHash());
                    if (trackerLink != null)
                        buf.append(trackerLink).append(' ');
                    buf.append("<b>").append(_("Primary Tracker")).append(":</b> ");
                    buf.append(getShortTrackerLink(announce, snark.getInfoHash()));
                    buf.append("</td></tr>");
                }
                List<List<String>> alist = meta.getAnnounceList();
                if (alist != null) {
                    buf.append("<tr><td>" +
                               "<img alt=\"\" border=\"0\" src=\"")
                       .append(_imgPath).append("details.png\"> <b>");
                    buf.append(_("Tracker List")).append(":</b> ");
                    for (List<String> alist2 : alist) {
                        buf.append('[');
                        boolean more = false;
                        for (String s : alist2) {
                            if (more)
                                buf.append(' ');
                            else
                                more = true;
                            buf.append(getShortTrackerLink(DataHelper.stripHTML(s), snark.getInfoHash()));
                        }
                        buf.append("] ");
                    }
                    buf.append("</td></tr>");
                }
            }

            if (meta != null) {
                String com = meta.getComment();
                if (com != null) {
                    if (com.length() > 1024)
                        com = com.substring(0, 1024);
                    buf.append("<tr><td><img alt=\"\" border=\"0\" src=\"")
                       .append(_imgPath).append("details.png\"> <b>")
                       .append(_("Comment")).append(":</b> ")
                       .append(DataHelper.stripHTML(com))
                       .append("</td></tr>\n");
                }
                long dat = meta.getCreationDate();
                if (dat > 0) {
                    String date = (new SimpleDateFormat("yyyy-MM-dd HH:mm")).format(new Date(dat));
                    buf.append("<tr><td><img alt=\"\" border=\"0\" src=\"")
                       .append(_imgPath).append("details.png\"> <b>")
                       .append(_("Created")).append(":</b> ")
                       .append(date).append(" UTC")
                       .append("</td></tr>\n");
                }
                String cby = meta.getCreatedBy();
                if (cby != null) {
                    if (cby.length() > 128)
                        cby = com.substring(0, 128);
                    buf.append("<tr><td><img alt=\"\" border=\"0\" src=\"")
                       .append(_imgPath).append("details.png\"> <b>")
                       .append(_("Created By")).append(":</b> ")
                       .append(DataHelper.stripHTML(cby))
                       .append("</td></tr>\n");
                }
            }

            String hex = I2PSnarkUtil.toHex(snark.getInfoHash());
            if (meta == null || !meta.isPrivate()) {
                buf.append("<tr><td><a href=\"")
                   .append(MagnetURI.MAGNET_FULL).append(hex);
                if (announce != null)
                    buf.append("&amp;tr=").append(announce);
                buf.append("\">")
                   .append(toImg("magnet", _("Magnet link")))
                   .append("</a> <b>Magnet:</b> <a href=\"")
                   .append(MagnetURI.MAGNET_FULL).append(hex);
                if (announce != null)
                    buf.append("&amp;tr=").append(announce);
                buf.append("\">")
                   .append(MagnetURI.MAGNET_FULL).append(hex);
                if (announce != null)
                    buf.append("&amp;tr=").append(announce);
                buf.append("</a>")
                   .append("</td></tr>\n");
            } else {
                buf.append("<tr><td>")
                   .append(_("Private torrent"))
                   .append("</td></tr>\n");
            }

            // We don't have the hash of the torrent file
            //buf.append("<tr><td>").append(_("Maggot link")).append(": <a href=\"").append(MAGGOT).append(hex).append(':').append(hex).append("\">")
            //   .append(MAGGOT).append(hex).append(':').append(hex).append("</a></td></tr>");

            buf.append("<tr><td>")
               .append("<img alt=\"\" border=\"0\" src=\"").append(_imgPath).append("size.png\" >&nbsp;<b>")
               .append(_("Size"))
               .append(":</b> ")
               .append(formatSize(snark.getTotalLength()));
            int pieces = snark.getPieces();
            double completion = (pieces - snark.getNeeded()) / (double) pieces;
            if (completion < 1.0)
                buf.append("&nbsp;<img alt=\"\" border=\"0\" src=\"").append(_imgPath).append("head_rx.png\" >&nbsp;<b>")
                   .append(_("Completion"))
                   .append(":</b> ")
                   .append((new DecimalFormat("0.00%")).format(completion));
            else
                buf.append("&nbsp;<img alt=\"\" border=\"0\" src=\"").append(_imgPath).append("head_rx.png\" >&nbsp;")
                   .append(_("Complete"));
            // else unknown
            long needed = snark.getNeededLength();
            if (needed > 0)
                buf.append("&nbsp;<img alt=\"\" border=\"0\" src=\"").append(_imgPath).append("head_rx.png\" >&nbsp;<b>")
                   .append(_("Remaining"))
                   .append(":</b> ")
                   .append(formatSize(needed));
            if (meta != null) {
                List<List<String>> files = meta.getFiles();
                int fileCount = files != null ? files.size() : 1;
                buf.append("&nbsp;<img alt=\"\" border=\"0\" src=\"").append(_imgPath).append("file.png\" >&nbsp;<b>")
                   .append(_("Files"))
                   .append(":</b> ")
                   .append(fileCount);
            }
            buf.append("&nbsp;<img alt=\"\" border=\"0\" src=\"").append(_imgPath).append("file.png\" >&nbsp;<b>")
               .append(_("Pieces"))
               .append(":</b> ")
               .append(pieces);
            buf.append("&nbsp;<img alt=\"\" border=\"0\" src=\"").append(_imgPath).append("file.png\" >&nbsp;<b>")
               .append(_("Piece size"))
               .append(":</b> ")
               .append(formatSize(snark.getPieceLength(0)))
               .append("</td></tr>\n");
        } else {
            // shouldn't happen
            buf.append("<tr><th>Not found<br>resource=\"").append(r.toString())
               .append("\"<br>base=\"").append(base)
               .append("\"<br>torrent=\"").append(torrentName)
               .append("\"</th></tr>\n");
        }
        buf.append("</table>\n");

        if (snark != null && !r.exists()) {
            // fixup TODO
            buf.append("<p>Does not exist<br>resource=\"").append(r.toString())
               .append("\"<br>base=\"").append(base)
               .append("\"<br>torrent=\"").append(torrentName)
               .append("\"</p></div></div></BODY></HTML>");
            return buf.toString();
        }

        File[] ls = null;
        if (r.isDirectory()) {
            ls = r.listFiles();
            Arrays.sort(ls, new ListingComparator());
        }  // if r is not a directory, we are only showing torrent info section
        
        if (ls == null) {
            // We are only showing the torrent info section
            buf.append("</div></div></BODY></HTML>");
            return buf.toString();
        }

        // second table - dir info
        buf.append("<table class=\"snarkDirInfo\"><thead>\n");
        buf.append("<tr>\n")
           .append("<th colspan=2>")
           .append("<img border=\"0\" src=\"").append(_imgPath).append("file.png\" title=\"")
           .append(_("Directory"))
           .append(": ")
           .append(directory)
           .append("\" alt=\"")
           .append(_("Directory"))
           .append("\"></th>\n");
        buf.append("<th align=\"right\">")
           .append("<img border=\"0\" src=\"").append(_imgPath).append("size.png\" title=\"")
           .append(_("Size"))
           .append("\" alt=\"")
           .append(_("Size"))
           .append("\"></th>\n");
        buf.append("<th class=\"headerstatus\">")
           .append("<img border=\"0\" src=\"").append(_imgPath).append("status.png\" title=\"")
           .append(_("Status"))
           .append("\" alt=\"")
           .append(_("Status"))
           .append("\"></th>\n");
        if (showPriority)
            buf.append("<th class=\"headerpriority\">")
               .append("<img border=\"0\" src=\"").append(_imgPath).append("priority.png\" title=\"")
               .append(_("Priority"))
               .append("\" alt=\"")
               .append(_("Priority"))
               .append("\"></th>\n");
        buf.append("</tr>\n</thead>\n");
        buf.append("<tr><td colspan=\"" + (showPriority ? '5' : '4') + "\" class=\"ParentDir\"><A HREF=\"");
        URIUtil.encodePath(buf, addPaths(decodedBase,"../"));
        buf.append("\"><img alt=\"\" border=\"0\" src=\"").append(_imgPath).append("up.png\"> ")
           .append(_("Up to higher level directory"))
           .append("</A></td></tr>\n");


        //DateFormat dfmt=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
        //                                               DateFormat.MEDIUM);
        boolean showSaveButton = false;
        for (int i=0 ; i< ls.length ; i++)
        {   
            //String encoded = encodePath(ls[i].getName());
            // bugfix for I2P - Backport from Jetty 6 (zero file lengths and last-modified times)
            // http://jira.codehaus.org/browse/JETTY-361?page=com.atlassian.jira.plugin.system.issuetabpanels%3Achangehistory-tabpanel#issue-tabs
            // See resource.diff attachment
            //Resource item = addPath(encoded);
            File item = ls[i];
            
            String rowClass = (i % 2 == 0 ? "snarkTorrentEven" : "snarkTorrentOdd");
            buf.append("<TR class=\"").append(rowClass).append("\">");
            
            // Get completeness and status string
            boolean complete = false;
            String status = "";
            long length = item.length();
            int fileIndex = -1;
            int priority = 0;
            if (item.isDirectory()) {
                complete = true;
                //status = toImg("tick") + ' ' + _("Directory");
            } else {
                if (snark == null || snark.getStorage() == null) {
                    // Assume complete, perhaps he removed a completed torrent but kept a bookmark
                    complete = true;
                    status = toImg("cancel") + ' ' + _("Torrent not found?");
                } else {
                    Storage storage = snark.getStorage();
                    fileIndex = storage.indexOf(item);

                            long remaining = storage.remaining(fileIndex);
                            if (remaining < 0) {
                                complete = true;
                                status = toImg("cancel") + ' ' + _("File not found in torrent?");
                            } else if (remaining == 0 || length <= 0) {
                                complete = true;
                                status = toImg("tick") + ' ' + _("Complete");
                            } else {
                                priority = storage.getPriority(fileIndex);
                                if (priority < 0)
                                    status = toImg("cancel");
                                else if (priority == 0)
                                    status = toImg("clock");
                                else
                                    status = toImg("clock_red");
                                status += " " +
                                         (100 * (length - remaining) / length) + "% " + _("complete") +
                                         " (" + DataHelper.formatSize2(remaining) + "B " + _("remaining") + ")";
                            }

                }
            }

            String path = addPaths(decodedBase, ls[i].getName());
            if (item.isDirectory() && !path.endsWith("/"))
                path=addPaths(path,"/");
            path = encodePath(path);
            String icon = toIcon(item);

            buf.append("<TD class=\"snarkFileIcon\">");
            if (complete) {
                buf.append("<a href=\"").append(path).append("\">");
                // thumbnail ?
                String plc = item.toString().toLowerCase(Locale.US);
                if (plc.endsWith(".jpg") || plc.endsWith(".jpeg") || plc.endsWith(".png") ||
                    plc.endsWith(".gif") || plc.endsWith(".ico")) {
                    buf.append("<img alt=\"\" border=\"0\" class=\"thumb\" src=\"")
                       .append(path).append("\"></a>");
                } else {
                    buf.append(toImg(icon, _("Open"))).append("</a>");
                }
            } else {
                buf.append(toImg(icon));
            }
            buf.append("</TD><TD class=\"snarkFileName\">");
            if (complete)
                buf.append("<a href=\"").append(path).append("\">");
            buf.append(DataHelper.escapeHTML(item.getName()));
            if (complete)
                buf.append("</a>");
            buf.append("</TD><TD ALIGN=right class=\"snarkFileSize\">");
            if (!item.isDirectory())
                buf.append(DataHelper.formatSize2(length)).append('B');
            buf.append("</TD><TD class=\"snarkFileStatus\">");
            //buf.append(dfmt.format(new Date(item.lastModified())));
            buf.append(status);
            buf.append("</TD>");
            if (showPriority) {
                buf.append("<td class=\"priority\">");
                if ((!complete) && (!item.isDirectory())) {
                    buf.append("<input type=\"radio\" value=\"5\" name=\"pri.").append(fileIndex).append("\" ");
                    if (priority > 0)
                        buf.append("checked=\"true\"");
                    buf.append('>').append(_("High"));

                    buf.append("<input type=\"radio\" value=\"0\" name=\"pri.").append(fileIndex).append("\" ");
                    if (priority == 0)
                        buf.append("checked=\"true\"");
                    buf.append('>').append(_("Normal"));

                    buf.append("<input type=\"radio\" value=\"-9\" name=\"pri.").append(fileIndex).append("\" ");
                    if (priority < 0)
                        buf.append("checked=\"true\"");
                    buf.append('>').append(_("Skip"));
                    showSaveButton = true;
                }
                buf.append("</td>");
            }
            buf.append("</TR>\n");
        }
        if (showSaveButton) {
            buf.append("<thead><tr><th colspan=\"4\">&nbsp;</th><th class=\"headerpriority\"><input type=\"submit\" value=\"");
            buf.append(_("Save priorities"));
            buf.append("\" name=\"foo\" ></th></tr></thead>\n");
        }
        buf.append("</table>\n");
        if (showPriority)
            buf.append("</form>");
        buf.append("</div></div></BODY></HTML>\n");

        return buf.toString();
    }

    /** @since 0.7.14 */
    private String toIcon(File item) {
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
        // Note that for this to work well, our custom mime.properties file must be loaded.
        String plc = path.toLowerCase(Locale.US);
        String mime = getMimeType(path);
        if (mime == null)
            mime = "";
        if (mime.equals("text/html"))
            icon = "html";
        else if (mime.equals("text/plain") ||
                 mime.equals("text/x-sfv") ||
                 mime.equals("application/rtf") ||
                 mime.equals("application/epub+zip") ||
                 mime.equals("application/x-mobipocket-ebook") ||
                 plc.endsWith(".azw4"))
            icon = "page";
        else if (mime.equals("application/java-archive") ||
                 plc.endsWith(".deb"))
            icon = "package";
        else if (plc.endsWith(".xpi2p"))
            icon = "plugin";
        else if (mime.equals("application/pdf"))
            icon = "page_white_acrobat";
        else if (mime.startsWith("image/"))
            icon = "photo";
        else if (mime.startsWith("audio/") || mime.equals("application/ogg"))
            icon = "music";
        else if (mime.startsWith("video/"))
            icon = "film";
        else if (mime.equals("application/zip") || mime.equals("application/x-gtar") ||
                 mime.equals("application/compress") || mime.equals("application/gzip") ||
                 mime.equals("application/x-7z-compressed") || mime.equals("application/x-rar-compressed") ||
                 mime.equals("application/x-tar") || mime.equals("application/x-bzip2"))
            icon = "compress";
        else if (plc.endsWith(".exe"))
            icon = "application";
        else if (plc.endsWith(".iso"))
            icon = "cd";
        else if (mime.equals("application/x-bittorrent"))
            icon = "magnet";
        else
            icon = "page_white";
        return icon;
    }
    
    /** @since 0.7.14 */
    private String toImg(String icon) {
        return "<img alt=\"\" height=\"16\" width=\"16\" src=\"" + _contextPath + "/.icons/" + icon + ".png\">";
    }

    /** @since 0.8.2 */
    private String toImg(String icon, String altText) {
        return "<img alt=\"" + altText + "\" height=\"16\" width=\"16\" src=\"" + _contextPath + "/.icons/" + icon + ".png\">";
    }

    /** @since 0.8.1 */
    private void savePriorities(Snark snark, Map<String, String[]> postParams) {
        Storage storage = snark.getStorage();
        if (storage == null)
            return;
        for (Map.Entry<String, String[]> entry : postParams.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("pri.")) {
                try {
                    int fileIndex = Integer.parseInt(key.substring(4));
                    String val = entry.getValue()[0];   // jetty arrays
                    int pri = Integer.parseInt(val);
                    storage.setPriority(fileIndex, pri);
                    //System.err.println("Priority now " + pri + " for " + file);
                } catch (Throwable t) { t.printStackTrace(); }
            }
        }
         snark.updatePiecePriorities();
        _manager.saveTorrentStatus(snark);
    }

    /**
     *  Is "a" equal to "b",
     *  or is "a" a directory and a parent of file or directory "b",
     *  canonically speaking?
     *
     *  @since 0.9.15
     */
    private static boolean isParentOf(File a, File b) {
        try {
            a = a.getCanonicalFile();
            b = b.getCanonicalFile();
        } catch (IOException ioe) {
            return false;
        }
        if (a.equals(b))
            return true;
        if (!a.isDirectory())
            return false;
        // easy case
        if (!b.getPath().startsWith(a.getPath()))
            return false;
        // dir by dir
        while (!a.equals(b)) {
            b = b.getParentFile();
            if (b == null)
                return false;
        }
        return true;
    }
}
