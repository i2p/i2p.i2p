package net.i2p.router.networkdb.kademlia;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

class FloodOnlyLookupTimeoutJob extends JobImpl {
    private final FloodSearchJob _search;
    private final Log _log;

    public FloodOnlyLookupTimeoutJob(RouterContext ctx, FloodOnlySearchJob job) {
        super(ctx);
        _search = job;
        _log = ctx.logManager().getLog(getClass());
    }

    public void runJob() {
        if (_log.shouldLog(Log.INFO))
            _log.info(_search.getJobId() + ": search timed out");
        _search.failed();
    }

    public String getName() { return "NetDb flood search (phase 1) timeout"; }
}
