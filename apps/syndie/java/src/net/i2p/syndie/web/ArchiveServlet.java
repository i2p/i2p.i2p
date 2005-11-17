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

/**
 *
 */
public class ArchiveServlet extends HttpServlet {
    
    public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handle(req, resp);
    }
    
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handle(req, resp);
    }
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handle(req, resp);
    }
    public void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handle(req, resp);
    }
    
    public void handle(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = req.getPathInfo();
        if ( (path == null) || (path.trim().length() <= 1) ) {
            renderRootIndex(resp);
            return;
        } else if (path.endsWith(Archive.INDEX_FILE)) {
            renderSummary(req.getHeader("If-None-Match"), resp);
        } else if (path.indexOf("export.zip") != -1) {
            ExportServlet.export(req, resp);
        } else {
            String blog = getBlog(path);
            if (path.endsWith(Archive.METADATA_FILE)) {
                renderMetadata(blog, resp);
            } else if (path.endsWith(".snd")) {
                renderEntry(blog, getEntry(path), resp);
            } else {
                renderBlogIndex(blog, resp);
            }
        }
    }
    
    private String getBlog(String path) {
        //System.err.println("Blog: [" + path + "]");
        int start = 0;
        int end = -1;
        int len = path.length();
        for (int i = 0; i < len; i++) {
            if (path.charAt(i) != '/') {
                start = i;
                break;
            }
        }
        for (int j = start + 1; j < len; j++) {
            if (path.charAt(j) == '/') {
                end = j;
                break;
            }
        }
        if (end < 0) end = len;
        String rv = path.substring(start, end);
        //System.err.println("Blog: [" + path + "] rv: [" + rv + "]");
        return rv;
    }
    
    private long getEntry(String path) {
        int start = path.lastIndexOf('/');
        if (start < 0) return -1;
        if (!(path.endsWith(".snd"))) return -1;
        String rv = path.substring(start+1, path.length()-".snd".length());
        //System.err.println("Entry: [" + path + "] rv: [" + rv + "]");
        try {
            return Long.parseLong(rv);
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }
    
    private void renderRootIndex(HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html;charset=utf-8");
        //resp.setCharacterEncoding("UTF-8");
        OutputStream out = resp.getOutputStream();
        out.write(DataHelper.getUTF8("<a href=\"archive.txt\">archive.txt</a><br />\n"));
        ArchiveIndex index = BlogManager.instance().getArchive().getIndex();
        Set blogs = index.getUniqueBlogs();
        for (Iterator iter = blogs.iterator(); iter.hasNext(); ) {
            Hash blog = (Hash)iter.next();
            String s = blog.toBase64();
            out.write(DataHelper.getUTF8("<a href=\"" + s + "/\">" + s + "</a><br />\n"));
        }
        out.close();
    }
    
    public static final String HEADER_EXPORT_CAPABLE = "X-Syndie-Export-Capable";
    
    private void renderSummary(String etag, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/plain;charset=utf-8");
        //resp.setCharacterEncoding("UTF-8");
        ArchiveIndex index = BlogManager.instance().getArchive().getIndex();
        byte[] indexUTF8 = DataHelper.getUTF8(index.toString());
        String newEtag = "\"" + I2PAppContext.getGlobalContext().sha().calculateHash(indexUTF8).toBase64() + "\"";
        if (etag != null && etag.equals(newEtag)) {
            resp.sendError(304, "Archive not modified");
            return;
        }
        resp.setHeader(HEADER_EXPORT_CAPABLE, "true");
        resp.setHeader("ETag", newEtag);
        OutputStream out = resp.getOutputStream();
        out.write(indexUTF8);
        out.close();
    }
    
    private void renderMetadata(String blog, HttpServletResponse resp) throws ServletException, IOException {
        byte b[] = Base64.decode(blog);
        if ( (b == null) || (b.length != Hash.HASH_LENGTH) ) {
            resp.sendError(404, "Invalid blog requested");
            return;
        }
        Hash h = new Hash(b);
        BlogInfo info = BlogManager.instance().getArchive().getBlogInfo(h);
        if (info == null) {
            resp.sendError(404, "Blog does not exist");
            return;
        }
        resp.setContentType("application/x-syndie-meta");
        OutputStream out = resp.getOutputStream();
        info.write(out);
        out.close();
    }
    
    private void renderBlogIndex(String blog, HttpServletResponse resp) throws ServletException, IOException {
        byte b[] = Base64.decode(blog);
        if ( (b == null) || (b.length != Hash.HASH_LENGTH) ) {
            resp.sendError(404, "Invalid blog requested");
            return;
        }
        Hash h = new Hash(b);
        
        BlogInfo info = BlogManager.instance().getArchive().getBlogInfo(h);
        if (info == null) {
            resp.sendError(404, "Blog does not exist");
            return;
        }
        resp.setContentType("text/html;charset=utf-8");
        //resp.setCharacterEncoding("UTF-8");
        OutputStream out = resp.getOutputStream();
        out.write(DataHelper.getUTF8("<a href=\"..\">..</a><br />\n"));
        out.write(DataHelper.getUTF8("<a href=\"" + Archive.METADATA_FILE + "\">" + Archive.METADATA_FILE + "</a><br />\n"));
        List entries = new ArrayList(64);
        BlogManager.instance().getArchive().getIndex().selectMatchesOrderByEntryId(entries, h, null);
        for (int i = 0; i < entries.size(); i++) {
            BlogURI entry = (BlogURI)entries.get(i);
            out.write(DataHelper.getUTF8("<a href=\"" + entry.getEntryId() + ".snd\">" + entry.getEntryId() + ".snd</a><br />\n"));
        }
        out.close();
    }
        
    private void renderEntry(String blog, long entryId, HttpServletResponse resp) throws ServletException, IOException {
        byte b[] = Base64.decode(blog);
        if ( (b == null) || (b.length != Hash.HASH_LENGTH) ) {
            resp.sendError(404, "Invalid blog requested");
            return;
        }
        Hash h = new Hash(b);
        BlogInfo info = BlogManager.instance().getArchive().getBlogInfo(h);
        if (info == null) {
            resp.sendError(404, "Blog does not exist");
            return;
        }
        File root = BlogManager.instance().getArchive().getArchiveDir();
        File blogDir = new File(root, blog);
        if (!blogDir.exists()) {
            resp.sendError(404, "Blog does not exist");
            return;
        }
        File entry = new File(blogDir, entryId + ".snd");
        if (!entry.exists()) {
            resp.sendError(404, "Entry does not exist");
            return;
        }
        resp.setContentType("application/x-syndie-post");
        dump(entry, resp);
    }
    
    private void dump(File source, HttpServletResponse resp) throws ServletException, IOException {
        FileInputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = resp.getOutputStream();
            byte buf[] = new byte[1024];
            int read = 0;
            while ( (read = in.read(buf)) != -1) 
                out.write(buf, 0, read);
            out.close();
            in.close();
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
    }
}
