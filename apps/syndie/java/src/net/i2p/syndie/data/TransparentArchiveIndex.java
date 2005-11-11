package net.i2p.syndie.data;

import java.io.*;
import java.text.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.data.*;
import net.i2p.syndie.Archive;
import net.i2p.syndie.BlogManager;

/**
 * Simple read-only summary of an archive, proxied to the BlogManager's instance
 */
public class TransparentArchiveIndex extends ArchiveIndex {
    public TransparentArchiveIndex() { super(I2PAppContext.getGlobalContext(), false); }
    
    private static ArchiveIndex index() { return BlogManager.instance().getArchive().getIndex(); }
    
    public String getVersion() { return index().getVersion(); }
    public Properties getHeaders() { return index().getHeaders(); }
    public int getAllBlogs() { return index().getAllBlogs(); }
    public int getNewBlogs() { return index().getNewBlogs(); }
    public int getAllEntries() { return index().getAllEntries(); }
    public int getNewEntries() { return index().getNewEntries(); }
    public long getTotalSize() { return index().getTotalSize(); }
    public long getNewSize() { return index().getNewSize(); }
    public long getGeneratedOn() { return index().getGeneratedOn(); }
    public ThreadIndex getThreadedIndex() { return index().getThreadedIndex(); }
    
    public String getNewSizeStr() { return index().getNewSizeStr(); }
    public String getTotalSizeStr() { return index().getTotalSizeStr(); }
    
    /** how many blogs/tags are indexed */
    public int getIndexBlogs() { return index().getIndexBlogs(); }
    /** get the blog used for the given blog/tag pair */
    public Hash getBlog(int index) { return index().getBlog(index); }
    /** get the tag used for the given blog/tag pair */
    public String getBlogTag(int index) { return index().getBlogTag(index); }
    /** get the highest entry ID for the given blog/tag pair */
    public long getBlogLastUpdated(int index) { return index().getBlogLastUpdated(index); }
    /** get the entry count for the given blog/tag pair */
    public int getBlogEntryCount(int index) { return index().getBlogEntryCount(index); }
    /** get the entry from the given blog/tag pair */
    public BlogURI getBlogEntry(int index, int entryIndex) { return index().getBlogEntry(index, entryIndex); }
    /** get the raw entry size (including attachments) from the given blog/tag pair */
    public long getBlogEntrySizeKB(int index, int entryIndex) { return index().getBlogEntrySizeKB(index, entryIndex); }
    public boolean getEntryIsKnown(BlogURI uri) { return index().getEntryIsKnown(uri); }
    public long getBlogEntrySizeKB(BlogURI uri) { return index().getBlogEntrySizeKB(uri); }
    public Set getBlogEntryTags(BlogURI uri) { return index().getBlogEntryTags(uri); }
    /** how many 'new' blogs are listed */
    public int getNewestBlogCount() { return index().getNewestBlogCount(); }
    public Hash getNewestBlog(int index) { return index().getNewestBlog(index); }
    /** how many 'new' entries are listed */
    public int getNewestBlogEntryCount() { return index().getNewestBlogEntryCount(); }
    public BlogURI getNewestBlogEntry(int index) { return index().getNewestBlogEntry(index); }
    
    /** list of locally known tags (String) under the given blog */
    public List getBlogTags(Hash blog) { return index().getBlogTags(blog); }
    /** list of unique blogs locally known (set of Hash) */
    public Set getUniqueBlogs() { return index().getUniqueBlogs(); }
    public void setLocation(String location) { return; }
    public void setIsLocal(String val) { return; }
    public void load(File location) throws IOException { return; }
    /** load up the index from an archive.txt */
    public void load(InputStream index) throws IOException { return; }
    
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
        index().selectMatchesOrderByEntryId(out, blog, tag); 
    }
    
    /** export the index into an archive.txt */
    public String toString() { return index().toString(); }
}
