package net.i2p.syndie.data;

import java.io.*;
import java.text.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.data.*;
import net.i2p.syndie.Archive;
import net.i2p.syndie.BlogManager;
import net.i2p.util.Log;

/**
 * Simple read-only summary of an archive
 */
public class ArchiveIndex {
    private I2PAppContext _context;
    private Log _log;
    protected String _version;
    protected long _generatedOn;
    protected int _allBlogs;
    protected int _newBlogs;
    protected int _allEntries;
    protected int _newEntries;
    protected long _totalSize;
    protected long _newSize;
    /** list of BlogSummary objects */
    protected List _blogs;
    /** list of Hash objects */
    protected List _newestBlogs;
    /** list of BlogURI objects */
    protected List _newestEntries;
    /** parent message to a set of replies, ordered with the oldest first */
    protected Map _replies;
    protected Properties _headers;
    private ThreadIndex _threadedIndex;
    
    public ArchiveIndex() {
        this(I2PAppContext.getGlobalContext(), false);
    }
    public ArchiveIndex(I2PAppContext ctx) {
        this(ctx, false); //true);
    }
    public ArchiveIndex(I2PAppContext ctx, boolean shouldLoad) {
        _context = ctx;
        _log = ctx.logManager().getLog(ArchiveIndex.class);
        _blogs = new ArrayList();
        _newestBlogs = new ArrayList();
        _newestEntries = new ArrayList();
        _headers = new Properties();
        _replies = Collections.synchronizedMap(new HashMap());
        _generatedOn = -1;
        _threadedIndex = null;
        if (shouldLoad)
            setIsLocal("true");
    }
    
    public String getVersion() { return _version; }
    public Properties getHeaders() { return _headers; }
    public int getAllBlogs() { return _allBlogs; }
    public int getNewBlogs() { return _newBlogs; }
    public int getAllEntries() { return _allEntries; }
    public int getNewEntries() { return _newEntries; }
    public long getTotalSize() { return _totalSize; }
    public long getNewSize() { return _newSize; }
    public long getGeneratedOn() { return _generatedOn; }
    public ThreadIndex getThreadedIndex() { return _threadedIndex; }
    public void setThreadedIndex(ThreadIndex index) { _threadedIndex = index; }
    
    public String getNewSizeStr() { 
        if (_newSize < 1024) return _newSize + "";
        if (_newSize < 1024*1024) return _newSize/1024 + "KB";
        else return _newSize/(1024*1024) + "MB";
    }
    public String getTotalSizeStr() { 
        if (_totalSize < 1024) return _totalSize + "";
        if (_totalSize < 1024*1024) return _totalSize/1024 + "KB";
        else return _totalSize/(1024*1024) + "MB";
    }
    
    /** how many blogs/tags are indexed */
    public int getIndexBlogs() { return _blogs.size(); }
    /** get the blog used for the given blog/tag pair */
    public Hash getBlog(int index) { return ((BlogSummary)_blogs.get(index)).blog; }
    /** get the tag used for the given blog/tag pair */
    public String getBlogTag(int index) { return ((BlogSummary)_blogs.get(index)).tag; }
    /** get the highest entry ID for the given blog/tag pair */
    public long getBlogLastUpdated(int index) { return ((BlogSummary)_blogs.get(index)).lastUpdated; }
    /** get the entry count for the given blog/tag pair */
    public int getBlogEntryCount(int index) { return ((BlogSummary)_blogs.get(index)).entries.size(); }
    /** get the entry from the given blog/tag pair */
    public BlogURI getBlogEntry(int index, int entryIndex) { return ((EntrySummary)((BlogSummary)_blogs.get(index)).entries.get(entryIndex)).entry; }
    /** get the raw entry size (including attachments) from the given blog/tag pair */
    public long getBlogEntrySizeKB(int index, int entryIndex) { return ((EntrySummary)((BlogSummary)_blogs.get(index)).entries.get(entryIndex)).size; }
    
