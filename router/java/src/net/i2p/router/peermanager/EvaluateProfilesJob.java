package net.i2p.router.peermanager;

import java.util.Date;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.JobImpl;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Run across all of the profiles, coallescing the stats and reorganizing them
 * into appropriate groups.  The stat coallesce must be run at least once a minute, 
 * so if the group reorg wants to get changed, this may want to be split into two
 * jobs.
 *
 */
class EvaluateProfilesJob extends JobImpl {
    private final static Log _log = new Log(EvaluateProfilesJob.class);
    
    public EvaluateProfilesJob() {}
    
    public String getName() { return "Evaluate peer profiles"; }
    public void runJob() {
	try {
	    long start = Clock.getInstance().now();
	    Set allPeers = ProfileOrganizer.getInstance().selectAllPeers(); 
	    long afterSelect = Clock.getInstance().now();
	    for (Iterator iter = allPeers.iterator(); iter.hasNext(); ) {
		Hash peer = (Hash)iter.next();
		PeerProfile profile = ProfileOrganizer.getInstance().getProfile(peer);
		if (profile != null)
		    profile.coallesceStats();
	    }
	    long afterCoallesce = Clock.getInstance().now();
	    ProfileOrganizer.getInstance().reorganize();
	    long afterReorganize = Clock.getInstance().now();

	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("Profiles coallesced and reorganized.  total: " + allPeers.size() + ", selectAll: " + (afterSelect-start) + "ms, coallesce: " + (afterCoallesce-afterSelect) + "ms, reorganize: " + (afterReorganize-afterSelect));
	} catch (Throwable t) {
	    _log.log(Log.CRIT, "Error evaluating profiles", t);
	} finally {
	    requeue(30*1000);
	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("Requeued for " + new Date(getTiming().getStartAfter()));
	}
    }
}
