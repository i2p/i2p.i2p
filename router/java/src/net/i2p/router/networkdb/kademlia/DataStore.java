package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;

public interface DataStore {
    public boolean isInitialized();
    public boolean isKnown(Hash key);
    public DatabaseEntry get(Hash key);
    public DatabaseEntry get(Hash key, boolean persist);
    public boolean put(Hash key, DatabaseEntry data);
    public boolean put(Hash key, DatabaseEntry data, boolean persist);
    public DatabaseEntry remove(Hash key);
    public DatabaseEntry remove(Hash key, boolean persist);
    public Set<Hash> getKeys();
    /** @since 0.8.3 */
    public Collection<DatabaseEntry> getEntries();
    /** @since 0.8.3 */
    public Set<Map.Entry<Hash, DatabaseEntry>> getMapEntries();
    public void stop();
    public void restart();
    public void rescan();
    public int countLeaseSets();

}
