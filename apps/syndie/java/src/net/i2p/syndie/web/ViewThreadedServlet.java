package net.i2p.syndie.web;

import java.io.*;
import java.util.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.*;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;

/**
 * Render the appropriate posts and the thread tree
 *
 */
public class ViewThreadedServlet extends BaseServlet {
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        List posts = getPosts(user, archive, req, index);
        renderBody(user, req, out, index, archive, posts);
        
        renderThreadNav(user, req, out, threadOffset, index);
        renderThreadTree(user, req, out, threadOffset, visibleEntry, archive, index, posts);
        renderThreadNav(user, req, out, threadOffset, index);
    }
    
    private void renderBody(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, Archive archive, List posts) throws IOException  {
        ThreadedHTMLRenderer renderer = new ThreadedHTMLRenderer(I2PAppContext.getGlobalContext());
        
        String uri = req.getRequestURI();
        String off = req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET);
        String tags = req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS);
        String author = req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR);

        for (int i = 0; i < posts.size(); i++) {
            BlogURI post = (BlogURI)posts.get(i);
            boolean inlineReply = (posts.size() == 1);
            //if (true)
            //    inlineReply = true;
            renderer.render(user, out, archive, post, inlineReply, index, uri, getAuthActionFields(), off, tags, author);
        }
    }
    
    private List getPosts(User user, Archive archive, HttpServletRequest req, ThreadIndex index) {
        List rv = new ArrayList(1);
        String author = req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR);
        String tags = req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS);
        if (author != null) {
            long dayBegin = BlogManager.instance().getDayBegin();
            String daysStr = req.getParameter(ThreadedHTMLRenderer.PARAM_DAYS_BACK);
            int days = 1;
            try {
                if (daysStr != null)
                    days = Integer.parseInt(daysStr);
            } catch (NumberFormatException nfe) {
                days = 1;
            }
            dayBegin -= (days-1) * 24*60*60*1000;
            
            ArchiveIndex aindex = archive.getIndex();
            PetNameDB db = user.getPetNameDB();
            if ("favorites".equals(author)) {
                for (Iterator nameIter = db.getNames().iterator(); nameIter.hasNext(); ) {
                    PetName pn = db.getByName((String)nameIter.next());
                    if (pn.isMember(FilteredThreadIndex.GROUP_FAVORITE) && AddressesServlet.PROTO_BLOG.equals(pn.getProtocol()) ) {
                        Hash loc = new Hash();
                        byte key[] = Base64.decode(pn.getLocation());
                        if ( (key != null) && (key.length == Hash.HASH_LENGTH) ) {
                            loc.setData(key);
                            aindex.selectMatchesOrderByEntryId(rv, loc, tags, dayBegin);
                        }
                    }
                }
                Collections.sort(rv, BlogURI.COMPARATOR);
            } else {
                Hash loc = new Hash();
                byte key[] = Base64.decode(author);
                if ( (key != null) && (key.length == Hash.HASH_LENGTH) ) {
                    loc.setData(key);
                    aindex.selectMatchesOrderByEntryId(rv, loc, tags, dayBegin);
                }
            }
        }
        
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
                    if (false) {
                        // entire thread, as a depth first search
                        while (node.getParent() != null)
                            node = node.getParent(); // hope the structure is loopless...
                        // depth first traversal
                        walkTree(rv, node);
                    } else {
                        // only the "current" unforked thread, as suggested by cervantes.
                        // e.g.
                        //  a--b--c--d
                        //   \-e--f--g
                        //         \-h
                        // would show "a--e--f--g" if node == {e, f, or g}, 
                        // or "a--b--c--d" if node == {a, b, c, or d},
                        // or "a--e--f--h" if node == h
                        rv.add(node.getEntry());
                        ThreadNode cur = node;
                        while (cur.getParent() != null) {
                            cur = cur.getParent();
                            rv.add(0, cur.getEntry()); // parents go before children...
                        }
                        cur = node;
                        while ( (cur != null) && (cur.getChildCount() > 0) ) {
                            cur = cur.getChild(0);
                            rv.add(cur.getEntry()); // and children after parents
                        }
                    }
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
        
        out.write("<span class=\"rightOffset\">");
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
        out.write("</span>");
        //out.write("<!-- thread nav end -->\n");
        out.write("</td></tr>\n");
    }
    
    private void renderThreadTree(User user, HttpServletRequest req, PrintWriter out, int threadOffset, BlogURI visibleEntry, Archive archive, ThreadIndex index, List visibleURIs) throws IOException {
        int numThreads = 10;
        renderThreadTree(user, out, index, archive, req, threadOffset, numThreads, visibleEntry, visibleURIs);
    }
     
    private void renderThreadTree(User user, PrintWriter out, ThreadIndex index, Archive archive, HttpServletRequest req,
                                  int threadOffset, int numThreads, BlogURI visibleEntry, List visibleURIs) {
        
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
            out.write("<!-- thread begin curRoot=" + curRoot + " threadOffset=" + threadOffset + " -->\n");
            renderThread(user, out, index, archive, req, node, 0, visibleEntry, state, visibleURIs);
            out.write("<!-- thread end -->\n");
            written++;
        }
        
        if (written <= 0)
            out.write("<tr class=\"threadEven\"><td colspan=\"3\">No matching threads</td></tr>\n");
        
        out.write("<!-- threads end -->\n");
    }
    
    private boolean renderThread(User user, PrintWriter out, ThreadIndex index, Archive archive, HttpServletRequest req,
                                 ThreadNode node, int depth, BlogURI visibleEntry, TreeRenderState state, List visibleURIs) {
        boolean isFavorite = false;
        boolean ignored = false;
        boolean displayed = false;
        
        if ( (visibleURIs != null) && (visibleURIs.contains(node.getEntry())) )
            displayed = true;
        
        HTMLRenderer rend = new HTMLRenderer(I2PAppContext.getGlobalContext());
        SMLParser parser = new SMLParser(I2PAppContext.getGlobalContext());
        
        PetName pn = user.getPetNameDB().getByLocation(node.getEntry().getKeyHash().toBase64());
        if (pn != null) {
            if (pn.isMember(FilteredThreadIndex.GROUP_FAVORITE)) {
                isFavorite = true;
            }
            if (pn.isMember(FilteredThreadIndex.GROUP_IGNORE))
                ignored = true;
        }
        
        state.incrementRowsWritten();
        if (state.getRowsWritten() % 2 == 0)
            out.write("<tr class=\"threadEven\">\n");
        else
            out.write("<tr class=\"threadOdd\">\n");

        out.write("<td class=\"thread\" colspan=\"3\">");
        out.write("<span class=\"threadInfoLeft\">");
        //out.write("<td class=\"threadFlag\">");
        out.write(getFlagHTML(user, node));
        //out.write("</td>\n<td class=\"threadLeft\" colspan=\"2\">\n");
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

        if (displayed) out.write("<b>");
        
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
        
        if (displayed) out.write("</b>");
        
        out.write("</a>\n");

        if ( (user.getBlog() != null) && (node.getEntry().getKeyHash().equals(user.getBlog())) ) {
            out.write("<img src=\"images/self.png\" alt=\"You wrote this\" border=\"0\" />\n");
        } else if (isFavorite) {
            out.write("<img src=\"images/favorites.png\" alt=\"favorites\" border=\"0\" />\n");
        } else if (ignored) {
            out.write("<img src=\"images/addToIgnored.png\" alt=\"ignored\" border=\"0\" />\n");
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

        out.write(": ");
        out.write("<a href=\"");
        if (false) {
            out.write(getViewPostLink(req, node, user, false));
        } else {
            out.write(getViewThreadLink(req, node, user));
        }
        out.write("\" title=\"View post\">");
        EntryContainer entry = archive.getEntry(node.getEntry());
        if (entry == null) throw new RuntimeException("Unable to fetch the entry " + node.getEntry());

        HeaderReceiver rec = new HeaderReceiver();
        parser.parse(entry.getEntry().getText(), rec);
        String subject = rec.getHeader(HTMLRenderer.HEADER_SUBJECT);
        if ( (subject == null) || (subject.trim().length() <= 0) )
            subject = "(no subject)";
        if (displayed) {
            // currently being rendered
            out.write("<b>");
            out.write(trim(subject, 40));
            out.write("</b>");
        } else {
            out.write(trim(subject, 40));
        }
        //out.write("</a>\n</td><td class=\"threadRight\">\n");
        out.write("</a>");
        if (false) {
            out.write(" (<a href=\"");
            out.write(getViewThreadLink(req, node, user));
            out.write("\" title=\"View all posts in the thread\">full thread</a>)\n");
        }
        
        out.write("</span><span class=\"threadInfoRight\">");
        
        out.write(" <a href=\"");
        BlogURI newestURI = new BlogURI(node.getMostRecentPostAuthor(), node.getMostRecentPostDate());
        if (false) {
            out.write(getViewPostLink(req, newestURI, user));
        } else {
            List paths = new ArrayList();
            paths.add(node);
            ThreadNode cur = null;
            while (paths.size() > 0) {
                cur = (ThreadNode)paths.remove(0);
                if (cur.getEntry().equals(newestURI))
                    break;
                for (int i = cur.getChildCount() - 1; i >= 0; i--)
                    paths.add(cur.getChild(i));
                if (paths.size() <= 0)
                    cur = null;
            }
            if (cur != null)
                out.write(getViewThreadLink(req, cur, user));
        }
        out.write("\" title=\"View the most recent post\">latest - ");

        long dayBegin = BlogManager.instance().getDayBegin();
        long postId = node.getMostRecentPostDate();
        if (postId >= dayBegin) {
            out.write("<b>today</b>");
        } else if (postId >= dayBegin - 24*60*60*1000) {
            out.write("<b>yesterday</b>");
        } else {
            int daysAgo = (int)((dayBegin - postId + 24*60*60*1000-1)/(24*60*60*1000));
            out.write(daysAgo + " days ago");
        }

        out.write("</a>\n");
        /*
        out.write(" <a href=\"");
        out.write(getViewThreadLink(req, node, user));
        out.write("\" title=\"View all posts in the thread\">full thread</a>\n");
         */
        out.write("</span>");
        out.write("</td></tr>\n");
        
        boolean rendered = true;
        
        if (showChildren) {
            for (int i = 0; i < node.getChildCount(); i++) {
                ThreadNode child = node.getChild(i);
                boolean childRendered = renderThread(user, out, index, archive, req, child, depth+1, visibleEntry, state, visibleURIs);
                rendered = rendered || childRendered;
            }
        }
        
        return rendered;
    }
    
    private String getFlagHTML(User user, ThreadNode node) {
        if ( (user.getBlog() != null) && (node.containsAuthor(user.getBlog())) )
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
    
    protected String getTitle() { return "Syndie :: View threads"; }
}
