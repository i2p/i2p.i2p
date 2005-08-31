package net.i2p.syndie;

import java.io.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.data.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;

/**
 */
public class CLI {
    public static final String USAGE = "Usage: \n" +
        "rootDir regenerateIndex\n" +
        "rootDir createBlog name description contactURL[ archiveURL]*\n" +
        "rootDir createEntry blogPublicKeyHash tag[,tag]* (NOW|entryId) (NONE|entryKeyBase64) smlFile[ attachmentFile]*\n" +
        "rootDir listMyBlogs\n" +
        "rootDir listTags blogPublicKeyHash\n" +
        "rootDir listEntries blogPublicKeyHash blogTag\n" +
        "rootDir renderEntry blogPublicKeyHash entryId (NONE|entryKeyBase64) summaryOnly includeImages\n";
    
    public static void main(String args[]) {
        //args = new String[] { "~/.syndie/", "listEntries", "9qXCJUyUBCCaiIShURo02ckxjrMvrtiDYENv2ATL3-Y=", "/" };
        //args = new String[] { "~/.syndie/", "renderEntry", "Vq~AlW-r7OM763okVUFIDvVFzxOjpNNsAx0rFb2yaE8=", "/", "20050811001", "NONE", "true", "false" };
        if (args.length < 2) {
            System.err.print(USAGE);
            return;
        }
        String command = args[1];
        if ("createBlog".equals(command))
            createBlog(args);
        else if ("listMyBlogs".equals(command))
            listMyBlogs(args);
        else if ("createEntry".equals(command))
            createEntry(args);
        else if ("listTags".equals(command))
            listPaths(args);
        else if ("listEntries".equals(command))
            listEntries(args);
        else if ("regenerateIndex".equals(command))
            regenerateIndex(args);
        else if ("renderEntry".equals(command))
            renderEntry(args);
        else
            System.out.print(USAGE);
    }
    
    private static void createBlog(String args[]) {
        BlogManager mgr = new BlogManager(I2PAppContext.getGlobalContext(), args[0]);
        String archives[] = new String[args.length - 5];
        System.arraycopy(args, 5, archives, 0, archives.length);
        BlogInfo info = mgr.createBlog(args[2], args[3], args[4], archives);
        System.out.println("Blog created: " + info);
        mgr.getArchive().regenerateIndex();
    }
    private static void listMyBlogs(String args[]) {
        BlogManager mgr = new BlogManager(I2PAppContext.getGlobalContext(), args[0]);
        List info = mgr.listMyBlogs();
        for (int i = 0; i < info.size(); i++) 
            System.out.println(info.get(i).toString());
    }
    
    private static void listPaths(String args[]) {
        // "rootDir listTags blogPublicKeyHash\n";
        BlogManager mgr = new BlogManager(I2PAppContext.getGlobalContext(), args[0]);
        List tags = mgr.getArchive().listTags(new Hash(Base64.decode(args[2])));
        System.out.println("tag count: " + tags.size());
        for (int i = 0; i < tags.size(); i++)
            System.out.println("Tag " + i + ": " + tags.get(i).toString());
    }

    private static void regenerateIndex(String args[]) {
        // "rootDir regenerateIndex\n";
        BlogManager mgr = new BlogManager(I2PAppContext.getGlobalContext(), args[0]);
        mgr.getArchive().regenerateIndex();
        System.out.println("Index regenerated");
    }

    private static void listEntries(String args[]) {
        // "rootDir listEntries blogPublicKeyHash tag\n";
        BlogManager mgr = new BlogManager(I2PAppContext.getGlobalContext(), args[0]);
        List entries = mgr.getArchive().listEntries(new Hash(Base64.decode(args[2])), -1, args[3], null);
        System.out.println("Entry count: " + entries.size());
        for (int i = 0; i < entries.size(); i++) {
            EntryContainer entry = (EntryContainer)entries.get(i);
            System.out.println("***************************************************");
            System.out.println("Entry " + i + ": " + entry.getURI().toString());
            System.out.println("===================================================");
            System.out.println(entry.getEntry().getText());
            System.out.println("===================================================");
            Attachment attachments[] = entry.getAttachments();
            for (int j = 0; j < attachments.length; j++) {
                System.out.println("Attachment " + j + ": " + attachments[j]);
            }
            System.out.println("===================================================");
        }
    }
    
