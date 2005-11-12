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
 *
 */
public class ViewThreadedServlet extends HttpServlet {
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
        
        if (user == null) {
            if ("Login".equals(action)) {
                user = BlogManager.instance().login(login, pass); // ignore failures - user will just be unauthorized
                if (!user.getAuthenticated())
                    user.invalidate();
            } else {
                user = new User();
            }
            forceNewIndex = true;
        } else if ("Login".equals(action)) {
            user = BlogManager.instance().login(login, pass); // ignore failures - user will just be unauthorized
            forceNewIndex = true;
        } else if ("Logout".equals(action)) {
            user = new User();
            forceNewIndex = true;
        }
        
        req.getSession().setAttribute("user", user);
        
        if (user.getAuthenticated()) {
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
                        forceNewIndex = true;
                } catch (DataFormatException dfe) {
                    // bad loc, ignore
                }
            }
        }
        
        FilteredThreadIndex index = (FilteredThreadIndex)req.getSession().getAttribute("threadIndex");
        
        Collection tags = getFilteredTags(req);
        Collection filteredAuthors = getFilteredAuthors(req);
        if (forceNewIndex || (index == null) || (!index.getFilteredTags().equals(tags)) || (!index.getFilteredAuthors().equals(filteredAuthors))) {
            index = new FilteredThreadIndex(user, BlogManager.instance().getArchive(), getFilteredTags(req), filteredAuthors);
            req.getSession().setAttribute("threadIndex", index);
        }
        
        render(user, req, resp.getWriter(), index);
    }
    
    private void render(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws ServletException, IOException {
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
        renderBody(user, req, out, index);
        
        renderThreadNav(user, req, out, threadOffset, index);
        renderThreadTree(user, req, out, threadOffset, visibleEntry, archive, index);
        renderThreadNav(user, req, out, threadOffset, index);
        renderEnd(user, req, out, index);
    }
    
    private void renderBegin(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws IOException {
        out.write(BEGIN_HTML);
    }
    private void renderNavBar(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws IOException {
        //out.write("<tr class=\"topNav\"><td class=\"topNav_user\" colspan=\"2\" nowrap=\"true\">\n");
        out.write("<tr class=\"topNav\"><td colspan=\"3\" nowrap=\"true\"><span class=\"topNav_user\">\n");
        out.write("<!-- nav bar begin -->\n");
        if (user.getAuthenticated()) {
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
        if (user.getAuthenticated() && user.getAllowAccessRemote()) {
            out.write("<a href=\"syndicate.jsp\" title=\"Syndicate data between other Syndie nodes\">Syndicate</a>\n");
            out.write("<a href=\"importfeed.jsp\" title=\"Import RSS/Atom data\">Import RSS/Atom</a>\n");
            out.write("<a href=\"admin.jsp\" title=\"Configure this Syndie node\">Admin</a>\n");
        }
        out.write("</span><!-- nav bar end -->\n</td></tr>\n");
    }
    
    private static final ArrayList SKIP_TAGS = new ArrayList();
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
    
    private void renderControlBar(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws IOException {
        out.write("<form action=\"");
        out.write(req.getRequestURI());
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
        if (author.equals(user.getBlog().toBase64()))
            out.write("<option value=\"" + user.getBlog().toBase64() + "\" selected=\"true\">Threads you posted in</option>\n");
        else
            out.write("<option value=\"" + user.getBlog().toBase64() + "\">Threads you posted in</option>\n");
        
        for (Iterator iter = names.iterator(); iter.hasNext(); ) {
            String name = (String) iter.next();
            PetName pn = db.getByName(name);
            if ("syndieblog".equals(pn.getProtocol())) {
                if (author.equals(pn.getLocation()))
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
    private void renderBody(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws IOException, ServletException  {
        ThreadedHTMLRenderer renderer = new ThreadedHTMLRenderer(I2PAppContext.getGlobalContext());
        Archive archive = BlogManager.instance().getArchive();
        List posts = getPosts(archive, req, index);
        
        String uri = req.getRequestURI();
        String off = req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET);
        String tags = req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS);
        String author = req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR);

        for (int i = 0; i < posts.size(); i++) {
            BlogURI post = (BlogURI)posts.get(i);
            renderer.render(user, out, archive, post, posts.size() == 1, index, uri, off, tags, author);
        }
    }
    
    private List getPosts(Archive archive, HttpServletRequest req, ThreadIndex index) {
        List rv = new ArrayList(1);
        String post = req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_POST);
        BlogURI uri = getAsBlogURI(post);
        if ( (uri != null) && (uri.getEntryId() > 0) ) {
            rv.add(uri);
        } else {
            String thread = req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_THREAD);
            uri = getAsBlogURI(thread);
            if ( (uri != null) && (uri.getEntryId() > 0) ) {
                ThreadNode node = index.getNode(uri);
                if (node != null) {
                    while (node.getParent() != null)
                        node = node.getParent(); // hope the structure is loopless...
                    // depth first traversal
                    walkTree(rv, node);
                } else {
                    rv.add(uri);
                }
            }
        }
        return rv;
    }
    
    private void walkTree(List uris, ThreadNode node) {
        if (node == null)
            return;
        if (uris.contains(node))
            return;
        uris.add(node.getEntry());
        for (int i = 0; i < node.getChildCount(); i++)
            walkTree(uris, node.getChild(i));
    }
    private void renderThreadNav(User user, HttpServletRequest req, PrintWriter out, int threadOffset, ThreadIndex index) throws IOException {
        out.write("<tr class=\"threadNav\" id=\"threads\"><td colspan=\"2\" nowrap=\"true\">\n");
        out.write("<!-- thread nav begin -->\n");
        if (threadOffset == 0) {
            out.write("&lt;&lt; First Page ");
        } else {
            out.write("<a href=\"");
            out.write(getNavLink(req, 0));
            out.write("\">&lt;&lt; First Page</a> ");
        }
        if (threadOffset > 0) {
            out.write("<a href=\"");
            int nxt = threadOffset - 10;
            if (nxt < 0)
                nxt = 0;
            out.write(getNavLink(req, nxt));
            out.write("\">&lt; Prev Page</a>\n");
        } else {
            out.write("&lt; Prev Page\n");
        }
        out.write("</td><td class=\"threadNavRight\" nowrap=\"true\">\n");
        
        int max = index.getRootCount();
        if (threadOffset + 10 > max) {
            out.write("Next Page&gt; Last Page&gt;&gt;\n");
        } else {
            out.write("<a href=\"");
            out.write(getNavLink(req, threadOffset + 10));
            out.write("\">Next Page&gt;</a> <a href=\"");
            out.write(getNavLink(req, -1));
            out.write("\">Last Page&gt;&gt;</a>\n");
        }
        out.write("<!-- thread nav end -->\n");
        out.write("</td></tr>\n");
    }
    
    private void renderThreadTree(User user, HttpServletRequest req, PrintWriter out, int threadOffset, BlogURI visibleEntry, Archive archive, ThreadIndex index) throws IOException {
        int numThreads = 10;
        renderThreadTree(user, out, index, archive, req, threadOffset, numThreads, visibleEntry);
    }
    
    private static final int getOffset(HttpServletRequest req) {
        String off = req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET);
        try {
            return Integer.parseInt(off);
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }
    private static final BlogURI getVisible(HttpServletRequest req) {
        return getAsBlogURI(req.getParameter(ThreadedHTMLRenderer.PARAM_VISIBLE));
    }
    private static final BlogURI getAsBlogURI(String uri) {
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
    
    private Collection getFilteredTags(HttpServletRequest req) {
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
    
    private Collection getFilteredAuthors(HttpServletRequest req) {
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

    private void renderThreadTree(User user, PrintWriter out, ThreadIndex index, Archive archive, HttpServletRequest req,
                                  int threadOffset, int numThreads, BlogURI visibleEntry) {
        
        if ( (visibleEntry != null) && (empty(req, ThreadedHTMLRenderer.PARAM_OFFSET)) ) {
            // we want to jump to a specific thread in the nav
            threadOffset = index.getRoot(visibleEntry);
        }

        if (threadOffset < 0)
            threadOffset = 0;
        out.write("<!-- threads begin -->\n");
        if (threadOffset + numThreads > index.getRootCount())
            numThreads = index.getRootCount() - threadOffset;
        TreeRenderState state = new TreeRenderState(new ArrayList());
        
        int written = 0;
        for (int curRoot = threadOffset; curRoot < numThreads + threadOffset; curRoot++) {
            ThreadNode node = index.getRoot(curRoot);
            out.write("<!-- thread begin node=" + node + " curRoot=" + curRoot + " threadOffset=" + threadOffset + " -->\n");
            renderThread(user, out, index, archive, req, node, 0, visibleEntry, state);
            out.write("<!-- thread end -->\n");
            written++;
        }
        
        if (written <= 0)
            out.write("<tr class=\"threadEven\"><td colspan=\"3\">No matching threads</td></tr>\n");
        
        out.write("<!-- threads end -->\n");
    }
    
    /**
     * @return true if some post in the thread has been written
     */
    private boolean renderThread(User user, PrintWriter out, ThreadIndex index, Archive archive, HttpServletRequest req,
                                 ThreadNode node, int depth, BlogURI visibleEntry, TreeRenderState state) {
        boolean isFavorite = false;
        
        HTMLRenderer rend = new HTMLRenderer(I2PAppContext.getGlobalContext());
        SMLParser parser = new SMLParser(I2PAppContext.getGlobalContext());
        
        PetName pn = user.getPetNameDB().getByLocation(node.getEntry().getKeyHash().toBase64());
        if (pn != null) {
            if (pn.isMember(FilteredThreadIndex.GROUP_FAVORITE)) {
                isFavorite = true;
            }
        }
        
        state.incrementRowsWritten();
        if (state.getRowsWritten() % 2 == 0)
            out.write("<tr class=\"threadEven\">\n");
        else
            out.write("<tr class=\"threadOdd\">\n");

        out.write("<td class=\"threadFlag\">");
        out.write(getFlagHTML(user, node));
        out.write("</td>\n<td class=\"threadLeft\">\n");
        for (int i = 0; i < depth; i++)
            out.write("<img src=\"images/threadIndent.png\" alt=\"\" border=\"0\" />");
        
        boolean showChildren = false;
        
        int childCount = node.getChildCount();
        
        if (childCount > 0) {
            boolean allowCollapse = false;

            if (visibleEntry != null) {
                if (node.getEntry().equals(visibleEntry)) {
                    // noop
                } else if (node.containsEntry(visibleEntry)) {
                    showChildren = true;
                    allowCollapse = true;
                }
            } else {
                // noop
            }
        
            if (allowCollapse) {
                out.write("<a href=\"");
                out.write(getCollapseLink(req, node));
                out.write("\" title=\"collapse thread\"><img border=\"0\" src=\"images/collapse.png\" alt=\"collapse\" /></a>\n");
            } else {
                out.write("<a href=\"");
                out.write(getExpandLink(req, node));
                out.write("\" title=\"expand thread\"><img border=\"0\" src=\"images/expand.png\" alt=\"expand\" /></a>\n");
            }
        } else {
            out.write("<img src=\"images/noSubthread.png\" alt=\"\" border=\"0\" />\n");
        }
        
        out.write("<a href=\"");
        out.write(getProfileLink(req, node.getEntry().getKeyHash()));
        out.write("\" title=\"View the user's profile\">");

        if (pn == null) {
            BlogInfo info = archive.getBlogInfo(node.getEntry().getKeyHash());
            String name = null;
            if (info != null)
                name = info.getProperty(BlogInfo.NAME);
            if ( (name == null) || (name.trim().length() <= 0) )
                name = node.getEntry().getKeyHash().toBase64().substring(0,6);
            out.write(trim(name, 30));
        } else {
            out.write(trim(pn.getName(), 30));
        }
        out.write("</a>\n");

        if (node.getEntry().getKeyHash().equals(user.getBlog())) {
            out.write("<img src=\"images/self.png\" alt=\"You wrote this\" border=\"0\" />\n");
        } else if (isFavorite) {
            out.write("<img src=\"images/favorites.png\" alt=\"favorites\" border=\"0\" />\n");
        } else {
            if (user.getAuthenticated()) {
                // give them a link to bookmark or ignore the peer
                out.write("(<a href=\"");
                out.write(getAddToGroupLink(req, node.getEntry().getKeyHash(), user, FilteredThreadIndex.GROUP_FAVORITE));
                out.write("\" title=\"Add as a friend\"><img src=\"images/addToFavorites.png\" alt=\"friend\" border=\"0\" /></a>\n");
                out.write("/<a href=\"");
                out.write(getAddToGroupLink(req, node.getEntry().getKeyHash(), user, FilteredThreadIndex.GROUP_IGNORE));
                out.write("\" title=\"Add to killfile\"><img src=\"images/addToIgnored.png\" alt=\"ignore\" border=\"0\" /></a>)\n");
            }
        }

        out.write(" @ ");
        out.write("<a href=\"");
        out.write(getViewPostLink(req, node, user, false));
        out.write("\" title=\"View post\">");
        out.write(rend.getEntryDate(node.getEntry().getEntryId()));
        out.write(": ");
        EntryContainer entry = archive.getEntry(node.getEntry());

        HeaderReceiver rec = new HeaderReceiver();
        parser.parse(entry.getEntry().getText(), rec);
        String subject = rec.getHeader(HTMLRenderer.HEADER_SUBJECT);
        if (subject == null)
            subject = "";
        out.write(trim(subject, 60));
        out.write("</a>\n</td><td class=\"threadRight\">\n");
        out.write("<a href=\"");
        out.write(getViewThreadLink(req, node, user));
        out.write("\" title=\"View all posts in the thread\">view thread</a>\n");
        out.write("</td></tr>\n");
        
        boolean rendered = true;
        
        if (showChildren) {
            for (int i = 0; i < node.getChildCount(); i++) {
                ThreadNode child = node.getChild(i);
                boolean childRendered = renderThread(user, out, index, archive, req, child, depth+1, visibleEntry, state);
                rendered = rendered || childRendered;
            }
        }
        
        return rendered;
    }
    
    private String trim(String orig, int maxLen) {
        if ( (orig == null) || (orig.length() <= maxLen) )
            return orig;
        return orig.substring(0, maxLen) + "...";
    }
    
    private String getFlagHTML(User user, ThreadNode node) {
        if (node.containsAuthor(user.getBlog()))
            return "<img src=\"images/self.png\" border=\"0\" alt=\"You have posted in the thread\" />";
        
        // grab all of the peers in the user's favorites group and check to see if 
        // they posted something in the given thread, flagging it if they have
        boolean favoriteFound = false;
        for (Iterator iter = user.getPetNameDB().getNames().iterator(); iter.hasNext(); ) {
            PetName pn = user.getPetNameDB().getByName((String)iter.next());
            if (pn.isMember(FilteredThreadIndex.GROUP_FAVORITE)) {
                Hash cur = new Hash();
                try {
                    cur.fromBase64(pn.getLocation());
                    if (node.containsAuthor(cur)) {
                        favoriteFound = true;
                        break;
                    }
                } catch (Exception e) {}
            }
        }
        if (favoriteFound) 
            return "<img src=\"images/favorites.png\" border=\"0\" alt=\"flagged author posted in the thread\" />";
        else
            return "&nbsp;"; 
    }
    
    private static final boolean empty(HttpServletRequest req, String param) {
        String val = req.getParameter(param);
        return (val == null) || (val.trim().length() <= 0);
    }
    
    private static final boolean empty(String val) {
        return (val == null) || (val.trim().length() <= 0);
    }
    
    private String getExpandLink(HttpServletRequest req, ThreadNode node) {
        return getExpandLink(node, req.getRequestURI(), req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_POST), 
                             req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_THREAD), 
                             req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET),
                             req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS),
                             req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR));
    }
    private static String getExpandLink(ThreadNode node, String uri, String viewPost, String viewThread, 
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
    private String getCollapseLink(HttpServletRequest req, ThreadNode node) {
        return getCollapseLink(node, req.getRequestURI(), 
                               req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_POST),
                               req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_THREAD),
                               req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET),
                               req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS),
                               req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR));
    }

    private String getCollapseLink(ThreadNode node, String uri, String viewPost, String viewThread, 
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
    private String getProfileLink(HttpServletRequest req, Hash author) {
        return getProfileLink(author);
    }
    private static String getProfileLink(Hash author) { return HTMLRenderer.getMetadataURL(author); }
    
    private String getAddToGroupLink(HttpServletRequest req, Hash author, User user, String group) {
        return getAddToGroupLink(user, author, group, req.getRequestURI(), 
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_VISIBLE),
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_POST), 
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_THREAD),
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET), 
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS), 
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR));
    }
    private String getAddToGroupLink(User user, Hash author, String group, String uri, String visible,
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
    private String getViewPostLink(HttpServletRequest req, ThreadNode node, User user, boolean isPermalink) {
        return ThreadedHTMLRenderer.getViewPostLink(req.getRequestURI(), node, user, isPermalink, 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET), 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS), 
                                                    req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR));
    }
    private String getViewThreadLink(HttpServletRequest req, ThreadNode node, User user) {
        return getViewThreadLink(req.getRequestURI(), node, user,
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET),
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS),
                                 req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR));
    }
    private static String getViewThreadLink(String uri, ThreadNode node, User user, String offset,
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
    private String getFilterByTagLink(HttpServletRequest req, ThreadNode node, User user, String tag, String author) { 
        return ThreadedHTMLRenderer.getFilterByTagLink(req.getRequestURI(), node, user, tag, author);
    }
    private String getNavLink(HttpServletRequest req, int offset) {
        return ThreadedHTMLRenderer.getNavLink(req.getRequestURI(),
                                               req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_POST), 
                                               req.getParameter(ThreadedHTMLRenderer.PARAM_VIEW_THREAD), 
                                               req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS), 
                                               req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR), 
                                               offset);
    }
    
    private void renderEnd(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws IOException {
        out.write(END_HTML);
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
"	align: left;\n" +
"	display: inline;\n" +
"}\n" +
".topNav_admin {\n" +
"	text-align: right;\n" +
"	float: right;\n" +
"	align: right;\n" +
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
   
    private static class TreeRenderState {
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
