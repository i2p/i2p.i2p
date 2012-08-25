package net.i2p.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;

import net.i2p.data.SimpleDataStructure;

/**
 * Group, without inherent ordering, a set of keys a certain distance away from
 * a local key, using XOR as the distance metric
 *
 * Refactored from net.i2p.router.networkdb.kademlia
 * @since 0.9.2
 */
public interface KBucket<T extends SimpleDataStructure> {

    /** 
     * Lowest order high bit for difference keys.
     * The lower-bounds distance of this bucket is 2**begin.
     * If begin == 0, this is the closest bucket.
     */
    public int getRangeBegin();

    /**
     * Highest high bit for the difference keys.
     * The upper-bounds distance of this bucket is (2**(end+1)) - 1.
     * If begin == end, the bucket cannot be split further.
     * If end == (numbits - 1), this is the furthest bucket.
     */
    public int getRangeEnd();

    /**
     * Number of keys already contained in this kbucket
     */
    public int getKeyCount();

    /**
     * Add the peer to the bucket
     *
     * @return true if added
     */
    public boolean add(T key);

    /**
     * Remove the key from the bucket
     * @return true if the key existed in the bucket before removing it, else false
     */
    public boolean remove(T key);
    
    /**
     *  Update the last-changed timestamp to now.
     */
    public void setLastChanged();

    /**
     *  The last-changed timestamp
     */
    public long getLastChanged();

    /**
     * Retrieve all routing table entries stored in the bucket
     * @return set of Hash structures
     */
    public Set<T> getEntries();

    public void getEntries(SelectionCollector<T> collector);

    public void clear();
}