    private static void renderEntry(String args[]) {
        //"rootDir renderEntry blogPublicKeyHash entryId (NONE|entryKeyBase64) summaryOnly includeImages\n";
        BlogManager mgr = new BlogManager(I2PAppContext.getGlobalContext(), args[0]);
        long id = -1;
        try {
            id = Long.parseLong(args[3]);
        } catch (NumberFormatException nfe) {
            nfe.printStackTrace();
            return;
        }
        SessionKey entryKey = null;
        if (!("NONE".equals(args[4]))) 
            entryKey = new SessionKey(Base64.decode(args[5]));
        EntryContainer entry = mgr.getArchive().getEntry(new BlogURI(new Hash(Base64.decode(args[2])), id), entryKey);
        if (entry != null) {
            HTMLRenderer renderer = new HTMLRenderer();
            boolean summaryOnly = "true".equalsIgnoreCase(args[5]);
            boolean showImages = "true".equalsIgnoreCase(args[6]);
            try {
                File f = File.createTempFile("syndie", ".html");
                Writer out = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
                renderer.render(null, mgr.getArchive(), entry, out, summaryOnly, showImages);
                out.flush();
                out.close();
                System.out.println("Rendered to " + f.getAbsolutePath() + ": " + f.length());
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        } else {
            System.err.println("Entry does not exist");
        }
    }

    private static void createEntry(String args[]) {
        // "rootDir createEntry blogPublicKey tag[,tag]* (NOW|entryId) (NONE|entryKeyBase64) smlFile[ attachmentFile]*\n" +

        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        BlogManager mgr = new BlogManager(ctx, args[0]);
        long entryId = -1;
        if ("NOW".equals(args[4])) {
            entryId = ctx.clock().now();
        } else {
            try {
                entryId = Long.parseLong(args[4]);
            } catch (NumberFormatException nfe) {
                nfe.printStackTrace();
                return;
            }
        }
        StringTokenizer tok = new StringTokenizer(args[3], ",");
        String tags[] = new String[tok.countTokens()];
        for (int i = 0; i < tags.length; i++)
            tags[i] = tok.nextToken();
        BlogURI uri = new BlogURI(new Hash(Base64.decode(args[2])), entryId);
        BlogInfo blog = mgr.getArchive().getBlogInfo(uri);
        if (blog == null) {
            System.err.println("Blog does not exist: " + uri);
            return;
        }
        SigningPrivateKey key = mgr.getMyPrivateKey(blog);
        
        try {
            byte smlData[] = read(args[6]);
            EntryContainer c = new EntryContainer(uri, tags, smlData);
            for (int i = 7; i < args.length; i++) {
                c.addAttachment(read(args[i]), new File(args[i]).getName(), 
                                "Attached file", "application/octet-stream");
            }
            SessionKey entryKey = null;
            if (!"NONE".equals(args[5]))
                entryKey = new SessionKey(Base64.decode(args[5]));
            c.seal(ctx, key, entryKey);
            boolean ok = mgr.getArchive().storeEntry(c);
            System.out.println("Blog entry created: " + c+ "? " + ok);
            if (ok)
                mgr.getArchive().regenerateIndex();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
    
    private static final byte[] read(String file) throws IOException {
        File f = new File(file);
        FileInputStream in = new FileInputStream(f);
        byte rv[] = new byte[(int)f.length()];
        if (rv.length != DataHelper.read(in, rv))
            throw new IOException("File not read completely");
        return rv;
    }
}
