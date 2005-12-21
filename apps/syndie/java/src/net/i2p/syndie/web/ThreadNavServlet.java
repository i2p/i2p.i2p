package net.i2p.syndie.web;

import java.io.*;
import java.util.*;

import javax.servlet.ServletException;
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
 * Export the thread nav as either RDF or XML
 *
 */
public class ThreadNavServlet extends BaseServlet {
    public static final String PARAM_COUNT = "count";
    public static final String PARAM_OFFSET = "offset";
    public static final String PARAM_FORMAT = "format";
    
    public static final String FORMAT_RDF = "rdf";
    public static final String FORMAT_XML = "xml";
    
    protected void render(User user, HttpServletRequest req, HttpServletResponse resp, ThreadIndex index) throws ServletException, IOException {
        int threadCount = empty(req, PARAM_COUNT) ? index.getRootCount() : getInt(req, PARAM_COUNT);
        int offset = getInt(req, PARAM_OFFSET);
        String uri = req.getRequestURI();
        if (uri.endsWith(FORMAT_XML)) {
            resp.setContentType("text/xml; charset=UTF-8");
            render(user, index, resp.getWriter(), threadCount, offset, FORMAT_XML);
        } else {
            resp.setContentType("application/rdf+xml; charset=UTF-8");
            render(user, index, resp.getWriter(), threadCount, offset, FORMAT_RDF);
        }
    }
    
    private int getInt(HttpServletRequest req, String param) {
        String val = req.getParameter(param);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }
        return -1;
    }
    
    private static final int DEFAULT_THREADCOUNT = 10;
    private static final int DEFAULT_THREADOFFSET = 0;
    
    private void render(User user, ThreadIndex index, PrintWriter out, int threadCount, int offset, String format) throws IOException {
        int startRoot = DEFAULT_THREADOFFSET;
        if (offset >= 0)
            startRoot = offset;
        renderStart(out, format);

        int endRoot = startRoot + (threadCount > 0 ? threadCount : DEFAULT_THREADCOUNT);
        if (endRoot >= index.getRootCount())
            endRoot = index.getRootCount() - 1;
        for (int i = startRoot; i <= endRoot; i++) {
            ThreadNode node = index.getRoot(i);
            if (FORMAT_XML.equals(format))
                out.write(node.toString());
            else
                render(user, node, out);
        }
        renderEnd(out, format);
    }
    private void renderStart(PrintWriter out, String format) throws IOException {
        out.write("<?xml version=\"1.0\" ?>\n");
        if (FORMAT_XML.equals(format)) {
            out.write("<threadTree>");
        } else {
            out.write("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" " +
                      "         xmlns:syndie=\"http://syndie.i2p.net/syndie.ns#\">\n");
            out.write("<rdf:Seq rdf:about=\"http://syndie.i2p.net/threads\">\n");
        }
    }
    private void renderEnd(PrintWriter out, String format) throws IOException {
        if (FORMAT_XML.equals(format)) {
            out.write("</threadTree>");
        } else {
            out.write("</rdf:Seq>\n");
            out.write("</rdf:RDF>\n");
        }
    }
    private void render(User user, ThreadNode node, PrintWriter out) throws IOException {
        Archive archive = BlogManager.instance().getArchive();
        String blog = node.getEntry().getKeyHash().toBase64();
        out.write("<rdf:li rdf:resource=\"entry://" + blog + "/" + node.getEntry().getEntryId() + "\">\n");
        out.write("<rdf:Description rdf:about=\"entry://" + blog + "/" + node.getEntry().getEntryId() + "\">");
        PetName pn = user.getPetNameDB().getByLocation(blog);
        String name = null;
        if (pn != null) {
            if (pn.isMember(FilteredThreadIndex.GROUP_FAVORITE))
                out.write("<syndie:favoriteauthor />\n");
            if (pn.isMember(FilteredThreadIndex.GROUP_IGNORE))
                out.write("<syndie:ignoredauthor />\n");
            name = pn.getName();
        } else {
            BlogInfo info = archive.getBlogInfo(node.getEntry().getKeyHash());
            if (info != null)
                name = info.getProperty(BlogInfo.NAME);
            if ( (name == null) || (name.trim().length() <= 0) )
                name = node.getEntry().getKeyHash().toBase64().substring(0,6);
        }
        out.write("<syndie:author syndie:blog=\"" + blog + "\">" + HTMLRenderer.sanitizeStrippedXML(name) + "</syndie:author>\n");
        if ( (user.getBlog() != null) && (node.containsAuthor(user.getBlog())) )
            out.write("<syndie:threadself />\n");
        
        EntryContainer entry = archive.getEntry(node.getEntry());
        if (entry == null) throw new RuntimeException("Unable to fetch the entry " + node.getEntry());

        SMLParser parser = new SMLParser(I2PAppContext.getGlobalContext());
        HeaderReceiver rec = new HeaderReceiver();
        parser.parse(entry.getEntry().getText(), rec);
        String subject = rec.getHeader(HTMLRenderer.HEADER_SUBJECT);
        if ( (subject == null) || (subject.trim().length() <= 0) )
            subject = "(no subject)";
        
        out.write("<syndie:subject>" + HTMLRenderer.sanitizeStrippedXML(subject) + "</syndie:subject>\n");
        
        long dayBegin = BlogManager.instance().getDayBegin();
        long postId = node.getEntry().getEntryId();
        int daysAgo = (int)((dayBegin - postId + 24*60*60*1000l-1l)/(24*60*60*1000l));
        out.write("<syndie:age>" + daysAgo + "</syndie:age>\n");
        
        out.write("<syndie:children>");
        out.write("<rdf:Seq rdf:about=\"entry://" + blog + "/" + node.getEntry().getEntryId() + "\">");
        for (int i = 0; i < node.getChildCount(); i++)
            render(user, node.getChild(i), out);
        out.write("</rdf:Seq>\n");
        out.write("</syndie:children>\n");
        
        out.write("</rdf:Description>\n");
        out.write("</rdf:li>\n");
    }
    
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        throw new UnsupportedOperationException("Not relevant...");
    }
}