    public boolean getEntryIsKnown(BlogURI uri) { return getEntry(uri) != null; }
    public long getBlogEntrySizeKB(BlogURI uri) {
        EntrySummary entry = getEntry(uri);
        if (entry == null) return -1;
        return entry.size;
    }
    private EntrySummary getEntry(BlogURI uri) {
        if ( (uri == null) || (uri.getKeyHash() == null) || (uri.getEntryId() < 0) ) return null;
        for (int i = 0; i < _blogs.size(); i++) {
            BlogSummary summary = (BlogSummary)_blogs.get(i);
            if (summary.blog.equals(uri.getKeyHash())) {
                for (int j = 0; j < summary.entries.size(); j++) {
                    EntrySummary entry = (EntrySummary)summary.entries.get(j);
                    if (entry.entry.equals(uri))
                        return entry;
                }
            }
        }
        return null;
    }
    public Set getBlogEntryTags(BlogURI uri) {
        Set tags = new HashSet();
        if ( (uri == null) || (uri.getKeyHash() == null) || (uri.getEntryId() < 0) ) return tags;
        for (int i = 0; i < _blogs.size(); i++) {
            BlogSummary summary = (BlogSummary)_blogs.get(i);
            if (summary.blog.equals(uri.getKeyHash())) {
                for (int j = 0; j < summary.entries.size(); j++) {
                    EntrySummary entry = (EntrySummary)summary.entries.get(j);
                    if (entry.entry.equals(uri)) {
                        tags.add(summary.tag);
                        break;
                    }
                }
            }
        }
        return tags;
    }
    public int getBlogEntryCount(Hash blog) {
        Set uris = new HashSet(64);
        for (int i = 0; i < _blogs.size(); i++) {
            BlogSummary summary = (BlogSummary)_blogs.get(i);
            if (summary.blog.equals(blog)) {
                uris.addAll(summary.entries);
                //for (int j = 0; j < summary.entries.size(); j++) {
                //    EntrySummary entry = (EntrySummary)summary.entries.get(j);
                //    uris.add(entry.entry);
                //}
            }
        }
        return uris.size();
    }
    
    /** how many 'new' blogs are listed */
    public int getNewestBlogCount() { return _newestBlogs.size(); }
    public Hash getNewestBlog(int index) { return (Hash)_newestBlogs.get(index); }
    /** how many 'new' entries are listed */
    public int getNewestBlogEntryCount() { return _newestEntries.size(); }
    public BlogURI getNewestBlogEntry(int index) { return (BlogURI)_newestEntries.get(index); }
    
