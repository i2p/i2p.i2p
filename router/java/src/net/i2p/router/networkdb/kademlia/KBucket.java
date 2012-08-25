package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;

import net.i2p.data.Hash;

/**
 * Group, without inherent ordering, a set of keys a certain distance away from
 * a local key, using XOR as the distance metric
 *
 */
interface KBucket {
    /** 
     * lowest order high bit for difference keys 
     */
    public int getRangeBegin();
    /**
     * highest high bit for the difference keys
     *
     */
    public int getRangeEnd();
    /**
     * Set the range low and high bits for difference keys
     */
    public void setRange(int lowOrderBitLimit, int highOrderBitLimit);
    /**
     * Number of keys already contained in this kbuckey
     */
    public int getKeyCount();
    /**
     * whether or not the key qualifies as part of this bucket
     *
     */
    public boolean shouldContain(Hash key);
    /**
     * Add the peer to the bucket
     *
     * @return number of keys in the bucket after the addition
     */
    public int add(Hash key);
    /**
     * Remove the key from the bucket
     * @return true if the key existed in the bucket before removing it, else false
     */
    public boolean remove(Hash key);
    
    /**
     * Retrieve all routing table entries stored in the bucket
     * @return set of Hash structures
     */
    public Set<Hash> getEntries();

    /**
     * Retrieve hashes stored in the bucket, excluding the ones specified 
     * @return set of Hash structures
     * @deprecated makes a copy, remove toIgnore in KBS instead
     */
    public Set<Hash> getEntries(Set<Hash> toIgnoreHashes);

    public void getEntries(SelectionCollector collector);
    
    /**
     * Fill the bucket with entries
     * @param entries set of Hash structures
     */
    public void setEntries(Set<Hash> entries);

    /** 
     * Generate a random key that would go inside this bucket
     *
     */
    public Hash generateRandomKey();
    
    public LocalHash getLocal();
}
