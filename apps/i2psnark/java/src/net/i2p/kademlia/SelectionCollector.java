package net.i2p.kademlia;

import net.i2p.data.SimpleDataStructure;

/**
 * Visit kbuckets, gathering matches
 * @since 0.9.2
 */
public interface SelectionCollector<T extends SimpleDataStructure> {
    public void add(T entry);
}
