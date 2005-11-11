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
            if (!user.getAuthenticated())
                user.invalidate();
        }
        
        String selector = req.getParameter("selector");
        if ( (selector == null) || (selector.length() <= 0) ) {
            selector = getDefaultSelector(user);
        }
        ArchiveViewerBean.Selector sel = new ArchiveViewerBean.Selector(selector);
        
        Archive archive = BlogManager.instance().getArchive();
        ArchiveIndex index = archive.getIndex();
        List entries = ArchiveViewerBean.pickEntryURIs(user, index, sel.blog, sel.tag, sel.entry, sel.group);
        
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
        if (sel.tag != null)
            page = page + "threads.jsp?" + ThreadedHTMLRenderer.PARAM_TAGS + '=' + HTMLRenderer.sanitizeXML(sel.tag);
        else if ( (sel.blog != null) && (sel.entry > 0) )
            page = page + "threads.jsp?" + ThreadedHTMLRenderer.PARAM_VIEW_POST + '=' + sel.blog.toBase64() + '/' + sel.entry;
        out.write("  <link>" + page +"</link>\n");
        out.write("  <description>Summary of the latest Syndie posts</description>\n");
        out.write("  <generator>Syndie</generator>\n");
        
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
    
    private static String getDefaultSelector(User user) {
        if ( (user == null) || (user.getDefaultSelector() == null) )
            return BlogManager.instance().getArchive().getDefaultSelector();
        else
            return user.getDefaultSelector();
    }
}
