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

import net.i2p.data.DataStructure;
import net.i2p.data.Hash;

public interface DataStore {
    public boolean isKnown(Hash key);
    public DataStructure get(Hash key);
    public void put(Hash key, DataStructure data);
    public DataStructure remove(Hash key);
    public DataStructure removeLease(Hash key);
    public Set getKeys();
    public void restart();
    public int countLeaseSets();

}
