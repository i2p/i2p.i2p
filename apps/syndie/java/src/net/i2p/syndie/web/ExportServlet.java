package net.i2p.syndie.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.syndie.Archive;
import net.i2p.syndie.BlogManager;
import net.i2p.syndie.data.BlogURI;

/**
 * Dump out a whole series of blog metadata and entries as a zip stream.  All metadata
 * is written before any entries, so it can be processed in order safely.
 *
 * HTTP parameters: 
 *  = meta (multiple values): base64 hash of the blog for which metadata is requested
 *  = entry (multiple values): blog URI of an entry being requested
 */
public class ExportServlet extends HttpServlet {
    
    public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        export(req, resp);
    }
    
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        export(req, resp);
    }
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        export(req, resp);
    }
    public void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        export(req, resp);
    }
    
    public static void export(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            doExport(req, resp);
        } catch (ServletException se) {
            se.printStackTrace();
            throw se;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            throw ioe;
        }
    }
    private static void doExport(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String meta[] = null;
        String entries[] = null;
        String type = req.getHeader("Content-Type");
        if ( (type == null) || (type.indexOf("boundary") == -1) ) {
            // it has to be POSTed with the request, name=value pairs.  the export servlet doesn't allow any
            // free form fields, so no worry about newlines, so lets parse 'er up
            List metaList = new ArrayList();
            List entryList = new ArrayList();
            StringBuffer key = new StringBuffer();
            StringBuffer val = null;
            String lenStr = req.getHeader("Content-length");
            int len = -1;
            if (lenStr != null)
                try { len = Integer.valueOf(lenStr).intValue(); } catch (NumberFormatException nfe) {}

            int read = 0;
            int c = 0;
            InputStream in = req.getInputStream();
            while ( (len == -1) || (read < len) ){
                c = in.read();
                if ( (c == '=') && (val == null) ) {
                    val = new StringBuffer(128);
                } else if ( (c == -1) || (c == '&') ) {
                    String k = (key == null ? "" : key.toString());
                    String v = (val == null ? "" : val.toString());
                    if ("meta".equals(k))
                        metaList.add(v.trim());
                    else if ("entry".equals(k))
                        entryList.add(v.trim());
                    key.setLength(0);
                    val = null;
                    // no newlines in the export servlet
                    if (c == -1)
                        break;
                } else {
                    if (val == null)
                        key.append((char)c);
                    else
                        val.append((char)c);
                }
                read++;
            }
            if (metaList != null) {
                meta = new String[metaList.size()];
                for (int i = 0; i < metaList.size(); i++)
                    meta[i] = (String)metaList.get(i);
            }
            if (entryList != null) {
                entries = new String[entryList.size()];
                for (int i = 0; i < entryList.size(); i++)
                    entries[i] = (String)entryList.get(i);
            }
        } else {
            meta = req.getParameterValues("meta");
            entries = req.getParameterValues("entry");
        }
        resp.setContentType("application/x-syndie-zip");
        resp.setStatus(200);
        OutputStream out = resp.getOutputStream();
        
        if (false) {
            StringBuffer bbuf = new StringBuffer(1024);
            bbuf.append("meta: ");
            if (meta != null)
                for (int i = 0; i < meta.length; i++)
                    bbuf.append(meta[i]).append(", ");
            bbuf.append("entries: ");
            if (entries != null)
                for (int i = 0; i < entries.length; i++)
                    bbuf.append(entries[i]).append(", ");
            System.out.println(bbuf.toString());
        }
        
        ZipOutputStream zo = null;
        if ( (meta != null) && (entries != null) && (meta.length + entries.length > 0) )
            zo = new ZipOutputStream(out);
        
        List metaFiles = getMetaFiles(meta);
        
        ZipEntry ze = null;
        byte buf[] = new byte[1024];
        int read = -1;
        for (int i = 0; metaFiles != null && i < metaFiles.size(); i++) {
            ze = new ZipEntry("meta" + i);
            ze.setTime(0);
            zo.putNextEntry(ze);
            FileInputStream in = null;
	    try {
                in = new FileInputStream((File)metaFiles.get(i));
                while ( (read = in.read(buf)) != -1)
                    zo.write(buf, 0, read);
                zo.closeEntry();
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
            }
        }
        
        List entryFiles = getEntryFiles(entries);
        for (int i = 0; entryFiles != null && i < entryFiles.size(); i++) {
            ze = new ZipEntry("entry" + i);
            ze.setTime(0);
            zo.putNextEntry(ze);
            FileInputStream in = null;
	    try {
                in = new FileInputStream((File)entryFiles.get(i));
                while ( (read = in.read(buf)) != -1) 
                    zo.write(buf, 0, read);
                zo.closeEntry();
	    } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
            }
        }
        
        if (zo != null) {
            zo.finish();
            zo.close();
        }
    }
    
    private static List getMetaFiles(String blogHashes[]) {
        if ( (blogHashes == null) || (blogHashes.length <= 0) ) return null;
        File dir = BlogManager.instance().getArchive().getArchiveDir();
        List rv = new ArrayList(blogHashes.length);
        for (int i = 0; i < blogHashes.length; i++) {
            byte hv[] = Base64.decode(blogHashes[i]);
            if ( (hv == null) || (hv.length != Hash.HASH_LENGTH) )
                continue;
            File blogDir = new File(dir, blogHashes[i]);
            File metaFile = new File(blogDir, Archive.METADATA_FILE);
            if (metaFile.exists())
                rv.add(metaFile);
        }
        return rv;
    }
    
    private static List getEntryFiles(String blogURIs[]) {
        if ( (blogURIs == null) || (blogURIs.length <= 0) ) return null;
        File dir = BlogManager.instance().getArchive().getArchiveDir();
        List rv = new ArrayList(blogURIs.length);
        for (int i = 0; i < blogURIs.length; i++) {
            BlogURI uri = new BlogURI(blogURIs[i]);
            if (uri.getEntryId() < 0)
                continue;
            File blogDir = new File(dir, uri.getKeyHash().toBase64());
            File entryFile = new File(blogDir, uri.getEntryId() + ".snd");
            if (entryFile.exists())
                rv.add(entryFile);
        }
        return rv;
    }
}
