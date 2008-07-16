package net.i2p.syndie;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.syndie.data.ArchiveIndex;
import net.i2p.syndie.data.BlogInfo;
import net.i2p.syndie.data.BlogURI;
import net.i2p.syndie.data.EntryContainer;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;

/**
 * Store blog info in the local filesystem.
 *
 * Entries are stored under:
 *  $rootDir/$h(blogKey)/$entryId.snd (the index lists them as YYYYMMDD_n_jKB)
 * Blog info is stored under:
 *  $rootDir/$h(blogKey)/meta.snm
 * Archive summary is stored under
 *  $rootDir/archive.txt
 * Any key=value pairs in
 *  $rootDir/archiveHeaders.txt
 * are injected into the archive.txt on regeneration.
 *
 * When entries are loaded for extraction/verification/etc, their contents are written to
 *  $cacheDir/$h(blogKey)/$entryId/ (e.g. $cacheDir/$h(blogKey)/$entryId/entry.sml)
 */
public class Archive {
    private I2PAppContext _context;
    private Log _log;
    private BlogManager _mgr;
    private File _rootDir;
    private File _cacheDir;
    private Map _blogInfo;
    private ArchiveIndex _index;
    private EntryExtractor _extractor;
    private String _defaultSelector;
    
    public static final String METADATA_FILE = "meta.snm";
    public static final String INDEX_FILE = "archive.txt";
    public static final String HEADER_FILE = "archiveHeaders.txt";

    private static final FilenameFilter _entryFilenameFilter = new FilenameFilter() {
        public boolean accept(File dir, String name) { return name.endsWith(".snd"); }
    };    
    
    public Archive(I2PAppContext ctx, String rootDir, String cacheDir, BlogManager mgr) {
        _context = ctx;
        _log = ctx.logManager().getLog(Archive.class);
        _mgr = mgr;
        _rootDir = new File(rootDir);
        if (!_rootDir.exists())
            _rootDir.mkdirs();
        _cacheDir = new File(cacheDir);
        if (!_cacheDir.exists())
            _cacheDir.mkdirs();
        _blogInfo = new HashMap();
        _index = null;
        _extractor = new EntryExtractor(ctx);
        _defaultSelector = ctx.getProperty("syndie.defaultSelector");
        if (_defaultSelector == null) _defaultSelector = "";
        reloadInfo();
    }
    
    public void reloadInfo() {
        File f[] = _rootDir.listFiles();
        List info = new ArrayList();
        for (int i = 0; i < f.length; i++) {
            if (f[i].isDirectory()) {
                File meta = new File(f[i], METADATA_FILE);
                if (meta.exists()) {
                    BlogInfo bi = new BlogInfo();
                    FileInputStream fi = null;
                    try {
                        fi = new FileInputStream(meta);
                        bi.load(fi);
                        if (_mgr.isBanned(bi.getKey().calculateHash())) {
                            fi.close();
                            fi = null;
                            _log.error("Deleting banned blog " + bi.getKey().calculateHash().toBase64());
                            delete(bi.getKey().calculateHash());
                            continue;
                        }
                        if (bi.verify(_context)) {
                            info.add(bi);
                        } else {
                            _log.error("BlogInfo is invalid: " + bi);
                            meta.delete();
                        }
                    } catch (IOException ioe) {
                        _log.error("Error loading the blog", ioe);
                    } finally {
                        if (fi != null) try { fi.close(); } catch (IOException ioe) {}
                    }
                }
            }
        }
        
        synchronized (_blogInfo) {
            _blogInfo.clear();
            for (int i = 0; i < info.size(); i++) {
                BlogInfo bi = (BlogInfo)info.get(i);
                _blogInfo.put(bi.getKey().calculateHash(), bi);
            }
        }
    }
    
    public String getDefaultSelector() { return _defaultSelector; }
    public void setDefaultSelector(String sel) {
        if (sel == null)
            _defaultSelector = "";
        else
            _defaultSelector = sel;
    }
        
