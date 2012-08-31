package net.i2p.kademlia;

import net.i2p.data.SimpleDataStructure;

/**
 *  Removes nothing and always rejects the add. Flood resistant..
 *  @since 0.9.2
 */
public class RejectTrimmer<T extends SimpleDataStructure> implements KBucketTrimmer<T> {
    public boolean trim(KBucket<T> kbucket, T toAdd) {
        return false;
    }
}
