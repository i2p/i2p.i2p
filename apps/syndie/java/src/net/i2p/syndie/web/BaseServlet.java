package net.i2p.syndie.web;

import java.io.*;
import java.util.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.*;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;

/**
 * Base servlet for handling request and rendering the templates
 *
 */
public abstract class BaseServlet extends HttpServlet {
    protected static final String PARAM_AUTH_ACTION = "syndie.auth";
    private static long _authNonce;
    private I2PAppContext _context;
    protected Log _log;
    
    public void init() throws ServletException { 
        super.init();
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(getClass());
        _authNonce = _context.random().nextLong();
    }
    
    protected boolean authAction(HttpServletRequest req) {
        return authAction(req.getParameter(PARAM_AUTH_ACTION));
    }
    protected boolean authAction(String auth) {
        if (auth == null) {
            return false;
        } else {
            try { 
                boolean rv = (Long.valueOf(auth).longValue() == _authNonce); 
                return rv;
            } catch (NumberFormatException nfe) {
                return false;
            }
        }
    }
    
    /**
     * write out hidden fields for params that need to be tacked onto an http request that updates 
     * data, to prevent spoofing
     */
    protected void writeAuthActionFields(Writer out) throws IOException {
        out.write("<input type=\"hidden\" name=\"" + PARAM_AUTH_ACTION + "\" value=\"" + _authNonce + "\" />");
    }
    protected String getAuthActionFields() throws IOException {
        return "<input type=\"hidden\" name=\"" + PARAM_AUTH_ACTION + "\" value=\"" + _authNonce + "\" />";
    }
    /** 
     * key=value& of params that need to be tacked onto an http request that updates data, to 
     * prevent spoofing 
     */
    protected static String getAuthActionParams() { return PARAM_AUTH_ACTION + '=' + _authNonce + '&'; }
    /** 
     * key=value& of params that need to be tacked onto an http request that updates data, to 
     * prevent spoofing 
     */
    public static void addAuthActionParams(StringBuffer buf) { 
        buf.append(PARAM_AUTH_ACTION).append('=').append(_authNonce).append('&'); 
    }
    
    public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html;charset=UTF-8");
        resp.setHeader("cache-control", "no-cache");
        resp.setHeader("pragma", "no-cache");
        
        User user = (User)req.getSession().getAttribute("user");
        String login = req.getParameter("login");
        String pass = req.getParameter("password");
        String action = req.getParameter("action");
        boolean forceNewIndex = false;

        boolean authAction = authAction(req);
        
        if (req.getParameter("regenerateIndex") != null)
            forceNewIndex = true;

        User oldUser = user;
        if (authAction)
            user = handleRegister(user, req);
        if (oldUser != user)
            forceNewIndex = true;
        