    public BlogInfo getBlogInfo(BlogURI uri) {
        if (uri == null) return null;
        synchronized (_blogInfo) {
            return (BlogInfo)_blogInfo.get(uri.getKeyHash());
        }
    }
    public BlogInfo getBlogInfo(Hash key) {
        synchronized (_blogInfo) { 
            return (BlogInfo)_blogInfo.get(key); 
        }
    }
    public boolean storeBlogInfo(BlogInfo info) { 
        if (!info.verify(_context)) {
            _log.warn("Not storing invalid blog " + info);
            return false;
        }
        
        if (_mgr.isBanned(info.getKey().calculateHash())) {
            _log.error("Not storing banned blog " + info.getKey().calculateHash().toBase64(), new Exception("Stored by"));
            return false;
        }
        
        boolean isNew = true;
        synchronized (_blogInfo) {
            BlogInfo old = (BlogInfo)_blogInfo.get(info.getKey().calculateHash());
            if ( (old == null) || (old.getEdition() < info.getEdition()) )
                _blogInfo.put(info.getKey().calculateHash(), info); 
            else
                isNew = false;
        }
        if (!isNew) return true; // valid entry, but not stored, since its old
        try {
            File blogDir = new File(_rootDir, info.getKey().calculateHash().toBase64());
            blogDir.mkdirs();
            File blogFile = new File(blogDir, "meta.snm");
            FileOutputStream out = new FileOutputStream(blogFile);
            info.write(out);
            out.close();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Blog info written to " + blogFile.getPath());
            return true;
        } catch (IOException ioe) {
            _log.error("Error writing out info", ioe);
            return false;
        }
    }
    
    public List listBlogs() {
        synchronized (_blogInfo) {
            return new ArrayList(_blogInfo.values());
        }
    }
    
    private File getEntryDir(File entryFile) {
        String name = entryFile.getName();
        if (!name.endsWith(".snd")) throw new RuntimeException("hmm, why are we trying to get an entry dir for " + entryFile.getAbsolutePath());
        String blog = entryFile.getParentFile().getName();
        File blogDir = new File(_cacheDir, blog);
        return new File(blogDir, name.substring(0, name.length()-4));
        //return new File(entryFile.getParentFile(), "." + name.substring(0, name.length()-4));
    }
    
