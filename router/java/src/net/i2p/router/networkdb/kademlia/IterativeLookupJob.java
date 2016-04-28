package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.util.Log;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;

/**
 * Ask the peer who sent us the DSRM for the RouterInfos...
 *
 * ... but If we have the routerInfo already, try to refetch it from that router itself,
 * (if the info is old or we don't think it is floodfill)
 * which will help us establish that router as a good floodfill and speed our
 * integration into the network.
 *
 * Very similar to SingleLookupJob.
 * This was all in IterativeLookupSelector.isMatch() but it caused deadlocks
 * with OutboundMessageRegistry.getOriginalMessages()
 * at both _search.newPeerToTry() and _search.failed().
 *
 * @since 0.8.9
 */
class IterativeLookupJob extends JobImpl {
    private final Log _log;
    private final DatabaseSearchReplyMessage _dsrm;
    private final IterativeSearchJob _search;

    public IterativeLookupJob(RouterContext ctx, DatabaseSearchReplyMessage dsrm, IterativeSearchJob search) {
        super(ctx);
        _log = ctx.logManager().getLog(IterativeLookupJob.class);
        _dsrm = dsrm;
        _search = search;
    }

    public void runJob() { 
                Hash from = _dsrm.getFromHash();
                // dsrm.getFromHash() can't be trusted - check against the list of
                // those we sent the search to in _search
                if (!_search.wasQueried(from)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(_search.getJobId() + ": ILJ DSRM from unqueried peer: " + _dsrm);
                    return;
                }

                // Chase the hashes from the reply
                // 255 max, see comments in SingleLookupJob
                int limit = Math.min(_dsrm.getNumReplies(), SingleLookupJob.MAX_TO_FOLLOW);
                int newPeers = 0;
                int oldPeers = 0;
                int invalidPeers = 0;
                for (int i = 0; i < limit; i++) {
                    Hash peer = _dsrm.getReply(i);
                    if (peer.equals(getContext().routerHash())) {
                        // us
                        oldPeers++;
                        continue;
                    }
                    if (peer.equals(from)) {
                        // unusual
                        invalidPeers++;
                        continue;
                    }
                    if (getContext().banlist().isBanlistedForever(peer)) {
                        oldPeers++;
                        continue;
                    }
                    RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(peer);
                    if (ri == null) {
                        // Take it on faith that it's ff to speed things up, we don't need the RI
                        // to query it.
                        // Zero-hop outbound tunnel will be failed in ISJ.sendQuery()
                        _search.newPeerToTry(peer);
                        if (_search.getFromHash() == null) {
                            // get the RI from the peer that told us about it
                            // Only if original search used expl. tunnels
                            getContext().jobQueue().addJob(new SingleSearchJob(getContext(), peer, from));
                        } else {
                            // don't look it up as we don't have a good way to do it securely...
                            // add to expl. queue to look up later? no, probably not safe either
                        }
                        newPeers++;
                    } else if (ri.getPublished() < getContext().clock().now() - 60*60*1000 ||
                             !FloodfillNetworkDatabaseFacade.isFloodfill(ri)) {
                        // get an updated RI from the (now ff?) peer
                        // Only if original search used expl. tunnels
                        if (_search.getFromHash() == null) {
                            getContext().jobQueue().addJob(new IterativeFollowupJob(getContext(), peer, peer, _search));
                        } else {
                            // for now, don't believe him, don't call newPeerToTry()
                            // is IFJ safe if we use the client tunnels?
                        }
                        oldPeers++;
                    } else {
                        // add it to the sorted queue
                        // this will check if we have already tried it
                        // should we really add? if we know about it but skipped it,
                        // it was for some reason?
                        _search.newPeerToTry(peer);
                        oldPeers++;
                    }
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info(_search.getJobId() + ": ILJ DSRM processed " + newPeers + '/' + oldPeers + '/' + invalidPeers + " new/old/invalid hashes");
                long timeSent = _search.timeSent(from);
                // assume 0 dup
                if (timeSent > 0) {
                    getContext().profileManager().dbLookupReply(from,  newPeers, oldPeers, invalidPeers, 0,
                                                            getContext().clock().now() - timeSent);
                }

                _search.failed(_dsrm.getFromHash(), false);
    }

    public String getName() { return "NetDb process DSRM"; }
}
