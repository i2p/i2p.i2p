package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;

/**
 * Ask a single peer for a single key.
 * This isn't really a flood-only search job at all, but we extend
 * FloodOnlySearchJob so we can use the same selectors, etc.
 *
 * Different from SingleSearchJob in that we tell the search to add it on success.
 *
 * @since 0.8.9
 */
class IterativeFollowupJob extends SingleSearchJob {
    private final IterativeSearchJob _search;

    public IterativeFollowupJob(RouterContext ctx, Hash key, Hash to, IterativeSearchJob search) {
        super(ctx, key, to);
        _search = search;
    }

    @Override
    public String getName() { return "Iterative search key from DSRM"; }

    @Override
    void success() {
        _search.newPeerToTry(_key);
        super.success();
    }
}
