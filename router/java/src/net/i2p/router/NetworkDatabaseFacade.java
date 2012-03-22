package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.router.networkdb.reseed.ReseedChecker;

/**
 * Defines the mechanism for interacting with I2P's network database
 *
 */ 
public abstract class NetworkDatabaseFacade implements Service {
    /**
     * Return the RouterInfo structures for the routers closest to the given key.
     * At most maxNumRouters will be returned
     *
     * @param key The key
     * @param maxNumRouters The maximum number of routers to return
     * @param peersToIgnore Hash of routers not to include
     */
    public abstract Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore);
    
    /**
     *  @return RouterInfo, LeaseSet, or null
     *  @since 0.8.3
     */
    public abstract DatabaseEntry lookupLocally(Hash key);
    public abstract void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs);
    public abstract LeaseSet lookupLeaseSetLocally(Hash key);
    public abstract void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs);
    public abstract RouterInfo lookupRouterInfoLocally(Hash key);
    /** 
     * return the leaseSet if another leaseSet already existed at that key 
     *
     * @throws IllegalArgumentException if the data is not valid
     */
    public abstract LeaseSet store(Hash key, LeaseSet leaseSet) throws IllegalArgumentException;
    /** 
     * return the routerInfo if another router already existed at that key 
     *
     * @throws IllegalArgumentException if the data is not valid
     */
    public abstract RouterInfo store(Hash key, RouterInfo routerInfo) throws IllegalArgumentException;
    /**
     * @throws IllegalArgumentException if the local router is not valid
     */
    public abstract void publish(RouterInfo localRouterInfo) throws IllegalArgumentException;
    public abstract void publish(LeaseSet localLeaseSet);
    public abstract void unpublish(LeaseSet localLeaseSet);
    public abstract void fail(Hash dbEntry);
    
    public abstract Set<Hash> getAllRouters();
    public int getKnownRouters() { return 0; }
    public int getKnownLeaseSets() { return 0; }
    public boolean isInitialized() { return true; }
    public void rescan() {}
    /** @deprecated moved to router console */
    public void renderStatusHTML(Writer out) throws IOException {}
    /** public for NetDbRenderer in routerconsole */
    public Set<LeaseSet> getLeases() { return Collections.EMPTY_SET; }
    /** public for NetDbRenderer in routerconsole */
    public Set<RouterInfo> getRouters() { return Collections.EMPTY_SET; }

    /** @since 0.9 */
    public ReseedChecker reseedChecker() { return null; };
}
