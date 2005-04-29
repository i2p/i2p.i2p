package net.i2p.router.peermanager;

import java.util.Date;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Run across all of the profiles, coallescing the stats and reorganizing them
 * into appropriate groups.  The stat coalesce must be run at least once a minute,
 * so if the group reorg wants to get changed, this may want to be split into two
 * jobs.
 *
 */
class EvaluateProfilesJob extends JobImpl {
    private Log _log;
    
    public EvaluateProfilesJob(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(EvaluateProfilesJob.class);
    }
    
    public String getName() { return "Evaluate peer profiles"; }
    public void runJob() {
        try {
            getContext().profileOrganizer().reorganize(true);
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Error evaluating profiles", t);
        } finally {
            requeue(30*1000);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Requeued for " + new Date(getTiming().getStartAfter()));
        }
    }
}
