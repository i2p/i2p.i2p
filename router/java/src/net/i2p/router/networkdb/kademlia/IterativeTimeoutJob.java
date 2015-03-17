package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;

/**
 *  This is the timeout for a single lookup, not for the whole search.
 *  It is called every time, it is not cancelled after the search succeeds
 *  or the peer replies with a DSRM. We rely on ISJ.failed(peer) to
 *  decide whether or not it actually timed out.
 *
 *  @since 0.8.9
 */
class IterativeTimeoutJob extends JobImpl {
    private final IterativeSearchJob _search;
    private final Hash _peer;

    public IterativeTimeoutJob(RouterContext ctx, Hash peer, IterativeSearchJob job) {
        super(ctx);
        _peer = peer;
        _search = job;
    }

    public void runJob() {
        _search.failed(_peer, true);
    }

    public String getName() { return "Iterative search timeout"; }
}
