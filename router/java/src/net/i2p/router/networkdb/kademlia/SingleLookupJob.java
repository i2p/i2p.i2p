package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Ask the peer who sent us the DSRM for the RouterInfos.
 *
 * If we have the routerInfo already, try to refetch it from that router itself,
 * if we aren't already connected to that router,
 * which will help us establish that router as a good floodfill and speed our
 * integration into the network.
 *
 * A simple version of SearchReplyJob in SearchJob.java.
 * Skip the profile updates - this should be rare.
 *
 */
class SingleLookupJob extends JobImpl {
    private Log _log;
    private DatabaseSearchReplyMessage _dsrm;
    public SingleLookupJob(RouterContext ctx, DatabaseSearchReplyMessage dsrm) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _dsrm = dsrm;
    }
    public void runJob() { 
        Hash from = _dsrm.getFromHash();
        for (int i = 0; i < _dsrm.getNumReplies(); i++) {
            Hash peer = _dsrm.getReply(i);
            if (peer.equals(getContext().routerHash())) // us
                continue;
            if (getContext().netDb().lookupRouterInfoLocally(peer) == null)
                getContext().jobQueue().addJob(new SingleSearchJob(getContext(), peer, from));
            else if (!getContext().commSystem().isEstablished(peer))
                getContext().jobQueue().addJob(new SingleSearchJob(getContext(), peer, peer));
        }
    }
    public String getName() { return "NetDb process DSRM"; }
}
