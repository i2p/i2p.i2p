package net.i2p.syndie;

import java.io.*;
import java.util.*;
import net.i2p.*;
import net.i2p.data.*;
import net.i2p.syndie.data.*;
import net.i2p.util.EepPost;

/**
 * Simple CLI to post an entry.
 * 
 */
public class CLIPost {
    public static final String USAGE = "Usage: \"" + CLIPost.class.getName() + " [args]\", where args are:"
        + "\n  --syndieDir $syndieRootDir  // syndie root dir, under which syndie.config exists"
        + "\n  --blog $blogHash            // base64 of the blog's key"
        + "\n  --sml $smlFile              // file with the SML entry"
        + "\n  [--importurl ($url|none)]   // defaults to http://localhost:7657/syndie/import.jsp"
        + "\n  [--proxyhost $hostname]     // HTTP proxy host for sending the data to the import URL"
        + "\n  [--proxyport $portnum]      // HTTP proxy port for sending the data to the import URL"
        + "\n  [--storelocal (true|false)] // should it be stored directly with the file system"
        + "\n                              // (false by default, since its stored locally via importurl)"
        + "\n  [--entryId ($num|next|now)] // entryId to use: explicit, the blog's next (default), or timestamp"
        + "\n  [--attachment$N $file $name $desc $type]"
        + "\n                              // Nth file / suggested name / description / mime type";
    
    public static void main(String args[]) {
        String rootDir = getArg(args, "syndieDir");
        String hashStr = getArg(args, "blog");
        String smlFile = getArg(args, "sml");
        if ( (rootDir == null) || (hashStr == null) || (smlFile == null) ) {
            System.err.println(USAGE);
            return;
        }
        
        String url = getArg(args, "importurl");
        String entryIdDef = getArg(args, "entryId");
        
        List attachmentFilenames = new ArrayList();
        List attachmentNames = new ArrayList();
        List attachmentDescriptions = new ArrayList();
        List attachmentMimeTypes = new ArrayList();
        while (true) {
            // --attachment$N $file $name $desc $type]
            String file = getAttachmentParam(args, attachmentFilenames.size(), 0);
            String name = getAttachmentParam(args, attachmentFilenames.size(), 1);
            String desc = getAttachmentParam(args, attachmentFilenames.size(), 2);
            String type = getAttachmentParam(args, attachmentFilenames.size(), 3);
            if ( (file != null) && (name != null) && (desc != null) && (type != null) ) {
                attachmentFilenames.add(file);
                attachmentNames.add(name);
                attachmentDescriptions.add(desc);
                attachmentMimeTypes.add(type);
            } else {
                break;
            }
        }
        
        List tags = readTags(smlFile);
        
        // don't support the entry key stuff yet...
        String entryKeyDef = null; //args[5];
        
        String loc = getArg(args, "storelocal");
        boolean storeLocal = false;
        if (loc != null)
            storeLocal = Boolean.valueOf(loc).booleanValue();
        
        if (!storeLocal && "none".equalsIgnoreCase(url)) {
            System.err.println("You need to post it somewhere, so either specify \"--storelocal true\"");
            System.err.println("or don't specify \"--importurl none\"");
            return;
        }
        
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        BlogManager mgr = new BlogManager(ctx, rootDir, false);
        EntryContainer entry = CLI.createEntry(ctx, mgr, hashStr, tags, entryIdDef, entryKeyDef, smlFile, storeLocal, 
                                               attachmentFilenames, attachmentNames, attachmentDescriptions, 
                                               attachmentMimeTypes);
        if (entry != null) {
            if (storeLocal)
                mgr.getArchive().regenerateIndex();
            if (!("none".equalsIgnoreCase(url))) {
                if ( (url == null) || (url.trim().length() <= 0) )
                    url = "http://localhost:7657/syndie/import.jsp";

                // now send it to the import URL
                BlogInfo info = mgr.getArchive().getBlogInfo(entry.getURI().getKeyHash());
                File fMeta = null;
                File fData = null;

                try {
                    fMeta = File.createTempFile("cli", ".snm", mgr.getTempDir());
                    fData = File.createTempFile("cli", ".snd", mgr.getTempDir());
                    FileOutputStream out = new FileOutputStream(fMeta);
                    info.write(out);
                    out.close();
                    out = new FileOutputStream(fData);
                    entry.write(out, true);
                    out.close();
                    fMeta.deleteOnExit();
                    fData.deleteOnExit();
                } catch (IOException ioe) {
                    System.err.println("Error writing temp files: " + ioe.getMessage());
                    return;
                }

                Map uploads = new HashMap(2);
                uploads.put("blogmeta0", fMeta);
                uploads.put("blogpost0", fData);

                String proxyHost = getArg(args, "proxyhost");
                String proxyPortStr = getArg(args, "proxyport");
                int proxyPort = -1;
                if (proxyPortStr != null) 
                    try { proxyPort = Integer.parseInt(proxyPortStr); } catch (NumberFormatException nfe) { }

                OnCompletion job = new OnCompletion();
                EepPost post = new EepPost();
                post.postFiles(url, (proxyPort > 0 ? proxyHost : null), proxyPort, uploads, job);
                boolean posted = job.waitForCompletion(30*1000);
                if (posted)
                    System.out.println("Posted successfully: " + entry.getURI().toString());
                else
                    System.out.println("Posting failed");
            } else if (storeLocal) {
                System.out.println("Store local successfully: " + entry.getURI().toString());
            } else {
                // foo
            }
        } else {
            System.err.println("Error creating the blog entry");
        }
    }
    
    private static class OnCompletion implements Runnable {
        private boolean _complete;
        public OnCompletion() { _complete = false; }
        public void run() { 
            _complete = true; 
            synchronized (OnCompletion.this) { 
                OnCompletion.this.notifyAll();
            }
        }
        public boolean waitForCompletion(long max) {
            long end = max + System.currentTimeMillis();
            while (!_complete) {
                long now = System.currentTimeMillis();
                if (now >= end)
                    return false;
                try {
                    synchronized (OnCompletion.this) {
                        OnCompletion.this.wait(end-now);
                    }
                } catch (InterruptedException ie) {}
            }
            return true;
        }
    }

    private static String getArg(String args[], String param) {
        if (args != null) 
            for (int i = 0; i + 1< args.length; i++)
                if (args[i].equalsIgnoreCase("--"+param))
                    return args[i+1];
        return null;
    }
    private static String getAttachmentParam(String args[], int attachmentNum, int paramNum) {
        if (args != null) 
            for (int i = 0; i + 4 < args.length; i++)
                if (args[i].equalsIgnoreCase("--attachment"+attachmentNum))
                    return args[i+1+paramNum];
        return null;
    }
    
    private static List readTags(String smlFile) {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(smlFile), "UTF-8"));
            String line = null;
            while ( (line = in.readLine()) != null) {
                if (line.length() <= 0)
                    return new ArrayList();
                else if (line.startsWith("Tags:"))
                    return parseTags(line.substring("Tags:".length()));
            }
            return null;
        } catch (IOException ioe) {
            return new ArrayList();
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }
    
    private static List parseTags(String tags) {
        if (tags == null) 
            return new ArrayList();
        StringTokenizer tok = new StringTokenizer(tags, " ,\t\n");
        List rv = new ArrayList();
        while (tok.hasMoreTokens()) {
            String cur = tok.nextToken().trim();
            if (cur.length() > 0)
                rv.add(cur);
        }
        return rv;
    }
}
