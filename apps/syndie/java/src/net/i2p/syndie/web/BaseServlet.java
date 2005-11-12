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

/**
 * Base servlet for handling request and rendering the templates
 *
 */
public abstract class BaseServlet extends HttpServlet {
    public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html");
        
        User user = (User)req.getSession().getAttribute("user");
        String login = req.getParameter("login");
        String pass = req.getParameter("password");
        String action = req.getParameter("action");
        boolean forceNewIndex = false;
        
        if (req.getParameter("regenerateIndex") != null)
            forceNewIndex = true;

        User oldUser = user;
        user = handleRegister(user, req);
        if (oldUser != user)
            forceNewIndex = true;
        
        if (user == null) {
            if ("Login".equals(action)) {
                user = BlogManager.instance().login(login, pass); // ignore failures - user will just be unauthorized
                if (!user.getAuthenticated())
                    user = BlogManager.instance().getDefaultUser();
            } else {
                user = BlogManager.instance().getDefaultUser();
            }
            forceNewIndex = true;
        } else if ("Login".equals(action)) {
            user = BlogManager.instance().login(login, pass); // ignore failures - user will just be unauthorized
            if (!user.getAuthenticated())
                user = BlogManager.instance().getDefaultUser();
            forceNewIndex = true;
        } else if ("Logout".equals(action)) {
            user = BlogManager.instance().getDefaultUser();
            forceNewIndex = true;
        }
        
        req.getSession().setAttribute("user", user);
        
        forceNewIndex = handleBookmarking(user, req) || forceNewIndex;
        handleUpdateProfile(user, req);
        
        FilteredThreadIndex index = (FilteredThreadIndex)req.getSession().getAttribute("threadIndex");
        
        Collection tags = getFilteredTags(req);
        Collection filteredAuthors = getFilteredAuthors(req);
        if (forceNewIndex || (index == null) || (!index.getFilteredTags().equals(tags)) || (!index.getFilteredAuthors().equals(filteredAuthors))) {
            index = new FilteredThreadIndex(user, BlogManager.instance().getArchive(), getFilteredTags(req), filteredAuthors);
            req.getSession().setAttribute("threadIndex", index);
        }
        
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

                    pn = new PetName(name, "syndie", "syndieblog", loc);
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
        
