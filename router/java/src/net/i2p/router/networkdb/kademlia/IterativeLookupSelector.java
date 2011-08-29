package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.MessageSelector;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Slightly modified version of FloodOnlyLookupSelector,
 *  plus it incorporates the functions of SingleLookupJob inline.
 *  Always follows the DSRM entries.
 *
 *  @since 0.8.9
 */
class IterativeLookupSelector implements MessageSelector {
    private final RouterContext _context;
    private final IterativeSearchJob _search;
    private boolean _matchFound;
    private final Log _log;

    public IterativeLookupSelector(RouterContext ctx, IterativeSearchJob search) {
        _context = ctx;
        _search = search;
        _log = ctx.logManager().getLog(getClass());
    }

    public boolean continueMatching() { 
        // don't use remaining searches count
        return (!_matchFound) && _context.clock().now() < getExpiration(); 
    }

    public long getExpiration() { return (_matchFound ? -1 : _search.getExpiration()); }

    /**
     *  This only returns true for DSMs, not for DSRMs.
     */
    public boolean isMatch(I2NPMessage message) {
        if (message == null) return false;
        if (message instanceof DatabaseStoreMessage) {
            DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
            // is it worth making sure the reply came in on the right tunnel?
            if (_search.getKey().equals(dsm.getKey())) {
                _matchFound = true;
                return true;
            }
        } else if (message instanceof DatabaseSearchReplyMessage) {
            DatabaseSearchReplyMessage dsrm = (DatabaseSearchReplyMessage)message;
            if (_search.getKey().equals(dsrm.getSearchKey())) {

                // TODO - dsrm.getFromHash() can't be trusted - check against the list of
                // those we sent the search to in _search ?
                Hash from = dsrm.getFromHash();

                // Moved from FloodOnlyLookupMatchJob so it is called for all replies
                // rather than just the last one
                // Got a netDb reply pointing us at other floodfills...
                if (_log.shouldLog(Log.INFO))
                    _log.info(_search.getJobId() + ": Processing DSRM via IterativeLookupJobs, apparently from " + from);

                // Chase the hashes from the reply
                // 255 max, see comments in SingleLookupJob
                int limit = Math.min(dsrm.getNumReplies(), SingleLookupJob.MAX_TO_FOLLOW);
                int newPeers = 0;
                int oldPeers = 0;
                int invalidPeers = 0;
                for (int i = 0; i < limit; i++) {
                    Hash peer = dsrm.getReply(i);
                    if (peer.equals(_context.routerHash())) {
                        // us
                        oldPeers++;
                        continue;
                    }
                    if (peer.equals(from)) {
                        // wtf
                        invalidPeers++;
                        continue;
                    }
                    RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
                    if (ri == null) {
                        // get the RI from the peer that told us about it
                        _context.jobQueue().addJob(new IterativeFollowupJob(_context, peer, from, _search));
                        newPeers++;
                    } else if (ri.getPublished() < _context.clock().now() - 60*60*1000 ||
                             !FloodfillNetworkDatabaseFacade.isFloodfill(ri)) {
                        // get an updated RI from the (now ff?) peer
                        _context.jobQueue().addJob(new IterativeFollowupJob(_context, peer, peer, _search));
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
                long timeSent = _search.timeSent(from);
                // assume 0 dup
                if (timeSent > 0) {
                    _context.profileManager().dbLookupReply(from,  newPeers, oldPeers, invalidPeers, 0,
                                                            _context.clock().now() - timeSent);
                }

                _search.failed(dsrm.getFromHash(), false);
                // fall through, always return false, we do not wish the match job to be called
            }
        }
        return false;
    }   
}
