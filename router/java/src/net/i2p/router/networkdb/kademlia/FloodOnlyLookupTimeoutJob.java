package net.i2p.router.networkdb.kademlia;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  This is the timeout for the whole search.
 */
class FloodOnlyLookupTimeoutJob extends JobImpl {
    private final FloodSearchJob _search;

    public FloodOnlyLookupTimeoutJob(RouterContext ctx, FloodSearchJob job) {
        super(ctx);
        _search = job;
    }

    public void runJob() {
        Log log = getContext().logManager().getLog(getClass());
        if (log.shouldLog(Log.INFO))
            log.info(_search.getJobId() + ": search timed out");
        _search.failed();
    }

    public String getName() { return "NetDb flood search timeout"; }
}
