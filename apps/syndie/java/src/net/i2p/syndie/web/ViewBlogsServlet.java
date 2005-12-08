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
 * List the blogs known in the archive
 *
 */
public class ViewBlogsServlet extends BaseServlet {
    private static final int MAX_AUTHORS_AT_ONCE = 100;
    private static final int MAX_TAGS = 50;
    
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        TreeSet orderedRoots = new TreeSet(new NewestEntryFirstComparator());
        // The thread index is ordered by last updated date, as opposed to root posting date,
        // so lets reorder things
        int count = index.getRootCount();
        for (int i = 0; i < count; i++) {
            ThreadNode node = index.getRoot(i);
            orderedRoots.add(node.getEntry());
        }
        
        TreeSet tags = new TreeSet();
        List writtenAuthors = new ArrayList();
        out.write("<tr><td colspan=\"3\"><b>Blogs:</b></td></tr>\n");
        out.write("<tr><td colspan=\"3\">");
        for (Iterator iter = orderedRoots.iterator(); iter.hasNext() && writtenAuthors.size() < MAX_AUTHORS_AT_ONCE; ) {
            BlogURI uri= (BlogURI)iter.next();
            String curTags[] = archive.getEntry(uri).getTags();
            if (curTags != null)
                for (int i = 0; i < curTags.length && tags.size() < MAX_TAGS; i++)
                    tags.add(curTags[i]);
            if (writtenAuthors.contains(uri.getKeyHash())) {
                // skip
            } else {
                BlogInfo info = archive.getBlogInfo(uri);
                if (info == null)
                    continue;
                String name = info.getProperty(BlogInfo.NAME);
                if ( (name == null) || (name.trim().length() <= 0) )
                    name = uri.getKeyHash().toBase64().substring(0,8);
                String desc = info.getProperty(BlogInfo.DESCRIPTION);
                if ( (desc == null) || (desc.trim().length() <= 0) ) 
                    desc = name + "'s blog";
                String age = null;
                long dayBegin = BlogManager.instance().getDayBegin();
                long postId = uri.getEntryId();
                if (postId >= dayBegin) {
                    age = "today";
                } else if (postId >= dayBegin - 24*60*60*1000) {
                    age = "yesterday";
                } else {
                    int daysAgo = (int)((dayBegin - postId + 24*60*60*1000-1)/(24*60*60*1000));
                    age = daysAgo + " days ago";
                }
                
                out.write("<a href=\"" + getControlTarget() + "?" 
                          + ThreadedHTMLRenderer.PARAM_AUTHOR + '=' + uri.getKeyHash().toBase64()
                          + "&" + ThreadedHTMLRenderer.PARAM_THREAD_AUTHOR + "=true&"
                          + "\" title=\"Posts by " + trim(HTMLRenderer.sanitizeTagParam(name), 32)
                          + ", last post " + age + "\">");
                out.write(HTMLRenderer.sanitizeString(desc, 32));
                out.write("</a> \n");
                writtenAuthors.add(uri.getKeyHash());
            }
        }
        out.write("</td></tr>\n");
        
        out.write("<tr><td colspan=\"3\"><b>Topics:</b></td></tr>\n");
        out.write("<tr><td colspan=\"3\">");
        for (Iterator iter = tags.iterator(); iter.hasNext(); ) {
            String tag = (String)iter.next();
            out.write("<a href=\"" + ThreadedHTMLRenderer.getFilterByTagLink(getControlTarget(), null, user, tag, null) 
                      + "\" title=\"View threads flagged with the tag '" + HTMLRenderer.sanitizeTagParam(tag) + "'\">");
            out.write(HTMLRenderer.sanitizeString(tag, 32));
            out.write("</a> ");
        }
        out.write("</td></tr>\n");
    }
    
    protected String getTitle() { return "Syndie :: View blogs"; }
}
