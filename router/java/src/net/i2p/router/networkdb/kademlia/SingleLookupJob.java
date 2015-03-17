package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
//import net.i2p.util.Log;

/**
 * Ask the peer who sent us the DSRM for the RouterInfos...
 *
 * ... but If we have the routerInfo already, try to refetch it from that router itself,
 * (if the info is old or we don't think it is floodfill)
 * which will help us establish that router as a good floodfill and speed our
 * integration into the network.
 *
 * A simple version of SearchReplyJob in SearchJob.java.
 * Skip the profile updates - this should be rare.
 *
 */
class SingleLookupJob extends JobImpl {
    //private final Log _log;
    private final DatabaseSearchReplyMessage _dsrm;

    /**
     *  I2NP spec allows 255, max actually sent (in ../HDLMJ) is 3,
     *  so just to prevent trouble, we don't want to queue 255 jobs at once
     */
    public static final int MAX_TO_FOLLOW = 8;

    public SingleLookupJob(RouterContext ctx, DatabaseSearchReplyMessage dsrm) {
        super(ctx);
        //_log = ctx.logManager().getLog(getClass());
        _dsrm = dsrm;
    }

    public void runJob() { 
        Hash from = _dsrm.getFromHash();
        int limit = Math.min(_dsrm.getNumReplies(), MAX_TO_FOLLOW);
        for (int i = 0; i < limit; i++) {
            Hash peer = _dsrm.getReply(i);
            if (peer.equals(getContext().routerHash())) // us
                continue;
            if (peer.equals(from)) // wtf
                continue;
            RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(peer);
            if (ri == null)
                getContext().jobQueue().addJob(new SingleSearchJob(getContext(), peer, from));
            else if (ri.getPublished() < getContext().clock().now() - 60*60*1000 ||
                     !FloodfillNetworkDatabaseFacade.isFloodfill(ri))
                getContext().jobQueue().addJob(new SingleSearchJob(getContext(), peer, peer));
        }
    }

    public String getName() { return "NetDb process DSRM"; }
}
