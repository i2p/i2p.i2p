package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.DataStructure;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;

import net.i2p.util.Log;
import net.i2p.util.Clock;

import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;

class DataPublisherJob extends JobImpl {
    private final static Log _log = new Log(DataPublisherJob.class);
    private KademliaNetworkDatabaseFacade _facade;
    private final static long RERUN_DELAY_MS = 30*1000;
    private final static int MAX_SEND_PER_RUN = 5; // publish no more than 5 at a time
    private final static long STORE_TIMEOUT = 60*1000; // give 'er a minute to send the data
    
    public DataPublisherJob(KademliaNetworkDatabaseFacade facade) {
	super();
	_facade = facade;
	getTiming().setStartAfter(Clock.getInstance().now()+RERUN_DELAY_MS); // not immediate...
    }
    
    public String getName() { return "Data Publisher Job"; }
    public void runJob() { 
	Set toSend = selectKeysToSend();
	_log.info("Keys being published in this timeslice: " + toSend);
	for (Iterator iter = toSend.iterator(); iter.hasNext(); ) {
	    Hash key = (Hash)iter.next();
	    DataStructure data = _facade.getDataStore().get(key);
	    if (data == null) {
		_log.warn("Trying to send a key we dont have? " + key);
		continue;
	    }
	    if (data instanceof LeaseSet) {
		LeaseSet ls = (LeaseSet)data;
		if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
		    _log.warn("Not publishing a lease that isn't current - " + key, new Exception("Publish expired lease?"));
		}
	    }
	    StoreJob store = new StoreJob(_facade, key, data, null, null, STORE_TIMEOUT);
	    JobQueue.getInstance().addJob(store);
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
