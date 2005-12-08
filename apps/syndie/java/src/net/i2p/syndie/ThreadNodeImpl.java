package net.i2p.syndie;

import java.util.*;
import net.i2p.data.Hash;
import net.i2p.syndie.data.BlogURI;
import net.i2p.syndie.data.ThreadNode;

/**
 * Simple memory intensive (but fast) node impl
 *
 */
class ThreadNodeImpl implements ThreadNode {
    /** write once, never updated once the tree is created */
    private Collection _recursiveAuthors;
    /** contains the BlogURI instances */
    private Collection _recursiveEntries;
    /** write once, never updated once the tree is created */
    private List _children;
    private BlogURI _entry;
    private ThreadNode _parent;
    private BlogURI _parentEntry;
    private Collection _tags;
    private Collection _recursiveTags;
    private long _mostRecentPostDate;
    private Hash _mostRecentPostAuthor;
    
    public ThreadNodeImpl() {
        _recursiveAuthors = new HashSet(1);
        _recursiveEntries = new HashSet(1);
        _children = new ArrayList(1);
        _entry = null;
        _parent = null;
        _parentEntry = null;
        _tags = new HashSet();
        _recursiveTags = new HashSet();
        _mostRecentPostDate = -1;
        _mostRecentPostAuthor = null;
    }
    
    void setEntry(BlogURI entry) { _entry = entry; }
    void addAuthor(Hash author) { _recursiveAuthors.add(author); }
    void addChild(ThreadNodeImpl child) { 
        if (!_children.contains(child))
            _children.add(child); 
    }
    void setParent(ThreadNodeImpl parent) { _parent = parent; }
    void setParentEntry(BlogURI parent) { _parentEntry = parent; }
    void addTag(String tag) { 
        _tags.add(tag); 
        _recursiveTags.add(tag);
    }
    
    void summarizeThread() {
        _recursiveAuthors.add(_entry.getKeyHash());
        _recursiveEntries.add(_entry);
        // children are always 'newer' than parents, even if their dates are older
        // (e.g. post #1 for a child on tuesday is 'newer' than post #5 for the parent on tuesday)
        _mostRecentPostDate = -1;
        _mostRecentPostAuthor = null;
        
        // we need to go through all children (recursively), in case the 
        // tree is out of order (which it shouldn't be, if its built carefully...)
        for (int i = 0; i < _children.size(); i++) {
            ThreadNodeImpl node = (ThreadNodeImpl)_children.get(i);
            node.summarizeThread();
            // >= so we can give reasonable order when a child is a reply to a parent
            // (since the child must have been posted after the parent)
            if (node.getMostRecentPostDate() >= _mostRecentPostDate) {
                _mostRecentPostDate = node.getMostRecentPostDate();
                _mostRecentPostAuthor = node.getMostRecentPostAuthor();
            }
            _recursiveTags.addAll(node.getRecursiveTags());
            _recursiveAuthors.addAll(node.getRecursiveAuthors());
            _recursiveEntries.addAll(node.getRecursiveEntries());
        }
        
        if (_mostRecentPostDate < 0) {
            _mostRecentPostDate = _entry.getEntryId();
            _mostRecentPostAuthor = _entry.getKeyHash();
        }
        
        // now reorder the children
        TreeSet ordered = new TreeSet(new NewestNodeFirstComparator());
        for (int i = 0; i < _children.size(); i++) {
            ThreadNodeImpl kid = (ThreadNodeImpl)_children.get(i);
            ordered.add(kid);
        }
        List kids = new ArrayList(ordered.size());
        for (Iterator iter = ordered.iterator(); iter.hasNext(); ) 
            kids.add(iter.next());
        _children = kids;
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("<node><entry>").append(getEntry().toString()).append("</entry>\n");
        buf.append("<tags>").append(getTags()).append("</tags>\n");
        buf.append("<mostRecentPostDate>").append(getMostRecentPostDate()).append("</mostRecentPostDate>\n");
        buf.append("<recursiveTags>").append(getRecursiveTags()).append("</recursiveTags>\n");
        buf.append("<children>\n");
        for (int i = 0; i < _children.size(); i++)
            buf.append(_children.get(i).toString());
        buf.append("</children>\n");
        buf.append("</node>\n");
        return buf.toString();
    }
    
    private Collection getRecursiveAuthors() { return _recursiveAuthors; }
    private Collection getRecursiveEntries() { return _recursiveEntries; }
    
    // interface-specified methods doing what one would expect...
    public boolean containsAuthor(Hash author) { return _recursiveAuthors.contains(author); }
    public boolean containsEntry(BlogURI uri) { return _recursiveEntries.contains(uri); }
    public ThreadNode getChild(int index) { return (ThreadNode)_children.get(index); }
    public int getChildCount() { return _children.size(); }
    public BlogURI getEntry() { return _entry; }
    public ThreadNode getParent() { return _parent; }
    public BlogURI getParentEntry() { return _parentEntry; }
    public boolean containsTag(String tag) { return _tags.contains(tag); }
    public Collection getTags() { return _tags; }
    public Collection getRecursiveTags() { return _recursiveTags; }
    public long getMostRecentPostDate() { return _mostRecentPostDate; }
    public Hash getMostRecentPostAuthor() { return _mostRecentPostAuthor; }
    public Iterator getRecursiveAuthorIterator() { return _recursiveAuthors.iterator(); }
}