    /** list of locally known tags (String) under the given blog */
    public List getBlogTags(Hash blog) {
        List rv = new ArrayList();
        for (int i = 0; i < _blogs.size(); i++) {
            if (getBlog(i).equals(blog))
                rv.add(getBlogTag(i));
        }
        return rv;
    }
    /** list of unique blogs locally known (set of Hash) */
    public Set getUniqueBlogs() {
        Set rv = new HashSet();
        for (int i = 0; i < _blogs.size(); i++) {
            rv.add(getBlog(i));
        }
        return rv;
    }
    public List getReplies(BlogURI uri) {
        Set replies = (Set)_replies.get(uri);
        if (replies == null) return Collections.EMPTY_LIST;
        synchronized (replies) {
            return new ArrayList(replies);
        }
    }
    public void setLocation(String location) {
        try {
            File l = new File(location);
            if (l.exists())
                load(l);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
    public void setIsLocal(String val) {
        if ("true".equals(val)) {
            try {
                File dir = BlogManager.instance().getArchive().getArchiveDir();
                load(new File(dir, Archive.INDEX_FILE));
            } catch (IOException ioe) {}
        }
    }
    
    public void load(File location) throws IOException {
        FileInputStream in = null;
        try {
            in = new FileInputStream(location);
            load(in);
        } finally {
            if (in != null)
                try { in.close(); } catch (IOException ioe) {}
        }
    }
    
    /** load up the index from an archive.txt */
    public void load(InputStream index) throws IOException {
        _allBlogs = 0;
        _allEntries = 0;
        _newBlogs = 0;
        _newEntries = 0;
        _newSize = 0;
        _totalSize = 0;
        _version = null;
        _blogs = new ArrayList();
        _newestBlogs = new ArrayList();
        _newestEntries = new ArrayList();
        _headers = new Properties();
        BufferedReader in = new BufferedReader(new InputStreamReader(index, "UTF-8"));
        String line = null;
        line = in.readLine();
        if (line == null)
            return;
        if (!line.startsWith("SyndieVersion:"))
            throw new IOException("Index is invalid - it starts with " + line);
        _version = line.substring("SyndieVersion:".length()).trim();
        if (!_version.startsWith("1."))
            throw new IOException("Index is not supported, we only handle versions 1.*, but it is " + _version);
        while ( (line = in.readLine()) != null) {
            if (line.length() <= 0)
                break;
            if (line.startsWith("Blog:")) break;
            int split = line.indexOf(':');
            if (split <= 0) continue;
            if (split >= line.length()-1) continue;
            _headers.setProperty(line.substring(0, split), line.substring(split+1));
        }
        if (line != null) {
            do {
                if (!line.startsWith("Blog:"))
                    break;
                loadBlog(line);
            } while ( (line = in.readLine()) != null);
        }
        
        // ignore the first line that doesnt start with blog - its blank
        while ( (line = in.readLine()) != null) {
            int split = line.indexOf(':');
            if (split <= 0) continue;
            if (split >= line.length()-1) continue;
            String key = line.substring(0, split);
            String val = line.substring(split+1);
            if (key.equals("AllBlogs"))
                _allBlogs = getInt(val);
            else if (key.equals("NewBlogs"))
                _newBlogs = getInt(val);
            else if (key.equals("AllEntries"))
                _allEntries = getInt(val);
            else if (key.equals("NewEntries"))
                _newEntries = getInt(val);
            else if (key.equals("TotalSize"))
                _totalSize = getInt(val);
            else if (key.equals("NewSize"))
                _newSize = getInt(val);
            else if (key.equals("NewestBlogs"))
                _newestBlogs = parseNewestBlogs(val);
            else if (key.equals("NewestEntries"))
                _newestEntries = parseNewestEntries(val);
            //else
            //    System.err.println("Key: " + key + " val: " + val);
        }
    }
    
    /**
     * Dig through the index for BlogURIs matching the given criteria, ordering the results by
     * their own entryIds.  
     *
     * @param out where to store the matches
     * @param blog if set, what blog key must the entries be under
     * @param tag if set, what tag must the entry be in
     *
     */
    public void selectMatchesOrderByEntryId(List out, Hash blog, String tag) {
        selectMatchesOrderByEntryId(out, blog, tag, 0);
    }
    public void selectMatchesOrderByEntryId(List out, Hash blog, String tag, long lowestEntryId) {
        TreeMap ordered = new TreeMap();
        for (int i = 0; i < _blogs.size(); i++) {
            BlogSummary summary = (BlogSummary)_blogs.get(i);
            if (blog != null) {
                if (!blog.equals(summary.blog))
                    continue;
            }
            
            if ( (tag != null) && (tag.trim().length() > 0) ) {
                if (!tag.equals(summary.tag)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Tag [" + summary.tag + "] does not match the requested [" + tag + "] in " + summary.blog.toBase64());
                    if (false) {
                        StringBuffer b = new StringBuffer(tag.length()*2);
                        for (int j = 0; j < tag.length(); j++) {
                            b.append((int)tag.charAt(j));
                            b.append('.');
                            if (summary.tag.length() > j+1)
                                b.append((int)summary.tag.charAt(j));
                            else
                                b.append('_');
                            b.append(' ');
                        }
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("tag.summary: " + b.toString());
                    }
                    continue;
                }
            }
            
            for (int j = 0; j < summary.entries.size(); j++) {
                EntrySummary entry = (EntrySummary)summary.entries.get(j);
                if (entry.entry.getEntryId() < lowestEntryId) {
                    long daysAgo1 = entry.entry.getEntryId() / (24*60*60*1000l);
                    long daysAgo2 = lowestEntryId / (24*60*60*1000l);
                    continue;
                } else {                    
                  String k = (Long.MAX_VALUE-entry.entry.getEntryId()) + "-" + entry.entry.getKeyHash().toBase64();
                  ordered.put(k, entry.entry);
                }
            }
        }
        for (Iterator iter = ordered.values().iterator(); iter.hasNext(); ) {
            BlogURI entry = (BlogURI)iter.next();
            if (entry.getEntryId() < lowestEntryId) {
                continue;
            }
            if (!out.contains(entry))
                out.add(entry);
        }
    }
    
    private static final int getInt(String val) {
        try {
            return Integer.parseInt(val.trim()); 
        } catch (NumberFormatException nfe) {
            nfe.printStackTrace();
            return 0;
        }
    }
    
    private List parseNewestBlogs(String vals) {
        List rv = new ArrayList();
        StringTokenizer tok = new StringTokenizer(vals, " \t\n");
        while (tok.hasMoreTokens())
            rv.add(new Hash(Base64.decode(tok.nextToken())));
        return rv;
    }
    private List parseNewestEntries(String vals) {
        List rv = new ArrayList();
        StringTokenizer tok = new StringTokenizer(vals, " \t\n");
        while (tok.hasMoreTokens())
            rv.add(new BlogURI(tok.nextToken()));
        return rv;
    }
    
    private void loadBlog(String line) throws IOException {
        // Blog: hash YYYYMMDD tag\t[ yyyymmdd_n_sizeKB]*
        StringTokenizer tok = new StringTokenizer(line.trim(), " \n\t");
        if (tok.countTokens() < 4)
            return;
        tok.nextToken();
        String keyStr = tok.nextToken();
        byte k[] = Base64.decode(keyStr);
        if ( (k == null) || (k.length != Hash.HASH_LENGTH) )
            return; // ignore bad hashes
        Hash keyHash = new Hash(k);
        String whenStr = tok.nextToken();
        long when = getIndexDate(whenStr);
        String tag = tok.nextToken();
        BlogSummary summary = new BlogSummary();
        summary.blog = keyHash;
        summary.tag = tag.trim();
        summary.lastUpdated = when;
        summary.entries = new ArrayList();
        while (tok.hasMoreTokens()) {
            String entry = tok.nextToken();
            long id = Archive.getEntryIdFromIndexName(entry);
            int kb = Archive.getSizeFromIndexName(entry);
            summary.entries.add(new EntrySummary(new BlogURI(keyHash, id), kb));
        }
        _blogs.add(summary);
    }
    
    private SimpleDateFormat _dateFmt = new SimpleDateFormat("yyyyMMdd", Locale.UK);
    private long getIndexDate(String yyyymmdd) {
        synchronized (_dateFmt) {
            try {
                return _dateFmt.parse(yyyymmdd).getTime();
            } catch (ParseException pe) {
                return -1;
            }
        } 
    }
    private String getIndexDate(long when) {
        synchronized (_dateFmt) {
            return _dateFmt.format(new Date(when));
        }
    }
    
    protected class BlogSummary {
        Hash blog;
        String tag;
        long lastUpdated;
        /** list of EntrySummary objects */
        List entries;
        
        public BlogSummary() {
            entries = new ArrayList();
        }
    }
    protected class EntrySummary {
        BlogURI entry;
        long size;
        public EntrySummary(BlogURI uri, long kb) {
            size = kb;
            entry = uri;
        }
        public int hashCode() {
            return entry.hashCode();
        }
        public boolean equals(Object obj) {
            if ( (obj instanceof EntrySummary) && (((EntrySummary)obj).entry.equals(entry)) )
                return true;
            return false;
        }
    }
    
    /** export the index into an archive.txt */
    public String toString() {
        StringBuffer rv = new StringBuffer(1024);
        rv.append("SyndieVersion: ").append(_version).append('\n');
        for (Iterator iter = _headers.keySet().iterator(); iter.hasNext(); ) {
            String key = (String)iter.next();
            String val = _headers.getProperty(key);
            rv.append(key).append(": ").append(val).append('\n');
        }
        for (int i = 0; i < _blogs.size(); i++) {
            rv.append("Blog: ");
            Hash blog = getBlog(i);
            String tag = getBlogTag(i);
            rv.append(Base64.encode(blog.getData())).append(' ');
            rv.append(getIndexDate(getBlogLastUpdated(i))).append(' ');
            rv.append(tag).append('\t');
            int entries = getBlogEntryCount(i);
            for (int j = 0; j < entries; j++) {
                BlogURI entry = getBlogEntry(i, j);
                long kb = getBlogEntrySizeKB(i, j);
                rv.append(Archive.getIndexName(entry.getEntryId(), (int)kb*1024)).append(' ');
            }
            rv.append('\n');
        }
        
        rv.append('\n');
        rv.append("AllBlogs: ").append(_allBlogs).append('\n');
        rv.append("NewBlogs: ").append(_newBlogs).append('\n');
        rv.append("AllEntries: ").append(_allEntries).append('\n');
        rv.append("NewEntries: ").append(_newEntries).append('\n');
        rv.append("TotalSize: ").append(_totalSize).append('\n');
        rv.append("NewSize: ").append(_newSize).append('\n');
        
        rv.append("NewestBlogs: ");
        for (int i = 0; i < _newestBlogs.size(); i++)
            rv.append(((Hash)(_newestBlogs.get(i))).toBase64()).append(' ');
        rv.append('\n');
            
        rv.append("NewestEntries: ");
        for (int i = 0; i < _newestEntries.size(); i++)
            rv.append(((BlogURI)_newestEntries.get(i)).toString()).append(' ');
        rv.append('\n');
        return rv.toString();
    }
    
    
    /** Usage: ArchiveIndex archive.txt */
    public static void main(String args[]) {
        try {
            ArchiveIndex i = new ArchiveIndex();
            i.load(new File(args[0]));
            System.out.println(i.toString());
        } catch (IOException ioe) { ioe.printStackTrace(); }
    }
}