        if (user == null) {
            if ("Login".equals(action)) {
                user = BlogManager.instance().login(login, pass); // ignore failures - user will just be unauthorized
                if (!user.getAuthenticated()) {
                    user = BlogManager.instance().getDefaultUser();
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Explicit login failed for [" + login + "], using default login");
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Explicit login successful for [" + login + "]");
                }
            } else {
                user = BlogManager.instance().getDefaultUser();
                if (_log.shouldLog(Log.INFO))
                    _log.info("Implicit login for the default user");
            }
            forceNewIndex = true;
        } else if (authAction && "Login".equals(action)) {
            user = BlogManager.instance().login(login, pass); // ignore failures - user will just be unauthorized
            if (!user.getAuthenticated()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Explicit relogin failed for [" + login + "] from [" + user.getUsername() + "], using default user");
                user = BlogManager.instance().getDefaultUser();
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Explicit relogin successful for [" + login + "] from [" + user.getUsername() + "]");
            }
            forceNewIndex = true;
        } else if (authAction && "Logout".equals(action)) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Explicit logout successful for [" + user.getUsername() + "], using default login");
            user = BlogManager.instance().getDefaultUser();
            forceNewIndex = true;
        }
        
        req.getSession().setAttribute("user", user);
        
        if (authAction) {
            handleAdmin(user, req);
        
            forceNewIndex = handleAddressbook(user, req) || forceNewIndex;
            forceNewIndex = handleBookmarking(user, req) || forceNewIndex;
            forceNewIndex = handleManageTags(user, req) || forceNewIndex;
            handleUpdateProfile(user, req);
        }
        
        // the 'dataImported' flag is set by successful fetches in the SyndicateServlet/RemoteArchiveBean
        if (user.resetDataImported()) {
            forceNewIndex = true;
            if (_log.shouldLog(Log.INFO))
                _log.info("Data imported, force regenerate");
        }
        
        FilteredThreadIndex index = (FilteredThreadIndex)req.getSession().getAttribute("threadIndex");
        
        boolean authorOnly = Boolean.valueOf(req.getParameter(ThreadedHTMLRenderer.PARAM_THREAD_AUTHOR)).booleanValue();
        if ( (index != null) && (authorOnly != index.getFilterAuthorsByRoot()) )
            forceNewIndex = true;
        
        Collection tags = getFilteredTags(req);
        Collection filteredAuthors = getFilteredAuthors(req);
        boolean tagsChanged = ( (index != null) && (!index.getFilteredTags().equals(tags)) );
        boolean authorsChanged = ( (index != null) && (!index.getFilteredAuthors().equals(filteredAuthors)) );
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("authorOnly=" + authorOnly + " forceNewIndex? " + forceNewIndex + " authors=" + filteredAuthors);
        
        if (forceNewIndex || (index == null) || (tagsChanged) || (authorsChanged) ) {
            index = new FilteredThreadIndex(user, BlogManager.instance().getArchive(), getFilteredTags(req), filteredAuthors, authorOnly);
            req.getSession().setAttribute("threadIndex", index);
            if (_log.shouldLog(Log.INFO))
                _log.info("New filtered index created (forced? " + forceNewIndex + ", tagsChanged? " + tagsChanged + ", authorsChanged? " + authorsChanged + ")");
        }
        
        render(user, req, resp, index);
    }
    protected void render(User user, HttpServletRequest req, HttpServletResponse resp, ThreadIndex index) throws IOException, ServletException {
        render(user, req, resp.getWriter(), index);
    }
    
    private boolean handleBookmarking(User user, HttpServletRequest req) {
        if (!user.getAuthenticated())
            return false;
        
        boolean rv = false;
        
        String loc = req.getParameter(ThreadedHTMLRenderer.PARAM_ADD_TO_GROUP_LOCATION);
        String group = req.getParameter(ThreadedHTMLRenderer.PARAM_ADD_TO_GROUP_NAME);
        if ( (loc != null) && (group != null) && (group.trim().length() > 0) ) {
            try {
                Hash key = new Hash();
                key.fromBase64(loc);
                PetNameDB db = user.getPetNameDB();
                PetName pn = db.getByLocation(loc);
                boolean isNew = false;
                if (pn == null) {
                    isNew = true;
                    BlogInfo info = BlogManager.instance().getArchive().getBlogInfo(key);
                    String name = null;
                    if (info != null)
                        name = info.getProperty(BlogInfo.NAME);
                    else
                        name = loc.substring(0,6);

                    if (db.containsName(name)) {
                        int i = 0;
                        while (db.containsName(name + i))
                            i++;
                        name = name + i;
                    }

                    pn = new PetName(name, AddressesServlet.NET_SYNDIE, AddressesServlet.PROTO_BLOG, loc);
                }
                pn.addGroup(group);
                if (isNew)
                    db.add(pn);
                BlogManager.instance().saveUser(user);
                // if we are ignoring someone, we need to recalculate the filters
                if (FilteredThreadIndex.GROUP_IGNORE.equals(group))
                    rv = true;
            } catch (DataFormatException dfe) {
                // bad loc, ignore
            }
        }
        
        String name = req.getParameter(ThreadedHTMLRenderer.PARAM_REMOVE_FROM_GROUP_NAME);
        group = req.getParameter(ThreadedHTMLRenderer.PARAM_REMOVE_FROM_GROUP);
        if ( (name != null) && (name.trim().length() > 0) ) {
            PetNameDB db = user.getPetNameDB();
            PetName pn = db.getByName(name);
            boolean changed = false;
            if (pn != null) {
                if ( (group != null) && (group.trim().length() > 0) ) {
                    // just remove them from the group
                    changed = pn.isMember(group);
                    pn.removeGroup(group);
                    if ( (changed) && (FilteredThreadIndex.GROUP_IGNORE.equals(group)) )
                        rv = true;
                } else {
                    // remove it completely
                    if (pn.isMember(FilteredThreadIndex.GROUP_IGNORE))
                        rv = true;
                    db.remove(pn);
                    changed = true;
                }
            }
            if (changed)
                BlogManager.instance().saveUser(user);
        }
        
        if (rv)
            _log.debug("Bookmarking required rebuild");
        return rv;
    }
    
    private boolean handleManageTags(User user, HttpServletRequest req) {
        if (!user.getAuthenticated())
            return false;
        
        boolean rv = false;
        
        String tag = req.getParameter(ThreadedHTMLRenderer.PARAM_ADD_TAG);
        if ( (tag != null) && (tag.trim().length() > 0) ) {
            tag = HTMLRenderer.sanitizeString(tag, false);
            String name = tag;
            PetNameDB db = user.getPetNameDB();
            PetName pn = db.getByLocation(tag);
            if (pn == null) {
                if (db.containsName(name)) {
                    int i = 0;
                    while (db.containsName(name + i))
                        i++;
                    name = tag + i;
                }

                pn = new PetName(name, AddressesServlet.NET_SYNDIE, AddressesServlet.PROTO_TAG, tag);
                db.add(pn);
                BlogManager.instance().saveUser(user);
            }
        }
        
        return false;
    }
    
    private boolean handleAddressbook(User user, HttpServletRequest req) {
        if ( (!user.getAuthenticated()) || (empty(AddressesServlet.PARAM_ACTION)) ) {
            return false;
        }
        
        String action = req.getParameter(AddressesServlet.PARAM_ACTION);
        
        if (AddressesServlet.ACTION_ADD_TAG.equals(action)) {
            String name = req.getParameter(AddressesServlet.PARAM_NAME);
            if (!user.getPetNameDB().containsName(name)) {
                PetName pn = new PetName(name, AddressesServlet.NET_SYNDIE, AddressesServlet.PROTO_TAG, name);
                user.getPetNameDB().add(pn);
                BlogManager.instance().saveUser(user);
            }
            return false;
        } else if ( (AddressesServlet.ACTION_ADD_ARCHIVE.equals(action)) || 
             (AddressesServlet.ACTION_ADD_BLOG.equals(action)) ||
             (AddressesServlet.ACTION_ADD_EEPSITE.equals(action)) || 
             (AddressesServlet.ACTION_ADD_OTHER.equals(action)) ||
             (AddressesServlet.ACTION_ADD_PEER.equals(action)) ) {
            PetName pn = buildNewAddress(req);
            if ( (pn != null) && (pn.getName() != null) && (pn.getLocation() != null) && 
                 (!user.getPetNameDB().containsName(pn.getName())) ) {
                user.getPetNameDB().add(pn);
                BlogManager.instance().saveUser(user);
                
                updateSyndication(user, pn.getLocation(), !empty(req, AddressesServlet.PARAM_SYNDICATE));
                
                if (pn.isMember(FilteredThreadIndex.GROUP_FAVORITE) ||
                    pn.isMember(FilteredThreadIndex.GROUP_IGNORE))
                    return true;
                else
                    return false;
            } else {
                // not valid, ignore
                return false;
            }
        } else if ( (AddressesServlet.ACTION_UPDATE_ARCHIVE.equals(action)) || 
             (AddressesServlet.ACTION_UPDATE_BLOG.equals(action)) ||
             (AddressesServlet.ACTION_UPDATE_EEPSITE.equals(action)) || 
             (AddressesServlet.ACTION_UPDATE_OTHER.equals(action)) ||
             (AddressesServlet.ACTION_UPDATE_PEER.equals(action)) ) {
            return updateAddress(user, req);
        } else if ( (AddressesServlet.ACTION_DELETE_ARCHIVE.equals(action)) || 
             (AddressesServlet.ACTION_DELETE_BLOG.equals(action)) ||
             (AddressesServlet.ACTION_DELETE_EEPSITE.equals(action)) || 
             (AddressesServlet.ACTION_DELETE_OTHER.equals(action)) ||
             (AddressesServlet.ACTION_DELETE_TAG.equals(action)) ||
             (AddressesServlet.ACTION_DELETE_PEER.equals(action)) ) {
            String name = req.getParameter(AddressesServlet.PARAM_NAME);
            PetName pn = user.getPetNameDB().getByName(name);
            if (pn != null) {
                user.getPetNameDB().remove(pn);
                BlogManager.instance().saveUser(user);
                updateSyndication(user, pn.getLocation(), false);
                if (pn.isMember(FilteredThreadIndex.GROUP_FAVORITE) ||
                    pn.isMember(FilteredThreadIndex.GROUP_IGNORE))
                    return true;
                else
                    return false;
            } else {
                return false;
            }
        } else {
            // not an addressbook op
            return false;
        }
    }
    
    private boolean updateAddress(User user, HttpServletRequest req) {
        PetName pn = user.getPetNameDB().getByName(req.getParameter(AddressesServlet.PARAM_NAME));
        if (pn != null) {
            boolean wasIgnored = pn.isMember(FilteredThreadIndex.GROUP_IGNORE);
            boolean wasFavorite = pn.isMember(FilteredThreadIndex.GROUP_FAVORITE);
            
            pn.setIsPublic(!empty(req, AddressesServlet.PARAM_IS_PUBLIC));
            pn.setLocation(req.getParameter(AddressesServlet.PARAM_LOC));
            pn.setNetwork(req.getParameter(AddressesServlet.PARAM_NET));
            pn.setProtocol(req.getParameter(AddressesServlet.PARAM_PROTO));
            if (empty(req, AddressesServlet.PARAM_FAVORITE))
                pn.removeGroup(FilteredThreadIndex.GROUP_FAVORITE);
            else
                pn.addGroup(FilteredThreadIndex.GROUP_FAVORITE);
            if (empty(req, AddressesServlet.PARAM_IGNORE))
                pn.removeGroup(FilteredThreadIndex.GROUP_IGNORE);
            else
                pn.addGroup(FilteredThreadIndex.GROUP_IGNORE);
            
            BlogManager.instance().saveUser(user);
            
            if (AddressesServlet.PROTO_ARCHIVE.equals(pn.getProtocol()))
                updateSyndication(user, pn.getLocation(), !empty(req, AddressesServlet.PARAM_SYNDICATE));
            
            return (wasIgnored != pn.isMember(FilteredThreadIndex.GROUP_IGNORE)) ||
                   (wasFavorite != pn.isMember(FilteredThreadIndex.GROUP_IGNORE));
        } else {
            return false;
        }
    }
    
    protected void updateSyndication(User user, String loc, boolean shouldAutomate) {
        if (BlogManager.instance().authorizeRemote(user)) {
            if (shouldAutomate)
                BlogManager.instance().scheduleSyndication(loc);
            else
                BlogManager.instance().unscheduleSyndication(loc);
        }
    }
    
    protected PetName buildNewAddress(HttpServletRequest req) {
        PetName pn = new PetName();
        pn.setName(req.getParameter(AddressesServlet.PARAM_NAME));
        pn.setIsPublic(!empty(req, AddressesServlet.PARAM_IS_PUBLIC));
        pn.setLocation(req.getParameter(AddressesServlet.PARAM_LOC));
        pn.setNetwork(req.getParameter(AddressesServlet.PARAM_NET));
        pn.setProtocol(req.getParameter(AddressesServlet.PARAM_PROTO));
        if (empty(req, AddressesServlet.PARAM_FAVORITE))
            pn.removeGroup(FilteredThreadIndex.GROUP_FAVORITE);
        else
            pn.addGroup(FilteredThreadIndex.GROUP_FAVORITE);
        if (empty(req, AddressesServlet.PARAM_IGNORE))
            pn.removeGroup(FilteredThreadIndex.GROUP_IGNORE);
        else
            pn.addGroup(FilteredThreadIndex.GROUP_IGNORE);
        return pn;
    }
    
    protected void handleUpdateProfile(User user, HttpServletRequest req) {
        if ( (user == null) || (!user.getAuthenticated()) || (user.getBlog() == null) )
            return;
        
        String action = req.getParameter("action");
        if ( (action == null) || !("Update profile".equals(action)) )
            return;
        
        String name = req.getParameter(ThreadedHTMLRenderer.PARAM_PROFILE_NAME);
        String desc = req.getParameter(ThreadedHTMLRenderer.PARAM_PROFILE_DESC);
        String url = req.getParameter(ThreadedHTMLRenderer.PARAM_PROFILE_URL);
        String other = req.getParameter(ThreadedHTMLRenderer.PARAM_PROFILE_OTHER);
        
        Properties opts = new Properties();
        if (!empty(name))
            opts.setProperty(BlogInfo.NAME, name.trim());
        if (!empty(desc))
            opts.setProperty(BlogInfo.DESCRIPTION, desc.trim());
        if (!empty(url))
            opts.setProperty(BlogInfo.CONTACT_URL, url.trim());
        if (!empty(other)) {
            StringBuffer key = new StringBuffer();
            StringBuffer val = null;
            for (int i = 0; i < other.length(); i++) {
                char c = other.charAt(i);
                if ( (c == ':') || (c == '=') ) {
                    if (val != null) {
                        val.append(c);
                    } else {
                        val = new StringBuffer();
                    }
                } else if ( (c == '\n') || (c == '\r') ) {
                    String k = key.toString().trim();
                    String v = (val != null ? val.toString().trim() : "");
                    if ( (k.length() > 0) && (v.length() > 0) ) {
                        opts.setProperty(k, v);
                    }
                    key.setLength(0);
                    val = null;
                } else if (val != null) {
                    val.append(c);
                } else {
                    key.append(c);
                }
            }
            // now finish the last of it
            String k = key.toString().trim();
            String v = (val != null ? val.toString().trim() : "");
            if ( (k.length() > 0) && (v.length() > 0) ) {
                opts.setProperty(k, v);
            }
        }
        
        String pass0 = req.getParameter("password");
        String pass1 = req.getParameter("passwordConfirm");
        String oldPass = req.getParameter("oldPassword");
        
        if ( (pass0 != null) && (pass1 != null) && (pass0.equals(pass1)) ) {
            BlogManager.instance().changePasswrd(user, oldPass, pass0, pass1);
        }
            
        if (user.getAuthenticated() && !BlogManager.instance().authorizeRemote(user)) {
            String adminPass = req.getParameter("adminPass");
            if (adminPass != null) {
                boolean authorized = BlogManager.instance().authorizeRemote(adminPass);
                if (authorized) {
                    user.setAllowAccessRemote(authorized);
                    BlogManager.instance().saveUser(user);
                }
            }
        }

        boolean updated = BlogManager.instance().updateMetadata(user, user.getBlog(), opts);
    }
    
    private User handleRegister(User user, HttpServletRequest req) {
        String l = req.getParameter("login");
        String p = req.getParameter("password");
        String name = req.getParameter("accountName");
        String desc = req.getParameter("description");
        String contactURL = req.getParameter("url");
        String regPass = req.getParameter("registrationPass");
        String action = req.getParameter("action");
        
        if ( (action != null) && ("Register".equals(action)) && !empty(l) ) {
            return BlogManager.instance().register(l, p, regPass, name, desc, contactURL);
        } else {
            return user;
        }
    }
    
    private void handleAdmin(User user, HttpServletRequest req) throws IOException {
        if (BlogManager.instance().authorizeRemote(user)) {
            String action = req.getParameter("action");
            if ( (action != null) && ("Save config".equals(action)) ) {
                boolean wantSingle = !empty(req, "singleuser");
                String defaultUser = req.getParameter("defaultUser");
                String defaultPass = req.getParameter("defaultPass");
                String regPass = req.getParameter("regpass");
                String remotePass = req.getParameter("remotepass");
                String proxyHost = req.getParameter("proxyhost");
                String proxyPort = req.getParameter("proxyport");
                
                // default user cannot be empty, but the rest can be blank
                if ( (!empty(defaultUser)) && (defaultPass != null) && (regPass != null) && (remotePass != null) && 
                     (proxyHost != null) && (proxyPort != null) ) {
                    int port = 4444;
                    try { port = Integer.parseInt(proxyPort); } catch (NumberFormatException nfe) {}
                    BlogManager.instance().configure(regPass, remotePass, null, null, proxyHost, port, wantSingle, 
                                                     null, defaultUser, defaultPass);
                }
            }
        }
    }
        
    
    protected void render(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws ServletException, IOException {
        Archive archive = BlogManager.instance().getArchive();
        int numThreads = 10;
        int threadOffset = getOffset(req);
        if (threadOffset == -1) {
            threadOffset = index.getRootCount() - numThreads;
        } 
        if (threadOffset < 0) {
            threadOffset = 0;
        }

        BlogURI visibleEntry = getVisible(req);
        
        int offset = 0;
        if ( empty(req, ThreadedHTMLRenderer.PARAM_OFFSET) && (visibleEntry != null) ) {
            // we're on a permalink, so jump the tree to the given thread
            threadOffset = index.getRoot(visibleEntry);
            if (threadOffset < 0)
                threadOffset = 0;
        }
        
        renderBegin(user, req, out, index);
        renderNavBar(user, req, out, index);
        renderControlBar(user, req, out, index);
        renderServletDetails(user, req, out, index, threadOffset, visibleEntry, archive);
        renderEnd(user, req, out, index);
    }
    
    protected void renderBegin(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws IOException {
        out.write("<html>\n<head><title>" + getTitle() + "</title>\n");
        out.write("<meta http-equiv=\"cache-control\" content=\"no-cache\" />");
        out.write("<meta http-equiv=\"pragma\" content=\"no-cache\" />");
        out.write("<style>");
        out.write(STYLE_HTML);
        Reader css = null;
        try {
            InputStream in = req.getSession().getServletContext().getResourceAsStream("/syndie.css");
            if (in != null) {
                css = new InputStreamReader(in, "UTF-8");
                char buf[] = new char[1024];
                int read = 0;
                while ( (read = css.read(buf)) != -1) 
                    out.write(buf, 0, read);
            }
        } finally {
            if (css != null)
                css.close();
        }
        String content = FileUtil.readTextFile("./docs/syndie_standard.css", -1, true); 
        if (content != null) out.write(content);
        out.write("</style>");
        out.write(BEGIN_HTML);
    }
    protected void renderNavBar(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws IOException {
        //out.write("<tr class=\"topNav\"><td class=\"topNav_user\" colspan=\"2\" nowrap=\"true\">\n");
        out.write("<tr class=\"topNav\"><td colspan=\"3\" nowrap=\"true\"><span class=\"topNav_user\">\n");
        out.write("<!-- nav bar begin -->\n");
        out.write("<a href=\"threads.jsp\" title=\"Syndie home\">Threads</a> <a href=\"blogs.jsp\" title=\"Blog summary\">Blogs</a> ");
        if (user.getAuthenticated() && (user.getBlog() != null) ) {
            out.write("Logged in as <a href=\"" + getProfileLink(req, user.getBlog()) + "\" title=\"Edit your profile\">");
            out.write(user.getUsername());
            out.write("</a>\n");
            out.write("(<a href=\"switchuser.jsp\" title=\"Log in as another user\">switch</a>)\n");
            out.write("<a href=\"" + getPostURI() + "\" title=\"Post a new thread\">Post</a>\n");
            out.write("<a href=\"addresses.jsp\" title=\"View your addressbook\">Addressbook</a>\n");
        } else {
            out.write("<form action=\"" + req.getRequestURI() + "\" method=\"POST\">\n");
            writeAuthActionFields(out);
            out.write("Login: <input type=\"text\" name=\"login\" title=\"Login name for your Syndie account\" />\n");
            out.write("Password: <input type=\"password\" name=\"password\" title=\"Password to get into your Syndie account\" />\n");
            out.write("<input type=\"submit\" name=\"action\" value=\"Login\" /></form>\n");
        }
        //out.write("</td><td class=\"topNav_admin\">\n");
        out.write("</span><span class=\"topNav_admin\">\n");
        out.write("<a href=\"about.html\" title=\"Basic Syndie info\">About</a> ");
        if (BlogManager.instance().authorizeRemote(user)) {
            out.write("<a href=\"" + getSyndicateLink(user, null) + "\" title=\"Syndicate data between other Syndie nodes\">Syndicate</a>\n");
            out.write("<a href=\"importfeed.jsp\" title=\"Import RSS/Atom data\">Import RSS/Atom</a>\n");
            out.write("<a href=\"admin.jsp\" title=\"Configure this Syndie node\">Admin</a>\n");
        }
        out.write("</span><!-- nav bar end -->\n</td></tr>\n");
    }
    
    protected String getSyndicateLink(User user, String location) { 
        if (location != null)
            return "syndicate.jsp?" + SyndicateServlet.PARAM_LOCATION + "=" + location;
        return "syndicate.jsp";
    }
    
    protected static final ArrayList SKIP_TAGS = new ArrayList();
    static {
        SKIP_TAGS.add("action");
        SKIP_TAGS.add("filter");
        // post and visible are skipped since we aren't good at filtering by tag when the offset will
        // skip around randomly.  at least, not yet.
        SKIP_TAGS.add(ThreadedHTMLRenderer.PARAM_VISIBLE);
        //SKIP_TAGS.add("post");
        //SKIP_TAGS.add("thread");
        SKIP_TAGS.add(ThreadedHTMLRenderer.PARAM_OFFSET); // if we are adjusting the filter, ignore the previous offset
        SKIP_TAGS.add(ThreadedHTMLRenderer.PARAM_DAYS_BACK);
        SKIP_TAGS.add("addLocation");
        SKIP_TAGS.add("addGroup");
        SKIP_TAGS.add("login");
        SKIP_TAGS.add("password");
    }
    
    private static final String CONTROL_TARGET = "threads.jsp";
    protected String getControlTarget() { return CONTROL_TARGET; }
    protected String getPostURI() { return "post.jsp"; }
    
    protected void renderControlBar(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws IOException {
        out.write("<form action=\"");
        //out.write(req.getRequestURI());
        out.write(getControlTarget());
        out.write("\" method=\"GET\">\n");
        String tags = "";
        String author = "";
        Enumeration params = req.getParameterNames();
        while (params.hasMoreElements()) {
            String param = (String)params.nextElement();
            String val = req.getParameter(param);
            if (ThreadedHTMLRenderer.PARAM_TAGS.equals(param)) {
                tags = val;
            } else if (ThreadedHTMLRenderer.PARAM_AUTHOR.equals(param)) {
                author = val;
            } else if (SKIP_TAGS.contains(param)) {
                // skip
            } else if (param.length() <= 0) {
                // skip
            } else {
                out.write("<input type=\"hidden\" name=\"" + param + "\" value=\"" + val + "\" />\n");
            }
        }
        out.write("<tr class=\"controlBar\"><td colspan=\"2\" width=\"99%\">\n");
        out.write("<!-- control bar begin -->\n");
        out.write("Filter: <select name=\"" + ThreadedHTMLRenderer.PARAM_AUTHOR + "\">\n");
        
        PetNameDB db = user.getPetNameDB();
        TreeSet names = new TreeSet(db.getNames());
        out.write("<option value=\"\">Any authors</option>\n");
        if (user.getAuthenticated()) {
            if ("favorites".equals(author))
                out.write("<option selected=\"true\" value=\"favorites\">All recent posts by favorite authors</option>\n");
            else
                out.write("<option value=\"favorites\">All recent posts by favorite authors</option>\n");
        }
        if (user.getBlog() != null) {
            if ( (author != null) && (author.equals(user.getBlog().toBase64())) )
                out.write("<option value=\"" + user.getBlog().toBase64() + "\" selected=\"true\">Threads you posted in</option>\n");
            else
                out.write("<option value=\"" + user.getBlog().toBase64() + "\">Threads you posted in</option>\n");
        }
        
        for (Iterator iter = names.iterator(); iter.hasNext(); ) {
            String name = (String) iter.next();
            PetName pn = db.getByName(name);
            if ("syndieblog".equals(pn.getProtocol()) && pn.isMember(FilteredThreadIndex.GROUP_FAVORITE)) {
                if ( (author != null) && (author.equals(pn.getLocation())) )
                    out.write("<option value=\"" + pn.getLocation() + "\" selected=\"true\">Threads " + name + " posted in</option>\n");
                else
                    out.write("<option value=\"" + pn.getLocation() + "\">Threads " + name + " posted in</option>\n");
            }
        }
        out.write("</select>\n");
        
        out.write("Tags: ");
        writeTagField(user, tags, out);

        String days = req.getParameter(ThreadedHTMLRenderer.PARAM_DAYS_BACK);
        if (days == null)
            days = "";
        out.write("Age: <input type=\"text\" name=\"" + ThreadedHTMLRenderer.PARAM_DAYS_BACK + "\" size=\"2\" value=\"" + days
                  + "\" title=\"Posts are filtered to include only ones which were made within this many days ago\" /> days\n");
        
        out.write("<input type=\"submit\" name=\"action\" value=\"Go\" />\n");
        out.write("</td><td class=\"controlBarRight\" width=\"1%\">");
        
        if ( (req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_POST) != null) ||
             (req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_THREAD) != null) )
            out.write("<a href=\"#threads\" title=\"Jump to the thread navigation\">Threads</a>");
        out.write("</td>\n");
        out.write("<!-- control bar end -->\n");
        out.write("</tr>\n");
        out.write("</form>\n");
    }
    
    protected void writeTagField(User user, String selectedTags, PrintWriter out) throws IOException {
        writeTagField(user, selectedTags, out, "Threads are filtered to include only ones with posts containing these tags", "Any tags - no filtering", true);
    }
    public static void writeTagField(User user, String selectedTags, Writer out, String title, String blankTitle, boolean includeFavoritesTag) throws IOException {
        Set favoriteTags = new TreeSet(user.getFavoriteTags());
        if (favoriteTags.size() <= 0) {
            out.write("<input type=\"text\" name=\"" + ThreadedHTMLRenderer.PARAM_TAGS + "\" size=\"10\" value=\"" + selectedTags
                      + "\" title=\"" + title + "\" />\n");
        } else {
            out.write("<select name=\"" + ThreadedHTMLRenderer.PARAM_TAGS 
                      + "\" title=\"" + title + "\">");
            out.write("<option value=\"\">" + blankTitle + "</option>\n");
            boolean matchFound = false;
            if (includeFavoritesTag) {
                out.write("<option value=\"");
                StringBuffer combinedBuf = new StringBuffer();
                for (Iterator iter = favoriteTags.iterator(); iter.hasNext(); ) {
                    String curFavTag = (String)iter.next();
                    combinedBuf.append(HTMLRenderer.sanitizeTagParam(curFavTag)).append(" ");
                }
                String combined = combinedBuf.toString();
                if (selectedTags.equals(combined)) {
                    out.write(combined + "\" selected=\"true\" >All favorite tags</option>\n");
                    matchFound = true;
                } else {
                    out.write(combined + "\" >All favorite tags</option>\n");
                }
            }
            
            for (Iterator iter = favoriteTags.iterator(); iter.hasNext(); ) {
                String curFavTag = (String)iter.next();
                if (selectedTags.equals(curFavTag)) {
                    out.write("<option value=\"" + HTMLRenderer.sanitizeTagParam(curFavTag) + "\" selected=\"true\" >" 
                              + HTMLRenderer.sanitizeString(curFavTag)  + "</option>\n");
                    matchFound = true;
                } else {
                    out.write("<option value=\"" + HTMLRenderer.sanitizeTagParam(curFavTag) + "\">" 
                              + HTMLRenderer.sanitizeString(curFavTag)  + "</option>\n");
                }
            }
            if ( (!matchFound) && (selectedTags != null) && (selectedTags.trim().length() > 0) )
                out.write("<option value=\"" + HTMLRenderer.sanitizeTagParam(selectedTags) 
                          + "\" selected=\"true\">" + HTMLRenderer.sanitizeString(selectedTags) + "</option>\n");
            
            out.write("</select>\n");
        }
    }
   
    protected abstract void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, 
                                                 ThreadIndex index, int threadOffset, BlogURI visibleEntry, 
                                                 Archive archive) throws IOException;
    
    protected static final int getOffset(HttpServletRequest req) {
        String off = req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET);
        try {
            return Integer.parseInt(off);
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }
    protected static final BlogURI getVisible(HttpServletRequest req) {
        return getAsBlogURI(req.getParameter(ThreadedHTMLRenderer.PARAM_VISIBLE));
    }
    protected static final BlogURI getAsBlogURI(String uri) {
        if (uri != null) {
            int split = uri.indexOf('/');
            if ( (split <= 0) || (split + 1 >= uri.length()) )
                return null;
            String blog = uri.substring(0, split);
            String id = uri.substring(split+1);
            try {
                Hash hash = new Hash();
                hash.fromBase64(blog);
                long msgId = Long.parseLong(id);
                if (msgId > 0)
                    return new BlogURI(hash, msgId);
            } catch (DataFormatException dfe) {
                return null;
            } catch (NumberFormatException nfe) {
                return null;
            }
        }
        return null;
    }
    

    protected String trim(String orig, int maxLen) {
        if ( (orig == null) || (orig.length() <= maxLen) )
            return orig;
        return orig.substring(0, maxLen) + "...";
    }
    
    protected static final boolean empty(HttpServletRequest req, String param) {
        String val = req.getParameter(param);
        return (val == null) || (val.trim().length() <= 0);
    }
    
    protected static final boolean empty(String val) {
        return (val == null) || (val.trim().length() <= 0);
    }
    
    protected String getExpandLink(HttpServletRequest req, ThreadNode node) {
        return getExpandLink(node, req.getRequestURI(), req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_POST), 
                             req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_THREAD), 
                             req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET),
                             req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS),
                             req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR));
    }
    protected static String getExpandLink(ThreadNode node, String uri, String viewPost, String viewThread, 
                                        String offset, String tags, String author) {
        StringBuffer buf = new StringBuffer(64);
        buf.append(uri);
        buf.append('?');
        // expand node == let one of node's children be visible
        if (node.getChildCount() > 0) {
            ThreadNode child = node.getChild(0);
            buf.append(ThreadedHTMLRenderer.PARAM_VISIBLE).append('=');
            buf.append(child.getEntry().getKeyHash().toBase64()).append('/');
            buf.append(child.getEntry().getEntryId()).append('&');
        }
        
        if (!empty(viewPost))
            buf.append(ThreadedHTMLRenderer.PARAM_VIEW_POST).append('=').append(viewPost).append('&');
        else if (!empty(viewThread))
            buf.append(ThreadedHTMLRenderer.PARAM_VIEW_THREAD).append('=').append(viewThread).append('&');
        
        if (!empty(offset))
            buf.append(ThreadedHTMLRenderer.PARAM_OFFSET).append('=').append(offset).append('&');
        
        if (!empty(tags)) 
            buf.append(ThreadedHTMLRenderer.PARAM_TAGS).append('=').append(tags).append('&');
        
        if (!empty(author)) 
            buf.append(ThreadedHTMLRenderer.PARAM_AUTHOR).append('=').append(author).append('&');
        
        return buf.toString();
    }
    protected String getCollapseLink(HttpServletRequest req, ThreadNode node) {
        return getCollapseLink(node, req.getRequestURI(), 
                               req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_POST),
                               req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_THREAD),
                               req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET),
                               req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS),
                               req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR));
    }

    protected String getCollapseLink(ThreadNode node, String uri, String viewPost, String viewThread, 
                                   String offset, String tags, String author) { 
        StringBuffer buf = new StringBuffer(64);
        buf.append(uri);
        // collapse node == let the node be visible
        buf.append('?').append(ThreadedHTMLRenderer.PARAM_VISIBLE).append('=');
        buf.append(node.getEntry().getKeyHash().toBase64()).append('/');
        buf.append(node.getEntry().getEntryId()).append('&');

        if (!empty(viewPost))
            buf.append(ThreadedHTMLRenderer.PARAM_VIEW_POST).append('=').append(viewPost).append('&');
        else if (!empty(viewThread))
            buf.append(ThreadedHTMLRenderer.PARAM_VIEW_THREAD).append('=').append(viewThread).append('&');
        
        if (!empty(offset))
            buf.append(ThreadedHTMLRenderer.PARAM_OFFSET).append('=').append(offset).append('&');
        
        if (!empty(tags))
            buf.append(ThreadedHTMLRenderer.PARAM_TAGS).append('=').append(tags).append('&');
        
        if (!empty(author))
            buf.append(ThreadedHTMLRenderer.PARAM_AUTHOR).append('=').append(author).append('&');
        
        return buf.toString();
    }
    protected String getProfileLink(HttpServletRequest req, Hash author) {
        return getProfileLink(author);
    }
    protected String getProfileLink(Hash author) { return ThreadedHTMLRenderer.buildProfileURL(author); }
    
    protected String getAddToGroupLink(HttpServletRequest req, Hash author, User user, String group) {
        return getAddToGroupLink(user, author, group, req.getRequestURI(), 
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_VISIBLE),
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_POST), 
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_THREAD),
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET), 
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS), 
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR));
    }
    protected String getAddToGroupLink(User user, Hash author, String group, String uri, String visible,
                                     String viewPost, String viewThread, String offset, String tags, String filteredAuthor) {
        StringBuffer buf = new StringBuffer(64);
        buf.append(uri);
        buf.append('?');
        if (!empty(visible))
            buf.append(ThreadedHTMLRenderer.PARAM_VISIBLE).append('=').append(visible).append('&');
        buf.append(ThreadedHTMLRenderer.PARAM_ADD_TO_GROUP_LOCATION).append('=').append(author.toBase64()).append('&');
        buf.append(ThreadedHTMLRenderer.PARAM_ADD_TO_GROUP_NAME).append('=').append(group).append('&');

        if (!empty(viewPost))
            buf.append(ThreadedHTMLRenderer.PARAM_VIEW_POST).append('=').append(viewPost).append('&');
        else if (!empty(viewThread))
            buf.append(ThreadedHTMLRenderer.PARAM_VIEW_THREAD).append('=').append(viewThread).append('&');
        
        if (!empty(offset))
            buf.append(ThreadedHTMLRenderer.PARAM_OFFSET).append('=').append(offset).append('&');

        if (!empty(tags))
            buf.append(ThreadedHTMLRenderer.PARAM_TAGS).append('=').append(tags).append('&');
        
        if (!empty(filteredAuthor))
            buf.append(ThreadedHTMLRenderer.PARAM_AUTHOR).append('=').append(filteredAuthor).append('&');
        
        addAuthActionParams(buf);
        return buf.toString();
    }
    protected String getRemoveFromGroupLink(User user, String name, String group, String uri, String visible,
                                            String viewPost, String viewThread, String offset, String tags, String filteredAuthor) {
        StringBuffer buf = new StringBuffer(64);
        buf.append(uri);
        buf.append('?');
        if (!empty(visible))
            buf.append(ThreadedHTMLRenderer.PARAM_VISIBLE).append('=').append(visible).append('&');
        buf.append(ThreadedHTMLRenderer.PARAM_REMOVE_FROM_GROUP_NAME).append('=').append(name).append('&');
        buf.append(ThreadedHTMLRenderer.PARAM_REMOVE_FROM_GROUP).append('=').append(group).append('&');

        if (!empty(viewPost))
            buf.append(ThreadedHTMLRenderer.PARAM_VIEW_POST).append('=').append(viewPost).append('&');
        else if (!empty(viewThread))
            buf.append(ThreadedHTMLRenderer.PARAM_VIEW_THREAD).append('=').append(viewThread).append('&');
        
        if (!empty(offset))
            buf.append(ThreadedHTMLRenderer.PARAM_OFFSET).append('=').append(offset).append('&');

        if (!empty(tags))
            buf.append(ThreadedHTMLRenderer.PARAM_TAGS).append('=').append(tags).append('&');
        
        if (!empty(filteredAuthor))
            buf.append(ThreadedHTMLRenderer.PARAM_AUTHOR).append('=').append(filteredAuthor).append('&');
        
        addAuthActionParams(buf);
        return buf.toString();
    }
    protected String getViewPostLink(HttpServletRequest req, ThreadNode node, User user, boolean isPermalink) {
        return ThreadedHTMLRenderer.getViewPostLink(req.getRequestURI(), node, user, isPermalink, 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET), 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS), 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR),
                                                    Boolean.valueOf(req.getParameter(ThreadedHTMLRenderer.PARAM_THREAD_AUTHOR)).booleanValue());
    }
    protected String getViewPostLink(HttpServletRequest req, BlogURI post, User user) {
        return ThreadedHTMLRenderer.getViewPostLink(req.getRequestURI(), post, user, false, 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET), 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS), 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR),
                                                    Boolean.valueOf(req.getParameter(ThreadedHTMLRenderer.PARAM_THREAD_AUTHOR)).booleanValue());
    }
    protected String getViewThreadLink(HttpServletRequest req, ThreadNode node, User user) {
        return getViewThreadLink(req.getRequestURI(), node, user,
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET),
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS),
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR),
                                 Boolean.valueOf(req.getParameter(ThreadedHTMLRenderer.PARAM_THREAD_AUTHOR)).booleanValue());
    }
    protected static String getViewThreadLink(String uri, ThreadNode node, User user, String offset,
                                            String tags, String author, boolean authorOnly) {
        StringBuffer buf = new StringBuffer(64);
        buf.append(uri);
        BlogURI expandTo = node.getEntry();
        if (node.getChildCount() > 0) {
            if (true) {
                // lets expand to the leaf
                expandTo = new BlogURI(node.getMostRecentPostAuthor(), node.getMostRecentPostDate());
            } else {
                // only expand one level
                expandTo = node.getChild(0).getEntry();
            }
        } 
        buf.append('?').append(ThreadedHTMLRenderer.PARAM_VISIBLE).append('=');
        buf.append(expandTo.getKeyHash().toBase64()).append('/');
        buf.append(expandTo.getEntryId()).append('&');
        
        buf.append(ThreadedHTMLRenderer.PARAM_VIEW_THREAD).append('=');
        buf.append(node.getEntry().getKeyHash().toBase64()).append('/');
        buf.append(node.getEntry().getEntryId()).append('&');
        
        if (!empty(offset))
            buf.append(ThreadedHTMLRenderer.PARAM_OFFSET).append('=').append(offset).append('&');
        
        if (!empty(tags))
            buf.append(ThreadedHTMLRenderer.PARAM_TAGS).append('=').append(tags).append('&');
        
        if (!empty(author)) {
            buf.append(ThreadedHTMLRenderer.PARAM_AUTHOR).append('=').append(author).append('&');
            if (authorOnly)
                buf.append(ThreadedHTMLRenderer.PARAM_THREAD_AUTHOR).append("=true&");
        }
        
        buf.append("#").append(node.getEntry().toString());
        return buf.toString();
    }
    protected String getFilterByTagLink(HttpServletRequest req, ThreadNode node, User user, String tag, String author) { 
        return ThreadedHTMLRenderer.getFilterByTagLink(req.getRequestURI(), node, user, tag, author);
    }
    protected String getNavLink(HttpServletRequest req, int offset) {
        return ThreadedHTMLRenderer.getNavLink(req.getRequestURI(),
                                               req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_POST), 
                                               req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_THREAD), 
                                               req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS), 
                                               req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR), 
                                               Boolean.valueOf(req.getParameter(ThreadedHTMLRenderer.PARAM_THREAD_AUTHOR)).booleanValue(), 
                                               offset);
    }
    
    protected void renderEnd(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws IOException {
        out.write(END_HTML);
    }

    protected Collection getFilteredTags(HttpServletRequest req) {
        String tags = req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS);
        if (tags != null) {
            StringTokenizer tok = new StringTokenizer(tags, "\n\t ");
            ArrayList rv = new ArrayList();
            while (tok.hasMoreTokens()) {
                String tag = tok.nextToken().trim();
                if (tag.length() > 0)
                    rv.add(tag);
            }
            return rv;
        } else {
            return Collections.EMPTY_LIST;
        }
    }
    
    protected Collection getFilteredAuthors(HttpServletRequest req) {
        List rv = new ArrayList();
        rv.addAll(getAuthors(req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR)));
        //rv.addAll(getAuthors(req.getParameter(ThreadedHTMLRenderer.PARAM_THREAD_AUTHOR)));
        return rv;
    }
    
    private Collection getAuthors(String authors) {
        if (authors != null) {
            StringTokenizer tok = new StringTokenizer(authors, "\n\t ");
            ArrayList rv = new ArrayList();
            while (tok.hasMoreTokens()) {
                try {
                    Hash h = new Hash();
                    h.fromBase64(tok.nextToken().trim());
                    rv.add(h);
                } catch (DataFormatException dfe) {}
            }
            return rv;
        } else {
            return Collections.EMPTY_LIST;
        }
    }
    
    private static final String BEGIN_HTML = "<link href=\"rss.jsp\" rel=\"alternate\" type=\"application/rss+xml\" >\n" +
