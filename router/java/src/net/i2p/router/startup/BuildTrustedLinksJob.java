package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.util.Log;

public class BuildTrustedLinksJob extends JobImpl {
    private static Log _log = new Log(BuildTrustedLinksJob.class);
    private Job _next;
    
    public BuildTrustedLinksJob(Job next) {
	_next = next;
    }
    
    public String getName() { return "Build Trusted Links"; }
    
    public void runJob() {
	// create trusted links with peers
	
	try { Thread.sleep(5000); } catch (InterruptedException ie) {}
	
	JobQueue.getInstance().addJob(_next);
    }
}
