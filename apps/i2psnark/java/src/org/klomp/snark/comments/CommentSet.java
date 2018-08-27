/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package org.klomp.snark.comments;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.util.SecureFileOutputStream;

/**
 * Store comments.
 *
 * Optimized for fast checking of duplicates, and retrieval of ratings.
 * Removes are not really removed, only marked as hidden, so
 * they don't reappear.
 * Duplicates are detected based on an approximate time range.
 * Max size of both elements and total text length is enforced.
 *
 * Supports persistence via save() and File constructor.
 *
 * NOT THREAD SAFE except for iterating AFTER the iterator() call.
 *
 * @since 0.9.31
 */
public class CommentSet extends AbstractSet<Comment> {

    private final HashMap<Integer, List<Comment>> map;
    private int size, realSize;
    private int myRating;
    private int totalRating;
    private int ratingSize;
    private int totalTextSize;
    private long latestCommentTime;
    private boolean modified;

    public static final int MAX_SIZE = 256;

    // Comment.java enforces max text length of 512, but
    // we don't want 256*512 in memory per-torrent, so
    // track and enforce separately.
    // Assume most comments are short or null.
    private static final int MAX_TOTAL_TEXT_LEN = MAX_SIZE * 16;

    private CommentSet() {
        super();
        map = new HashMap<Integer, List<Comment>>(4);
    }

    public CommentSet(Collection<Comment> coll) {
        super();
        map = new HashMap<Integer, List<Comment>>(coll.size());
        addAll(coll);
    }

