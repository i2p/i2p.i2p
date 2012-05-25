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
    public boolean isInitialized();
    public boolean isKnown(Hash key);
    public DataStructure get(Hash key);
    public DataStructure get(Hash key, boolean persist);
    public void put(Hash key, DataStructure data);
    public void put(Hash key, DataStructure data, boolean persist);
    public DataStructure remove(Hash key);
    public DataStructure remove(Hash key, boolean persist);
    public Set getKeys();
    public void stop();
    public void restart();
    public void rescan();
    public int countLeaseSets();

}