    /**
     * Expensive operation, reading all entries within the blog and parsing out the tags.
     * Whenever possible, query the index instead of the archive
     *
     */
    public List listTags(Hash blogKeyHash) {
        List rv = new ArrayList();
        BlogInfo info = getBlogInfo(blogKeyHash);
        if (info == null)
            return rv;
        
        File blogDir = new File(_rootDir, Base64.encode(blogKeyHash.getData()));
        File entries[] = blogDir.listFiles(_entryFilenameFilter);
        for (int j = 0; j < entries.length; j++) {
            try {
                File entryDir = getEntryDir(entries[j]);
                EntryContainer entry = null;
                if (entryDir.exists())
                    entry = getCachedEntry(entryDir);
                if ( (entry == null) || (!entryDir.exists()) ) {
                    if (!extractEntry(entries[j], entryDir, info)) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Entry " + entries[j].getPath() + " is not valid");
                        entries[j].delete();
                        continue;
                    }
                    entry = getCachedEntry(entryDir);
                }
                String tags[] = entry.getTags();
                for (int t = 0; t < tags.length; t++) {
                    if (!rv.contains(tags[t])) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Found a new tag in cached " + entry.getURI() + ": " + tags[t]);
                        rv.add(tags[t]);
                    }
                }
            } catch (IOException ioe) {
                _log.error("Error listing tags", ioe);
            }
        } // end iterating over the entries 
        
        return rv;
    }

    /**
     * Extract the entry to the given dir, returning true if it was verified properly
     *
     */
    private boolean extractEntry(File entryFile, File entryDir, BlogInfo info) throws IOException {
        if (!entryDir.exists())
            entryDir.mkdirs();
        
        boolean ok = true;
        try {
            ok = _extractor.extract(entryFile, entryDir, null, info);
        } catch (IOException ioe) {
            ok = false;
            _log.error("Error extracting " + entryFile.getPath() + ", deleting it", ioe);
        }
        if (!ok) {
            File files[] = entryDir.listFiles();
            for (int i = 0; i < files.length; i++)
                files[i].delete();
            entryDir.delete();
        }
        return ok;
    }
    
    private EntryContainer getCachedEntry(File entryDir) {
        try {
            CachedEntry ce = new CachedEntry(entryDir);
            if (ce.isValid())
                return ce;
            return null;
        } catch (IOException ioe) {
            _log.warn("Error reading cached entry... deleting cache elements");
        }
        
        File files[] = entryDir.listFiles();
        for (int i = 0; i < files.length; i++)
            files[i].delete();
        entryDir.delete();
        return null;
    }
    
    public EntryContainer getEntry(BlogURI uri) { return getEntry(uri, null); }
    public EntryContainer getEntry(BlogURI uri, SessionKey blogKey) {
        List entries = listEntries(uri, null, blogKey);
        if (entries.size() > 0)
            return (EntryContainer)entries.get(0);
        else
            return null;
    }
    
    public List listEntries(BlogURI uri, String tag, SessionKey blogKey) {
        if (uri == null) return new ArrayList();
        return listEntries(uri.getKeyHash(), uri.getEntryId(), tag, blogKey);
    }
    public List listEntries(Hash blog, long entryId, String tag, SessionKey blogKey) { 
        List rv = new ArrayList();
        BlogInfo info = getBlogInfo(blog);
        if (info == null)
            return rv;
        
        File blogDir = new File(_rootDir, blog.toBase64());
        File entries[] = blogDir.listFiles(_entryFilenameFilter);
        if (entries == null)
            return rv;
        for (int i = 0; i < entries.length; i++) {
            try {
                EntryContainer entry = null;
                if (blogKey == null) {
                    // no key, cache.
                    File entryDir = getEntryDir(entries[i]);
                    if (entryDir.exists()) {
                        entry = getCachedEntry(entryDir);
                    }
                    if ((entry == null) || !entryDir.exists()) {
                        if (!extractEntry(entries[i], entryDir, info)) {
                            _log.error("Entry " + entries[i].getPath() + " is not valid");
                            entries[i].delete();
                            continue;
                        }
                        entry = getCachedEntry(entryDir);
                    }
                } else {
                    // we have an explicit key - no caching
                    entry = new EntryContainer();
                    FileInputStream fi = null;
                    try {
                        fi = new FileInputStream(entries[i]);
                        entry.load(fi);
                    } finally {
                        if (fi != null) try { fi.close(); } catch (IOException ioe) {}
                    }
                    boolean ok = entry.verifySignature(_context, info);
                    if (!ok) {
                        _log.error("Keyed entry " + entries[i].getPath() + " is not valid");
                        entries[i].delete();
                        continue;
                    }

                    entry.parseRawData(_context, blogKey);
                
                    entry.setCompleteSize((int)entries[i].length());
                }
                
                if (entryId >= 0) {
                    if (entry.getURI().getEntryId() == entryId) {
                        rv.add(entry);
                        return rv;
                    }
                } else if (tag != null) {
                    String tags[] = entry.getTags();
                    for (int j = 0; j < tags.length; j++) {
                        if (tags[j].equals(tag)) {
                            rv.add(entry);
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("cached entry matched requested tag [" + tag + "]: " + entry.getURI());
                            break;
                        }
                    }
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("cached entry is ok and no id or tag was requested: " + entry.getURI());
                    rv.add(entry);
                }
            } catch (IOException ioe) {
                _log.error("Error listing entries", ioe);
            }
        }
        return rv;
    }
    
    public synchronized void delete(Hash blog) {
        if (blog == null) return;
        File blogDir = new File(_rootDir, blog.toBase64());
        boolean deleted = FileUtil.rmdir(blogDir, false);
        File cacheDir = new File(_cacheDir, blog.toBase64());
        deleted = FileUtil.rmdir(cacheDir, false) && deleted;
        _log.info("Deleted blog " + blog.toBase64() + " completely? " + deleted);
    }
    
    public boolean storeEntry(EntryContainer container) {
        if (container == null) return false;
        BlogURI uri = container.getURI();
        if (uri == null) return false;

        File blogDir = new File(_rootDir, uri.getKeyHash().toBase64());
        blogDir.mkdirs();
        File entryFile = new File(blogDir, getEntryFilename(uri.getEntryId()));
        if (entryFile.exists()) return true;


        BlogInfo info = getBlogInfo(uri);
        if (info == null) {
            _log.error("no blog metadata for the uri " + uri);
            return false;
        }
        if (!container.verifySignature(_context, info)) {
            _log.error("Not storing the invalid blog entry at " + uri);
            return false;
        } else {
            //System.out.println("Signature is valid: " + container.getSignature() + " for info " + info);
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();   
            container.write(baos, true);
            byte data[] = baos.toByteArray();
            FileOutputStream out = new FileOutputStream(entryFile);
            out.write(data);
            out.close();
            container.setCompleteSize(data.length);
            return true;
        } catch (IOException ioe) {
            _log.error("Error storing", ioe);
            return false;
        }
    }
    
    public static String getEntryFilename(long entryId) { return entryId + ".snd"; }
    
    private static SimpleDateFormat _dateFmt = new SimpleDateFormat("yyyyMMdd", Locale.UK);
    public static String getIndexName(long entryId, int numBytes) {
        try {
            synchronized (_dateFmt) {
                String yy = _dateFmt.format(new Date(entryId));
                long begin = _dateFmt.parse(yy).getTime();
                long n = entryId - begin;
                int kb = numBytes / 1024;
                return yy + '_' + n + '_' + kb + "KB";
            }
        } catch (NumberFormatException nfe) {
            nfe.printStackTrace();
            return "UNKNOWN";
        } catch (ParseException pe) {
            pe.printStackTrace();
            return "UNKNOWN";
        }
    }

    public static long getEntryIdFromIndexName(String entryIndexName) {
        if (entryIndexName == null) return -1;
        if (entryIndexName.endsWith(".snd"))
            entryIndexName = entryIndexName.substring(0, entryIndexName.length() - 4);
        int endYY = entryIndexName.indexOf('_');
        if (endYY <= 0) return -1;
        int endN = entryIndexName.indexOf('_', endYY+1);
        if (endN <= 0) return -1;
        String yy = entryIndexName.substring(0, endYY);
        String n = entryIndexName.substring(endYY+1, endN);
        try {
            synchronized (_dateFmt) {
                long dayBegin = _dateFmt.parse(yy).getTime();
                long dayEntry = Long.parseLong(n);
                return dayBegin + dayEntry;
            }
        } catch (NumberFormatException nfe) {
            nfe.printStackTrace();
        } catch (ParseException pe) {
            pe.printStackTrace();
        }
        return -1;
    }
    public static int getSizeFromIndexName(String entryIndexName) {
        if (entryIndexName == null) return -1;
        if (entryIndexName.endsWith(".snd"))
            entryIndexName = entryIndexName.substring(0, entryIndexName.length() - 4);
        int beginSize = entryIndexName.lastIndexOf('_');
        if ( (beginSize <= 0) || (beginSize >= entryIndexName.length()-3) )
            return -1;
        try {
            String sz = entryIndexName.substring(beginSize+1, entryIndexName.length()-2);
            return Integer.parseInt(sz);
        } catch (NumberFormatException nfe) {
            nfe.printStackTrace();
        } 
        return -1;
    }
    
    public ArchiveIndex getIndex() { 
        if (_index == null)
            regenerateIndex();
        return _index; 
    }

    public File getArchiveDir() { return _rootDir; }
    public File getIndexFile() { return new File(_rootDir, INDEX_FILE); }
    public void regenerateIndex() {
        reloadInfo();
        _index = ArchiveIndexer.index(_context, this);
        try {
            FileOutputStream out = new FileOutputStream(new File(_rootDir, INDEX_FILE));
            out.write(DataHelper.getUTF8(_index.toString()));
            out.flush();
        } catch (IOException ioe) {
            _log.error("Error writing out the index");
        }
    }
}
