package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.router.InNetMessage;
import net.i2p.router.InNetMessagePool;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Pull a message off the inbound net message pool and begin its processing. 
 * This job requeues itself on completion
 *
 */
public class ProcessInboundNetMessageJob extends JobImpl {
    private static Log _log = new Log(ProcessInboundNetMessageJob.class);
    
    public ProcessInboundNetMessageJob() { }
    
    public String getName() { return "Check For Inbound Network Message"; }
    
    public void runJob() {
	// start up the network comm system
	
	if (InNetMessagePool.getInstance().getCount() > 0) {
	    InNetMessage inMessage = InNetMessagePool.getInstance().getNext();
	    processMessage(inMessage);
	    // there are messages, no need to delay as there's real work to do
	} else {
	    getTiming().setStartAfter(Clock.getInstance().now()+1000);
	}
	
	JobQueue.getInstance().addJob(this); 
    }
    
    private void processMessage(InNetMessage message) {
	_log.debug("Received message from " + message.getFromRouter() + "/" + message.getFromRouterHash() + " containing : " + message.getMessage());
    }
}
