package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.OutNetMessage;
import net.i2p.router.OutNetMessagePool;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Fetch an outbound message from the outbound pool, check its validity, get a bid
 * from transports, and queue it for delivery on the "winning" transport
 *
 */
public class FetchOutNetMessageJob extends JobImpl { 
    private static Log _log = new Log(FetchOutNetMessageJob.class);
    private CommSystemFacadeImpl _facade;
    
    public FetchOutNetMessageJob(CommSystemFacadeImpl facade) {
	super();
	_facade = facade;
    }
    
    public String getName() { return "Check For Pending Outbound Network Message"; }
    public void runJob() {
	OutNetMessage msg = OutNetMessagePool.getInstance().getNext();
	if (msg != null) {
	    processMessage(msg);
	} else {
	    _log.debug("No new outbound messages");
	    getTiming().setStartAfter(Clock.getInstance().now()+1000);
	}
	
	JobQueue.getInstance().addJob(this);
	//JobQueue.getInstance().addJob(new FetchOutNetMessageJob(_facade));
    }
    
    private void processMessage(OutNetMessage msg) {	
	JobQueue.getInstance().addJob(new GetBidsJob(_facade, msg));
    }
}
