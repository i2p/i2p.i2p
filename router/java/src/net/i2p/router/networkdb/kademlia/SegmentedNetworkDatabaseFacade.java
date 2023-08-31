package net.i2p.router.networkdb.kademlia;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.i2p.data.BlindData;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.reseed.ReseedChecker;

public abstract class SegmentedNetworkDatabaseFacade { // extends FloodfillNetworkDatabaseFacade {
    public SegmentedNetworkDatabaseFacade(RouterContext context) {
        // super(context, null);
    }

    public abstract FloodfillNetworkDatabaseFacade getSubNetDB(String id);

    public abstract FloodfillNetworkDatabaseFacade floodfillNetDB();

    public abstract FloodfillNetworkDatabaseFacade multiHomeNetDB();

    public abstract FloodfillNetworkDatabaseFacade clientNetDB(String id);

    public abstract FloodfillNetworkDatabaseFacade exploratoryNetDB();

    public abstract FloodfillNetworkDatabaseFacade localNetDB();

    public abstract void startup();

    public abstract void shutdown();

    /**
     * Return the RouterInfo structures for the routers closest to the given key.
     * At most maxNumRouters will be returned
     *
     * @param key           The key
     * @param maxNumRouters The maximum number of routers to return
     * @param peersToIgnore Hash of routers not to include
     */
    public abstract Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, String dbid);

    /**
     * @return RouterInfo, LeaseSet, or null
     * @since 0.9.59
     */
    public abstract DatabaseEntry lookupLocally(Hash key, String dbid);

    /**
     * Not for use without validation
     * 
     * @return RouterInfo, LeaseSet, or null, NOT validated
     * @since 0.9.59
     */
    public abstract DatabaseEntry lookupLocallyWithoutValidation(Hash key, String dbid);

    public abstract void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, String dbid);

    /**
     * Lookup using the client's tunnels
     * 
     * @param fromLocalDest use these tunnels for the lookup, or null for
     *                      exploratory
     * @since 0.9.59
     */
    public abstract void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs,
            Hash fromLocalDest, String dbid);

    public abstract LeaseSet lookupLeaseSetHashIsClient(Hash key);

    public abstract LeaseSet lookupLeaseSetLocally(Hash key, String dbid);

    public abstract LeaseSet lookupLeaseSetLocally(Hash key);

    public abstract void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, String dbid);

    public abstract RouterInfo lookupRouterInfoLocally(Hash key, String dbid);

    /**
     * Unconditionally lookup using the client's tunnels.
     * No success or failed jobs, no local lookup, no checks.
     * Use this to refresh a leaseset before expiration.
     *
     * @param fromLocalDest use these tunnels for the lookup, or null for
     *                      exploratory
     * @since 0.9.59
     */
    public abstract void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest, String dbid);

    /**
     * Unconditionally lookup using the client's tunnels.
     *
     * @param fromLocalDest     use these tunnels for the lookup, or null for
     *                          exploratory
     * @param onFindJob         may be null
     * @param onFailedLookupJob may be null
     * @since 0.9.59
     */
    public abstract void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob,
            long timeoutMs, Hash fromLocalDest, String dbid);

    /**
     * Lookup using the client's tunnels
     * Succeeds even if LS validation fails due to unsupported sig type
     *
     * @param fromLocalDest use these tunnels for the lookup, or null for
     *                      exploratory
     * @since 0.9.59
     */
    public abstract void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest,
            String dbid);

    /**
     * Lookup locally in netDB and in badDest cache
     * Succeeds even if LS validation failed due to unsupported sig type
     *
     * @since 0.9.59
     */
    public abstract Destination lookupDestinationLocally(Hash key, String dbid);

    /**
     * @return the leaseSet if another leaseSet already existed at that key
     *
     * @throws IllegalArgumentException if the data is not valid
     */
    public abstract LeaseSet store(Hash key, LeaseSet leaseSet, String dbid) throws IllegalArgumentException;

    /**
     * @return the routerInfo if another router already existed at that key
     *
     * @throws IllegalArgumentException if the data is not valid
     */
    public abstract RouterInfo store(Hash key, RouterInfo routerInfo, String dbid) throws IllegalArgumentException;

    /**
     * @return the old entry if it already existed at that key
     * @throws IllegalArgumentException if the data is not valid
     * @since 0.9.59
     */
    public DatabaseEntry store(Hash key, DatabaseEntry entry, String dbid) throws IllegalArgumentException {
        if (entry.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
            return getSubNetDB(dbid).store(key, (RouterInfo) entry);
        if (entry.getType() == DatabaseEntry.KEY_TYPE_LEASESET)
            return getSubNetDB(dbid).store(key, (LeaseSet) entry);
        throw new IllegalArgumentException("unknown type");
    }

    public LeaseSet store(Hash key, LeaseSet leaseSet) {
        return store(key, leaseSet, null);
    }

    public RouterInfo store(Hash key, RouterInfo routerInfo) {
        return store(key, routerInfo, null);
    }

    /**
     * @throws IllegalArgumentException if the local router is not valid
     */
    public abstract void publish(RouterInfo localRouterInfo) throws IllegalArgumentException;

    public abstract void publish(LeaseSet localLeaseSet, String dbid);

    public abstract void unpublish(LeaseSet localLeaseSet, String dbid);

    public abstract void unpublish(LeaseSet localLeaseSet);

    public abstract void fail(Hash dbEntry, String dbid);

    public abstract void fail(Hash dbEntry);

    /**
     * The last time we successfully published our RI.
     * 
     * @since 0.9.59
     */
    public long getLastRouterInfoPublishTime(String dbid) {
        return 0;
    }

    public long getLastRouterInfoPublishTime() {
        return 0;
    }

    public abstract Set<Hash> getAllRouters(String dbid);
    public abstract Set<Hash> getAllRouters();

    public int getKnownRouters(String dbid) {
        return 0;
    }

    public int getKnownRouters() {
        return 0;
    }

    public int getKnownLeaseSets(String dbid) {
        return 0;
    }

    public boolean isInitialized(String dbid) {
        return true;
    }

    public boolean isInitialized() {
        return true;
    }

    public void rescan(String dbid) {
    }

    /** Debug only - all user info moved to NetDbRenderer in router console */
    public void renderStatusHTML(Writer out) throws IOException {
    }

    /** public for NetDbRenderer in routerconsole */
    public Set<LeaseSet> getLeases(String dbid) {
        return Collections.emptySet();
    }

    /** public for NetDbRenderer in routerconsole */
    public Set<RouterInfo> getRouters(String dbid) {
        return Collections.emptySet();
    }

    public Set<RouterInfo> getRouters() {
        return Collections.emptySet();
    }

    public Set<RouterInfo> getRoutersKnownToClients() {
        return Collections.emptySet();
    }

    public Set<LeaseSet> getLeasesKnownToClients() {
        return Collections.emptySet();
    }

    public List<String> getClients() {
        return Collections.emptyList();
    }

    /** @since 0.9.59 */
    public ReseedChecker reseedChecker() {
        return floodfillNetDB().reseedChecker();
    };

    /**
     * For convenience, so users don't have to cast to FNDF, and unit tests using
     * Dummy NDF will work.
     *
     * @return false; FNDF overrides to return actual setting
     * @since IPv6
     */
    public boolean floodfillEnabled() {
        return floodfillNetDB().floodfillEnabled();
    };

    /**
     * Is it permanently negative cached?
     *
     * @param key only for Destinations; for RouterIdentities, see Banlist
     * @since 0.9.59
     */
    public boolean isNegativeCachedForever(Hash key, String dbid) {
        return floodfillNetDB().isNegativeCachedForever(key);
    }

    /**
     * @param spk unblinded key
     * @return BlindData or null
     * @since 0.9.59
     */
    public BlindData getBlindData(SigningPublicKey spk) {
        return floodfillNetDB().getBlindData(spk);
    }

    public List<BlindData> getLocalClientsBlindData() {
        return floodfillNetDB().getBlindData();
    }

    /**
     * @param bd new BlindData to put in the cache
     * @since 0.9.59
     */
    public void setBlindData(BlindData bd, String dbid) {
        floodfillNetDB().setBlindData(bd);
    }

    /**
     * For console ConfigKeyringHelper
     * 
     * @since 0.9.59
     */
    public List<BlindData> getBlindData(String dbid) {
        return floodfillNetDB().getBlindData();
    }

    /**
     * For console ConfigKeyringHelper
     * 
     * @return true if removed
     * @since 0.9.59
     */
    public boolean removeBlindData(SigningPublicKey spk, String dbid) {
        return floodfillNetDB().removeBlindData(spk);
    }

    /**
     * Notify the netDB that the routing key changed at midnight UTC
     *
     * @since 0.9.59
     */
    public void routingKeyChanged() {
        floodfillNetDB().routingKeyChanged();
    }

    public void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, String dbid) {
        floodfillNetDB().lookupLeaseSetRemotely(key, onFindJob, onFailedLookupJob, timeoutMs, key);
    }
    public List<String> lookupClientBySigningPublicKey(SigningPublicKey spk) {
        return Collections.emptyList();
    }
    public BlindData getBlindData(SigningPublicKey spk, String dbid) {
        return floodfillNetDB().getBlindData(spk);
    }

    public abstract String getDbidByHash(Hash clientKey);
}
