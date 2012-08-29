package net.i2p.kademlia;

import net.i2p.data.SimpleDataStructure;

/**
 *  Called when a kbucket can no longer be split and is too big
 *  @since 0.9.2
 */
public interface KBucketTrimmer<K extends SimpleDataStructure> {
    /**
     *  Called from add() just before adding the entry.
     *  You may call getEntries() and/or remove() from here.
     *  Do NOT call add().
     *  To always discard a newer entry, always return false.
     *
     *  @param kbucket the kbucket that is now too big
     *  @return true to actually add the entry.
     */
    public boolean trim(KBucket<K> kbucket, K toAdd);
}
