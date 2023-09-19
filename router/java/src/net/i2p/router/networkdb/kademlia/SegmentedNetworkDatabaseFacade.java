package net.i2p.router.networkdb.kademlia;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

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

/**
 * SegmentedNetworkDatabaseFacade
 * 
 * This class implements an interface for managing many netDbs as part of a
 * single I2P instance, each representing it's own view of the network. This
 * allows the I2P clients to operate agnostic of what information the other
 * clients are obtaining from the network database, and prevents information
 * intended for clients from entering the table used by the router for
 * Floodfill operations.
 * 
 * The benefit of this is that we can use this to provide an effective barrier
 * against "Context-Confusion" attacks which exploit the fact that messages sent
 * to clients can update the routing table used by a floodfill, providing
 * evidence that the floodfill hosts the corresponding client. When applied
 * correctly, so that every client uses a unique subDb, the entire class of attack
 * should be neutralized.
 * 
 * The drawback of this is that it makes the netDb less efficient. Clients and
 * Floodfills who share a netDb can update the tables used by those netDbs when
 * a Client encounters an entry obtained by a Floodfill or vice-versa. Clients also
 * must sometimes search the netDb for keys that are owned by other clients or by
 * a co-located Floodfill, if one exists.
 * 
 * In some contexts, it makes sense to view all the tables at once, especially when
 * viewing information from the UI. The functions 'getLeases*', 'getRouters*', and
 * 'getLocalClient*' are provided for this purpose.
 * 
 * In the future, this could also be extended to provide Whanau-like functionality
 * by determining when clients and the local floodfill disagree on the content of a
 * leaseSet.
 * 
 * See implementation: FloodfillNetworkDatabaseSegmentor
 * 
 * @author idk
 * @since 0.9.60
 */
public abstract class SegmentedNetworkDatabaseFacade {
    public SegmentedNetworkDatabaseFacade(RouterContext context) {
        // super(context, null);
    }

    /**
     * Get a sub-netDb using a string identifier
     * 
     * @since 0.9.60
     */
    protected abstract FloodfillNetworkDatabaseFacade getSubNetDB(String dbid);
    /**
     * Get a sub-netDb using a Hash identifier
     * 
     * @since 0.9.60
     */
    protected abstract FloodfillNetworkDatabaseFacade getSubNetDB(Hash dbid);
    /**
     * Get the main netDb, the one which is used if we're a floodfill
     * 
     * @since 0.9.60
     */
    public abstract FloodfillNetworkDatabaseFacade mainNetDB();
    /**
     * Get the multihome netDb, the one which is used if we're a floodfill AND we
     * have a multihome address sent to us
     * 
     * @since 0.9.60
     */
    public abstract FloodfillNetworkDatabaseFacade multiHomeNetDB();
    /**
     * Get a client netDb for a given client string identifier. Will never
     * return the mainNetDB.
     * 
     * @since 0.9.60
     */
    public abstract FloodfillNetworkDatabaseFacade clientNetDB(String dbid);
    /**
     * Get a client netDb for a given client Hash identifier. Will never
     * return the mainNetDB.
     * 
     * @since 0.9.60
     */
    public abstract FloodfillNetworkDatabaseFacade clientNetDB(Hash dbid);
    /**
     * Shut down the network database and all subDbs.
     * 
     * @since 0.9.60
     */
    public abstract void shutdown();
    /**
     * Lookup the leaseSet for a given key in only client dbs.
     * 
     * @since 0.9.60
     */
    public abstract LeaseSet lookupLeaseSetHashIsClient(Hash key);
    /**
     * Lookup the leaseSet for a given key locally across all dbs if dbid is
     * null, or locally for the given dbid if it is not null. Use carefully,
     * this function crosses db boundaries and is intended only for local use.
     * 
     * @since 0.9.60
     */
    protected abstract LeaseSet lookupLeaseSetLocally(Hash key, String dbid);
    /**
     * Lookup the dbid for a given hash.
     * 
     * @since 0.9.60
     */
    public abstract String getDbidByHash(Hash clientKey);
    /**
     * Get a set of all sub-netDbs.
     * 
     * @since 0.9.60
     */
    public abstract Set<FloodfillNetworkDatabaseFacade> getSubNetDBs();
    /**
     * Get a set of all client dbid strings
     * 
     * @since 0.9.60
     */
    public abstract List<String> getClients();
    /**
     * Make sure the SNDF is initialized
     */
    public boolean isInitialized() {
        return mainNetDB().isInitialized();
    }
    /**
     * Get a set of all routers
     * 
     * @since 0.9.60
     */
    public Set<RouterInfo> getRouters() {
        return mainNetDB().getRouters();
    }

    /** 
     * Get a set of all routers known to clients, which should always be zero.
     * 
     * @since 0.9.60 
     */
    public Set<RouterInfo> getRoutersKnownToClients() {
        Set<RouterInfo> ris = new HashSet<>();
        Set<FloodfillNetworkDatabaseFacade> fndfs = getSubNetDBs();
        for (FloodfillNetworkDatabaseFacade fndf : fndfs) {
            ris.addAll(fndf.getRouters());
        }
        return ris;
    }

    /**
     * Get a set of all leases known to all clients.
     * 
     * @since 0.9.60
     */
    public Set<LeaseSet> getLeasesKnownToClients() {
        Set<LeaseSet> lss = new HashSet<>();
        Set<FloodfillNetworkDatabaseFacade> fndfs = getSubNetDBs();
        for (FloodfillNetworkDatabaseFacade fndf : fndfs) {
            lss.addAll(fndf.getLeases());
        }
        return lss;
    }
    /**
     * Check if the mainNetDB needs to reseed
     *  
     * @since 0.9.60 
     * */
    public ReseedChecker reseedChecker() {
        return mainNetDB().reseedChecker();
    };
    /**
     * For console ConfigKeyringHelper
     * 
     * @since 0.9.60
     */
    public List<String> lookupClientBySigningPublicKey(SigningPublicKey spk) {
        return Collections.emptyList();
    }
    /**
     * For console ConfigKeyringHelper
     * 
     * @since 0.9.60
     */
    public List<BlindData> getLocalClientsBlindData() {
        return Collections.emptyList();
    }
}
