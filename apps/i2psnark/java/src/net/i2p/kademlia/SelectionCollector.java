package net.i2p.kademlia;

import net.i2p.data.SimpleDataStructure;

/**
 * Visit kbuckets, gathering matches
 */
public interface SelectionCollector<T extends SimpleDataStructure> {
    public void add(T entry);
}
