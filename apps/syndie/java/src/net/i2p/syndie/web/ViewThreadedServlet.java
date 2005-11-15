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
        renderBody(user, req, out, index);
        
        renderThreadNav(user, req, out, threadOffset, index);
        renderThreadTree(user, req, out, threadOffset, visibleEntry, archive, index);
        renderThreadNav(user, req, out, threadOffset, index);
    }   
    
    private void renderBody(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws IOException  {
        ThreadedHTMLRenderer renderer = new ThreadedHTMLRenderer(I2PAppContext.getGlobalContext());
        Archive archive = BlogManager.instance().getArchive();
        List posts = getPosts(archive, req, index);
        
        String uri = req.getRequestURI();
        String off = req.getParameter(ThreadedHTMLRenderer.PARAM_OFFSET);
        String tags = req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS);
        String author = req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR);

        for (int i = 0; i < posts.size(); i++) {
            BlogURI post = (BlogURI)posts.get(i);
            renderer.render(user, out, archive, post, posts.size() == 1, index, uri, getAuthActionFields(), off, tags, author);
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
            out.write("<!-- thread begin curRoot=" + curRoot + " threadOffset=" + threadOffset + " -->\n");
            renderThread(user, out, index, archive, req, node, 0, visibleEntry, state);
            out.write("<!-- thread end -->\n");
            written++;
        }
        
        if (written <= 0)
            out.write("<tr class=\"threadEven\"><td colspan=\"3\">No matching threads</td></tr>\n");
        
        out.write("<!-- threads end -->\n");
    }
    
    private boolean renderThread(User user, PrintWriter out, ThreadIndex index, Archive archive, HttpServletRequest req,
                                 ThreadNode node, int depth, BlogURI visibleEntry, TreeRenderState state) {
        boolean isFavorite = false;
        boolean ignored = false;
        
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
        out.write(trim(subject, 40));
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
