package net.i2p.syndie.data;

import java.util.*;

/**
 * List of threads, ordered with the most recently updated thread first.
 * Each node in the tree summarizes everything underneath it as well.
 *
 */
public class ThreadIndex {
    /** ordered list of threads, with most recent first */
    private List _roots;
    /** map of BlogURI to ThreadNode */
    private Map _nodes;
    
    protected ThreadIndex() {
        // no need to synchronize, since the thread index doesn't change after
        // its first built
        _roots = new ArrayList();
        _nodes = new HashMap(64);
    }
    
    public int getRootCount() { return _roots.size(); }
    public ThreadNode getRoot(int index) { return (ThreadNode)_roots.get(index); }
    public ThreadNode getNode(BlogURI uri) { return (ThreadNode)_nodes.get(uri); }
    /** 
     * get the root of the thread that the given uri is located in, or -1.
     * The implementation depends only on getRoot/getNode/getRootCount and not on the
     * data structures, so should be safe for subclasses who adjust those methods
     *
     */
    public int getRoot(BlogURI uri) {
        ThreadNode node = getNode(uri);
        if (node == null) return -1;
        while (node.getParent() != null)
            node = node.getParent();
        for (int i = 0; i < getRootCount(); i++) {
            ThreadNode cur = getRoot(i);
            if (cur.equals(node))
                return i;
        }
        return -1;
    }
  
    /** call this in the right order - most recently updated thread first */
    protected void addRoot(ThreadNode node) { _roots.add(node); }
    /** invocation order here doesn't matter */
    protected void addEntry(BlogURI uri, ThreadNode node) { _nodes.put(uri, node); }
}
