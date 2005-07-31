package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;

/**
 * Visit kbuckets, gathering matches
 */
interface SelectionCollector {
    public void add(Hash entry);
}
