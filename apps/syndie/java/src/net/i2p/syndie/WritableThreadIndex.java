package net.i2p.syndie;

import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.SMLParser;
import net.i2p.syndie.sml.HTMLRenderer;

/**
 *
 */
class WritableThreadIndex extends ThreadIndex {
    /** map of child (BlogURI) to parent (BlogURI) */
    private Map _parents;
    /** map of entry (BlogURI) to tags (String[]) */
    private Map _tags;
    private static final String[] NO_TAGS = new String[0];
    /** b0rk if the thread seems to go too deep */
    private static final int MAX_THREAD_DEPTH = 64;
    
    WritableThreadIndex() {
        super(); 
        _parents = new HashMap();
        _tags = new TreeMap(new NewestEntryFirstComparator());
    }
    
    void addParent(BlogURI parent, BlogURI child) { _parents.put(child, parent); }
    void addEntry(BlogURI entry, String tags[]) { 
        if (tags == null) tags = NO_TAGS;
        String oldTags[] = (String[])_tags.put(entry, tags);
    }
    
    /** 
     * pull the data added together into threads, and stash them in the 
     * roots, organized chronologically
     *
     */
    void organizeTree() {
        Map nodes = new HashMap(_tags.size());
        for (Iterator iter = _tags.keySet().iterator(); iter.hasNext(); ) {
            BlogURI entry = (BlogURI)iter.next();
            String tags[] = (String[])_tags.get(entry);
            BlogURI parent = (BlogURI)_parents.get(entry);
            ThreadNodeImpl node = new ThreadNodeImpl();
            node.setEntry(entry);
            if (tags != null)
                for (int i = 0; i < tags.length; i++)
                    node.addTag(tags[i]);
            if (parent != null)
                node.setParentEntry(parent);
            addEntry(entry, node);
            nodes.put(entry, node);
        }
        
        SMLParser parser = new SMLParser(I2PAppContext.getGlobalContext());
        HeaderReceiver rec = new HeaderReceiver();
        Archive archive = BlogManager.instance().getArchive();
        
        TreeSet roots = new TreeSet(new NewestNodeFirstComparator());
        for (Iterator iter = nodes.keySet().iterator(); iter.hasNext(); ) {
            BlogURI entry = (BlogURI)iter.next();
            ThreadNodeImpl node = (ThreadNodeImpl)nodes.get(entry);
            int depth = 0;
            // climb the tree
            while (node.getParentEntry() != null) {
                ThreadNodeImpl parent = (ThreadNodeImpl)nodes.get(node.getParentEntry());
                if (parent == null) break;
                
                // if the parent doesn't want replies, only include replies under the tree
                // if they're written by the same author
                BlogURI parentURI = parent.getEntry();
                EntryContainer parentEntry = archive.getEntry(parentURI);
                if (parentEntry != null) {
                    parser.parse(parentEntry.getEntry().getText(), rec);
                    String refuse = rec.getHeader(HTMLRenderer.HEADER_REFUSE_REPLIES);
                    if ( (refuse != null) && (Boolean.valueOf(refuse).booleanValue()) ) {
                        if (parent.getEntry().getKeyHash().equals(entry.getKeyHash())) {
                            // same author, allow the reply
                        } else {
                            // different author, refuse
                            parent = null;
                            break;
                        }
                    }
                }
                
                node.setParent(parent);
                parent.addChild(node);
                node = parent;
                depth++;
                if (depth > MAX_THREAD_DEPTH)
                    break;
            }
        
            node.summarizeThread();
            roots.add(node);
        }
        
        // store them, sorted by most recently updated thread first
        for (Iterator iter = roots.iterator(); iter.hasNext(); ) 
            addRoot((ThreadNode)iter.next());
        
        _parents.clear();
        _tags.clear();
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("<threadIndex>");
        for (int i = 0; i < getRootCount(); i++) {
            ThreadNode root = getRoot(i);
            buf.append(root.toString());
        }
        buf.append("</threadIndex>\n");
        return buf.toString();
    }
    
    /** sort BlogURI instances with the highest entryId first */
    private class NewestEntryFirstComparator implements Comparator {
        public int compare(Object lhs, Object rhs) {
            BlogURI left = (BlogURI)lhs;
            BlogURI right = (BlogURI)rhs;
            if (left.getEntryId() > right.getEntryId()) {
                return -1;
            } else if (left.getEntryId() == right.getEntryId()) {
                return DataHelper.compareTo(left.getKeyHash().getData(), right.getKeyHash().getData());
            } else {
                return 1;
            }
        }
    }
    /** sort ThreadNodeImpl instances with the highest entryId first */
    private class NewestNodeFirstComparator implements Comparator {
        public int compare(Object lhs, Object rhs) {
            ThreadNodeImpl left = (ThreadNodeImpl)lhs;
            ThreadNodeImpl right = (ThreadNodeImpl)rhs;
            if (left.getEntry().getEntryId() > right.getEntry().getEntryId()) {
                return -1;
            } else if (left.getEntry().getEntryId() == right.getEntry().getEntryId()) {
                return DataHelper.compareTo(left.getEntry().getKeyHash().getData(), right.getEntry().getKeyHash().getData());
            } else {
                return 1;
            }
        }
    }
}
