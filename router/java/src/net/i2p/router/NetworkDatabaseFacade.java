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
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterInfo;
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
    
    /**
     *  Not for use without validation
     *  @return RouterInfo, LeaseSet, or null, NOT validated
     *  @since 0.9.38
     */
    public abstract DatabaseEntry lookupLocallyWithoutValidation(Hash key);

    public abstract void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs);
    
    /**
     *  Lookup using the client's tunnels
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.10
     */
    public abstract void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, Hash fromLocalDest);

    public abstract LeaseSet lookupLeaseSetLocally(Hash key);
    public abstract void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs);
    public abstract RouterInfo lookupRouterInfoLocally(Hash key);
    
    /**
     *  Unconditionally lookup using the client's tunnels.
     *  No success or failed jobs, no local lookup, no checks.
     *  Use this to refresh a leaseset before expiration.
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.25
     */
    public abstract void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest);

    /**
     *  Lookup using the client's tunnels
     *  Succeeds even if LS validation fails due to unsupported sig type
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.16
     */
    public abstract void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest);

    /**
     *  Lookup locally in netDB and in badDest cache
     *  Succeeds even if LS validation failed due to unsupported sig type
     *
     *  @since 0.9.16
     */
    public abstract Destination lookupDestinationLocally(Hash key);

    /** 
     * @return the leaseSet if another leaseSet already existed at that key 
     *
     * @throws IllegalArgumentException if the data is not valid
     */
    public abstract LeaseSet store(Hash key, LeaseSet leaseSet) throws IllegalArgumentException;

    /** 
     * @return the routerInfo if another router already existed at that key 
     *
     * @throws IllegalArgumentException if the data is not valid
     */
    public abstract RouterInfo store(Hash key, RouterInfo routerInfo) throws IllegalArgumentException;

    /** 
     *  @return the old entry if it already existed at that key 
     *  @throws IllegalArgumentException if the data is not valid
     *  @since 0.9.16
     */
    public DatabaseEntry store(Hash key, DatabaseEntry entry) throws IllegalArgumentException {
        if (entry.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
            return store(key, (RouterInfo) entry);
        if (entry.getType() == DatabaseEntry.KEY_TYPE_LEASESET)
            return store(key, (LeaseSet) entry);
        throw new IllegalArgumentException("unknown type");
    }

    /**
     * @throws IllegalArgumentException if the local router is not valid
     */
    public abstract void publish(RouterInfo localRouterInfo) throws IllegalArgumentException;
    public abstract void publish(LeaseSet localLeaseSet);
    public abstract void unpublish(LeaseSet localLeaseSet);
    public abstract void fail(Hash dbEntry);

    /**
     *  The last time we successfully published our RI.
     *  @since 0.9.9
     */
    public long getLastRouterInfoPublishTime() { return 0; }
    
    public abstract Set<Hash> getAllRouters();
    public int getKnownRouters() { return 0; }
    public int getKnownLeaseSets() { return 0; }
    public boolean isInitialized() { return true; }
    public void rescan() {}

    /** Debug only - all user info moved to NetDbRenderer in router console */
    public void renderStatusHTML(Writer out) throws IOException {}
    /** public for NetDbRenderer in routerconsole */
    public Set<LeaseSet> getLeases() { return Collections.emptySet(); }
    /** public for NetDbRenderer in routerconsole */
    public Set<RouterInfo> getRouters() { return Collections.emptySet(); }

    /** @since 0.9 */
    public ReseedChecker reseedChecker() { return null; };

    /**
     *  For convenience, so users don't have to cast to FNDF, and unit tests using
     *  Dummy NDF will work.
     *
     *  @return false; FNDF overrides to return actual setting
     *  @since IPv6
     */
    public boolean floodfillEnabled() { return false; };

    /**
     *  Is it permanently negative cached?
     *
     *  @param key only for Destinations; for RouterIdentities, see Banlist
     *  @since 0.9.16
     */
    public boolean isNegativeCachedForever(Hash key) { return false; }
}