"</head>\n" +
"<body>\n" +
"<span style=\"display: none\"><a href=\"#bodySubject\">Jump to the beginning of the first post rendered, if any</a>\n" +
"<a href=\"#threads\">Jump to the thread navigation</a>\n</span>\n" +
"<table border=\"0\" width=\"100%\" class=\"overallTable\">\n";
    private static final String STYLE_HTML = ".overallTable {\n" +
"	border-spacing: 0px;\n" +
"	border-width: 0px;\n" +
"	border: 0px;\n" +
"	margin: 0px;\n" +
"	padding: 0px;\n" +
"}\n" +
".topNav {\n" +
"	background-color: #BBBBBB;\n" +
"}\n" +
".topNav_user {\n" +
"	text-align: left;\n" +
"	float: left;\n" +
"	display: inline;\n" +
"}\n" +
".topNav_admin {\n" +
"	text-align: right;\n" +
"	float: right;\n" +
"	margin: 0 5px 0 0;\n" +
"	display: inline;\n" +
"}\n" +
".controlBar {\n" +
"	background-color: #BBBBBB;\n" +
"}\n" +
".controlBarRight {\n" +
"	text-align: right;\n" +
"}\n" +
".threadEven {\n" +
"	background-color: #FFFFFF;\n" +
"	white-space: nowrap;\n" +
"}\n" +
".threadOdd {\n" +
"	background-color: #EEEEEE;\n" +
"	white-space: nowrap;\n" +
"}\n" +
".threadLeft {\n" +
"	text-align: left;\n" +
"	align: left;\n" +
"}\n" +
".threadNav {\n" +
"	background-color: #BBBBBB;\n" +
"}\n" +
".threadNavRight {\n" +
"	text-align: right;\n" +
"	float: right;\n" +
"	background-color: #BBBBBB;\n" +
"}\n" +
".rightOffset {\n" +
"                   float: right;\n" +
"                   margin: 0 5px 0 0;\n" +
"	display: inline;\n" +
"}\n" +    
".threadInfoLeft {\n" +
"                   float: left;\n" +
"                   margin: 5px 0px 0 0;\n" +
"	display: inline;\n" +
"}\n" +    
".threadInfoRight {\n" +
"                   float: right;\n" +
"                   margin: 0 5px 0 0;\n" +
"	display: inline;\n" +
"}\n" +    
".postMeta {\n" +
"	background-color: #BBBBFF;\n" +
"}\n" +
".postMetaSubject {\n" +
"	text-align: left;\n" +
"}\n" +
".postMetaLink {\n" +
"	text-align: right;\n" +
"}\n" +
".postDetails {\n" +
"	background-color: #DDDDFF;\n" +
"}\n" +
".postReply {\n" +
"	background-color: #BBBBFF;\n" +
"}\n" +
".postReplyText {\n" +
"	background-color: #BBBBFF;\n" +
"}\n" +
".postReplyOptions {\n" +
"	background-color: #BBBBFF;\n" +
"}\n" +
".syndieBlogFavorites {\n" +
"                   float: left;\n" +
"                   margin: 5px 0px 0 0;\n" +
"	display: inline;\n" +
"}\n" +
".syndieBlogList {\n" +
"                   float: right;\n" +
"                   margin: 5px 0px 0 0;\n" +
"	display: inline;\n" +
"}\n";

    
    private static final String END_HTML = "</table>\n" +
"</body>\n";
   
    protected String getTitle() { return "Syndie"; }
    
    protected static class TreeRenderState {
        private int _rowsWritten;
        private int _rowsSkipped;
        private List _ignored;
        public TreeRenderState(List ignored) { 
            _rowsWritten = 0; 
            _rowsSkipped = 0;
            _ignored = ignored;
        }
        public int getRowsWritten() { return _rowsWritten; }
        public void incrementRowsWritten() { _rowsWritten++; }
        public int getRowsSkipped() { return _rowsSkipped; }
        public void incrementRowsSkipped() { _rowsSkipped++; }
        public List getIgnoredAuthors() { return _ignored; }
    }
}
