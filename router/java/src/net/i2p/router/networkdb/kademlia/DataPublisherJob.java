package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

class DataPublisherJob extends JobImpl {
    private Log _log;
    private KademliaNetworkDatabaseFacade _facade;
    private final static long RERUN_DELAY_MS = 60*1000;
    private final static int MAX_SEND_PER_RUN = 1; // publish no more than 2 at a time
    private final static long STORE_TIMEOUT = 60*1000; // give 'er a minute to send the data
    
    public DataPublisherJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(DataPublisherJob.class);
        _facade = facade;
        getTiming().setStartAfter(ctx.clock().now()+RERUN_DELAY_MS); // not immediate...
    }
    
    public String getName() { return "Data Publisher Job"; }
    public void runJob() {
        Set toSend = selectKeysToSend();
        if (_log.shouldLog(Log.INFO))
            _log.info("Keys being published in this timeslice: " + toSend);
        for (Iterator iter = toSend.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            DataStructure data = _facade.getDataStore().get(key);
            if (data == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Trying to send a key we dont have? " + key);
                continue;
            }
            if (data instanceof LeaseSet) {
                LeaseSet ls = (LeaseSet)data;
                if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Not publishing a lease that isn't current - " + key, 
                                  new Exception("Publish expired lease?"));
                }
            }
            StoreJob store = new StoreJob(getContext(), _facade, key, data, null, null, STORE_TIMEOUT);
            getContext().jobQueue().addJob(store);
        }
        requeue(RERUN_DELAY_MS);
    }
    
    private Set selectKeysToSend() {
        Set explicit = _facade.getExplicitSendKeys();
        Set toSend = new HashSet(MAX_SEND_PER_RUN);
        if (explicit.size() < MAX_SEND_PER_RUN) {
            toSend.addAll(explicit);
            _facade.removeFromExplicitSend(explicit);
            
            Set passive = _facade.getPassivelySendKeys();
            Set psend = new HashSet(passive.size());
            for (Iterator iter = passive.iterator(); iter.hasNext(); ) {
                if (toSend.size() >= MAX_SEND_PER_RUN) break;
                Hash key = (Hash)iter.next();
                toSend.add(key);
                psend.add(key);
            }
            _facade.removeFromPassiveSend(psend);
        } else {
            for (Iterator iter = explicit.iterator(); iter.hasNext(); ) {
                if (toSend.size() >= MAX_SEND_PER_RUN) break;
                Hash key = (Hash)iter.next();
                toSend.add(key);
            }
            _facade.removeFromExplicitSend(toSend);
        }
        
        return toSend;
    }
}
