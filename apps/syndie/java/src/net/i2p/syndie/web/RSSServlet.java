package net.i2p.syndie.web;

import java.io.*;
import java.util.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

import net.i2p.I2PAppContext;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;

/**
 *
 */
public class RSSServlet extends HttpServlet {
    
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/rss+xml");
        
        User user = (User)req.getSession().getAttribute("user");
        if (user == null) {
            String login = req.getParameter("login");
            String pass = req.getParameter("password");
            user = BlogManager.instance().login(login, pass); // ignore failures - user will just be unauthorized
            if (!user.getAuthenticated()) {
                user.invalidate();
                user = BlogManager.instance().getDefaultUser();
            }
        }
        
        String tags = req.getParameter(ThreadedHTMLRenderer.PARAM_TAGS);
        Set tagSet = new HashSet();
        if (tags != null) {
            StringTokenizer tok = new StringTokenizer(tags, " \n\t\r");
            while (tok.hasMoreTokens()) {
                String tag = (String)tok.nextToken();
                tag = tag.trim();
                if (tag.length() > 0)
                    tagSet.add(tag);
            }
        }
        
        int count = 10;
        String wanted = req.getParameter("wanted");
        if (wanted != null) {
            try {
                count = Integer.parseInt(wanted);
            } catch (NumberFormatException nfe) {
                count = 10;
            }
        }
        if (count < 0) count = 10;
        if (count > 100) count = 100;
        
        Archive archive = BlogManager.instance().getArchive();
        FilteredThreadIndex index = new FilteredThreadIndex(user, archive, tagSet, null, false);
        List entries = new ArrayList();
        // depth first search of the most recent threads
        for (int i = 0; i < count && i < index.getRootCount(); i++) {
            ThreadNode node = index.getRoot(i);
            if (node != null)
                walkTree(entries, node);
        }
        
        StringBuffer cur = new StringBuffer();
        cur.append(req.getScheme());
        cur.append("://");
        cur.append(req.getServerName());
        if (req.getServerPort() != 80)
            cur.append(':').append(req.getServerPort());
        cur.append(req.getContextPath()).append('/');
        String urlPrefix = cur.toString();
        
        Writer out = resp.getWriter();
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n");
        out.write("<rss version=\"2.0\">\n");
        out.write(" <channel>\n");
        out.write("  <title>Syndie feed</title>\n");
        String page = urlPrefix;
        if (tags != null)
            page = page + "threads.jsp?" + ThreadedHTMLRenderer.PARAM_TAGS + '=' + HTMLRenderer.sanitizeXML(tags);
        out.write("  <link>" + page +"</link>\n");
        out.write("  <description>Summary of the latest Syndie posts</description>\n");
        out.write("  <generator>Syndie</generator>\n");
        
        RSSRenderer r = new RSSRenderer(I2PAppContext.getGlobalContext());
        for (int i = 0; i < count && i < entries.size(); i++) {
            BlogURI uri = (BlogURI)entries.get(i);
            EntryContainer entry = archive.getEntry(uri);
            r.render(user, archive, entry, urlPrefix, out);
        }
        
        out.write(" </channel>\n");
        out.write("</rss>\n");
        out.close();
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
}
