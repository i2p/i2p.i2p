package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

class TransientDataStore implements DataStore {
    private Log _log;
    private Map<Hash, DataStructure> _data;
    protected RouterContext _context;
    
    public TransientDataStore(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TransientDataStore.class);
        _data = new ConcurrentHashMap(1024);
        if (_log.shouldLog(Log.INFO))
            _log.info("Data Store initialized");
    }
    
    public boolean isInitialized() { return true; }

    public void stop() {
        _data.clear();
    }
    
    public void restart() {
        stop();
    }
    
    public void rescan() {}

    public Set getKeys() {
        return new HashSet(_data.keySet());
    }
    
    /** for PersistentDataStore only - don't use here */
    public DataStructure get(Hash key, boolean persist) {
        throw new IllegalArgumentException("no");
    }

    public DataStructure get(Hash key) {
        return _data.get(key);
    }
    
    public boolean isKnown(Hash key) {
        return _data.containsKey(key);
    }

    public int countLeaseSets() {
        int count = 0;
        for (DataStructure d : _data.values()) {
            if (d instanceof LeaseSet)
                count++;
        }
        return count;
    }
    
    /** nothing published more than 5 minutes in the future */
    private final static long MAX_FUTURE_PUBLISH_DATE = 5*60*1000;
    /** don't accept tunnels set to expire more than 3 hours in the future, which is insane */
    private final static long MAX_FUTURE_EXPIRATION_DATE = KademliaNetworkDatabaseFacade.MAX_LEASE_FUTURE;
    
    /** for PersistentDataStore only - don't use here */
    public void put(Hash key, DataStructure data, boolean persist) {
        throw new IllegalArgumentException("no");
    }

    public void put(Hash key, DataStructure data) {
        if (data == null) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Storing key " + key);
        DataStructure old = null;
        old = _data.put(key, data);
        if (data instanceof RouterInfo) {
            _context.profileManager().heardAbout(key);
            RouterInfo ri = (RouterInfo)data;
            if (old != null) {
                RouterInfo ori = (RouterInfo)old;
                if (ri.getPublished() < ori.getPublished()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Almost clobbered an old router! " + key + ": [old published on " + new Date(ori.getPublished()) + " new on " + new Date(ri.getPublished()) + "]");
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Number of router options for " + key + ": " + ri.getOptions().size() + " (old one had: " + ori.getOptions().size() + ")", new Exception("Updated routerInfo"));
                    _data.put(key, old);
                } else if (ri.getPublished() > _context.clock().now() + MAX_FUTURE_PUBLISH_DATE) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Hmm, someone tried to give us something with the publication date really far in the future (" + new Date(ri.getPublished()) + "), dropping it");
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Number of router options for " + key + ": " + ri.getOptions().size() + " (old one had: " + ori.getOptions().size() + ")", new Exception("Updated routerInfo"));
                    _data.put(key, old);
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Updated the old router for " + key + ": [old published on " + new Date(ori.getPublished()) + " new on " + new Date(ri.getPublished()) + "]");
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Number of router options for " + key + ": " + ri.getOptions().size() + " (old one had: " + ori.getOptions().size() + ")", new Exception("Updated routerInfo"));
                }
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Brand new router for " + key + ": published on " + new Date(ri.getPublished()));
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Number of router options for " + key + ": " + ri.getOptions().size(), new Exception("Updated routerInfo"));
            }
        } else if (data instanceof LeaseSet) {
            LeaseSet ls = (LeaseSet)data;
            if (old != null) {
                LeaseSet ols = (LeaseSet)old;
                if (ls.getEarliestLeaseDate() < ols.getEarliestLeaseDate()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Almost clobbered an old leaseSet! " + key + ": [old published on " + new Date(ols.getEarliestLeaseDate()) + " new on " + new Date(ls.getEarliestLeaseDate()) + "]");
                    _data.put(key, old);
                } else if (ls.getEarliestLeaseDate() > _context.clock().now() + MAX_FUTURE_EXPIRATION_DATE) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Hmm, someone tried to give us something with the expiration date really far in the future (" + new Date(ls.getEarliestLeaseDate()) + "), dropping it");
                    _data.put(key, old);
                }
            }
        }
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_data);
    }
    @Override
    public boolean equals(Object obj) {
        if ( (obj == null) || (obj.getClass() != getClass()) ) return false;
        TransientDataStore ds = (TransientDataStore)obj;
        return DataHelper.eq(ds._data, _data);
    }
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("Transient DataStore: ").append(_data.size()).append("\nKeys: ");
        for (Map.Entry<Hash, DataStructure> e : _data.entrySet()) {
            Hash key = e.getKey();
            DataStructure dp = e.getValue();
            buf.append("\n\t*Key:   ").append(key.toString()).append("\n\tContent: ").append(dp.toString());
        }
        buf.append("\n");
        return buf.toString();
    }
    
    /** for PersistentDataStore only - don't use here */
    public DataStructure remove(Hash key, boolean persist) {
        throw new IllegalArgumentException("no");
    }

    public DataStructure remove(Hash key) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Removing key " + key.toBase64());
        return _data.remove(key);
    }
}
