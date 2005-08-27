package net.i2p.syndie.web;

import java.io.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;

/**
 * Dump out a whole series of blog metadata and entries as a zip stream.  All metadata
 * is written before any entries, so it can be processed in order safely.
 *
 * HTTP parameters: 
 *  = meta (multiple values): base64 hash of the blog for which metadata is requested
 *  = entry (multiple values): blog URI of an entry being requested
 */
public class ExportServlet extends HttpServlet {
    
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        export(req, resp);
    }
    
    public static void export(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String meta[] = req.getParameterValues("meta");
        String entries[] = req.getParameterValues("entry");
        resp.setContentType("application/x-syndie-zip");
        resp.setStatus(200);
        OutputStream out = resp.getOutputStream();
        ZipOutputStream zo = new ZipOutputStream(out);
        
        List metaFiles = getMetaFiles(meta);
        
        ZipEntry ze = null;
        byte buf[] = new byte[1024];
        int read = -1;
        for (int i = 0; metaFiles != null && i < metaFiles.size(); i++) {
            ze = new ZipEntry("meta" + i);
            ze.setTime(0);
            zo.putNextEntry(ze);
            FileInputStream in = new FileInputStream((File)metaFiles.get(i));
            while ( (read = in.read(buf)) != -1)
                zo.write(buf, 0, read);
            zo.closeEntry();
        }
        
        List entryFiles = getEntryFiles(entries);
        for (int i = 0; entryFiles != null && i < entryFiles.size(); i++) {
            ze = new ZipEntry("entry" + i);
            ze.setTime(0);
            zo.putNextEntry(ze);
            FileInputStream in = new FileInputStream((File)entryFiles.get(i));
            while ( (read = in.read(buf)) != -1) 
                zo.write(buf, 0, read);
            zo.closeEntry();
        }
        
        zo.finish();
        zo.close();
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
