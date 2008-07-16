package net.i2p.syndie;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.syndie.data.Attachment;
import net.i2p.syndie.data.BlogInfo;
import net.i2p.syndie.data.BlogURI;
import net.i2p.syndie.data.EntryContainer;
import net.i2p.syndie.sml.HTMLRenderer;

/**
 */
public class CLI {
    public static final String USAGE = "Usage: \n" +
        "rootDir regenerateIndex\n" +
        "rootDir createBlog name description contactURL[ archiveURL]*\n" +
        "rootDir createEntry blogPublicKeyHash tag[,tag]* (NEXT|NOW|entryId) (NONE|entryKeyBase64) smlFile[ attachmentFile attachmentName attachmentDescription mimeType]*\n" +
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
            HTMLRenderer renderer = new HTMLRenderer(I2PAppContext.getGlobalContext());
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
        // "rootDir createEntry blogPublicKeyHash tag[,tag]* (NEXT|NOW|entryId) (NONE|entryKeyBase64) "
        //  smlFile[ attachmentFile attachmentName attachmentDescription mimeType]*\n"
        String rootDir = args[0];
        String hashStr = args[2];
        List tags = new ArrayList();
        StringTokenizer tok = new StringTokenizer(args[3], ",");
        while (tok.hasMoreTokens())
            tags.add(tok.nextToken().trim());
        String entryIdDef = args[4];
        String entryKeyDef = args[5];
        String smlFile = args[6];
        List attachmentFilenames = new ArrayList();
        List attachmentNames = new ArrayList();
        List attachmentDescriptions = new ArrayList();
        List attachmentMimeTypes = new ArrayList();
        for (int i = 7; i + 3 < args.length; i += 4) {
            attachmentFilenames.add(args[i].trim());
            attachmentNames.add(args[i+1].trim());
            attachmentDescriptions.add(args[i+2].trim());
            attachmentMimeTypes.add(args[i+3].trim());
        }
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        BlogManager mgr = new BlogManager(ctx, rootDir);
        EntryContainer entry = createEntry(ctx, mgr, hashStr, tags, entryIdDef, entryKeyDef, smlFile, true,
                                           attachmentFilenames, attachmentNames, attachmentDescriptions, 
                                           attachmentMimeTypes);
        if (entry != null)
            mgr.getArchive().regenerateIndex();
    }
    
    /**
     * Create a new entry, storing it into the blogManager's archive and incrementing the 
     * blog's "most recent id" setting.  This does not however regenerate the manager's index.
     *
     * @param blogHashStr base64(SHA256(blog public key))
     * @param tags list of tags/categories to post under (String elements
     * @param entryIdDef NEXT (for next entry id for the blog, or midnight of the current day),
     *                   NOW (current time), or an explicit entry id
     * @param entryKeyDef session key under which the entry should be encrypted
     * @param smlFilename file in which the sml entry is to be found
     * @param storeLocal if true, should this entry be stored in the mgr.getArchive()
     * @param attachmentFilenames list of filenames for attachments to load
     * @param attachmentNames list of names to use for the given attachments
     * @param attachmentDescriptions list of descriptions for the given attachments
     * @param attachmentMimeTypes list of mime types to use for the given attachments
     * @return blog URI posted, or null
     */
    public static EntryContainer createEntry(I2PAppContext ctx, BlogManager mgr, String blogHashStr, List tags, 
                                             String entryIdDef, String entryKeyDef, String smlFilename, boolean storeLocal,
                                             List attachmentFilenames, List attachmentNames, 
                                             List attachmentDescriptions, List attachmentMimeTypes) {
        Hash blogHash = new Hash(Base64.decode(blogHashStr));
        User user = mgr.getUser(blogHash);
        long entryId = -1;
        if ("NOW".equalsIgnoreCase(entryIdDef)) {
            entryId = ctx.clock().now();
        } else if ("NEXT".equalsIgnoreCase(entryIdDef) || (entryIdDef == null)) {
            entryId = mgr.getNextBlogEntry(user);
        } else {
            try {
                entryId = Long.parseLong(entryIdDef);
            } catch (NumberFormatException nfe) {
                nfe.printStackTrace();
                return null;
            }
        }
        String tagVals[] = new String[(tags != null ? tags.size() : 0)];
        if (tags != null)
            for (int i = 0; i < tags.size(); i++)
                tagVals[i] = ((String)tags.get(i)).trim();
        BlogURI uri = new BlogURI(blogHash, entryId);
        BlogInfo blog = mgr.getArchive().getBlogInfo(uri);
        if (blog == null) {
            System.err.println("Blog does not exist: " + uri);
            return null;
        }
        SigningPrivateKey key = mgr.getMyPrivateKey(blog);
        
        try {
            byte smlData[] = read(smlFilename);
            EntryContainer c = new EntryContainer(uri, tagVals, smlData);
            if ( (attachmentFilenames != null) && 
                 (attachmentFilenames.size() == attachmentNames.size()) && 
                 (attachmentFilenames.size() == attachmentDescriptions.size()) && 
                 (attachmentFilenames.size() == attachmentMimeTypes.size()) ) {
                for (int i = 0; i < attachmentFilenames.size(); i++) {
                    File attachmentFile = new File((String)attachmentFilenames.get(i));
                    String name = (String)attachmentNames.get(i);
                    String descr = (String)attachmentDescriptions.get(i);
                    String mimetype = (String)attachmentMimeTypes.get(i);
                    c.addAttachment(read(attachmentFile.getAbsolutePath()), name, descr, mimetype);
                }
            }
            SessionKey entryKey = null;
            if ( (entryKeyDef != null) && (entryKeyDef.trim().length() > 0) && (!"NONE".equalsIgnoreCase(entryKeyDef)) )
                entryKey = new SessionKey(Base64.decode(entryKeyDef));
            c.seal(ctx, key, entryKey);
            if (storeLocal) {
                boolean ok = mgr.getArchive().storeEntry(c);
                //System.out.println("Blog entry created: " + c+ "? " + ok);
                if (!ok) {
                    System.err.println("Error: store failed");
                    return null;
                }
            }
            user.setMostRecentEntry(uri.getEntryId());
            mgr.storeUser(user); // saves even if !user.getAuthenticated()
            return c;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
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
