package net.i2p.router.news;

import java.io.BufferedInputStream;
import java.util.Collections;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;

import org.cybergarage.util.Debug;
import org.cybergarage.xml.Node;
import org.cybergarage.xml.ParserException;

/**
 *  Store and retrieve news entries from disk.
 *  Each entry is stored in a separate file, with the name
 *  derived from the UUID.
 *
 *  @since 0.9.23
 */
class PersistNews {

    private static final String DIR = "docs/feed/news";
    private static final String PFX = "news-";
    private static final String SFX = ".xml.gz";
    private static final String XML_START = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

    /**
     *  Store each entry.
     *  Old entries are always overwritten, as they may change even without the updated date changing.
     *
     *  @param entries each one should be "entry" at the root
     *  @return success
     */
    public static boolean store(I2PAppContext ctx, List<Node> entries) {
        Log log = ctx.logManager().getLog(PersistNews.class);
        File dir = new SecureDirectory(ctx.getConfigDir(), DIR);
        if (!dir.exists())
            dir.mkdirs();
        StringBuilder buf = new StringBuilder();
        boolean rv = true;
        for (Node entry : entries) {
            Node nid = entry.getNode("id");
            if (nid == null) {
                if (log.shouldWarn())
                    log.warn("entry without UUID");
                continue;
            }
            String id = nid.getValue();
            if (id == null) {
                if (log.shouldWarn())
                    log.warn("entry without UUID");
                continue;
            }
            String name = idToName(ctx, id);
            File file = new File(dir, name);
            Writer out = null;
            try {
                out = new OutputStreamWriter(new GZIPOutputStream(new SecureFileOutputStream(file)));
                out.write(XML_START);
                XMLParser.toString(buf, entry);
                out.write(buf.toString());
                buf.setLength(0);
            } catch (IOException ioe) {
                if (log.shouldWarn())
                    log.warn("failed store to " + file, ioe);
                rv = false;
            } finally {
                if (out != null) try { out.close(); } catch (IOException ioe) {}
            }
        }
        return rv;
    }

    /**
     *  This does not check for any missing values.
     *  Any fields in any NewsEntry may be null.
     *  Content is not sanitized by NewsXMLParser here, do that before storing.
     *
     *  @return non-null, sorted by updated date, newest first
     */
    public static List<NewsEntry> load(I2PAppContext ctx) {
        Log log = ctx.logManager().getLog(PersistNews.class);
        File dir = new File(ctx.getConfigDir(), DIR);
        List<NewsEntry> rv = new ArrayList<NewsEntry>();
        File[] files = dir.listFiles(new FileSuffixFilter(PFX, SFX));
        if (files == null)
            return rv;
        for (File file : files) {
            String name = file.getName();
            XMLParser parser = new XMLParser(ctx);
            InputStream in = null;
            Node node;
            boolean error = false;
            try {
                in = new GZIPInputStream(new FileInputStream(file));
                node = parser.parse(in);
                NewsEntry entry = extract(node);
                if (entry != null) {
                    rv.add(entry);
                } else {
                    if (log.shouldWarn())
                        log.warn("load error from " + file);
                    error = true;
                }
            } catch (ParserException pe) {
                if (log.shouldWarn())
                    log.warn("load error from " + file, pe);
                error = true;
            } catch (IOException ioe) {
                if (log.shouldWarn())
                    log.warn("load error from " + file, ioe);
                error = true;
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
            }
            if (error)
                file.delete();
        }
        Collections.sort(rv);
        return rv;
    }

    /**
     *  This does not check for any missing values.
     *  Any fields in any NewsEntry may be null.
     *  Content is not sanitized by NewsXMLParser here, do that before storing.
     *
     *  @return non-null, throws on errors
     */
    private static NewsEntry extract(Node entry) {
        NewsEntry e = new NewsEntry();
        Node n = entry.getNode("title");
        if (n != null) {
            e.title = n.getValue();
            if (e.title != null)
                e.title = e.title.trim();
        }
        n = entry.getNode("link");
        if (n != null) {
            String a = n.getAttributeValue("href");
            if (a.length() > 0)
                e.link = a.trim();
        }
        n = entry.getNode("id");
        if (n != null) {
            e.id = n.getValue();
            if (e.id != null)
                e.id = e.id.trim();
        }
        n = entry.getNode("updated");
        if (n != null) {
            String v = n.getValue();
            if (v != null) {
                long time = RFC3339Date.parse3339Date(v.trim());
                if (time > 0)
                    e.updated = time;
            }
        }
        n = entry.getNode("summary");
        if (n != null) {
            e.summary = n.getValue();
            if (e.summary != null)
                e.summary = e.summary.trim();
        }
        n = entry.getNode("author");
        if (n != null) {
            n = n.getNode("name");
            if (n != null) {
                e.authorName = n.getValue();
                if (e.authorName != null)
                    e.authorName = e.authorName.trim();
            }
        }
        n = entry.getNode("content");
        if (n != null) {
            String a = n.getAttributeValue("type");
            if (a.length() > 0)
                e.contentType = a;
            // now recursively sanitize
            // and convert everything in the content to string
            StringBuilder buf = new StringBuilder(256);
            for (int i = 0; i < n.getNNodes(); i++) {
                Node sn = n.getNode(i);
                XMLParser.toString(buf, sn);
            }
            e.content = buf.toString();
        }
        return e;
    }

    /**
     *  Unused for now, as we don't have any way to remember it's deleted.
     *
     *  @return success
     */
    public static boolean delete(I2PAppContext ctx, NewsEntry entry) {
        String id = entry.id;
        if (id == null)
            return false;
        String name = idToName(ctx, id);
        File dir = new File(ctx.getConfigDir(), DIR);
        File file = new File(dir, name);
        return file.delete();
    }

    /**
     *  @param id non-null
     */
    private static String idToName(I2PAppContext ctx, String id) {
        byte[] bid = DataHelper.getUTF8(id);
        byte[] hash = new byte[Hash.HASH_LENGTH];
        ctx.sha().calculateHash(bid, 0, bid.length, hash, 0);
        return PFX + Base64.encode(hash) + SFX;
    }

/****
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: PersistNews file.xml");
            System.exit(1);
        }
        I2PAppContext ctx = new I2PAppContext();
        Debug.initialize(ctx);
        XMLParser parser = new XMLParser(ctx);
        InputStream in = null;
        try {
            in = new FileInputStream(args[0]);
            Node root = parser.parse(in);
            List<Node> entries = NewsXMLParser.getNodes(root, "entry");
            store(ctx, entries);
            System.out.println("Stored " + entries.size() + " entries");
        } catch (ParserException pe) {
            System.out.println("load error from " + args[0]);
            pe.printStackTrace();
        } catch (IOException ioe) {
            System.out.println("load error from " + args[0]);
            ioe.printStackTrace();
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
        List<NewsEntry> entries = load(ctx);
        System.out.println("Loaded " + entries.size() + " news entries");
        for (int i = 0; i < entries.size(); i++) {
            NewsEntry e = entries.get(i);
            System.out.println("\n****** News #" + (i+1) + ": " + e.title + '\n' + e.content);
        }
    }
****/
}
