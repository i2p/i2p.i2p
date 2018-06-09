package org.klomp.snark.web;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.servlet.util.ServletUtil;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;
import net.i2p.util.UIMessages;

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
import org.klomp.snark.comments.Comment;
import org.klomp.snark.comments.CommentSet;

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
    private static final String WARBASE = "/.resources/";
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
            return;
        }

        if (_context.isRouterContext())
            _themePath = "/themes/snark/" + _manager.getTheme() + '/';
        else
            _themePath = _contextPath + WARBASE + "themes/snark/" + _manager.getTheme() + '/';
        _imgPath = _themePath + "images/";
        req.setCharacterEncoding("UTF-8");

        String pOverride = _manager.util().connected() ? null : "";
        String peerString = getQueryString(req, pOverride, null, null);

        // AJAX for mainsection
        if ("/.ajax/xhr1.html".equals(path)) {
            setHTMLHeaders(resp);
            PrintWriter out = resp.getWriter();
            //if (_log.shouldLog(Log.DEBUG))
            //    _manager.addMessage((_context.clock().now() / 1000) + " xhr1 p=" + req.getParameter("p"));
            writeMessages(out, false, peerString);
            boolean canWrite;
            synchronized(this) {
                canWrite = _resourceBase.canWrite();
            }
            writeTorrents(out, req, canWrite);
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
                File resource = getResource(pathInContext);
                if (resource == null) {
                    resp.sendError(404);
                } else {
                    String base = addPaths(req.getRequestURI(), "/");
                    String listing = getListHTML(resource, base, true, method.equals("POST") ? req.getParameterMap() : null,
                                                 req.getParameter("sort"));
                    if (method.equals("POST")) {
                        // P-R-G
                        sendRedirect(req, resp, "");
                    } else if (listing != null) {
                        setHTMLHeaders(resp);
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

        boolean noCollapse = noCollapsePanels(req);
        boolean collapsePanels = _manager.util().collapsePanels();

        setHTMLHeaders(resp);
        PrintWriter out = resp.getWriter();
        out.write(DOCTYPE + "<html>\n" +
                  "<head><link rel=\"shortcut icon\" href=\"" + _themePath + "favicon.ico\">\n" +
                  "<title>");
        if (_contextName.equals(DEFAULT_NAME))
            out.write(_t("I2PSnark"));
        else
            out.write(_contextName);
        out.write(" - ");
        if (isConfigure)
            out.write(_t("Configuration"));
        else
            out.write(_t("Anonymous BitTorrent Client"));
        String peerParam = req.getParameter("p");
        if ("2".equals(peerParam))
            out.write(" | Debug Mode");
        out.write("</title>\n");

        // we want it to go to the base URI so we don't refresh with some funky action= value
        int delay = 0;
        if (!isConfigure) {
            delay = _manager.getRefreshDelaySeconds();
            if (delay > 0) {
                String jsPfx = _context.isRouterContext() ? "" : ".resources";
                String downMsg = _context.isRouterContext() ? _t("Router is down") : _t("I2PSnark has stopped");
                // fallback to metarefresh when javascript is disabled
                out.write("<noscript><meta http-equiv=\"refresh\" content=\"" + delay + ";" + _contextPath + "/" + peerString + "\"></noscript>\n");
                out.write("<script src=\"" + jsPfx + "/js/ajax.js\" type=\"text/javascript\"></script>\n" +
                          "<script type=\"text/javascript\">\n"  +
                          "var failMessage = \"<div class=\\\"routerdown\\\"><b>" + downMsg + "<\\/b><\\/div>\";\n" +
                          "function requestAjax1() { ajax(\"" + _contextPath + "/.ajax/xhr1.html" +
                          peerString.replace("&amp;", "&") +  // don't html escape in js
                          "\", \"mainsection\", " + (delay*1000) + "); }\n" +
                          "function initAjax() { setTimeout(requestAjax1, " + (delay*1000) +");  }\n"  +
                          "</script>\n");
            }
        }
        out.write(HEADER_A + _themePath + HEADER_B);

        //  ...and inject CSS to display panels uncollapsed
        if (noCollapse || !collapsePanels) {
            out.write(HEADER_A + _themePath + HEADER_C);
        }
        out.write("</head>\n");

        if (isConfigure || delay <= 0)
            out.write("<body>");
        else
            out.write("<body onload=\"initAjax()\">");
        out.write("<center>");
        List<Tracker> sortedTrackers = null;
        if (isConfigure) {
            out.write("<div class=\"snarknavbar\"><a href=\"" + _contextPath + "/\" title=\"");
            out.write(_t("Torrents"));
            out.write("\" class=\"snarkNav nav_main\">");
            if (_contextName.equals(DEFAULT_NAME))
                out.write(_t("I2PSnark"));
            else
                out.write(_contextName);
            out.write("</a>");
        } else {
            out.write("<div class=\"snarknavbar\"><a href=\"" + _contextPath + '/' + peerString + "\" title=\"");
            out.write(_t("Refresh page"));
            out.write("\" class=\"snarkNav nav_main\">");
            if (_contextName.equals(DEFAULT_NAME))
                out.write(_t("I2PSnark"));
            else
                out.write(_contextName);
            out.write("</a>\n");
            sortedTrackers = _manager.getSortedTrackers();
            if (_context.isRouterContext()) {
                //out.write("<a href=\"http://forum.i2p/viewforum.php?f=21\" class=\"snarkNav nav_forum\" target=\"_blank\">");
                //out.write(_t("Forum"));
                //out.write("</a>\n");
                for (Tracker t : sortedTrackers) {
                    if (t.baseURL == null || !t.baseURL.startsWith("http"))
                        continue;
                    if (_manager.util().isKnownOpenTracker(t.announceURL))
                        continue;
                    out.write(" <a href=\"" + t.baseURL + "\" class=\"snarkNav nav_tracker\" target=\"_blank\">" + t.name + "</a>");
                }
            }
        }
        out.write("</div>\n");
        String newURL = req.getParameter("newURL");
        if (newURL != null && newURL.trim().length() > 0 && req.getMethod().equals("GET"))
            _manager.addMessage(_t("Click \"Add torrent\" button to fetch torrent"));
        out.write("<div class=\"page\"><div id=\"mainsection\" class=\"mainsection\">");

        writeMessages(out, isConfigure, peerString);

        if (isConfigure) {
            // end of mainsection div
            out.write("<div class=\"logshim\"></div></div>\n");
            writeConfigForm(out, req);
            writeTrackerForm(out, req);
        } else {
            boolean canWrite;
            synchronized(this) {
                canWrite = _resourceBase.canWrite();
            }
            boolean pageOne = writeTorrents(out, req, canWrite);
            // end of mainsection div
            if (pageOne) {
                out.write("</div><div id=\"lowersection\">\n");
                if (canWrite) {
                    writeAddForm(out, req);
                    writeSeedForm(out, req, sortedTrackers);
                }
                writeConfigLink(out);
                // end of lowersection div
            }
            out.write("</div>\n");
        }
        out.write(FOOTER);
    }

    /**
     *  The standard HTTP headers for all HTML pages
     *
     *  @since 0.9.16 moved from doGetAndPost()
     */
    private static void setHTMLHeaders(HttpServletResponse resp) {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html; charset=UTF-8");
        // "no-store, max-age=0" forces all our images to be reloaded on ajax refresh
        resp.setHeader("Cache-Control", "max-age=86400, no-cache, must-revalidate");
        resp.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'");
        resp.setDateHeader("Expires", 86400);
        resp.setHeader("Pragma", "no-cache");
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        resp.setHeader("X-XSS-Protection", "1; mode=block");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Referrer-Policy", "no-referrer");
        resp.setHeader("Accept-Ranges", "none");
    }

    private void writeMessages(PrintWriter out, boolean isConfigure, String peerString) throws IOException {
        List<UIMessages.Message> msgs = _manager.getMessages();
        if (!msgs.isEmpty()) {
            out.write("\n<div class=\"snarkMessages\" tabindex=\"0\">");
            out.write("<a id=\"closeLog\" href=\"" + _contextPath + '/');
            if (isConfigure)
                out.write("configure");
            if (peerString.length() > 0)
                out.write(peerString + "&amp;");
            else
                out.write("?");
            int lastID = msgs.get(msgs.size() - 1).id;
            out.write("action=Clear&amp;id=" + lastID + "&amp;nonce=" + _nonce + "\">");
            String tx = _t("clear messages");
            out.write(toThemeImg("delete", tx, tx));
            out.write("</a>" +
                      "\n<ul>\n");
            // FIXME translate, only show once
            //out.write("<noscript><li class=\"noscriptWarning\">Warning! Javascript is disabled in your browser. If <a href=\"configure\">page refresh</a> is enabled, ");
            //out.write("you will lose any input in the add/create torrent sections when a refresh occurs.</li></noscript>");
            for (int i = msgs.size()-1; i >= 0; i--) {
                String msg = msgs.get(i).message;
                out.write("<li>" + msg + "</li>\n");
            }
            out.write("</ul>\n</div>");
        }
    }

    /**
     *  @param canWrite is the data directory writable?
     *  @return true if on first page
     */
    private boolean writeTorrents(PrintWriter out, HttpServletRequest req, boolean canWrite) throws IOException {
        /** dl, ul, down rate, up rate, peers, size */
        final long stats[] = {0,0,0,0,0,0};
        String peerParam = req.getParameter("p");
        String stParam = req.getParameter("st");

        List<Snark> snarks = getSortedSnarks(req);
        boolean isForm = _manager.util().connected() || !snarks.isEmpty();
        if (isForm) {
            out.write("<form action=\"_post\" method=\"POST\">\n");
            writeHiddenInputs(out, req, null);
        }
        out.write(TABLE_HEADER);

        // Opera and text-mode browsers: no &thinsp; and no input type=image values submitted
        // Using a unique name fixes Opera, except for the buttons with js confirms, see below
        String ua = req.getHeader("User-Agent");
        boolean isDegraded = ua != null && ServletUtil.isTextBrowser(ua);
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

        String currentSort = req.getParameter("sort");
        boolean showSort = total > 1;
        out.write("<tr><th class=\"snarkGraphicStatus\">");
        String sort = ("2".equals(currentSort)) ? "-2" : "2";
        if (showSort) {
            out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
            out.write("\">");
        }
        String tx = _t("Status");
        out.write(toThemeImg("status", tx,
                             showSort ? _t("Sort by {0}", tx)
                                      : tx));
        if (showSort)
            out.write("</a>");
        out.write("</th>\n<th class=\"snarkTorrentStatus\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            boolean hasPeers = false;
            int end = Math.min(start + pageSize, snarks.size());
            for (int i = start; i < end; i++) {
                if (snarks.get(i).getPeerCount() > 0) {
                    hasPeers = true;
                    break;
                }
            }
            if (hasPeers) {
                out.write(" <a href=\"" + _contextPath + '/');
                if (peerParam != null) {
                    // disable peer view
                    out.write(getQueryString(req, "", null, null));
                    out.write("\">");
                    tx = _t("Hide Peers");
                    out.write(toThemeImg("hidepeers", tx, tx));
                } else {
                    // enable peer view
                    out.write(getQueryString(req, "1", null, null));
                    out.write("\">");
                    tx = _t("Show Peers");
                    out.write(toThemeImg("showpeers", tx, tx));
                }
                out.write("</a>\n");
            }
        }
        out.write("</th>\n<th colspan=\"2\" align=\"left\">");
        // cycle through sort by name or type
        boolean isTypeSort = false;
        if (showSort) {
            if (currentSort == null || "0".equals(currentSort) || "1".equals(currentSort)) {
                sort = "-1";
            } else if ("-1".equals(currentSort)) {
                sort = "12";
                isTypeSort = true;
            } else if ("12".equals(currentSort)) {
                sort = "-12";
                isTypeSort = true;
            } else {
                sort = "";
            }
            out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
            out.write("\">");
        }
        tx = _t("Torrent");
        out.write(toThemeImg("torrent", tx,
                             showSort ? _t("Sort by {0}", (isTypeSort ? _t("File type") : tx))
                                      : tx));
        if (showSort)
            out.write("</a>");
        out.write("</th>\n<th id=\"pagenav\" align=\"center\">");
        if (total > 0 && (start > 0 || total > pageSize)) {
            writePageNav(out, req, start, pageSize, total, noThinsp);
        }
        out.write("</th>\n<th class=\"snarkTorrentETA\" align=\"right\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            if (showSort) {
                sort = ("-4".equals(currentSort)) ? "4" : "-4";
                out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
                out.write("\">");
            }
            // Translators: Please keep short or translate as " "
            tx = _t("ETA");
            out.write(toThemeImg("eta", tx,
                                 showSort ? _t("Sort by {0}", _t("Estimated time remaining"))
                                          : _t("Estimated time remaining")));
            if (showSort)
                out.write("</a>");
        }
        out.write("</th>\n<th class=\"snarkTorrentDownloaded\" align=\"right\">");
        // cycle through sort by size or downloaded
        boolean isDlSort = false;
        if (showSort) {
            if ("-5".equals(currentSort)) {
                sort = "5";
            } else if ("5".equals(currentSort)) {
                sort = "-6";
                isDlSort = true;
            } else if ("-6".equals(currentSort)) {
                sort = "6";
                isDlSort = true;
            } else {
                sort = "-5";
            }
            out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
            out.write("\">");
        }
        // Translators: Please keep short or translate as " "
        tx = _t("RX");
        out.write(toThemeImg("head_rx", tx,
                             showSort ? _t("Sort by {0}", (isDlSort ? _t("Downloaded") : _t("Size")))
                                      : _t("Downloaded")));
        if (showSort)
            out.write("</a>");
        out.write("</th>\n<th class=\"snarkTorrentUploaded\" align=\"right\">");
        boolean isRatSort = false;
        if (!snarks.isEmpty()) {
            // cycle through sort by uploaded or ratio
            boolean nextRatSort = false;
            if (showSort) {
                if ("-7".equals(currentSort)) {
                    sort = "7";
                } else if ("7".equals(currentSort)) {
                    sort = "-11";
                    nextRatSort = true;
                } else if ("-11".equals(currentSort)) {
                    sort = "11";
                    nextRatSort = true;
                    isRatSort = true;
                } else if ("11".equals(currentSort)) {
                    sort = "-7";
                    isRatSort = true;
                } else {
                    sort = "-7";
                }
                out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
                out.write("\">");
            }
            // Translators: Please keep short or translate as " "
            tx = _t("TX");
            out.write(toThemeImg("head_tx", tx,
                                 showSort ? _t("Sort by {0}", (nextRatSort ? _t("Upload ratio") : _t("Uploaded")))
                                          : _t("Uploaded")));
            if (showSort)
                out.write("</a>");
        }
        out.write("</th>\n<th class=\"snarkTorrentRateDown\" align=\"right\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            if (showSort) {
                sort = ("-8".equals(currentSort)) ? "8" : "-8";
                out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
                out.write("\">");
            }
            // Translators: Please keep short or translate as " "
            tx = _t("RX Rate");
            out.write(toThemeImg("head_rxspeed", tx,
                                 showSort ? _t("Sort by {0}", _t("Down Rate"))
                                          : _t("Down Rate")));
            if (showSort)
                out.write("</a>");
        }
        out.write("</th>\n<th class=\"snarkTorrentRateUp\" align=\"right\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            if (showSort) {
                sort = ("-9".equals(currentSort)) ? "9" : "-9";
                out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
                out.write("\">");
            }
            // Translators: Please keep short or translate as " "
            tx = _t("TX Rate");
            out.write(toThemeImg("head_txspeed", tx,
                                 showSort ? _t("Sort by {0}", _t("Up Rate"))
                                          : _t("Up Rate")));
            if (showSort)
                out.write("</a>");
        }
        out.write("</th>\n<th class=\"snarkTorrentAction\" align=\"center\">");

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
            out.write(_t("Stop all torrents and the I2P tunnel"));
            out.write("\" src=\"" + _imgPath + "stop_all.png\" alt=\"");
            out.write(_t("Stop All"));
            out.write("\">");
            if (isDegraded)
                out.write("</a>");
            for (Snark s : snarks) {
                if (s.isStopped()) {
                    // show startall too
                    if (isDegraded)
                        out.write("<a href=\"" + _contextPath + "/?action=StartAll&amp;nonce=" + _nonce + "\"><img title=\"");
                    else
                        out.write("<input type=\"image\" name=\"action_StartAll\" value=\"foo\" title=\"");
                    out.write(_t("Start all stopped torrents"));
                    out.write("\" src=\"" + _imgPath + "start_all.png\" alt=\"");
                    out.write(_t("Start All"));
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
            out.write(_t("Start all torrents and the I2P tunnel"));
            out.write("\" src=\"" + _imgPath + "start_all.png\" alt=\"");
            out.write(_t("Start All"));
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

        for (int i = 0; i < total; i++) {
            Snark snark = snarks.get(i);
            boolean showPeers = showDebug || "1".equals(peerParam) || Base64.encode(snark.getInfoHash()).equals(peerParam);
            boolean hide = i < start || i >= start + pageSize;
            displaySnark(out, req, snark, uri, i, stats, showPeers, isDegraded, noThinsp, showDebug, hide, isRatSort, canWrite);
        }

        if (total == 0) {
            out.write("<tr class=\"snarkTorrentNoneLoaded\">" +
                      "<td colspan=\"11\">");
            synchronized(this) {
                File dd = _resourceBase;
                if (!dd.exists() && !dd.mkdirs()) {
                    out.write(_t("Data directory cannot be created") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else if (!dd.isDirectory()) {
                    out.write(_t("Not a directory") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else if (!dd.canRead()) {
                    out.write(_t("Unreadable") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else if (!canWrite) {
                    out.write(_t("No write permissions for data directory") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else {
                    out.write(_t("No torrents loaded."));
                }
            }
            out.write("</td></tr>\n");
        } else /** if (snarks.size() > 1) */ {
            out.write("<tfoot><tr>\n" +
                      "    <th id=\"snarkTorrentTotals\" align=\"left\" colspan=\"6\">");
            out.write("<span id=\"totals\">");
            out.write(_t("Totals"));
            out.write(":&nbsp;");
            out.write(ngettext("1 torrent", "{0} torrents", total));
            out.write(", ");
            out.write(formatSize(stats[5]));
            if (_manager.util().connected() && total > 0) {
                out.write(", ");
                out.write(ngettext("1 connected peer", "{0} connected peers", (int) stats[4]));
            }
            DHT dht = _manager.util().getDHT();
            if (dht != null) {
                int dhts = dht.size();
                if (dhts > 0) {
                    out.write(", <span>");
                    out.write(ngettext("1 DHT peer", "{0} DHT peers", dhts));
                    out.write("</span>");
                }
            }
            String IPString = _manager.util().getOurIPString();
            if(!IPString.equals("unknown")) {
                // Only truncate if it's an actual dest
                out.write(";&nbsp;<span id=\"ourDest\">");
                out.write(_t("Dest"));
                out.write(":&nbsp;<tt title=\"");
                out.write(_t("Our destination (identity) for this session"));
                out.write("\">");
                out.write(IPString.substring(0, 4));
                out.write("</tt></span>");
            }
            out.write("</span>");
            out.write("</th>\n");
            if (_manager.util().connected() && total > 0) {
                out.write("    <th class=\"snarkTorrentDownloaded\" align=\"right\">" + formatSize(stats[0]) + "</th>\n" +
                      "    <th class=\"snarkTorrentUploaded\" align=\"right\">" + formatSize(stats[1]) + "</th>\n" +
                      "    <th class=\"snarkTorrentRateDown\" align=\"right\">" + formatSizeDec(stats[2]) + "ps</th>\n" +
                      "    <th class=\"snarkTorrentRateUp\" align=\"right\">" + formatSizeDec(stats[3]) + "ps</th>\n" +
                      "    <th class=\"snarkTorrentAction\"></th>");
            } else {
                out.write("<th colspan=\"5\"></th>");
            }
            // TODO javascript handler to remember checkbox status for debug panel visibility (otherwise resets with ajax/meta refresh)
            if (dht != null) {
                if (showDebug) {
                    out.write("</tr>\n<tr class=\"dhtDebug\">");
                    out.write("<th colspan=\"11\">");
                    out.write("<div id=\"dhtDebugPanel\">");
                    out.write("<input class=\"toggle_input\" id=\"toggle_debug\" type=\"checkbox\"><label class=\"toggleview\" for=\"toggle_debug\">");
                    out.write(toThemeImg("debug"));
                    out.write(' ');
                    out.write(_t("Dht Debug"));
                    out.write("</label><div id=\"dhtDebugInner\">");
                    out.write(dht.renderStatusHTML());
                    out.write("</div></div></th>");
                }
            }
            out.write("</tr></tfoot>\n");
        }

        out.write("</table>");
        if (isForm)
            out.write("</form>\n");

        return start == 0;
    }

    /**
     *  hidden inputs for nonce and paramters p, st, and sort
     *
     *  @param out writes to it
     *  @param action if non-null, add it as the action
     *  @since 0.9.16
     */
    private void writeHiddenInputs(PrintWriter out, HttpServletRequest req, String action) {
        StringBuilder buf = new StringBuilder(256);
        writeHiddenInputs(buf, req, action);
        out.write(buf.toString());
    }

    /**
     *  hidden inputs for nonce and paramters p, st, and sort
     *
     *  @param buf appends to it
     *  @param action if non-null, add it as the action
     *  @since 0.9.16
     */
    private void writeHiddenInputs(StringBuilder buf, HttpServletRequest req, String action) {
        buf.append("<input type=\"hidden\" name=\"nonce\" value=\"")
           .append(_nonce).append("\" >\n");
        String peerParam = req.getParameter("p");
        if (peerParam != null) {
            buf.append("<input type=\"hidden\" name=\"p\" value=\"")
               .append(DataHelper.stripHTML(peerParam)).append("\" >\n");
        }
        String stParam = req.getParameter("st");
        if (stParam != null) {
            buf.append("<input type=\"hidden\" name=\"st\" value=\"")
               .append(DataHelper.stripHTML(stParam)).append("\" >\n");
        }
        String soParam = req.getParameter("sort");
        if (soParam != null) {
            buf.append("<input type=\"hidden\" name=\"sort\" value=\"")
               .append(DataHelper.stripHTML(soParam)).append("\" >\n");
        }
        if (action != null) {
            buf.append("<input type=\"hidden\" name=\"action\" value=\"")
               .append(action).append("\" >\n");
        }
    }

    /**
     *  Build HTML-escaped and stripped query string
     *
     *  @param p override or "" for default or null to keep the same as in req
     *  @param st override or "" for default or null to keep the same as in req
     *  @param so override or "" for default or null to keep the same as in req
     *  @return non-null, possibly empty
     *  @since 0.9.16
     */
    private static String getQueryString(HttpServletRequest req, String p, String st, String so) {
        StringBuilder buf = new StringBuilder(64);
        if (p == null) {
            p = req.getParameter("p");
            if (p != null)
                p = DataHelper.stripHTML(p);
        }
        if (p != null && !p.equals(""))
            buf.append("?p=").append(p);
        if (so == null) {
            so = req.getParameter("sort");
            if (so != null)
                so = DataHelper.stripHTML(so);
        }
        if (so != null && !so.equals("")) {
            if (buf.length() <= 0)
                buf.append("?sort=");
            else
                buf.append("&amp;sort=");
            buf.append(so);
        }
        if (st == null) {
            st = req.getParameter("st");
            if (st != null)
                st = DataHelper.stripHTML(st);
        }
        if (st != null && !st.equals("")) {
            if (buf.length() <= 0)
                buf.append("?st=");
            else
                buf.append("&amp;st=");
            buf.append(st);
        }
        return buf.toString();
    }

    /**
     *  @since 0.9.6
     */
    private void writePageNav(PrintWriter out, HttpServletRequest req, int start, int pageSize, int total,
                              boolean noThinsp) {
            // Page nav
            if (start > 0) {
                // First
                out.write("<a href=\"" + _contextPath);
                out.write(getQueryString(req, null, "", null));
                out.write("\">");
                out.write(toThemeImg("first", _t("First"), _t("First page")));
                out.write("</a>&nbsp;");
                int prev = Math.max(0, start - pageSize);
                //if (prev > 0) {
                if (true) {
                    // Back
                    out.write("&nbsp;<a href=\"" + _contextPath);
                    String sprev = (prev > 0) ? Integer.toString(prev) : "";
                    out.write(getQueryString(req, null, sprev, null));
                    out.write("\">");
                    out.write(toThemeImg("previous", _t("Prev"), _t("Previous page")));
                    out.write("</a>&nbsp;");
                }
            } else {
                out.write(
                          "<img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "first.png\">" +
                          "&nbsp;" +
                          "<img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "previous.png\">" +
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
                //out.write("&nbsp;" + _t("Page {0}", page) + thinsp(noThinsp) + pages + "&nbsp;");
                out.write("&nbsp;&nbsp;" + page + thinsp(noThinsp) + pages + "&nbsp;&nbsp;");
            }
            if (start + pageSize < total) {
                int next = start + pageSize;
                //if (next + pageSize < total) {
                if (true) {
                    // Next
                    out.write("&nbsp;<a href=\"" + _contextPath);
                    out.write(getQueryString(req, null, Integer.toString(next), null));
                    out.write("\">");
                    out.write(toThemeImg("next", _t("Next"), _t("Next page")));
                    out.write("</a>&nbsp;");
                }
                // Last
                int last = ((total - 1) / pageSize) * pageSize;
                out.write("&nbsp;<a href=\"" + _contextPath);
                out.write(getQueryString(req, null, Integer.toString(last), null));
                out.write("\">");
                out.write(toThemeImg("last", _t("Last"), _t("Last page")));
                out.write("</a>&nbsp;");
            } else {
                out.write("&nbsp;" +
                          "<img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "next.png\">" +
                          "&nbsp;" +
                          "<img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "last.png\">");
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
                _manager.addMessage(_t("Torrent file {0} does not exist", newFile));
            }
            if ( (f != null) && (f.exists()) ) {
                // NOTE - All this is disabled - load from local file disabled
                File local = new File(_manager.getDataDir(), f.getName());
                String canonical = null;
                try {
                    canonical = local.getCanonicalPath();

                    if (local.exists()) {
                        if (_manager.getTorrent(canonical) != null)
                            _manager.addMessage(_t("Torrent already running: {0}", newFile));
                        else
                            _manager.addMessage(_t("Torrent already in the queue: {0}", newFile));
                    } else {
                        boolean ok = FileUtil.copy(f.getAbsolutePath(), local.getAbsolutePath(), true);
                        if (ok) {
                            _manager.addMessage(_t("Copying torrent to {0}", local.getAbsolutePath()));
                            _manager.addTorrent(canonical);
                        } else {
                            _manager.addMessage(_t("Unable to copy the torrent to {0}", local.getAbsolutePath()) + ' ' + _t("from {0}", f.getAbsolutePath()));
                        }
                    }
                } catch (IOException ioe) {
                    _log.warn("hrm: " + local, ioe);
                }
            } else
          *****/
            if (newURL != null) {
                newURL = newURL.trim();
                String newDir = req.getParameter("nofilter_newDir");
                File dir = null;
                if (newDir != null) {
                    newDir = newDir.trim();
                    if (newDir.length() > 0) {
                        dir = new SecureFile(newDir);
                        if (!dir.isAbsolute()) {
                            _manager.addMessage(_t("Data directory must be an absolute path") + ": " + dir);
                            return;
                        }
                        if (!dir.isDirectory() && !dir.mkdirs()) {
                            _manager.addMessage(_t("Data directory cannot be created") + ": " + dir);
                            return;
                        }
                        Collection<Snark> snarks = _manager.getTorrents();
                        for (Snark s : snarks) {
                            Storage storage = s.getStorage();
                            if (storage == null)
                                continue;
                            File sbase = storage.getBase();
                            if (isParentOf(sbase, dir)) {
                                _manager.addMessage(_t("Cannot add torrent {0} inside another torrent: {1}",
                                                      dir.getAbsolutePath(), sbase));
                                return;
                            }
                        }
                    }
                }
                File dd = _manager.getDataDir();
                if (!dd.canWrite()) {
                    _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
                    return;
                }
                if (newURL.startsWith("http://")) {
                    FetchAndAdd fetch = new FetchAndAdd(_context, _manager, newURL, dir);
                    _manager.addDownloader(fetch);
                } else if (newURL.startsWith(MagnetURI.MAGNET) || newURL.startsWith(MagnetURI.MAGGOT)) {
                    addMagnet(newURL, dir);
                } else if (newURL.length() == 40 && newURL.replaceAll("[a-fA-F0-9]", "").length() == 0) {
                    // hex
                    newURL = newURL.toUpperCase(Locale.US);
                    addMagnet(MagnetURI.MAGNET_FULL + newURL, dir);
                } else if (newURL.length() == 32 && newURL.replaceAll("[a-zA-Z2-7]", "").length() == 0) {
                    // b32
                    newURL = newURL.toUpperCase(Locale.US);
                    addMagnet(MagnetURI.MAGNET_FULL + newURL, dir);
                } else {
                    _manager.addMessage(_t("Invalid URL: Must start with \"http://\", \"{0}\", or \"{1}\"",
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
                                _manager.addMessage(_t("Magnet deleted: {0}", name));
                                return;
                            }
                            File f = new File(name);
                            File dd = _manager.getDataDir();
                            boolean canDelete = dd.canWrite() || !f.exists();
                            _manager.stopTorrent(snark, canDelete);
                            // TODO race here with the DirMonitor, could get re-added
                            if (f.delete()) {
                                _manager.addMessage(_t("Torrent file deleted: {0}", f.getAbsolutePath()));
                            } else if (f.exists()) {
                                if (!canDelete)
                                    _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
                                _manager.addMessage(_t("Torrent file could not be deleted: {0}", f.getAbsolutePath()));
                            }
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
                                    _manager.addMessage(_t("Download deleted: {0}", name));
                                else
                                    _manager.addMessage(_t("Magnet deleted: {0}", name));
                                return;
                            }
                            File f = new File(name);
                            File dd = _manager.getDataDir();
                            boolean canDelete = dd.canWrite() || !f.exists();
                            _manager.stopTorrent(snark, canDelete);
                            // TODO race here with the DirMonitor, could get re-added
                            if (f.delete()) {
                                _manager.addMessage(_t("Torrent file deleted: {0}", f.getAbsolutePath()));
                            } else if (f.exists()) {
                                if (!canDelete)
                                    _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
                                _manager.addMessage(_t("Torrent file could not be deleted: {0}", f.getAbsolutePath()));
                                return;
                            }
                            Storage storage = snark.getStorage();
                            if (storage == null)
                                break;
                            List<List<String>> files = meta.getFiles();
                            if (files == null) { // single file torrent
                                for (File df : storage.getFiles()) {
                                    // should be only one
                                    if (df.delete())
                                        _manager.addMessage(_t("Data file deleted: {0}", df.getAbsolutePath()));
                                    else if (df.exists())
                                        _manager.addMessage(_t("Data file could not be deleted: {0}", df.getAbsolutePath()));
                                    // else already gone
                                }
                                break;
                            }
                            // step 1 delete files
                            for (File df : storage.getFiles()) {
                                if (df.delete()) {
                                    //_manager.addMessage(_t("Data file deleted: {0}", df.getAbsolutePath()));
                                } else if (df.exists()) {
                                    _manager.addMessage(_t("Data file could not be deleted: {0}", df.getAbsolutePath()));
                                // else already gone
                                }
                            }
                            // step 2 delete dirs bottom-up
                            Set<File> dirs = storage.getDirectories();
                            if (dirs == null)
                                break;  // directory deleted out from under us
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Dirs to delete: " + DataHelper.toString(dirs));
                            boolean ok = false;
                            for (File df : dirs) {
                                if (df.delete()) {
                                    ok = true;
                                    //_manager.addMessage(_t("Data dir deleted: {0}", df.getAbsolutePath()));
                                } else if (df.exists()) {
                                    ok = false;
                                    _manager.addMessage(_t("Directory could not be deleted: {0}", df.getAbsolutePath()));
                                    if (_log.shouldLog(Log.WARN))
                                        _log.warn("Could not delete dir " + df);
                                // else already gone
                                }
                            }
                            // step 3 message for base (last one)
                            if (ok)
                                _manager.addMessage(_t("Directory deleted: {0}", storage.getBase()));
                            break;
                        }
                    }
                }
            }
        } else if ("Save".equals(action)) {
            String dataDir = req.getParameter("nofilter_dataDir");
            boolean filesPublic = req.getParameter("filesPublic") != null;
            boolean autoStart = req.getParameter("autoStart") != null;
            boolean smartSort = req.getParameter("smartSort") != null;
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
            String lang = req.getParameter("lang");
            boolean ratings = req.getParameter("ratings") != null;
            boolean comments = req.getParameter("comments") != null;
            // commentsName is filtered in SnarkManager.updateConfig()
            String commentsName = req.getParameter("nofilter_commentsName");
            boolean collapsePanels = req.getParameter("collapsePanels") != null;
            _manager.updateConfig(dataDir, filesPublic, autoStart, smartSort, refreshDel, startupDel, pageSize,
                                  seedPct, eepHost, eepPort, i2cpHost, i2cpPort, i2cpOpts,
                                  upLimit, upBW, useOpenTrackers, useDHT, theme,
                                  lang, ratings, comments, commentsName, collapsePanels);
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
                    File dd = _manager.getDataDir();
                    if (!dd.canWrite()) {
                        _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
                        return;
                    }
                    String torrentName = baseFile.getName();
                    if (torrentName.toLowerCase(Locale.US).endsWith(".torrent")) {
                        _manager.addMessage(_t("Cannot add a torrent ending in \".torrent\": {0}", baseFile.getAbsolutePath()));
                        return;
                    }
                    Snark snark = _manager.getTorrentByBaseName(torrentName);
                    if (snark != null) {
                        _manager.addMessage(_t("Torrent with this name is already running: {0}", torrentName));
                        return;
                    }
                    if (isParentOf(baseFile,_manager.getDataDir()) ||
                        isParentOf(baseFile, _manager.util().getContext().getBaseDir()) ||
                        isParentOf(baseFile, _manager.util().getContext().getConfigDir())) {
                        _manager.addMessage(_t("Cannot add a torrent including an I2P directory: {0}", baseFile.getAbsolutePath()));
                        return;
                    }
                    Collection<Snark> snarks = _manager.getTorrents();
                    for (Snark s : snarks) {
                        Storage storage = s.getStorage();
                        if (storage == null)
                            continue;
                        File sbase = storage.getBase();
                        if (isParentOf(sbase, baseFile)) {
                            _manager.addMessage(_t("Cannot add torrent {0} inside another torrent: {1}",
                                                  baseFile.getAbsolutePath(), sbase));
                            return;
                        }
                        if (isParentOf(baseFile, sbase)) {
                            _manager.addMessage(_t("Cannot add torrent {0} including another torrent: {1}",
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
                            _manager.addMessage(_t("Error - Cannot include alternate trackers without a primary tracker"));
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
                            _manager.addMessage(_t("Error - Cannot mix private and public trackers in a torrent"));
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
                        Storage s = new Storage(_manager.util(), baseFile, announceURL, announceList, null, isPrivate, null);
                        s.close(); // close the files... maybe need a way to pass this Storage to addTorrent rather than starting over
                        MetaInfo info = s.getMetaInfo();
                        File torrentFile = new File(_manager.getDataDir(), s.getBaseName() + ".torrent");
                        // FIXME is the storage going to stay around thanks to the info reference?
                        // now add it, but don't automatically start it
                        boolean ok = _manager.addTorrent(info, s.getBitField(), torrentFile.getAbsolutePath(), baseFile, true);
                        if (!ok)
                            return;
                        _manager.addMessage(_t("Torrent created for \"{0}\"", baseFile.getName()) + ": " + torrentFile.getAbsolutePath());
                        if (announceURL != null && !_manager.util().getOpenTrackers().contains(announceURL))
                            _manager.addMessage(_t("Many I2P trackers require you to register new torrents before seeding - please do so before starting \"{0}\"", baseFile.getName()));
                    } catch (IOException ioe) {
                        _manager.addMessage(_t("Error creating a torrent for \"{0}\"", baseFile.getAbsolutePath()) + ": " + ioe);
                        _log.error("Error creating a torrent", ioe);
                    }
                } else {
                    _manager.addMessage(_t("Cannot create a torrent for the nonexistent data: {0}", baseFile.getAbsolutePath()));
                }
            } else {
                _manager.addMessage(_t("Error creating torrent - you must enter a file or directory"));
            }
        } else if ("StopAll".equals(action)) {
            _manager.stopAllTorrents(false);
        } else if ("StartAll".equals(action)) {
            _manager.startAllTorrents();
        } else if ("Clear".equals(action)) {
            String sid = req.getParameter("id");
            if (sid != null) {
                try {
                    int id = Integer.parseInt(sid);
                    _manager.clearMessages(id);
                } catch (NumberFormatException nfe) {}
            }
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
        resp.setStatus(303);
        resp.getOutputStream().close();
    }

    /** @since 0.9 */
    private void processTrackerForm(String action, HttpServletRequest req) {
        if (action.equals(_t("Delete selected")) || action.equals(_t("Save tracker configuration"))) {
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
                        _manager.addMessage(_t("Removed") + ": " + DataHelper.stripHTML(k));
                        changed = true;
                     }
                } else if (k.startsWith("ttype_")) {
                     String val = req.getParameter(k);
                     k = k.substring(6);
                     if ("1".equals(val))
                         open.add(k);
                     else if ("2".equals(val))
                         priv.add(k);
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

        } else if (action.equals(_t("Add tracker"))) {
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
                    String type = req.getParameter("add_tracker_type");
                    if ("1".equals(type)) {
                        List<String> newOpen = new ArrayList<String>(_manager.util().getOpenTrackers());
                        newOpen.add(aurl);
                        _manager.saveOpenTrackers(newOpen);
                    } else if ("2".equals(type)) {
                        List<String> newPriv = new ArrayList<String>(_manager.getPrivateTrackers());
                        newPriv.add(aurl);
                        _manager.savePrivateTrackers(newPriv);
                    }
                } else {
                    _manager.addMessage(_t("Enter valid tracker name and URLs"));
                }
            } else {
                _manager.addMessage(_t("Enter valid tracker name and URLs"));
            }
        } else if (action.equals(_t("Restore defaults"))) {
            _manager.setDefaultTrackerMap();
            _manager.saveOpenTrackers(null);
            _manager.addMessage(_t("Restored default trackers"));
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

    private List<Snark> getSortedSnarks(HttpServletRequest req) {
        ArrayList<Snark> rv = new ArrayList<Snark>(_manager.getTorrents());
        if (rv.size() > 1) {
            int sort = 0;
            String ssort = req.getParameter("sort");
            if (ssort != null) {
                try {
                    sort = Integer.parseInt(ssort);
                } catch (NumberFormatException nfe) {}
            }
            String lang;
            if (_manager.isSmartSortEnabled())
                lang = Translate.getLanguage(_manager.util().getContext());
            else
                lang = null;
            // Java 7 TimSort - may be unstable
            DataHelper.sort(rv, Sorters.getComparator(sort, lang, this));
        }
        return rv;
    }

    private static final int MAX_DISPLAYED_FILENAME_LENGTH = 50;
    private static final int MAX_DISPLAYED_ERROR_LENGTH = 43;

    /**
     *  Display one snark (one line in table, unless showPeers is true)
     *
     *  @param stats in/out param (totals)
     *  @param statsOnly if true, output nothing, update stats only
     *  @param canWrite is the i2psnark data directory writable?
     */
    private void displaySnark(PrintWriter out, HttpServletRequest req,
                              Snark snark, String uri, int row, long stats[], boolean showPeers,
                              boolean isDegraded, boolean noThinsp, boolean showDebug, boolean statsOnly,
                              boolean showRatios, boolean canWrite) throws IOException {
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
            String start = ServletUtil.truncate(basename, MAX_DISPLAYED_FILENAME_LENGTH);
            if (start.indexOf(' ') < 0 && start.indexOf('-') < 0) {
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
        String b64 = Base64.encode(snark.getInfoHash());
        String b64Short = b64.substring(0, 6);
        // isValid means isNotMagnet
        boolean isValid = meta != null;
        boolean isMultiFile = isValid && meta.getFiles() != null;

        String err = snark.getTrackerProblems();
        int knownPeers = Math.max(curPeers, snark.getTrackerSeenPeers());

        String rowClass = (row % 2 == 0 ? "snarkTorrentEven" : "snarkTorrentOdd");
        String statusString;
        if (snark.isChecking()) {
            statusString = toThemeImg("stalled", "", _t("Checking")) + "</td>" +
                           "<td class=\"snarkTorrentStatus\"><b>" + _t("Checking") + "</b>" + ' ' +
                           (new DecimalFormat("0.00%")).format(snark.getCheckingProgress());
        } else if (snark.isAllocating()) {
            statusString = toThemeImg("stalled", "", _t("Allocating")) + "</td>" +
                           "<td class=\"snarkTorrentStatus\"><b>" + _t("Allocating") + "</b>";
        } else if (err != null && isRunning && curPeers == 0) {
        //} else if (err != null && curPeers == 0) {
            // Also don't show if seeding... but then we won't see the not-registered error
            //       && remaining != 0 && needed != 0) {
            // let's only show this if we have no peers, otherwise PEX and DHT should bail us out, user doesn't care
            //if (isRunning && curPeers > 0 && !showPeers)
            //    statusString = "<img alt=\"\" border=\"0\" src=\"" + _imgPath + "trackererror.png\" title=\"" + err + "\"></td>" +
            //                   "<td class=\"snarkTorrentStatus " + rowClass + "\">" + _t("Tracker Error") +
            //                   ": <a href=\"" + uri + "?p=" + Base64.encode(snark.getInfoHash()) + "\">" +
            //                   curPeers + thinsp(noThinsp) +
            //                   ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
            //else if (isRunning)
            //if (isRunning) {
                statusString = toThemeImg("trackererror", "", err) + "</td>" +
                               "<td class=\"snarkTorrentStatus\"><b>" + _t("Tracker Error") +
                               ":</b> " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
            //} else {
            //    if (err.length() > MAX_DISPLAYED_ERROR_LENGTH)
            //        err = DataHelper.escapeHTML(err.substring(0, MAX_DISPLAYED_ERROR_LENGTH)) + "&hellip;";
            //    else
            //        err = DataHelper.escapeHTML(err);
            //    statusString = toThemeImg("trackererror", "", err) + "</td>" +
            //                   "<td class=\"snarkTorrentStatus\">" + _t("Tracker Error");
            //}
        } else if (snark.isStarting()) {
            statusString = toThemeImg("stalled", "", _t("Starting")) + "</td>" +
                           "<td class=\"snarkTorrentStatus\"><b class=\"alwaysShow\">" + _t("Starting") + "</b>";
        } else if (remaining == 0 || needed == 0) {  // < 0 means no meta size yet
            // partial complete or seeding
            if (isRunning) {
                String img;
                String txt;
                String tooltip;
                if (remaining == 0) {
                    img = "seeding";
                    txt = _t("Seeding");
                    tooltip = ngettext("Seeding to {0} peer", "Seeding to {0} peers", knownPeers);
                } else {
                    // partial
                    img = "complete";
                    txt = _t("Complete");
                    tooltip = txt;
                }
                if (curPeers > 0 && !showPeers) {
                    statusString = toThemeImg(img, "", tooltip) + "</td>" +
                               "<td class=\"snarkTorrentStatus\"><b>" + txt +
                               ":</b> <a href=\"" + uri + getQueryString(req, b64, null, null) + '#' + b64Short + "\">" +
                               curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
                } else {
                    statusString = toThemeImg(img, "", tooltip) + "</td>" +
                               "<td class=\"snarkTorrentStatus\"><b>" + txt +
                               ":</b> " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
                }
            } else {
                statusString = toThemeImg("complete", "", _t("Complete")) + "</td>" +
                               "<td class=\"snarkTorrentStatus\"><b class=\"alwaysShow\">" + _t("Complete") + "</b>";
            }
        } else {
            if (isRunning && curPeers > 0 && downBps > 0 && !showPeers) {
                statusString = toThemeImg("downloading", "", _t("OK") + " (" + _t("Downloading from {0} of {1} peers in swarm", curPeers, knownPeers) + ")") + "</td>" +
                               "<td class=\"snarkTorrentStatus\"><b>" + _t("OK") +
                               ":</b> <a href=\"" + uri + getQueryString(req, b64, null, null) + '#' + b64Short + "\">" +
                               curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
            } else if (isRunning && curPeers > 0 && downBps > 0) {
                statusString = toThemeImg("downloading", "", _t("OK") + ", " + ngettext("Downloading from {0} peer", "Downloading from {0} peers", curPeers)) + "</td>" +
                               "<td class=\"snarkTorrentStatus\"><b>" + _t("OK") +
                               ":</b> " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
            } else if (isRunning && curPeers > 0 && !showPeers) {
                statusString = toThemeImg("stalled", "", _t("Stalled") + " (" + ngettext("Connected to {0} peer", "Connected to {0} peers", curPeers)) + "</td>" +
                               "<td class=\"snarkTorrentStatus\"><b>" + _t("Stalled") +
                               ":</b> <a href=\"" + uri + getQueryString(req, b64, null, null) + '#' + b64Short + "\">" +
                               curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers) + "</a>";
            } else if (isRunning && curPeers > 0) {
                statusString = toThemeImg("stalled", "", _t("Stalled") + " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")") + "</td>" +
                               "<td class=\"snarkTorrentStatus\"><b>" + _t("Stalled") +
                               ":</b> " + curPeers + thinsp(noThinsp) +
                               ngettext("1 peer", "{0} peers", knownPeers);
            } else if (isRunning && knownPeers > 0) {
                statusString = toThemeImg("nopeers", "", _t("No Peers") + " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")") + "</td>" +
                               "<td class=\"snarkTorrentStatus\"><b>" + _t("No Peers") +
                               ":</b> 0" + thinsp(noThinsp) + knownPeers ;
            } else if (isRunning) {
                statusString = toThemeImg("nopeers", "", _t("No Peers")) + "</td>" +
                               "<td class=\"snarkTorrentStatus\"><b class=\"alwaysShow\">" + _t("No Peers") + "</b>";
            } else {
                statusString = toThemeImg("stopped", "", _t("Stopped")) + "</td>" +
                               "<td class=\"snarkTorrentStatus\"><b class=\"alwaysShow\">" + _t("Stopped") + "</b>";
            }
        }

        out.write("<tr class=\"" + rowClass + "\" id=\"" + b64Short + "\">");
        out.write("<td class=\"snarkGraphicStatus\" align=\"center\">");
        out.write(statusString + "</td>\n\t");

        // (i) icon column
        out.write("<td class=\"snarkTrackerDetails\">");
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
        out.write("</td>\n<td class=\"snarkTorrentDetails\">");
        if (isValid) {
            // Link to local details page - note that trailing slash on a single-file torrent
            // gets us to the details page instead of the file.
            StringBuilder buf = new StringBuilder(128);
            buf.append("<a href=\"").append(encodedBaseName)
               .append("/\" title=\"").append(_t("Torrent details"))
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
        out.write("</td><td class=\"snarkTorrentName\">");
        // No need for javascript here.. css now handles this
        //if (isMultiFile) {
            // link on the whole td
        //    out.write(" onclick=\"document.location='" + encodedBaseName + "/';\">");
        //} else {
        //    out.write('>');
        //}
        if (remaining == 0 || isMultiFile) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("<a href=\"").append(encodedBaseName);
            if (isMultiFile)
                buf.append('/');
            buf.append("\" title=\"");
            if (isMultiFile)
                buf.append(_t("View files"));
            else
                buf.append(_t("Open file"));
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
        if (remaining > 0) {
            long percent = 100 * (total - remaining) / total;
            out.write("<div class=\"percentBarOuter\">");
            out.write("<div class=\"percentBarInner\" style=\"width: " + percent + "%;\">");
            out.write("<div class=\"percentBarText\" tabindex=\"0\" title=\"");
            out.write(percent + "% " + _t("complete") + "; " + formatSize(remaining) + ' ' + _t("remaining"));
            out.write("\">");
            out.write(formatSize(total-remaining) + thinsp(noThinsp) + formatSize(total));
            out.write("</div></div></div>");
        } else if (remaining == 0) {
            // needs locale configured for automatic translation
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm, EEE dd MMM yyyy");
            fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
            long[] dates = _manager.getSavedAddedAndCompleted(snark);
            String date = fmt.format(new Date(dates[1]));
            out.write("<div class=\"percentBarComplete\" title=\"");
            out.write(_t("Completed") + ": " + date + "\">");
            out.write(formatSize(total)); // 3GB
            out.write("</div>");
        }
        //else
        //    out.write("??");  // no meta size yet
        out.write("</td>\n\t");
        out.write("<td align=\"right\" class=\"snarkTorrentUploaded\">");
        if (isValid) {
            if (showRatios) {
                if (total > 0) {
                    double ratio = uploaded / ((double) total);
                    out.write((new DecimalFormat("0.000")).format(ratio));
                    out.write("&nbsp;x");
                }
            } else if (uploaded > 0) {
                out.write(formatSize(uploaded));
            }
        }
        out.write("</td>\n\t");
        out.write("<td align=\"right\" class=\"snarkTorrentRateDown\">");
        if (isRunning && needed > 0 && (downBps > 0 || curPeers > 0))
            out.write(formatSizeDec(downBps) + "ps");
        out.write("</td>\n\t");
        out.write("<td align=\"right\" class=\"snarkTorrentRateUp\">");
        if (isRunning && isValid && (upBps > 0 || curPeers > 0))
            out.write(formatSizeDec(upBps) + "ps");
        out.write("</td>\n\t");
        out.write("<td align=\"center\" class=\"snarkTorrentAction\">");
        if (snark.isChecking()) {
            // show no buttons
        } else if (isRunning) {
            // Stop Button
            if (isDegraded)
                out.write("<a href=\"" + _contextPath + "/?action=Stop_" + b64 + "&amp;nonce=" + _nonce +
                          getQueryString(req, "", null, null).replace("?", "&amp;") + "\"><img title=\"");
            else
                out.write("<input type=\"image\" name=\"action_Stop_" + b64 + "\" value=\"foo\" title=\"");
            out.write(_t("Stop the torrent"));
            out.write("\" src=\"" + _imgPath + "stop.png\" alt=\"");
            out.write(_t("Stop"));
            out.write("\">");
            if (isDegraded)
                out.write("</a>");
        } else if (!snark.isStarting()) {
            if (!_manager.isStopping()) {
                // Start Button
                // This works in Opera but it's displayed a little differently, so use noThinsp here too so all 3 icons are consistent
                if (noThinsp)
                    out.write("<a href=\"" + _contextPath + "/?action=Start_" + b64 + "&amp;nonce=" + _nonce +
                              getQueryString(req, "", null, null).replace("?", "&amp;") + "\"><img title=\"");
                else
                    out.write("<input type=\"image\" name=\"action_Start_" + b64 + "\" value=\"foo\" title=\"");
                out.write(_t("Start the torrent"));
                out.write("\" src=\"" + _imgPath + "start.png\" alt=\"");
                out.write(_t("Start"));
                out.write("\">");
                if (isDegraded)
                    out.write("</a>");
            }
            if (isValid && canWrite) {
                // Remove Button
                // Doesnt work with Opera so use noThinsp instead of isDegraded
                if (noThinsp)
                    out.write("<a href=\"" + _contextPath + "/?action=Remove_" + b64 + "&amp;nonce=" + _nonce +
                              getQueryString(req, "", null, null).replace("?", "&amp;") + "\"><img title=\"");
                else
                    out.write("<input type=\"image\" name=\"action_Remove_" + b64 + "\" value=\"foo\" title=\"");
                out.write(_t("Remove the torrent from the active list, deleting the .torrent file"));
                out.write("\" onclick=\"if (!confirm('");
                // Can't figure out how to escape double quotes inside the onclick string.
                // Single quotes in translate strings with parameters must be doubled.
                // Then the remaining single quote must be escaped
                out.write(_t("Are you sure you want to delete the file \\''{0}\\'' (downloaded data will not be deleted) ?",
                            escapeJSString(snark.getName())));
                out.write("')) { return false; }\"");
                out.write(" src=\"" + _imgPath + "remove.png\" alt=\"");
                out.write(_t("Remove"));
                out.write("\">");
                if (isDegraded)
                    out.write("</a>");
            }

            // We can delete magnets without write privs
            if (!isValid || canWrite) {
                // Delete Button
                // Doesnt work with Opera so use noThinsp instead of isDegraded
                if (noThinsp)
                    out.write("<a href=\"" + _contextPath + "/?action=Delete_" + b64 + "&amp;nonce=" + _nonce +
                              getQueryString(req, "", null, null).replace("?", "&amp;") + "\"><img title=\"");
                else
                    out.write("<input type=\"image\" name=\"action_Delete_" + b64 + "\" value=\"foo\" title=\"");
                out.write(_t("Delete the .torrent file and the associated data files"));
                out.write("\" onclick=\"if (!confirm('");
                // Can't figure out how to escape double quotes inside the onclick string.
                // Single quotes in translate strings with parameters must be doubled.
                // Then the remaining single quote must be escaped
                out.write(_t("Are you sure you want to delete the torrent \\''{0}\\'' and all downloaded data?",
                            escapeJSString(fullBasename)));
                out.write("')) { return false; }\"");
                out.write(" src=\"" + _imgPath + "delete.png\" alt=\"");
                out.write(_t("Delete"));
                out.write("\">");
                if (isDegraded)
                    out.write("</a>");
            }
        }
        out.write("</td>\n</tr>\n");

        if(showPeers && isRunning && curPeers > 0) {
            List<Peer> peers = snark.getPeerList();
            if (!showDebug)
                Collections.sort(peers, new PeerComparator());
            for (Peer peer : peers) {
                if (!peer.isConnected())
                    continue;
                out.write("<tr class=\"peerinfo " + rowClass + "\"><td class=\"snarkGraphicStatus\" title=\"");
                out.write(_t("Peer attached to swarm"));
                out.write("\"></td><td colspan=\"4\">");
                PeerID pid = peer.getPeerID();
                String ch = pid != null ? pid.toString().substring(0, 4) : "????";
                String client;
                if ("AwMD".equals(ch))
                    client = _t("I2PSnark");
                else if ("LUFa".equals(ch))
                    client = "Vuze" + getAzVersion(pid.getID());
                else if ("LUJJ".equals(ch))
                    client = "BiglyBT" + getAzVersion(pid.getID());
                else if ("LVhE".equals(ch))
                    client = "XD" + getAzVersion(pid.getID());
                else if ("ZV".equals(ch.substring(2,4)) || "VUZP".equals(ch))
                    client = "Robert" + getRobtVersion(pid.getID());
                else if (ch.startsWith("LV")) // LVCS 1.0.2?; LVRS 1.0.4
                    client = "Transmission" + getAzVersion(pid.getID());
                else if ("LUtU".equals(ch))
                    client = "KTorrent" + getAzVersion(pid.getID());
                else if ("CwsL".equals(ch))
                    client = "I2PSnarkXL";
                else if ("BFJT".equals(ch))
                    client = "I2PRufus";
                else if ("TTMt".equals(ch))
                    client = "I2P-BT";
                else
                    client = _t("Unknown") + " (" + ch + ')';
                out.write(client + "&nbsp;<tt title=\"");
                out.write(_t("Destination (identity) of peer"));
                out.write("\">" + peer.toString().substring(5, 9)+ "</tt>");
                if (showDebug)
                    out.write(" inactive " + (peer.getInactiveTime() / 1000) + "s");
                out.write("</td>\n\t");
                out.write("<td class=\"snarkTorrentETA\">");
                out.write("</td>\n\t");
                out.write("<td align=\"right\" class=\"snarkTorrentDownloaded\">");
                float pct;
                if (isValid) {
                    pct = (float) (100.0 * peer.completed() / meta.getPieces());
                    if (pct >= 100.0)
                        out.write(_t("Seed"));
                    else {
                        String ps = String.valueOf(pct);
                        if (ps.length() > 5)
                            ps = ps.substring(0, 5);
                        out.write("<div class=\"percentBarOuter\">");
                        out.write("<div class=\"percentBarInner\" style=\"width:" + ps + "%;\">");
                        out.write("<div class=\"percentBarText\" tabindex=\"0\">" + ps + "%</div>");
                        out.write("</div></div>");
                    }
                } else {
                    pct = (float) 101.0;
                    // until we get the metainfo we don't know how many pieces there are
                    //out.write("??");
                }
                out.write("</td>\n\t");
                out.write("<td class=\"snarkTorrentUploaded\">");
                out.write("</td>\n\t");
                out.write("<td align=\"right\" class=\"snarkTorrentRateDown\">");
                if (needed > 0) {
                    if (peer.isInteresting() && !peer.isChoked()) {
                        out.write("<span class=\"unchoked\">");
                        out.write(formatSizeDec(peer.getDownloadRate()) + "ps</span>");
                    } else {
                        out.write("<span class=\"choked\" title=\"");
                        if (!peer.isInteresting())
                            out.write(_t("Uninteresting (The peer has no pieces we need)"));
                        else
                            out.write(_t("Choked (The peer is not allowing us to request pieces)"));
                        out.write("\">");
                        out.write(formatSizeDec(peer.getDownloadRate()) + "ps</span>");
                    }
                } else if (!isValid) {
                    //if (peer supports metadata extension) {
                        out.write("<span class=\"unchoked\">");
                        out.write(formatSizeDec(peer.getDownloadRate()) + "ps</span>");
                    //} else {
                    //}
                }
                out.write("</td>\n\t");
                out.write("<td align=\"right\" class=\"snarkTorrentRateUp\">");
                if (isValid && pct < 100.0) {
                    if (peer.isInterested() && !peer.isChoking()) {
                        out.write("<span class=\"unchoked\">");
                        out.write(formatSizeDec(peer.getUploadRate()) + "ps</span>");
                    } else {
                        out.write("<span class=\"choked\" title=\"");
                        if (!peer.isInterested())
                            out.write(_t("Uninterested (We have no pieces the peer needs)"));
                        else
                            out.write(_t("Choking (We are not allowing the peer to request pieces)"));
                        out.write("\">");
                        out.write(formatSizeDec(peer.getUploadRate()) + "ps</span>");
                    }
                }
                out.write("</td>\n\t");
                out.write("<td class=\"snarkTorrentAction\">");
                out.write("</td></tr>\n\t");
                if (showDebug)
                    out.write("<tr class=\"debuginfo " + rowClass + "\"><td class=\"snarkGraphicStatus\"></td><td colspan=\"10\">" + peer.getSocket() + "</td></tr>");
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
        StringBuilder buf = new StringBuilder(8);
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
                   .append("\" title=\"").append(_t("Details at {0} tracker", name)).append("\" target=\"_blank\">");
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
            buf.append(linkUrl);
            toThemeImg(buf, "details", _t("Info"), "");
            buf.append("</a>");
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
            buf.append("<a href=\"http://").append(urlEncode(host)).append("/\" target=\"blank\">");
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
        writeHiddenInputs(out, req, "Add");
        out.write("<div class=\"addtorrentsection\">");
        out.write("<input class=\"toggle_input\" id=\"toggle_addtorrent\" type=\"checkbox\"><label class=\"toggleview\" for=\"toggle_addtorrent\">");
        out.write(toThemeImg("add"));
        out.write(' ');
        out.write(_t("Add Torrent"));
        out.write("</label>");

        out.write("<hr>\n<table border=\"0\"><tr><td>");
        out.write(_t("From URL"));
        out.write(":<td><input type=\"text\" name=\"nofilter_newURL\" size=\"85\" value=\"" + newURL + "\" spellcheck=\"false\"");
        out.write(" title=\"");
        out.write(_t("Enter the torrent file download URL (I2P only), magnet link, maggot link, or info hash"));
        out.write("\">\n");
        // not supporting from file at the moment, since the file name passed isn't always absolute (so it may not resolve)
        //out.write("From file: <input type=\"file\" name=\"newFile\" size=\"50\" value=\"" + newFile + "\" /><br>");
        out.write("<input type=\"submit\" class=\"add\" value=\"");
        out.write(_t("Add torrent"));
        out.write("\" name=\"foo\" ><br>\n" +
                  "<tr><td>");

        out.write(_t("Data dir"));
        out.write(":<td><input type=\"text\" name=\"nofilter_newDir\" size=\"85\" value=\"\" spellcheck=\"false\"");
        out.write(" title=\"");
        out.write(_t("Enter the directory to save the data in (default {0})", _manager.getDataDir().getAbsolutePath()));
        out.write("\"></td></tr>\n");

        out.write("<tr><td>&nbsp;<td><span class=\"snarkAddInfo\">");
        out.write(_t("You can also copy .torrent files to: {0}.", "<code>" + _manager.getDataDir().getAbsolutePath() + "</code>"));
        out.write("\n");
        out.write(_t("Removing a .torrent will cause it to stop."));
        out.write("<br></span></table>\n");
        out.write("</div></form></div>");
    }

    private void writeSeedForm(PrintWriter out, HttpServletRequest req, List<Tracker> sortedTrackers) throws IOException {
        out.write("<a name=\"add\"></a><div class=\"newtorrentsection\"><div class=\"snarkNewTorrent\">\n");
        // *not* enctype="multipart/form-data", so that the input type=file sends the filename, not the file
        out.write("<form action=\"_post\" method=\"POST\">\n");
        writeHiddenInputs(out, req, "Create");
        out.write("<input class=\"toggle_input\" id=\"toggle_createtorrent\" type=\"checkbox\"><label class=\"toggleview\" for=\"toggle_createtorrent\">");
        out.write(toThemeImg("create"));
        out.write(' ');
        out.write(_t("Create Torrent"));
        out.write("</label><hr>\n<table border=\"0\"><tr><td>");
        //out.write("From file: <input type=\"file\" name=\"newFile\" size=\"50\" value=\"" + newFile + "\" /><br>\n");
        out.write(_t("Data to seed"));
        out.write(":<td>"
                  + "<input type=\"text\" name=\"nofilter_baseFile\" size=\"85\" value=\""
                  + "\" spellcheck=\"false\" title=\"");
        out.write(_t("File or directory to seed (full path or within the directory {0} )",
                    _manager.getDataDir().getAbsolutePath() + File.separatorChar));
        out.write("\" > <input type=\"submit\" class=\"create\" value=\"");
        out.write(_t("Create torrent"));
        out.write("\" name=\"foo\" >");
        out.write("<tr><td>\n");
        out.write(_t("Trackers"));
        out.write(":<td><table id=\"trackerselect\"><tr><td>Name</td><td align=\"center\">");
        out.write(_t("Primary"));
        out.write("</td><td align=\"center\">");
        out.write(_t("Alternates"));
        out.write("</td><td>");
        out.write(_t("Tracker Type"));
        out.write("</td></tr>\n");

        for (Tracker t : sortedTrackers) {
            List<String> openTrackers = _manager.util().getOpenTrackers();
            List<String> privateTrackers = _manager.getPrivateTrackers();
            boolean isPrivate = privateTrackers.contains(t.announceURL);
            boolean isKnownOpen = _manager.util().isKnownOpenTracker(t.announceURL);
            boolean isOpen = isKnownOpen || openTrackers.contains(t.announceURL);
            String name = t.name;
            String announceURL = t.announceURL.replace("&#61;", "=");
            String homeURL = t.baseURL;
            out.write("<tr><td><span class=\"trackerName\">");
            out.write(name);
            out.write("</span></td><td align=\"center\"><input type=\"radio\" name=\"announceURL\" value=\"");
            out.write(announceURL);
            out.write("\"");
            if (announceURL.equals(_lastAnnounceURL))
                out.write(" checked");
            out.write("></td><td align=\"center\"><input type=\"checkbox\" name=\"backup_");
            out.write(announceURL);
            out.write("\" value=\"foo\"></td><td>");

            if (!(isOpen || isPrivate))
                out.write(_t("Standard"));
            if (isOpen)
                out.write(_t("Open"));
            if (isPrivate) {
                out.write(_t("Private"));
            }
        }
        out.write("</td></tr><tr><td><i>");
        out.write(_t("none"));
        out.write("</i></td><td align=\"center\"><input type=\"radio\" name=\"announceURL\" value=\"none\"");
        if (_lastAnnounceURL == null)
            out.write(" checked");
        out.write("></td><td></td><td></td></tr></table>\n");
        // make the user add a tracker on the config form now
        //out.write(_t("or"));
        //out.write("&nbsp;<input type=\"text\" name=\"announceURLOther\" size=\"57\" value=\"http://\" " +
        //          "title=\"");
        //out.write(_t("Specify custom tracker announce URL"));
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
        boolean smartSort = _manager.isSmartSortEnabled();
        boolean useOpenTrackers = _manager.util().shouldUseOpenTrackers();
        //String openTrackers = _manager.util().getOpenTrackerString();
        boolean useDHT = _manager.util().shouldUseDHT();
        boolean useRatings = _manager.util().ratingsEnabled();
        boolean useComments = _manager.util().commentsEnabled();
        boolean collapsePanels = _manager.util().collapsePanels();
        //int seedPct = 0;

        boolean noCollapse = noCollapsePanels(req);

        out.write("<form action=\"" + _contextPath + "/configure\" method=\"POST\">\n" +
                  "<div class=\"configsectionpanel\"><div class=\"snarkConfig\">\n");
        writeHiddenInputs(out, req, "Save");
        out.write("<span class=\"snarkConfigTitle\">");
        out.write(toThemeImg("config"));
        out.write(' ');
        out.write(_t("Configuration"));
        out.write("</span><hr>\n"   +
                  "<table border=\"0\" id=\"configs\"><tr><td>");

        out.write(_t("Data directory"));
        out.write(":<td colspan=\"2\"><input name=\"nofilter_dataDir\" size=\"80\""
                  + " title=\"");
        out.write(_t("Directory where torrents and downloaded/shared files are stored"));
        out.write("\" value=\"" +
                  DataHelper.escapeHTML(dataDir) + "\" spellcheck=\"false\"></td>\n" +

                  "<tr><td><label for=\"filesPublic\">");
        out.write(_t("Files readable by all"));
        out.write(":</label><td colspan=\"2\"><input type=\"checkbox\" class=\"optbox\" name=\"filesPublic\" id=\"filesPublic\" value=\"true\" "
                  + (filesPublic ? "checked " : "") 
                  + "title=\"");
        out.write(_t("Set file permissions to allow other local users to access the downloaded files"));
        out.write("\" >" +

                  "<tr><td><label for=\"autoStart\">");
        out.write(_t("Auto start torrents"));
        out.write(":</label><td colspan=\"2\"><input type=\"checkbox\" class=\"optbox\" name=\"autoStart\" id=\"autoStart\" value=\"true\" "
                  + (autoStart ? "checked " : "") 
                  + "title=\"");
        out.write(_t("Automatically start torrents when added and restart torrents when I2PSnark starts"));
        out.write("\" >" +

                  "<tr><td><label for=\"smartSort\">");
        out.write(_t("Smart torrent sorting"));
        out.write(":</label><td colspan=\"2\"><input type=\"checkbox\" class=\"optbox\" name=\"smartSort\" id=\"smartSort\" value=\"true\" "
                  + (smartSort ? "checked " : "")
                  + "title=\"");
        out.write(_t("Ignore words such as 'a' and 'the' when sorting"));
        out.write("\" >" +

                  "<tr><td><label for=\"collapsePanels\">");
        out.write(_t("Collapsible panels"));
        out.write(":</label><td colspan=\"2\"><input type=\"checkbox\" class=\"optbox\" name=\"collapsePanels\" id=\"collapsePanels\" value=\"true\" "
                  + (collapsePanels ? "checked " : "")
                  + "title=\"");
        if (noCollapse) {
            out.write(_t("Your browser does not support this feature."));
            out.write("\" disabled=\"disabled");
        } else {
            out.write(_t("Allow the 'Add Torrent' and 'Create Torrent' panels to be collapsed, and collapse by default in non-embedded mode"));
        }
        out.write("\">");

        if (!_context.isRouterContext()) {
            try {
                // class only in standalone builds
                Class helper = Class.forName("org.klomp.snark.standalone.ConfigUIHelper");
                Method getLangSettings = helper.getMethod("getLangSettings", new Class[] {I2PAppContext.class});
                String langSettings = (String) getLangSettings.invoke(null, _context);
                // If we get to here, we have the language settings
                out.write("<tr><td>");
                out.write(_t("Language"));
                out.write(": <td colspan=\"2\">");
                out.write(langSettings);
            } catch (ClassNotFoundException e) {
            } catch (NoSuchMethodException e) {
            } catch (IllegalAccessException e) {
            } catch (InvocationTargetException e) {
            }
        }

        out.write("<tr><td>");
        out.write(_t("Theme"));
        out.write(":<td colspan=\"2\">");
        if (_manager.getUniversalTheming()) {
            out.write("<select name='theme' disabled=\"disabled\" title=\"");
            out.write(_t("To change themes manually, disable universal theming"));
            out.write("\"><option>");
            out.write(_manager.getTheme());
            out.write("</option></select><i>");
            out.write(_t("Universal theming is enabled."));
            out.write("</i> <a href=\"/configui\" target=\"_blank\">[");
            out.write(_t("Configure"));
            out.write("]</a>");
        } else {
            out.write("<select name='theme'>");
            String theme = _manager.getTheme();
            String[] themes = _manager.getThemes();
            Arrays.sort(themes);
            for (int i = 0; i < themes.length; i++) {
                if(themes[i].equals(theme))
                    out.write("\n<OPTION value=\"" + themes[i] + "\" SELECTED>" + themes[i]);
                else
                    out.write("\n<OPTION value=\"" + themes[i] + "\">" + themes[i]);
            }
            out.write("</select>\n");
        }

        out.write("<tr><td>");
        out.write(_t("Refresh time"));
        out.write(":<td colspan=\"2\"><select name=\"refreshDelay\""
                  + " title=\"");
        out.write(_t("How frequently torrent status is updated on the main page"));
        out.write("\">");
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
                out.write(_t("Never"));
            out.write("</option>\n");
        }
        out.write("</select>" +

                  "<tr><td>");
        if (_context.isRouterContext()) {
            out.write(_t("Startup delay"));
            out.write(": <td colspan=\"2\"><input name=\"startupDelay\" size=\"4\" class=\"r\""
                      + " title=\"");
            out.write(_t("How long before auto-started torrents are loaded when I2PSnark starts"));
            out.write("\" value=\"" + _manager.util().getStartupDelay() + "\"> ");
            out.write(_t("minutes"));
            out.write("\n" +

                      "<tr><td>");
        }
        out.write(_t("Page size"));
        out.write(":<td colspan=\"2\"><input name=\"pageSize\" size=\"4\" maxlength=\"6\" class=\"r\""
                  + " title=\"");
        out.write(_t("Maximum number of torrents to display per page"));
        out.write("\" value=\"" + _manager.getPageSize() + "\"> ");
        out.write(_t("torrents"));
        out.write("\n");


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
        out.write(_t("Total uploader limit"));
        out.write(":<td colspan=\"2\"><input type=\"text\" name=\"upLimit\" class=\"r\" value=\""
                  + _manager.util().getMaxUploaders() + "\" size=\"4\" maxlength=\"3\""
                  + " title=\"");
        out.write(_t("Maximum number of peers for uploading"));
        out.write("\"> ");
        out.write(_t("peers"));
        out.write("\n" +

                  "<tr><td>");
        out.write(_t("Up bandwidth limit"));
        out.write(":<td><input type=\"text\" name=\"upBW\" class=\"r\" value=\""
                  + _manager.util().getMaxUpBW() + "\" size=\"4\" maxlength=\"4\""
                  + " title=\"");
        out.write(_t("Maximum bandwidth allocated for uploading"));
        out.write("\"> KBps <td id=\"bwHelp\"><i>");
        out.write(_t("Half available bandwidth recommended."));
        if (_context.isRouterContext()) {
            out.write("</i> <a href=\"/config.jsp\" target=\"blank\" title=\"");
            out.write(_t("View or change router bandwidth"));
            out.write("\">[");
            out.write(_t("Configure"));
            out.write("]</a>");
        }
        out.write("\n<tr><td><label for=\"useOpenTrackers\">");
        out.write(_t("Use open trackers also"));
        out.write(":</label><td colspan=\"2\"><input type=\"checkbox\" class=\"optbox\" name=\"useOpenTrackers\" id=\"useOpenTrackers\" value=\"true\" "
                  + (useOpenTrackers ? "checked " : "")
                  + "title=\"");
        out.write(_t("Announce torrents to open trackers as well as trackers listed in the torrent file"));
        out.write("\" ></td></tr>\n" +

                  "<tr><td><label for=\"useDHT\">");
        out.write(_t("Enable DHT"));
        out.write(":</label><td colspan=\"2\"><input type=\"checkbox\" class=\"optbox\" name=\"useDHT\" id=\"useDHT\" value=\"true\" "
                  + (useDHT ? "checked " : "")
                  + "title=\"");
        out.write(_t("Use DHT to find additional peers"));
        out.write("\" ></td></tr>\n" +

                  "<tr><td><label for=\"ratings\">");
        out.write(_t("Enable Ratings"));
        out.write(":</label><td colspan=\"2\"><input type=\"checkbox\" class=\"optbox\" name=\"ratings\" id=\"ratings\" value=\"true\" "
                  + (useRatings ? "checked " : "") 
                  + "title=\"");
        out.write(_t("Show ratings on torrent pages"));
        out.write("\" ></td></tr>\n" +

                  "<tr><td><label for=\"comments\">");
        out.write(_t("Enable Comments"));
        out.write(":</label><td colspan=\"2\"><input type=\"checkbox\" class=\"optbox\" name=\"comments\" id=\"comments\" value=\"true\" "
                  + (useComments ? "checked " : "") 
                  + "title=\"");
        out.write(_t("Show comments on torrent pages"));
        out.write("\"></td></tr><tr id=\"configureAuthor\"><td>");
        out.write(_t("Comment Author"));
        out.write(":</td><td colspan=\"2\"><input type=\"text\" name=\"nofilter_commentsName\" spellcheck=\"false\" value=\""
                      + DataHelper.escapeHTML(_manager.util().getCommentsName()) + "\" size=\"32\" maxlength=\"32\" title=\"");
            out.write(_t("Set the author name for your comments and ratings"));
            out.write("\" ></td></tr>\n");

        //          "<tr><td>");
        //out.write(_t("Open tracker announce URLs"));
        //out.write(": <td><input type=\"text\" name=\"openTrackers\" value=\""
        //          + openTrackers + "\" size=\"50\" ><br>\n");

        //out.write("\n");
        //out.write("EepProxy host: <input type=\"text\" name=\"eepHost\" value=\""
        //          + _manager.util().getEepProxyHost() + "\" size=\"15\" /> ");
        //out.write("port: <input type=\"text\" name=\"eepPort\" value=\""
        //          + _manager.util().getEepProxyPort() + "\" size=\"5\" maxlength=\"5\" /><br>\n");

        Map<String, String> options = new TreeMap<String, String>(_manager.util().getI2CPOptions());
        out.write("<tr><td>");
        out.write(_t("Inbound Settings"));
        out.write(":<td colspan=\"2\">");
        out.write(renderOptions(1, 10, SnarkManager.DEFAULT_TUNNEL_QUANTITY,
                                options.remove("inbound.quantity"), "inbound.quantity", TUNNEL));
        out.write("&nbsp;");
        out.write(renderOptions(0, 4, 3, options.remove("inbound.length"), "inbound.length", HOP));
        out.write("<tr><td>");
        out.write(_t("Outbound Settings"));
        out.write(":<td colspan=\"2\">");
        out.write(renderOptions(1, 10, SnarkManager.DEFAULT_TUNNEL_QUANTITY,
                                options.remove("outbound.quantity"), "outbound.quantity", TUNNEL));
        out.write("&nbsp;");
        out.write(renderOptions(0, 4, 3, options.remove("outbound.length"), "outbound.length", HOP));

        if (!_context.isRouterContext()) {
            out.write("<tr><td>");
            out.write(_t("I2CP host"));
            out.write(":<td colspan=\"2\"><input type=\"text\" name=\"i2cpHost\" value=\""
                      + _manager.util().getI2CPHost() + "\" size=\"15\" > " +

                      "<tr><td>");
            out.write(_t("I2CP port"));
            out.write(":<td colspan=\"2\"><input type=\"text\" name=\"i2cpPort\" class=\"r\" value=\"" +
                      + _manager.util().getI2CPPort() + "\" size=\"5\" maxlength=\"5\" >\n");
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
        out.write(_t("I2CP options"));
        out.write(":<td colspan=\"2\"><textarea name=\"i2cpOpts\" cols=\"60\" rows=\"1\" wrap=\"off\" spellcheck=\"false\" >"
                  + opts.toString() + "</textarea>\n" +
                  "<tr class=\"spacer\"><td colspan=\"3\">&nbsp;\n" +  // spacer
                  "<tr><td colspan=\"3\"><input type=\"submit\" class=\"accept\" value=\"");
        out.write(_t("Save configuration"));
        out.write("\" name=\"foo\" >\n" +
                  "<tr class=\"spacer\"><td colspan=\"3\">&nbsp;\n" +  // spacer
                  "</table></div></div></form>");
    }

    /** @since 0.9 */
    private void writeTrackerForm(PrintWriter out, HttpServletRequest req) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<form action=\"" + _contextPath + "/configure\" method=\"POST\">\n" +
                   "<div class=\"configsectionpanel\"><div class=\"snarkConfig\">\n");
        writeHiddenInputs(buf, req, "Save2");
        buf.append("<span class=\"snarkConfigTitle\">");
        toThemeImg(buf, "config");
        buf.append(' ');
        buf.append(_t("Trackers"));
        buf.append("</span><hr>\n"   +
                   "<table class=\"trackerconfig\"><tr><th title=\"")
            .append(_t("Select trackers for removal from I2PSnark's known list"))
           //.append(_t("Remove"))
           .append("\"></th><th>")
           .append(_t("Name"))
           .append("</th><th>")
           .append(_t("Website URL"))
           .append("</th><th>")
           .append(_t("Standard"))
           .append("</th><th>")
           .append(_t("Open"))
           .append("</th><th>")
           .append(_t("Private"))
           .append("</th><th>")
           .append(_t("Announce URL"))
           .append("</th></tr>\n");
        List<String> openTrackers = _manager.util().getOpenTrackers();
        List<String> privateTrackers = _manager.getPrivateTrackers();
        for (Tracker t : _manager.getSortedTrackers()) {
            String name = t.name;
            String homeURL = t.baseURL;
            String announceURL = t.announceURL.replace("&#61;", "=");
            boolean isPrivate = privateTrackers.contains(t.announceURL);
            boolean isKnownOpen = _manager.util().isKnownOpenTracker(t.announceURL);
            boolean isOpen = isKnownOpen || openTrackers.contains(t.announceURL);
            buf.append("<tr class=\"knownTracker\"><td><input type=\"checkbox\" class=\"optbox\" id=\"").append(name).append("\" name=\"delete_")
               .append(name).append("\" title=\"").append(_t("Mark tracker for deletion")).append("\">" +
                       "</td><td><label for=\"").append(name).append("\">").append(name)
               .append("</label></td><td>").append(urlify(homeURL, 35))
               .append("</td><td><input type=\"radio\" class=\"optbox\" value=\"0\" tabindex=\"-1\" name=\"ttype_")
               .append(announceURL).append("\"");
            if (!(isOpen || isPrivate))
                buf.append(" checked=\"checked\"");
            else if (isKnownOpen)
                buf.append(" disabled=\"disabled\"");
            buf.append(">" +
                       "</td><td><input type=\"radio\" class=\"optbox\" value=\"1\" tabindex=\"-1\" name=\"ttype_")
               .append(announceURL).append("\"");
            if (isOpen)
                buf.append(" checked=\"checked\"");
            else if (t.announceURL.equals("http://diftracker.i2p/announce.php") ||
                     t.announceURL.equals("http://tracker2.postman.i2p/announce.php"))
                buf.append(" disabled=\"disabled\"");
            buf.append(">" +
                       "</td><td><input type=\"radio\" class=\"optbox\" value=\"2\" tabindex=\"-1\" name=\"ttype_")
               .append(announceURL).append("\"");
            if (isPrivate) {
                buf.append(" checked=\"checked\"");
            } else if (isKnownOpen ||
                       t.announceURL.equals("http://diftracker.i2p/announce.php") ||
                       t.announceURL.equals("http://tracker2.postman.i2p/announce.php")) {
                buf.append(" disabled=\"disabled\"");
            }
            buf.append(">" +
                       "</td><td>").append(urlify(announceURL, 35))
               .append("</td></tr>\n");
        }
        buf.append("<tr><td><b>")
           .append(_t("Add")).append(":</b></td>" +
                   "<td><input type=\"text\" class=\"trackername\" name=\"tname\" spellcheck=\"false\"></td>" +
                   "<td><input type=\"text\" class=\"trackerhome\" name=\"thurl\" spellcheck=\"false\"></td>" +
                   "<td><input type=\"radio\" class=\"optbox\" value=\"0\" name=\"add_tracker_type\" checked=\"checked\"></td>" +
                   "<td><input type=\"radio\" class=\"optbox\" value=\"1\" name=\"add_tracker_type\"></td>" +
                   "<td><input type=\"radio\" class=\"optbox\" value=\"2\" name=\"add_tracker_type\"></td>" +
                   "<td><input type=\"text\" class=\"trackerannounce\" name=\"taurl\" spellcheck=\"false\"></td></tr>\n" +
                   "<tr class=\"spacer\"><td colspan=\"7\">&nbsp;</td></tr>\n" +  // spacer
                   "<tr><td colspan=\"7\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"default\" value=\"").append(_t("Add tracker")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"delete\" value=\"").append(_t("Delete selected")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"add\" value=\"").append(_t("Add tracker")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"accept\" value=\"").append(_t("Save tracker configuration")).append("\">\n" +
                   // "<input type=\"reset\" class=\"cancel\" value=\"").append(_t("Cancel")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"reload\" value=\"").append(_t("Restore defaults")).append("\">\n" +
                   "</td></tr>" +
                   "<tr class=\"spacer\"><td colspan=\"7\">&nbsp;</td></tr>\n" +  // spacer
                   "</table></div></div></form>\n");
        out.write(buf.toString());
    }

    private void writeConfigLink(PrintWriter out) throws IOException {
        out.write("<div class=\"configsection\"><span class=\"snarkConfig\">\n" +
                  "<span class=\"snarkConfigTitle\"><a href=\"configure\">");
        out.write(toThemeImg("config"));
        out.write(' ');
        out.write(_t("Configuration"));
        out.write("</a></span></span></div>\n");
    }

    /**
     *  @param url in base32 or hex
     *  @param dataDir null to default to snark data directory
     *  @since 0.8.4
     */
    private void addMagnet(String url, File dataDir) {
        try {
            MagnetURI magnet = new MagnetURI(_manager.util(), url);
            String name = magnet.getName();
            byte[] ih = magnet.getInfoHash();
            String trackerURL = magnet.getTrackerURL();
            _manager.addMagnet(name, ih, trackerURL, true, dataDir);
        } catch (IllegalArgumentException iae) {
            _manager.addMessage(_t("Invalid magnet URL {0}", url));
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
    private String renderOptions(int min, int max, int dflt, String strNow, String selName, String name) {
        int now = dflt;
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
    private String _t(String s) {
        return _manager.util().getString(s);
    }

    /** translate */
    private String _t(String s, Object o) {
        return _manager.util().getString(s, o);
    }

    /** translate */
    private String _t(String s, Object o, Object o2) {
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

    private static String formatSize(long bytes) {
        return DataHelper.formatSize2(bytes) + 'B';	
    }

    /** @since 0.9.34 */
    private static String formatSizeDec(long bytes) {
        return DataHelper.formatSize2Decimal(bytes) + 'B';	
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
            display = escapeHTML2(link);
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

    private static final String escapeChars[] = {"\"", "<", ">", "'"};
    private static final String escapeCodes[] = {"&quot;", "&lt;", "&gt;", "&apos;"};

    /**
     * Modded from DataHelper.
     * Does not escape ampersand. String must already have escaped ampersand.
     * @param unescaped the unescaped string, non-null
     * @return the escaped string
     * @since 0.9.33
     */
    private static String escapeHTML2(String unescaped) {
        String escaped = unescaped;
        for (int i = 0; i < escapeChars.length; i++) {
            escaped = escaped.replace(escapeChars[i], escapeCodes[i]);
        }
        return escaped;
    }

    private static final String DOCTYPE = "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\">\n";
    private static final String HEADER_A = "<link href=\"";
    private static final String HEADER_B = "snark.css?" + CoreVersion.VERSION + "\" rel=\"stylesheet\" type=\"text/css\" >";
    private static final String HEADER_C = "nocollapse.css?" + CoreVersion.VERSION + "\" rel=\"stylesheet\" type=\"text/css\" >";

    private static final String TABLE_HEADER = "<table border=\"0\" class=\"snarkTorrents\" width=\"100%\" >\n" +
                                               "<thead>\n";

    private static final String FOOTER = "</div></center>\n</body>\n</html>";


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
     * @param sortParam may be null
     * @return String of HTML or null if postParams != null
     * @since 0.7.14
     */
    private String getListHTML(File xxxr, String base, boolean parent, Map<String, String[]> postParams, String sortParam)
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
                if (String.valueOf(_nonce).equals(nonce)) {
                    if (postParams.get("savepri") != null) {
                        savePriorities(snark, postParams);
                    } else if (postParams.get("addComment") != null) {
                        saveComments(snark, postParams);
                    } else if (postParams.get("deleteComments") != null) {
                        deleteComments(snark, postParams);
                    } else if (postParams.get("setCommentsEnabled") != null) {
                        saveCommentsSetting(snark, postParams);
                    } else if (postParams.get("stop") != null) {
                        _manager.stopTorrent(snark, false);
                    } else if (postParams.get("start") != null) {
                        _manager.startTorrent(snark);
                    } else if (postParams.get("recheck") != null) {
                        _manager.recheckTorrent(snark);
                    } else {
                        _manager.addMessage("Unknown command");
                    }
                } else {
                    _manager.addMessage("Please retry form submission (bad nonce)");
                }
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

        boolean showStopStart = snark != null;
        boolean showPriority = snark != null && snark.getStorage() != null && !snark.getStorage().complete() &&
                               r.isDirectory();

        StringBuilder buf=new StringBuilder(4096);
        buf.append(DOCTYPE).append("<html><head><title>");
        if (title.endsWith("/"))
            title = title.substring(0, title.length() - 1);
        final String directory = title;
        final int dirSlash = directory.indexOf('/');
        final boolean isTopLevel = dirSlash <= 0;
        title = _t("Torrent") + ": " + DataHelper.escapeHTML(title);
        buf.append(title);
        buf.append("</title>\n").append(HEADER_A).append(_themePath).append(HEADER_B)
           // hide javascript-dependent buttons when js is unavailable
           .append("<noscript><style type=\"text/css\">.script {display: none;}</style></noscript>")
           .append("<link rel=\"shortcut icon\" href=\"" + _themePath + "favicon.ico\">\n");
        if (showPriority)
            buf.append("<script src=\"").append(_contextPath).append(WARBASE + "js/folder.js\" type=\"text/javascript\"></script>\n");
        buf.append("</head><body");
        if (showPriority)
            buf.append(" onload=\"setupbuttons()\"");
        buf.append(">\n<center><div class=\"snarknavbar\"><a href=\"").append(_contextPath).append("/\" title=\"Torrents\"");
        buf.append(" class=\"snarkNav nav_main\">");
        if (_contextName.equals(DEFAULT_NAME))
            buf.append(_t("I2PSnark"));
        else
            buf.append(_contextName);
        buf.append("</a></div>\n");

        if (parent)  // always true
            buf.append("<div class=\"page\">\n<div class=\"mainsection\">");
        // for stop/start/check
        final boolean er = isTopLevel && snark != null && _manager.util().ratingsEnabled();
        final boolean ec = isTopLevel && snark != null && _manager.util().commentsEnabled(); // global setting
        final boolean esc = ec && _manager.getSavedCommentsEnabled(snark); // per-torrent setting
        final boolean includeForm = showStopStart || showPriority || er || ec;
        if (includeForm) {
            buf.append("<form action=\"").append(base).append("\" method=\"POST\">\n");
            buf.append("<input type=\"hidden\" name=\"nonce\" value=\"").append(_nonce).append("\" >\n");
            if (sortParam != null) {
                buf.append("<input type=\"hidden\" name=\"sort\" value=\"")
                   .append(DataHelper.stripHTML(sortParam)).append("\" >\n");
            }
        }
        if (snark != null) {
            // first table - torrent info
            buf.append("<table class=\"snarkTorrentInfo\">\n");
            buf.append("<tr><th></th><th><b>")
               .append(_t("Torrent"))
               .append(":</b> ")
               .append(DataHelper.escapeHTML(snark.getBaseName()))
               .append("</th></tr>\n");

            String fullPath = snark.getName();
            String baseName = encodePath((new File(fullPath)).getName());
            buf.append("<tr><td>");
            toThemeImg(buf, "file");
            buf.append("</td><td><b>")
               .append(_t("Torrent file"))
               .append(":</b> <a href=\"").append(_contextPath).append('/').append(baseName).append("\">")
               .append(DataHelper.escapeHTML(fullPath))
               .append("</a></td></tr>\n");
            if (snark.getStorage() != null) {
                buf.append("<tr><td>");
                toThemeImg(buf, "file");
                buf.append("</td><td><b>")
                   .append(_t("Data location"))
                   .append(":</b> ")
                   .append(DataHelper.escapeHTML(snark.getStorage().getBase().getPath()))
                   .append("</td></tr>\n");
            }
            String hex = I2PSnarkUtil.toHex(snark.getInfoHash());
            buf.append("<tr><td>");
            toThemeImg(buf, "details");
            buf.append("</td><td><b>")
               .append(_t("Info hash"))
               .append(":</b> <span id=\"infohash\">")
               .append(hex.toUpperCase(Locale.US))
               .append("</span></td></tr>\n");

            String announce = null;
            MetaInfo meta = snark.getMetaInfo();
            if (meta != null) {
                announce = meta.getAnnounce();
                if (announce == null)
                    announce = snark.getTrackerURL();
                if (announce != null) {
                    announce = DataHelper.stripHTML(announce);
                    buf.append("<tr><td>");
                    toThemeImg(buf, "details");
                    buf.append("</td><td><b>").append(_t("Primary Tracker")).append(":</b> <span class=\"info_tracker\">");
                    buf.append(getShortTrackerLink(announce, snark.getInfoHash()));
                    buf.append("</span></td></tr>");
                }
                List<List<String>> alist = meta.getAnnounceList();
                if (alist != null && !alist.isEmpty()) {
                    buf.append("<tr><td>");
                    toThemeImg(buf, "details");
                    buf.append("</td><td><b>")
                       .append(_t("Tracker List")).append(":</b> ");
                    for (List<String> alist2 : alist) {
                        buf.append("<span class=\"info_tracker\">");
                        boolean more = false;
                        for (String s : alist2) {
                            if (more)
                                buf.append(' ');
                            else
                                more = true;
                            buf.append(getShortTrackerLink(DataHelper.stripHTML(s), snark.getInfoHash()));
                        }
                        buf.append("</span> ");
                    }
                    buf.append("</td></tr>\n");
                }
            }

            if (meta != null) {
                String com = meta.getComment();
                if (com != null && com.length() > 0) {
                    if (com.length() > 1024)
                        com = com.substring(0, 1024);
                    buf.append("<tr><td>");
                    toThemeImg(buf, "details");
                    buf.append("</td><td><b>")
                       .append(_t("Comment")).append(":</b> ")
                       .append(DataHelper.stripHTML(com))
                       .append("</td></tr>\n");
                }
                long dat = meta.getCreationDate();
                // needs locale configured for automatic translation
                SimpleDateFormat fmt = new SimpleDateFormat("HH:mm, EEEE dd MMMM yyyy");
                fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                if (dat > 0) {
                    String date = fmt.format(new Date(dat));
                    buf.append("<tr><td>");
                    toThemeImg(buf, "details");
                    buf.append("</td><td><b>")
                       .append(_t("Created")).append(":</b> ")
                       .append(date)
                       .append("</td></tr>\n");
                }
                String cby = meta.getCreatedBy();
                if (cby != null && cby.length() > 0) {
                    if (cby.length() > 128)
                        cby = com.substring(0, 128);
                    buf.append("<tr><td>");
                    toThemeImg(buf, "details");
                    buf.append("</td><td><b>")
                       .append(_t("Created By")).append(":</b> ")
                       .append(DataHelper.stripHTML(cby))
                       .append("</td></tr>\n");
                }
                long[] dates = _manager.getSavedAddedAndCompleted(snark);
                if (dates[0] > 0) {
                    String date = fmt.format(new Date(dates[0]));
                    buf.append("<tr><td>");
                    toThemeImg(buf, "details");
                    buf.append("</td><td><b>")
                       .append(_t("Added")).append(":</b> ")
                       .append(date)
                       .append("</td></tr>\n");
                }
                if (dates[1] > 0) {
                    String date = fmt.format(new Date(dates[1]));
                    buf.append("<tr><td>");
                    toThemeImg(buf, "details");
                    buf.append("</td><td><b>")
                       .append(_t("Completed")).append(":</b> ")
                       .append(date)
                       .append("</td></tr>\n");
                }
            }

            if (meta == null || !meta.isPrivate()) {
                buf.append("<tr><td><a href=\"")
                   .append(MagnetURI.MAGNET_FULL).append(hex);
                if (announce != null)
                    buf.append("&amp;tr=").append(announce);
                buf.append("\">")
                   .append(toImg("magnet", _t("Magnet link")))
                   .append("</a></td><td><b>Magnet:</b> <a href=\"")
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
                buf.append("<tr><td>");
                toThemeImg(buf, "details");
                buf.append("</td><td><b>")
                   .append(_t("Private torrent"))
                   .append("</td></tr>\n");
            }

            // We don't have the hash of the torrent file
            //buf.append("<tr><td>").append(_t("Maggot link")).append(": <a href=\"").append(MAGGOT).append(hex).append(':').append(hex).append("\">")
            //   .append(MAGGOT).append(hex).append(':').append(hex).append("</a></td></tr>");

            buf.append("<tr id=\"torrentInfoStats\"><td colspan=\"2\"><span>");
            toThemeImg(buf, "size");
            buf.append("<b>")
               .append(_t("Size"))
               .append(":</b> ")
               .append(formatSize(snark.getTotalLength()));
            int pieces = snark.getPieces();
            double completion = (pieces - snark.getNeeded()) / (double) pieces;
            buf.append("</span>&nbsp;<span>");
            toThemeImg(buf, "head_rx");
            buf.append("<b>");
            if (completion < 1.0)
                buf.append(_t("Completion"))
                   .append(":</b> ")
                   .append((new DecimalFormat("0.00%")).format(completion));
            else
                buf.append(_t("Complete")).append("</b>");
            // up ratio
            buf.append("</span>&nbsp;<span>");
            toThemeImg(buf, "head_tx");
            buf.append("<b>")
               .append(_t("Upload ratio"))
               .append(":</b> ");
            long uploaded = snark.getUploaded();
            if (uploaded > 0) {
                double ratio = uploaded / ((double) snark.getTotalLength());
                buf.append((new DecimalFormat("0.000")).format(ratio));
                buf.append("&#8239;x");
            } else {
                buf.append('0');
            }
            // not including skipped files, but -1 when not running
            long needed = snark.getNeededLength();
            if (needed < 0) {
                // including skipped files, valid when not running
                needed = snark.getRemainingLength();
            }
            if (needed > 0) {
                buf.append("</span>&nbsp;<span>");
                toThemeImg(buf, "head_rx");
                buf.append("<b>")
                   .append(_t("Remaining"))
                   .append(":</b> ")
                   .append(formatSize(needed));
            }
            long skipped = snark.getSkippedLength();
            if (skipped > 0) {
                buf.append("</span>&nbsp;<span>");
                toThemeImg(buf, "head_rx");
                buf.append("<b>")
                   .append(_t("Skipped"))
                   .append(":</b> ")
                   .append(formatSize(skipped));
            }
            if (meta != null) {
                List<List<String>> files = meta.getFiles();
                int fileCount = files != null ? files.size() : 1;
                buf.append("</span>&nbsp;<span>");
                toThemeImg(buf, "file");
                buf.append("<b>")
                   .append(_t("Files"))
                   .append(":</b> ")
                   .append(fileCount);
            }
            buf.append("</span>&nbsp;<span>");
            toThemeImg(buf, "file");
            buf.append("<b>")
               .append(_t("Pieces"))
               .append(":</b> ")
               .append(pieces);
            buf.append("</span>&nbsp;<span>");
            toThemeImg(buf, "file");
            buf.append("<b>")
               .append(_t("Piece size"))
               .append(":</b> ")
               .append(formatSize(snark.getPieceLength(0)))
               .append("</span></td></tr>\n");

            // buttons
            if (showStopStart) {
                buf.append("<tr id=\"torrentInfoControl\"><td colspan=\"2\">");
                if (snark.isChecking()) {
                    buf.append("<span id=\"fileCheck\"><b>").append(_t("Checking")).append("&hellip; ")
                       .append((new DecimalFormat("0.00%")).format(snark.getCheckingProgress()))
                       .append("&nbsp;<a href=\"").append(base).append("\">")
                       .append(_t("Refresh page for results")).append("</a></span>");
                } else if (snark.isStarting()) {
                    buf.append("<b>").append(_t("Starting")).append("&hellip;</b>");
                } else if (snark.isAllocating()) {
                    buf.append("<b>").append(_t("Allocating")).append("&hellip;</b>");
                } else {
                    boolean isRunning = !snark.isStopped();
                    buf.append("<input type=\"submit\" value=\"");
                    if (isRunning)
                        buf.append(_t("Stop")).append("\" name=\"stop\" class=\"stoptorrent\">\n");
                    else
                        buf.append(_t("Start")).append("\" name=\"start\" class=\"starttorrent\">\n");
                    buf.append("<input type=\"submit\" name=\"recheck\" value=\"").append(_t("Force Recheck"));
                    if (isRunning)
                        buf.append("\" class=\"disabled\" disabled=\"disabled\" title=\"")
                           .append(_t("Stop the torrent in order to check file integrity"))
                           .append("\">\n");
                    else
                        buf.append("\" class=\"reload\" title=\"")
                           .append(_t("Check integrity of the downloaded files"))
                           .append("\">\n");
                }
                buf.append("</td></tr>\n");
            }
        } else {
            // snark == null
            // shouldn't happen
            buf.append("<table class=\"resourceError\" id=\"NotFound\"><tr><th colspan=\"2\">")
               .append(_t("Resource Not found"))
               .append("</th></tr><tr><td><b>").append(_t("Resource")).append(":</b></td><td>").append(r.toString())
               .append("</td></tr><tr><td><b>").append(_t("Base")).append(":</b></td><td>").append(base)
               .append("</td></tr><tr><td><b>").append(_t("Torrent")).append(":</b></td><td>").append(DataHelper.escapeHTML(torrentName))
               .append("</td></tr>\n");
        }
        buf.append("</table>\n");

        if (snark != null && !r.exists()) {
            // fixup TODO
            buf.append("<table class=\"resourceError\" id=\"DoesNotExist\"><tr><th colspan=\"2\">")
               .append(_t("Resource Does Not Exist"))
               .append("</th></tr><tr><td><b>").append(_t("Resource")).append(":</b></td><td>").append(r.toString())
               .append("</td></tr><tr><td><b>").append(_t("Base")).append(":</b></td><td>").append(base)
               .append("</td></tr><tr><td><b>").append(_t("Torrent")).append(":</b></td><td>").append(DataHelper.escapeHTML(torrentName))
               .append("</td></tr></table></div></div></center>\n</body>\n</html>");
            return buf.toString();
        }

        File[] ls = null;
        if (r.isDirectory()) {
            ls = r.listFiles();
        }  // if r is not a directory, we are only showing torrent info section

        if (ls == null) {
            // We are only showing the torrent info section
            if (er || ec)
                displayComments(snark, er, ec, esc, buf);
            if (includeForm)
                buf.append("</form>");
            buf.append("</div></div>\n</body>\n</html>");
            return buf.toString();
        }

        Storage storage = snark != null ? snark.getStorage() : null;
        List<Sorters.FileAndIndex> fileList = new ArrayList<Sorters.FileAndIndex>(ls.length);
        // precompute remaining for all files for efficiency
        long[] remainingArray = (storage != null) ? storage.remaining() : null;
        for (int i = 0; i < ls.length; i++) {
            fileList.add(new Sorters.FileAndIndex(ls[i], storage, remainingArray));
        }

        boolean showSort = fileList.size() > 1;
        if (showSort) {
            int sort = 0;
            if (sortParam != null) {
                try {
                    sort = Integer.parseInt(sortParam);
                } catch (NumberFormatException nfe) {}
            }
            Collections.sort(fileList, Sorters.getFileComparator(sort, this));
        }

        // second table - dir info
        buf.append("<table class=\"snarkDirInfo\"><thead>\n");
        buf.append("<tr>\n")
           .append("<th colspan=2>");
        String tx = _t("Directory");
        // cycle through sort by name or type
        String sort;
        boolean isTypeSort = false;
        if (showSort) {
            if (sortParam == null || "0".equals(sortParam) || "1".equals(sortParam)) {
                sort = "-1";
            } else if ("-1".equals(sortParam)) {
                sort = "12";
                isTypeSort = true;
            } else if ("12".equals(sortParam)) {
                sort = "-12";
                isTypeSort = true;
            } else {
                sort = "";
            }
            buf.append("<a href=\"").append(base)
               .append(getQueryString(sort)).append("\">");
        }
        toThemeImg(buf, "file", tx,
                   showSort ? _t("Sort by {0}", (isTypeSort ? _t("File type") : _t("Name")))
                            : tx + ": " + directory);
        if (showSort)
            buf.append("</a>");
        if (!isTopLevel) {
            buf.append("&nbsp;");
            buf.append(DataHelper.escapeHTML(directory.substring(dirSlash + 1)));
        }
        buf.append("</th>\n<th align=\"right\">");
        if (showSort) {
            sort = ("5".equals(sortParam)) ? "-5" : "5";
            buf.append("<a href=\"").append(base)
               .append(getQueryString(sort)).append("\">");
        }
        tx = _t("Size");
        toThemeImg(buf, "size", tx,
                   showSort ? _t("Sort by {0}", tx) : tx);
        if (showSort)
            buf.append("</a>");
        buf.append("</th>\n<th class=\"headerstatus\">");
        boolean showRemainingSort = showSort && showPriority;
        if (showRemainingSort) {
            sort = ("10".equals(sortParam)) ? "-10" : "10";
            buf.append("<a href=\"").append(base)
               .append(getQueryString(sort)).append("\">");
        }
        tx = _t("Download Status");
        toThemeImg(buf, "status", tx,
                   showRemainingSort ? _t("Sort by {0}", _t("Remaining")) : tx);
        if (showRemainingSort)
            buf.append("</a>");
        if (showPriority) {
            buf.append("</th>\n<th class=\"headerpriority\">");
            if (showSort) {
                sort = ("13".equals(sortParam)) ? "-13" : "13";
                buf.append("<a href=\"").append(base)
                   .append(getQueryString(sort)).append("\">");
            }
            tx = _t("Download Priority");
            toThemeImg(buf, "priority", tx,
                       showSort ? _t("Sort by {0}", tx) : tx);
            if (showSort)
                buf.append("</a>");
        }
        buf.append("</th>\n</tr>\n</thead>\n");
        buf.append("<tr><td colspan=\"" + (showPriority ? '5' : '4') + "\" class=\"ParentDir\"><A HREF=\"");
        URIUtil.encodePath(buf, addPaths(decodedBase,"../"));
        buf.append("\">");
        toThemeImg(buf, "up");
        buf.append(' ')
           .append(_t("Up to higher level directory"))
           .append("</A></td></tr>\n");


        //DateFormat dfmt=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
        //                                               DateFormat.MEDIUM);
        boolean showSaveButton = false;
        boolean rowEven = true;
        for (Sorters.FileAndIndex fai : fileList)
        {
            //String encoded = encodePath(ls[i].getName());
            // bugfix for I2P - Backport from Jetty 6 (zero file lengths and last-modified times)
            // http://jira.codehaus.org/browse/JETTY-361?page=com.atlassian.jira.plugin.system.issuetabpanels%3Achangehistory-tabpanel#issue-tabs
            // See resource.diff attachment
            //Resource item = addPath(encoded);
            File item = fai.file;

            String rowClass = (rowEven ? "snarkTorrentEven" : "snarkTorrentOdd");
            rowEven = !rowEven;
            buf.append("<tr class=\"").append(rowClass).append("\">");

            // Get completeness and status string
            boolean complete = false;
            String status = "";
            long length = item.length();
            int fileIndex = fai.index;
            int priority = 0;
            if (fai.isDirectory) {
                complete = true;
                //status = toImg("tick") + ' ' + _t("Directory");
            } else {
                if (snark == null || snark.getStorage() == null) {
                    // Assume complete, perhaps he removed a completed torrent but kept a bookmark
                    complete = true;
                    status = toImg("cancel") + ' ' + _t("Torrent not found?");
                } else {

                            long remaining = fai.remaining;
                            if (remaining < 0) {
                                complete = true;
                                status = toImg("cancel") + ' ' + _t("File not found in torrent?");
                            } else if (remaining == 0 || length <= 0) {
                                complete = true;
                                status = "<div class=\"priorityIndicator\">" + toImg("tick") + "</div>" + _t("Complete");
                            } else {
                                priority = fai.priority;
                                if (priority < 0)
                                    status = "<div class=\"priorityIndicator\">" + toImg("cancel") + "</div>";
                                else if (priority == 0)
                                    status = "<div class=\"priorityIndicator\">" + toImg("clock") + "</div>";
                                else
                                    status = "<div class=\"priorityIndicator\">" + toImg("clock_red") + "</div>";
                                long percent = 100 * (length - remaining) / length;
                                status += " <div class=\"percentBarOuter\">" +
                                         "<div class=\"percentBarInner\" style=\"width: " +
                                         percent + "%;\"><div class=\"percentBarText\" tabindex=\"0\" title=\"" +
                                         formatSize(remaining) + ' ' + _t("remaining") +
                                         "\">" + percent + "%</div></div></div>";
                            }

                }
            }

            String path = addPaths(decodedBase, item.getName());
            if (item.isDirectory() && !path.endsWith("/"))
                path=addPaths(path,"/");
            path = encodePath(path);
            String icon = toIcon(item);
            String mime = getMimeType(path);
            if (mime == null)
                mime = "";

            buf.append("<td class=\"snarkFileIcon\">");
            if (complete) {
                buf.append("<a href=\"").append(path).append("\">");
                // thumbnail ?
                String plc = item.toString().toLowerCase(Locale.US);
                if (mime.startsWith("image/")) {
                    buf.append("<img alt=\"\" border=\"0\" class=\"thumb\" src=\"")
                       .append(path).append("\"></a>");
                } else {
                    buf.append(toImg(icon, _t("Open"))).append("</a>");
                }
            } else {
                buf.append(toImg(icon));
            }
            buf.append("</td><td class=\"snarkFileName\">");
            if (complete) {
                buf.append("<a href=\"").append(path);
                // send browser-viewable files to new tab to avoid potential display in iframe
                if (mime.startsWith("text/") ||
                    mime.startsWith("image/") ||
                    mime.startsWith("audio/") ||
                    mime.startsWith("video/") ||
                    mime.equals("application/ogg"))
                    buf.append("\" target=\"_blank");
                buf.append("\">");
            }
            buf.append(DataHelper.escapeHTML(item.getName()));
            if (complete)
                buf.append("</a>");
            buf.append("</td><td align=right class=\"snarkFileSize\">");
            if (!item.isDirectory())
                buf.append(formatSize(length));
            buf.append("</td><td class=\"snarkFileStatus\">");
            //buf.append(dfmt.format(new Date(item.lastModified())));
            buf.append(status);
            buf.append("</td>");
            if (showPriority) {
                buf.append("<td class=\"priority\">");
                if ((!complete) && (!item.isDirectory())) {
                    buf.append("<label class=\"priorityHigh\" title=\"").append(_t("Download file at high priority")).append("\">")
                       .append("\n<input type=\"radio\" onclick=\"priorityclicked();\" class=\"prihigh\" value=\"5\" name=\"pri.").append(fileIndex).append("\" ");
                    if (priority > 0)
                        buf.append("checked=\"checked\"");
                    buf.append('>')
                       .append(_t("High")).append("</label>");

                    buf.append("<label class=\"priorityNormal\" title=\"").append(_t("Download file at normal priority")).append("\">")
                    .append("\n<input type=\"radio\" onclick=\"priorityclicked();\" class=\"prinorm\" value=\"0\" name=\"pri.").append(fileIndex).append("\" ");
                    if (priority == 0)
                        buf.append("checked=\"checked\"");
                    buf.append('>')
                       .append(_t("Normal")).append("</label>");

                    buf.append("<label class=\"prioritySkip\" title=\"").append(_t("Do not download this file")).append("\">")
                    .append("\n<input type=\"radio\" onclick=\"priorityclicked();\" class=\"priskip\" value=\"-9\" name=\"pri.").append(fileIndex).append("\" ");
                    if (priority < 0)
                        buf.append("checked=\"checked\"");
                    buf.append('>')
                       .append(_t("Skip")).append("</label>");
                    showSaveButton = true;
                }
                buf.append("</td>");
            }
            buf.append("</tr>\n");
        }
        if (showSaveButton) {
            buf.append("<thead><tr id=\"setPriority\"><th class=\"headerpriority\" colspan=\"5\">" +
                       "<span class=\"script\"><a class=\"control\" id=\"setallhigh\" href=\"javascript:void(null);\" onclick=\"setallhigh();\">")
               .append(toImg("clock_red")).append(_t("Set all high")).append("</a>\n" +
                       "<a class=\"control\" id=\"setallnorm\" href=\"javascript:void(null);\" onclick=\"setallnorm();\">")
               .append(toImg("clock")).append(_t("Set all normal")).append("</a>\n" +
                       "<a class=\"control\" id=\"setallskip\" href=\"javascript:void(null);\" onclick=\"setallskip();\">")
               .append(toImg("cancel")).append(_t("Skip all")).append("</a></span>\n" +
                       "<input type=\"submit\" class=\"accept\" value=\"").append(_t("Save priorities"))
               .append("\" name=\"savepri\" >\n" +
                       "</th></tr></thead>\n");
        }
        buf.append("</table>\n");
        if (er || ec)
            displayComments(snark, er, ec, esc, buf);
        // for stop/start/check
        if (includeForm)
            buf.append("</form>");
        buf.append("</div></div>\n</body>\n</html>\n");

        return buf.toString();
    }

    /**
     * @param er ratings enabled globally
     * @param ec comments enabled globally
     * @param esc comments enabled this torrent
     * @since 0.9.31
     */
    private void displayComments(Snark snark, boolean er, boolean ec, boolean esc, StringBuilder buf) {
            Iterator<Comment> iter = null;
            int myRating = 0;
            CommentSet comments = snark.getComments();
            boolean canRate = esc && _manager.util().getCommentsName().length() > 0;

            buf.append("<div id=\"snarkCommentSection\"><table class=\"snarkCommentInfo\">\n<tr><th colspan=\"3\">")
               .append(_t("Ratings and Comments"));
            if (esc && !canRate) {
                buf.append("&nbsp;&nbsp;&nbsp;<span id=\"nameRequired\">");
                buf.append(_t("Author name required to rate or comment"));
                buf.append("&nbsp;&nbsp;<a href=\"").append(_contextPath).append("/configure#configureAuthor\">[");
                buf.append(_t("Configure"));
                buf.append("]</a></span>");
            } else if (esc) {
                buf.append("&nbsp;&nbsp;&nbsp;<span id=\"nameRequired\"><span class=\"commentAuthorName\" title=\"")
                   .append(_t("Your author name for published comments and ratings"))
                   .append("\">");
                buf.append(DataHelper.escapeHTML(_manager.util().getCommentsName()));
                buf.append("</span></span>");
            }

            buf.append("</th></tr>\n<tr id=\"commentsConfig\"><td>");
            buf.append(_t("Comments"));
            buf.append(":</td><td><label><input type=\"checkbox\" class=\"optbox\" name=\"enableComments\" id=\"enableComments\" ");
            if (esc)
                buf.append("checked=\"checked\"");
            else if (!ec)
                buf.append("disabled=\"disabled\"");
            buf.append(">&nbsp;");
            buf.append(_t("Enable viewing and posting comments for this torrent"));
            buf.append("</label></td><td class=\"commentAction\">");
            if (ec) {
                buf.append("<input type=\"submit\" name=\"setCommentsEnabled\" value=\"");
                buf.append(_t("Save Preference"));
                buf.append("\" class=\"accept\">");
            }
            buf.append("</td></tr>\n");

            // new rating / comment form
            if (canRate) {
                buf.append("<tr id=\"newRating\">\n");
                if (er) {
                    buf.append("<td>\n<select name=\"myRating\">\n");
                    for (int i = 5; i >= 0; i--) {
                        buf.append("<option value=\"").append(i).append("\" ");
                        if (i == myRating)
                            buf.append("selected=\"selected\"");
                        buf.append('>');
                        if (i != 0) {
                            for (int j = 0; j < i; j++) {
                                buf.append("★");
                            }
                            buf.append(' ').append(ngettext("1 star", "{0} stars", i));
                        } else {
                            buf.append("☆ ").append(_t("No rating"));
                        }
                        buf.append("</option>\n");
                    }
                    buf.append("</select>\n</td>");
                } else {
                    buf.append("<td></td>");
                }
                if (esc) {
                    buf.append("<td id=\"addCommentText\"><textarea name=\"nofilter_newComment\" cols=\"44\" rows=\"4\"></textarea></td>");
                } else {
                    buf.append("<td></td>");
                }
                buf.append("<td class=\"commentAction\"><input type=\"submit\" name=\"addComment\" value=\"");
                if (er && esc)
                    buf.append(_t("Rate and Comment"));
                else if (er)
                    buf.append(_t("Rate Torrent"));
                else
                    buf.append(_t("Add Comment"));
                buf.append("\" class=\"accept\"></td>\n");
                buf.append("</tr>\n");
            }

            if (comments != null) {
                synchronized(comments) {
                    // current rating
                    if (er) {
                        buf.append("<tr id=\"myRating\"><td>");
                        myRating = comments.getMyRating();
                        if (myRating > 0) {
                            buf.append(_t("My Rating")).append(":</td><td colspan=\"2\" class=\"commentRating\">");
                            String img = toThemeImg("rateme", "★", "");
                            for (int i = 0; i < myRating; i++) {
                                buf.append(img);
                            }
                        }
                        buf.append("</td></tr>");
                    }
                    if (er) {
                        buf.append("<tr id=\"showRatings\"><td>");
                        int rcnt = comments.getRatingCount();
                        if (rcnt > 0) {
                            double avg = comments.getAverageRating();
                            buf.append(_t("Average Rating")).append(":</td><td>").append(avg);  // todo format
                            //buf.append(' ').append(_t("Total Ratings")).append(": ").append(rcnt);
                        } else {

                            buf.append(_t("Average Rating")).append(":</td><td colspan=\"2\">");
                            buf.append(_t("No community ratings currently available"));
                        }
                        buf.append("</td></tr>");
                    }
                    if (ec) {
                        int sz = comments.size();
                        if (sz > 0)
                            iter = comments.iterator();
                    }
                }
            }

            buf.append("</table>");
            // TODO  disable / enable comments for this torrent
            // existing ratings / comments table
            int ccount = 0;
            if (iter != null) {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                buf.append("<table class=\"snarkComments\">");

                while (iter.hasNext()) {
                    Comment c = iter.next();
                    buf.append("<tr><td class=\"commentAuthor\">");
                    // (c.isMine())
                    //  buf.append(_t("me"));
                    //else if (c.getName() != null)
                    // TODO can't be hidden... hide if comments are hidden?
                    if (c.getName() != null) {
                        buf.append("<span class=\"commentAuthorName\">");
                        buf.append(DataHelper.escapeHTML(c.getName()));
                        buf.append("</span>");
                    }
                    buf.append("</td><td class=\"commentRating\">");
                    if (er) {
                        int rt = c.getRating();
                        if (rt > 0) {
                            String img = toThemeImg("rateme", "★", "");
                            for (int i = 0; i < rt; i++) {
                                buf.append(img);
                            }
                        }
                    }
                    buf.append("</td><td class=\"commentDate\">").append(fmt.format(new Date(c.getTime())));
                    buf.append("</td><td class=\"commentText\">");
                    if (esc) {
                        if (c.getText() != null) {
                            buf.append("<div class=\"commentWrapper\">");
                            buf.append(DataHelper.escapeHTML(c.getText()));
                            buf.append("</div></td><td class=\"commentDelete\"><input type=\"checkbox\" class=\"optbox\" name=\"cdelete.")
                               .append(c.getID()).append("\" title=\"").append(_t("Mark for deletion")).append("\">");
                            ccount++;
                        } else {
                            buf.append("</td><td class=\"commentDelete\">"); // insert empty named columns to maintain table layout
                        }
                    } else {
                        buf.append("</td><td class=\"commentDelete\">"); // insert empty named columns to maintain table layout
                    }
                    buf.append("</td></tr>\n");
                }
                if (esc && ccount > 0) {
                    // TODO format better
                    buf.append("<tr id=\"commentDeleteAction\"><td colspan=\"5\" class=\"commentAction\" align=\"right\"><input type=\"submit\" name=\"deleteComments\" value=\"");
                    buf.append(_t("Delete Selected"));
                    buf.append("\" class=\"delete\"></td></tr>\n");
                }
                buf.append("</table>");
            } else if (esc) {
                //buf.append(_t("No comments for this torrent"));
            } // iter != null
            buf.append("</div>");
    }

    /**
     *  @param so null ok
     *  @return query string or ""
     *  @since 0.9.16
     */
    private static String getQueryString(String so) {
        if (so != null && !so.equals(""))
            return "?sort=" + DataHelper.stripHTML(so);
        return "";
    }

    /**
     *  Pick an icon; try to catch the common types in an i2p environment.
     *
     *  @return file name not including ".png"
     *  @since 0.7.14
     */
    private String toIcon(File item) {
        if (item.isDirectory())
            return "folder";
        return toIcon(item.toString());
    }

    /**
     *  Pick an icon; try to catch the common types in an i2p environment
     *  Pkg private for FileTypeSorter.
     *
     *  @return file name not including ".png"
     *  @since 0.7.14
     */
    String toIcon(String path) {
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
                 plc.endsWith(".azw4"))
            icon = "page";
        else if  (mime.equals("application/epub+zip") ||
                 mime.equals("application/x-mobipocket-ebook"))
            icon = "ebook";
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
        else if (mime.equals("application/zip")) {
            if (plc.endsWith(".su3") || plc.endsWith(".su2") || plc.endsWith(".sud"))
                icon = "itoopie_xxsm";
            else
                icon = "compress";
        } else if (mime.equals("application/x-gtar") || mime.equals("application/x-xz") ||
                 mime.equals("application/compress") || mime.equals("application/gzip") ||
                 mime.equals("application/x-7z-compressed") || mime.equals("application/x-rar-compressed") ||
                 mime.equals("application/x-tar") || mime.equals("application/x-bzip2"))
            icon = "compress";
        else if (plc.endsWith(".exe"))
            icon = "application";
        else if (plc.endsWith(".iso") || plc.endsWith(".nrg"))
            icon = "cd";
        else if (mime.equals("application/x-bittorrent"))
            icon = "magnet";
        else
            icon = "page_white";
        return icon;
    }

    /**
     *  Icon file in the .war. Always 16x16.
     *
     *  @param icon name without the ".png"
     *  @since 0.7.14
     */
    private String toImg(String icon) {
        return toImg(icon, "");
    }

    /**
     *  Icon file in the .war. Always 16x16.
     *
     *  @param icon name without the ".png"
     *  @since 0.8.2
     */
    private String toImg(String icon, String altText) {
        return "<img alt=\"" + altText + "\" height=\"16\" width=\"16\" src=\"" + _contextPath + WARBASE + "icons/" + icon + ".png\">";
    }

    /**
     *  Image file in the theme.
     *
     *  @param image name without the ".png"
     *  @since 0.9.16
     */
    private String toThemeImg(String image) {
        return toThemeImg(image, "", "");
    }

    /**
     *  Image file in the theme.
     *
     *  @param image name without the ".png"
     *  @since 0.9.16
     */
    private void toThemeImg(StringBuilder buf, String image) {
        toThemeImg(buf, image, "", "");
    }

    /**
     *  Image file in the theme.
     *
     *  @param image name without the ".png"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.16
     */
    private String toThemeImg(String image, String altText, String titleText) {
        StringBuilder buf = new StringBuilder(128);
        toThemeImg(buf, image, altText, titleText);
        return buf.toString();
    }

    /**
     *  Image file in the theme.
     *
     *  @param image name without the ".png"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.16
     */
    private void toThemeImg(StringBuilder buf, String image, String altText, String titleText) {
        buf.append("<img alt=\"").append(altText).append("\" src=\"").append(_imgPath).append(image).append(".png\"");
        if (titleText.length() > 0)
            buf.append(" title=\"").append(titleText).append('"');
        buf.append('>');
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

    /** @since 0.9.31 */
    private void saveComments(Snark snark, Map<String, String[]> postParams) {
        String[] a = postParams.get("myRating");
        String r = (a != null) ? a[0] : null;
        a = postParams.get("nofilter_newComment");
        String c = (a != null) ? a[0] : null;
        if ((r == null || r.equals("0")) && (c == null || c.length() == 0))
            return;
        int rat = 0;
        try {
            rat = Integer.parseInt(r);
        } catch (NumberFormatException nfe) {}
        Comment com = new Comment(c, _manager.util().getCommentsName(), rat);
        boolean changed = snark.addComments(Collections.singletonList(com));
        if (!changed)
            _log.warn("Add of comment ID " + com.getID() + " UNSUCCESSFUL");
    }

    /** @since 0.9.31 */
    private void deleteComments(Snark snark, Map<String, String[]> postParams) {
        CommentSet cs = snark.getComments();
        if (cs == null)
            return;
        synchronized(cs) {
            for (Map.Entry<String, String[]> entry : postParams.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("cdelete.")) {
                    try {
                        int id = Integer.parseInt(key.substring(8));
                        boolean changed = cs.remove(id);
                        if (!changed)
                            _log.warn("Delete of comment ID " + id + " UNSUCCESSFUL");
                    } catch (NumberFormatException nfe) {}
                }
            }
        }
    }

    /** @since 0.9.31 */
    private void saveCommentsSetting(Snark snark, Map<String, String[]> postParams) {
        boolean yes = postParams.get("enableComments") != null;
        _manager.setSavedCommentsEnabled(snark, yes);
    }

    /** @since 0.9.32 */
    private static boolean noCollapsePanels(HttpServletRequest req) {
        // check for user agents that can't toggle the collapsible panels...
        String ua = req.getHeader("user-agent");
        return ua != null && (ua.contains("Konq") || ua.contains("konq") ||
                              ua.contains("Qupzilla") || ua.contains("Dillo") ||
                              ua.contains("Netsurf") || ua.contains("Midori"));
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
