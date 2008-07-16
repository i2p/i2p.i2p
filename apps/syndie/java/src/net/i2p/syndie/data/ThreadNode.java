package net.i2p.syndie.data;

import java.util.Collection;
import java.util.Iterator;

import net.i2p.data.Hash;

/**
 *
 */
public interface ThreadNode {
    /** current post */
    public BlogURI getEntry();
    /** how many direct replies there are to the current entry */
    public int getChildCount();
    /** the given direct reply */
    public ThreadNode getChild(int index);
    /** parent this is actually a reply to */
    public BlogURI getParentEntry();
    /** parent in the tree, maybe not a direct parent, but the closest one */
    public ThreadNode getParent();
    /** true if this entry, or any child, is written by the given author */
    public boolean containsAuthor(Hash author);
    /** true if this node, or any child, includes the given URI */
    public boolean containsEntry(BlogURI uri);
    /** list of tags (String) of this node only */
    public Collection getTags();
    /** list of tags (String) of this node or any children in the tree */
    public Collection getRecursiveTags();
    /** date of the most recent post, recursive */
    public long getMostRecentPostDate();
    /** author of the most recent post, recurisve */
    public Hash getMostRecentPostAuthor();
    /** walk across the authors of the entire thread */
    public Iterator getRecursiveAuthorIterator();
}