    /**
     *  File must be gzipped.
     *  Need not be sorted.
     *  See Comment.toPersistentString() for format.
     */
    public CommentSet(File file) throws IOException {
        this();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(file)), "UTF-8"));
            String line = null;
            while ((line = br.readLine()) != null) {
                Comment c = Comment.fromPersistentString(line);
                if (c != null)
                    add(c);
            }
        } finally {
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
        modified = false;
    }

    /**
     *  File will be gzipped.
     *  Not sorted, includes hidden.
     *  See Comment.toPersistentString() for format.
     *  Sets isModified() to false.
     */
    public void save(File file) throws IOException {
        PrintWriter out = null;
        try {
            out = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(new SecureFileOutputStream(file)), "UTF-8"));
            for (List<Comment> l : map.values()) {
                for (Comment c : l) {
                    out.println(c.toPersistentString());
                }
            }
            if (out.checkError())
                throw new IOException("Failed write to " + file);
            modified = false;
        } finally {
            if (out != null) out.close();
        }
    }

    /**
     *  Max length for strings enforced in Comment.java.
     *  Max total length for strings enforced here.
     *  Enforces max size for set
     */
    @Override
    public boolean add(Comment c) {
        if (realSize >= MAX_SIZE && !c.isMine())
            return false;
        String s = c.getText();
        if (s != null && totalTextSize + s.length() > MAX_TOTAL_TEXT_LEN)
            return false;
        // If isMine and no text and rating changed, don't bother
        if (c.isMine() && c.getText() == null && c.getRating() == myRating)
            return false;
        int hCode = c.hashCode();
        // check previous and next buckets
        Integer phc = Integer.valueOf(hCode - 1);
        List<Comment> plist = map.get(phc);
        if (plist != null && plist.contains(c))
            return false;
        Integer nhc = Integer.valueOf(hCode + 1);
        List<Comment> nxlist = map.get(nhc);
        if (nxlist != null && nxlist.contains(c))
            return false;
        // check this bucket
        Integer hc = Integer.valueOf(hCode);
        List<Comment> list = map.get(hc);
        if (list == null) {
            list = Collections.singletonList(c);
            map.put(hc, list);
            addStats(c);
            return true;
        }
        if (list.contains(c))
            return false;
        if (list.size() == 1) {
            // presume unmodifiable singletonList
            List<Comment> nlist = new ArrayList<Comment>(2);
            nlist.add(list.get(0));
            map.put(hc, nlist);
            list = nlist;
        }
        list.add(c);
        // If isMine and no text and comment changed, remove old ones
        if (c.isMine() && c.getText() == null)
            removeMyOldRatings(c.getID());
        addStats(c);
        return true;
    }

    /**
     *  Only hides the comment, doesn't really remove it.
     *  @return true if present and not previously hidden
     */
    @Override
    public boolean remove(Object o) {
        if (o == null || !(o instanceof Comment))
            return false;
        Comment c = (Comment) o;
        Integer hc = Integer.valueOf(c.hashCode());
        List<Comment> list = map.get(hc);
        if (list == null)
            return false;
        int i = list.indexOf(c);
        if (i >= 0) {
            Comment cc = list.get(i);
            if (!cc.isHidden()) {
                removeStats(cc);
                cc.setHidden();
                return true;
            }
        }
        return false;
    }

    /**
     *  Remove the id as retrieved from Comment.getID().
     *  Only hides the comment, doesn't really remove it.
     *  This is for the UI.
     *
     *  @return true if present and not previously hidden
     */
    public boolean remove(int id) {
        // not the most efficient but should be rare.
        for (List<Comment> l : map.values()) {
            for (Comment c : l) {
                if (c.getID() == id) {
                    return remove(c);
                }
            }
        }
        return false;
    }

    /**
     *  Remove all ratings of mine with empty comments,
     *  except the ID specified.
     */
    private void removeMyOldRatings(int exceptID) {
        for (List<Comment> l : map.values()) {
            for (Comment c : l) {
                if (c.isMine() && c.getText() == null && c.getID() != exceptID && !c.isHidden()) {
                    removeStats(c);
                    c.setHidden();
                }
            }
        }
    }

    /** may be hidden */
    private void addStats(Comment c) {
        realSize++;
        if (!c.isHidden()) {
            size++;
            int r = c.getRating();
            if (r > 0) {
                if (c.isMine()) {
                    myRating = r;
                } else {
                    totalRating += r;
                    ratingSize++;
                }
            }
            long time = c.getTime();
            if (time > latestCommentTime)
                latestCommentTime = time;
        }
        String t = c.getText();
        if (t != null)
            totalTextSize += t.length();
        modified = true;
    }

    /** call before setting hidden */
    private void removeStats(Comment c) {
        if (!c.isHidden()) {
            size--;
            int r = c.getRating();
            if (r > 0) {
                if (c.isMine()) {
                    if (myRating == r)
                        myRating = 0;
                } else {
                    totalRating -= r;
                    ratingSize--;
                }
            }
            modified = true;
        }
    }

    /**
     *  Is not adjusted if the latest comment wasn't hidden but is then hidden.
     *  @return the timestamp of the most recent non-hidden comment
     */
    public long getLatestCommentTime() { return latestCommentTime; }

    /**
     *  @return true if modified since instantiation
     */
    public boolean isModified() { return modified; }

    /**
     *  @return 0 if none, or 1-5
     */
    public int getMyRating() { return myRating; }

    /**
     *  @return Number of ratings making up the average rating
     */
    public int getRatingCount() { return ratingSize; }

    /**
     *  @return 0 if none, or 1-5
     */
    public double getAverageRating() {
        if (ratingSize <= 0)
            return 0.0d;
        return totalRating / (double) ratingSize;
    }

    /**
     *  Actually clears everything, including hidden.
     *  Resets ratings to zero.
     */
    @Override
    public void clear() {
        if (realSize > 0) {
            modified = true;
            realSize = 0;
            map.clear();
            size = 0;
            myRating = 0;
            totalRating = 0;
            ratingSize = 0;
            totalTextSize = 0;
        }
    }

    /**
     *  May be more than what the iterator returns,
     *  we do additional deduping in the iterator.
     *
     *  @return the non-hidden size
     */
    public int size() {
        return size;
    }

    /**
     *  Will be in reverse-sort order, i.e. newest-first.
     *  The returned iterator is thread-safe after this call.
     *  Changes after this call will not be reflected in the iterator.
     *  iter.remove() has no effect on the underlying set.
     *  Hidden comments not included.
     *
     *  Returned values may be less than indicated in size()
     *  due to additional deduping in the iterator.
     */
    public Iterator<Comment> iterator() {
        if (size <= 0)
            return Collections.<Comment>emptyList().iterator();
        List<Comment> list = new ArrayList<Comment>(size);
        for (List<Comment> l : map.values()) {
            int hc = l.get(0).hashCode();
            List<Comment> prevList = map.get(Integer.valueOf(hc - 1));
            for (Comment c : l) {
                if (!c.isHidden()) {
                    // additional deduping at boundary
                    if (prevList != null) {
                        boolean dup = false;
                        for (Comment pc : prevList) {
                            if (c.equalsIgnoreTimestamp(pc)) {
                                dup = true;
                                break;
                            }
                        }
                        if (dup)
                            continue;
                    }
                    list.add(c);
                }
            }
        }
        Collections.sort(list);
        return list.iterator();
    }
}
