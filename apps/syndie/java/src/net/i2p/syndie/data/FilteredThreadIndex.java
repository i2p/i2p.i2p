package net.i2p.syndie.data;

import java.util.*;
import net.i2p.syndie.*;
import net.i2p.data.*;
import net.i2p.client.naming.*;

/**
 *
 */
public class FilteredThreadIndex extends ThreadIndex {
    private User _user;
    private Archive _archive;
    private ThreadIndex _baseIndex;
    private Collection _filteredTags;
    private List _roots;
    private List _ignoredAuthors;
    private Collection _filteredAuthors;
    private boolean _filterAuthorsByRoot;

    public static final String GROUP_FAVORITE = "Favorite";
    public static final String GROUP_IGNORE = "Ignore";

    public FilteredThreadIndex(User user, Archive archive, Collection tags, Collection authors, boolean filterAuthorsByRoot) {
        super();
        _user = user;
        _archive = archive;
        _baseIndex = _archive.getIndex().getThreadedIndex();
        _filteredTags = tags;
        if (_filteredTags == null)
            _filteredTags = Collections.EMPTY_SET;
        _filteredAuthors = authors;
        if (_filteredAuthors == null)
            _filteredAuthors = Collections.EMPTY_SET;
        _filterAuthorsByRoot = filterAuthorsByRoot;
        
        _ignoredAuthors = new ArrayList();
        for (Iterator iter = user.getPetNameDB().iterator(); iter.hasNext(); ) {
            PetName pn = (PetName)iter.next();
            if (pn.isMember(GROUP_IGNORE)) {
                try {
                    Hash h = new Hash();
                    h.fromBase64(pn.getLocation());
                    _ignoredAuthors.add(h);
                } catch (DataFormatException dfe) {
                    // ignore
                }
            }
        }
        
        filter();
    }
    
    private void filter() {
        _roots = new ArrayList(_baseIndex.getRootCount());
        for (int i = 0; i < _baseIndex.getRootCount(); i++) {
            ThreadNode node = _baseIndex.getRoot(i);
            if (!isIgnored(node, _ignoredAuthors, _filteredTags, _filteredAuthors, _filterAuthorsByRoot))
                _roots.add(node);
        }
    }
    
    private boolean isIgnored(ThreadNode node, List ignoredAuthors, Collection requestedTags, Collection filteredAuthors, boolean filterAuthorsByRoot) {
        if (filteredAuthors.size() <= 0) {
            boolean allAuthorsIgnored = true;
            for (Iterator iter = node.getRecursiveAuthorIterator(); iter.hasNext(); ) {
                Hash author = (Hash)iter.next();
                if (!ignoredAuthors.contains(author)) {
                    allAuthorsIgnored = false;
                    break;
                }
            }
            
            if ( (allAuthorsIgnored) && (ignoredAuthors.size() > 0) )
                return true;
        } else {
            boolean filteredAuthorMatches = false;
            for (Iterator iter = filteredAuthors.iterator(); iter.hasNext(); ) {
                Hash author = (Hash)iter.next();
                if (filterAuthorsByRoot) {
                    if (node.getEntry().getKeyHash().equals(author)) {
                        filteredAuthorMatches = true;
                        break;
                    }
                } else { 
                    if (node.containsAuthor(author)) {
                        filteredAuthorMatches = true;
                        break;
                    }
                }
            }
            if (!filteredAuthorMatches)
                return true;
        }
        
        // ok, author checking passed, so only ignore the thread if tags were specified and the
        // thread doesn't contain that tag
        
        if (requestedTags.size() > 0) {
            Collection nodeTags = node.getRecursiveTags();
            for (Iterator iter = requestedTags.iterator(); iter.hasNext(); ) 
                if (nodeTags.contains(iter.next()))
                    return false;
            // authors we aren't ignoring have posted in the thread, but the user is filtering
            // posts by tags, and this thread doesn't include any of those tags
            return true;
        } else {
            // we aren't filtering by tags, and we haven't been refused by the author
            // filtering
            return false;
        }
    }
    
    public int getRootCount() { return _roots.size(); }
    public ThreadNode getRoot(int index) { return (ThreadNode)_roots.get(index); }
    public ThreadNode getNode(BlogURI uri) { return _baseIndex.getNode(uri); }
    public Collection getFilteredTags() { return _filteredTags; }
    public Collection getFilteredAuthors() { return _filteredAuthors; }
    public boolean getFilterAuthorsByRoot() { return _filterAuthorsByRoot; }
}
