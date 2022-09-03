package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.router.OutNetMessage;
import net.i2p.util.Log;

/**
 * Ask a connected peer for his RI.
 * Modified from SingleSearchJob.
 *
 * Mainly for older routers. As of 0.9.55, transports will
 * periodically send their RI.
 * Some old routers may not respond or may send DSRM,
 * e.g. if hidden (and i2pd?)
 *
 * @since 0.9.56
 */
class DirectLookupJob extends FloodOnlySearchJob {
    private OutNetMessage _onm;
    private final RouterInfo _oldRI;

    private static final int TIMEOUT = 8*1000;

    /**
     *  @param peer for Router Info only
     */
    public DirectLookupJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade, Hash peer, RouterInfo oldRI, Job onFind, Job onFail) {
        super(ctx, facade, peer, onFind, onFail, TIMEOUT);
        _oldRI = oldRI;
    }

    @Override
    public String getName() { return "NetDb direct RI request"; }

    @Override
    public boolean shouldProcessDSRM() { return false; } // don't loop

    @Override
    public void runJob() {
        RouterContext ctx = getContext();
        _onm = ctx.messageRegistry().registerPending(_replySelector, _onReply, _onTimeout);
        DatabaseLookupMessage dlm = new DatabaseLookupMessage(ctx, true);
        dlm.setFrom(ctx.routerHash());
        long exp = ctx.clock().now() + 5*1000;
        dlm.setMessageExpiration(exp);
        dlm.setSearchKey(_key);
        dlm.setSearchType(DatabaseLookupMessage.Type.RI);
        OutNetMessage m = new OutNetMessage(ctx, dlm, exp,
                                            OutNetMessage.PRIORITY_MY_NETDB_LOOKUP, _oldRI);
        ctx.commSystem().processMessage(m);
        _lookupsRemaining.set(1);
    }

    @Override
    void failed() {
        RouterContext ctx = getContext();
        ctx.messageRegistry().unregisterPending(_onm);
        ctx.profileManager().dbLookupFailed(_key);
        _facade.complete(_key);
        for (Job j : _onFailed) {
            ctx.jobQueue().addJob(j);
        }
    }

    @Override
    void success() {
        // don't give him any credit
        //getContext().profileManager().dbLookupSuccessful(_to, System.currentTimeMillis()-_created);
        _facade.complete(_key);
        RouterContext ctx = getContext();
        for (Job j : _onFind) {
            ctx.jobQueue().addJob(j);
        }
    }
}
