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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;

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
    public abstract Set findNearestRouters(Hash key, int maxNumRouters, Set peersToIgnore);
    
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
}


class DummyNetworkDatabaseFacade extends NetworkDatabaseFacade {
    private Map _routers;
    private RouterContext _context;
    
    public DummyNetworkDatabaseFacade(RouterContext ctx) {
        _routers = new HashMap();
        _context = ctx;
    }

    public void restart() {}
    public void shutdown() {}
    public void startup() {
        RouterInfo info = _context.router().getRouterInfo();
        _routers.put(info.getIdentity().getHash(), info);
    }
    
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {}
    public LeaseSet lookupLeaseSetLocally(Hash key) { return null; }
    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        RouterInfo info = lookupRouterInfoLocally(key);
        if (info == null) 
            _context.jobQueue().addJob(onFailedLookupJob);
        else
            _context.jobQueue().addJob(onFindJob);
    }
    public RouterInfo lookupRouterInfoLocally(Hash key) { return (RouterInfo)_routers.get(key); }
    public void publish(LeaseSet localLeaseSet) {}
    public void publish(RouterInfo localRouterInfo) {}
    public LeaseSet store(Hash key, LeaseSet leaseSet) { return leaseSet; }
    public RouterInfo store(Hash key, RouterInfo routerInfo) {
        _routers.put(key, routerInfo);
        return routerInfo;
    }
    public void unpublish(LeaseSet localLeaseSet) {}
    public void fail(Hash dbEntry) {}    
    
    public Set findNearestRouters(Hash key, int maxNumRouters, Set peersToIgnore) { return new HashSet(_routers.values()); }

    public void renderStatusHTML(Writer out) throws IOException {}
}