        return rv;
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
        out.write(BEGIN_HTML);
    }
    protected void renderNavBar(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws IOException {
        //out.write("<tr class=\"topNav\"><td class=\"topNav_user\" colspan=\"2\" nowrap=\"true\">\n");
        out.write("<tr class=\"topNav\"><td colspan=\"3\" nowrap=\"true\"><span class=\"topNav_user\">\n");
        out.write("<!-- nav bar begin -->\n");
        if (user.getAuthenticated() && (user.getBlog() != null) ) {
            out.write("Logged in as <a href=\"" + getProfileLink(req, user.getBlog()) + "\" title=\"Edit your profile\">");
            out.write(user.getUsername());
            out.write("</a>\n");
            out.write("(<a href=\"switchuser.jsp\" title=\"Log in as another user\">switch</a>)\n");
            out.write("<a href=\"post.jsp\" title=\"Post a new thread\">Post a new thread</a>\n");
        } else {
            out.write("<form action=\"" + req.getRequestURI() + "\" method=\"GET\">\n");
            out.write("Login: <input type=\"text\" name=\"login\" />\n");
            out.write("Password: <input type=\"password\" name=\"password\" />\n");
            out.write("<input type=\"submit\" name=\"action\" value=\"Login\" /></form>\n");
        }
        //out.write("</td><td class=\"topNav_admin\">\n");
        out.write("</span><span class=\"topNav_admin\">\n");
        if (BlogManager.instance().authorizeRemote(user)) {
            out.write("<a href=\"syndicate.jsp\" title=\"Syndicate data between other Syndie nodes\">Syndicate</a>\n");
            out.write("<a href=\"importfeed.jsp\" title=\"Import RSS/Atom data\">Import RSS/Atom</a>\n");
            out.write("<a href=\"admin.jsp\" title=\"Configure this Syndie node\">Admin</a>\n");
        }
        out.write("</span><!-- nav bar end -->\n</td></tr>\n");
    }
    
    protected static final ArrayList SKIP_TAGS = new ArrayList();
    static {
        SKIP_TAGS.add("action");
        SKIP_TAGS.add("filter");
        // post and visible are skipped since we aren't good at filtering by tag when the offset will
        // skip around randomly.  at least, not yet.
        SKIP_TAGS.add("visible");
        //SKIP_TAGS.add("post");
        //SKIP_TAGS.add("thread");
        SKIP_TAGS.add("offset"); // if we are adjusting the filter, ignore the previous offset
        SKIP_TAGS.add("login");
        SKIP_TAGS.add("password");
    }
    
    private static final String CONTROL_TARGET = "threads.jsp";
    protected String getControlTarget() { return CONTROL_TARGET; }
    
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
        out.write("<tr class=\"controlBar\"><td colspan=\"2\">\n");
        out.write("<!-- control bar begin -->\n");
        out.write("Filter: <select name=\"" + ThreadedHTMLRenderer.PARAM_AUTHOR + "\">\n");
        
        PetNameDB db = user.getPetNameDB();
        TreeSet names = new TreeSet(db.getNames());
        out.write("<option value=\"\">Any authors</option>\n");
        if (user.getBlog() != null) {
            if ( (author != null) && (author.equals(user.getBlog().toBase64())) )
                out.write("<option value=\"" + user.getBlog().toBase64() + "\" selected=\"true\">Threads you posted in</option>\n");
            else
                out.write("<option value=\"" + user.getBlog().toBase64() + "\">Threads you posted in</option>\n");
        }
        
        for (Iterator iter = names.iterator(); iter.hasNext(); ) {
            String name = (String) iter.next();
            PetName pn = db.getByName(name);
            if ("syndieblog".equals(pn.getProtocol())) {
                if ( (author != null) && (author.equals(pn.getLocation())) )
                    out.write("<option value=\"" + pn.getLocation() + "\" selected=\"true\">Threads " + name + " posted in</option>\n");
                else
                    out.write("<option value=\"" + pn.getLocation() + "\">Threads " + name + " posted in</option>\n");
            }
        }
        out.write("</select>\n");
        
        out.write("Tags: <input type=\"text\" name=\"" + ThreadedHTMLRenderer.PARAM_TAGS + "\" size=\"10\" value=\"" + tags + "\" />\n");

        out.write("<input type=\"submit\" name=\"action\" value=\"Go\" />\n");
        out.write("</td><td class=\"controlBarRight\"><a href=\"#threads\" title=\"Jump to the thread navigation\">Threads</a></td>\n");
        out.write("<!-- control bar end -->\n");
        out.write("</tr>\n");
        out.write("</form>\n");
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
        
        return buf.toString();
    }
    protected String getViewPostLink(HttpServletRequest req, ThreadNode node, User user, boolean isPermalink) {
        return ThreadedHTMLRenderer.getViewPostLink(req.getRequestURI(), node, user, isPermalink, 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET), 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS), 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR));
    }
    protected String getViewThreadLink(HttpServletRequest req, ThreadNode node, User user) {
        return getViewThreadLink(req.getRequestURI(), node, user,
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET),
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS),
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR));
    }
    protected static String getViewThreadLink(String uri, ThreadNode node, User user, String offset,
                                            String tags, String author) {
        StringBuffer buf = new StringBuffer(64);
        buf.append(uri);
        if (node.getChildCount() > 0) {
            buf.append('?').append(ThreadedHTMLRenderer.PARAM_VISIBLE).append('=');
            ThreadNode child = node.getChild(0);
            buf.append(child.getEntry().getKeyHash().toBase64()).append('/');
            buf.append(child.getEntry().getEntryId()).append('&');
        } else {
            buf.append('?').append(ThreadedHTMLRenderer.PARAM_VISIBLE).append('=');
            buf.append(node.getEntry().getKeyHash().toBase64()).append('/');
            buf.append(node.getEntry().getEntryId()).append('&');
        }
        buf.append(ThreadedHTMLRenderer.PARAM_VIEW_THREAD).append('=');
        buf.append(node.getEntry().getKeyHash().toBase64()).append('/');
        buf.append(node.getEntry().getEntryId()).append('&');
        
        if (!empty(offset))
            buf.append(ThreadedHTMLRenderer.PARAM_OFFSET).append('=').append(offset).append('&');
        
        if (!empty(tags))
            buf.append(ThreadedHTMLRenderer.PARAM_TAGS).append('=').append(tags).append('&');
        
        if (!empty(author))
            buf.append(ThreadedHTMLRenderer.PARAM_AUTHOR).append('=').append(author).append('&');
        
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
        String authors = req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR);
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
    
    private static final String BEGIN_HTML = "<html>\n" +
"<head>\n" +
"<title>Syndie</title>\n" +
"<style>\n" +
".overallTable {\n" +
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
".threadRight {\n" +
"	text-align: right;\n" +
"}\n" +
".threadNav {\n" +
"	background-color: #BBBBBB;\n" +
"}\n" +
".threadNavRight {\n" +
"	text-align: right;\n" +
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
"</style>\n" +
"<link href=\"style.jsp\" rel=\"stylesheet\" type=\"text/css\" >\n" +
"<link href=\"rss.jsp\" rel=\"alternate\" type=\"application/rss+xml\" >\n" +
"</head>\n" +
"<body>\n" +
"<span style=\"display: none\"><a href=\"#bodySubject\">Jump to the beginning of the first post rendered, if any</a>\n" +
"<a href=\"#threads\">Jump to the thread navigation</a>\n</span>\n" +
"<table border=\"0\" width=\"100%\" class=\"overallTable\">\n";
    
    private static final String END_HTML = "</table>\n" +
"</body>\n";
   
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
