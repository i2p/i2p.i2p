package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.DataStructure;

import java.util.Set;

public interface DataStore {
    public boolean isKnown(Hash key);
    public DataStructure get(Hash key);
    public void put(Hash key, DataStructure data);
    public DataStructure remove(Hash key);
    public Set getKeys();
}
