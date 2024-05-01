package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

class SearchReplyJob extends JobImpl {
    private final DatabaseSearchReplyMessage _msg;
    private final Log _log;
    /** 
     * Peer who we think sent us the reply.  Note: could be spoofed!  If the
     * attacker knew we were searching for a particular key from a 
     * particular peer, they could send us some searchReply messages with
     * bad values, trying to get us to consider that peer unreliable.  
     * Potential fixes include either authenticated 'from' address or use a
     * nonce in the search + searchReply (and check for it in the selector).
     *
     */
    private final Hash _peer;
    private int _seenPeers;
    private int _newPeers;
    private int _duplicatePeers;
    private final long _duration;
    private final SearchJob _searchJob;

    public SearchReplyJob(RouterContext enclosingContext, SearchJob job, DatabaseSearchReplyMessage message, Hash peer, long duration) {
        super(enclosingContext);
        _log = enclosingContext.logManager().getLog(getClass());
        _searchJob = job;
        _msg = message;
        _peer = peer;
        if (duration > 0)
            _duration = duration;
        else
            _duration = 0;
    }

    public String getName() { return "Process Reply for Kademlia Search"; }

    public void runJob() {
        int count = Math.min(_msg.getNumReplies(), 2 * SingleLookupJob.MAX_TO_FOLLOW);
        for (int i = 0; i < count; i++) {
            processPeer(i);
        }

        if (count == 0 && _log.shouldDebug())
            _log.debug(getJobId() + ": dbSearchReply received with no routers referenced");

        // either they didn't tell us anything new or we have verified
        // (or failed to verify) all of them.  we're done
        getContext().profileManager().dbLookupReply(_peer, _newPeers, _seenPeers, 
                                                    0, _duplicatePeers, _duration);
        if (_newPeers > 0)
            _searchJob.newPeersFound(_newPeers);
    }

    private void processPeer(int curIndex) {
            Hash peer = _msg.getReply(curIndex);

            boolean shouldAdd = false;

            RouterInfo info = getContext().netDb().lookupRouterInfoLocally(peer);
            if (info == null) {
                // if the peer is giving us lots of bad peer references, 
                // dont try to fetch them.

                boolean sendsBadInfo = getContext().profileOrganizer().peerSendsBadReplies(_peer);
                if (!sendsBadInfo) {
                    // we don't need to search for everthing we're given here - only ones that
                    // are next in our search path...
                    // note: no need to think about banlisted targets in the netdb search, given
                    //       the floodfill's behavior
                    // This keeps us from continually chasing blocklisted floodfills
                    if (getContext().banlist().isBanlisted(peer)) {
                    //    if (_log.shouldLog(Log.INFO))
                    //        _log.info("Not looking for a banlisted peer...");
                    //    getContext().statManager().addRateData("netDb.searchReplyValidationSkipped", 1, 0);
                    } else {
                        //getContext().netDb().lookupRouterInfo(peer, new ReplyVerifiedJob(getContext(), peer), new ReplyNotVerifiedJob(getContext(), peer), _timeoutMs);
                        //_repliesPendingVerification++;
                        shouldAdd = true;
                    }
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Peer " + _peer.toBase64() + " sends us bad replies, so not verifying " + peer.toBase64());
                    getContext().statManager().addRateData("netDb.searchReplyValidationSkipped", 1);
                }
            }

            if (_searchJob.wasAttempted(peer)) {
                _duplicatePeers++;
            } 
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": dbSearchReply received on search referencing router " + peer +
                           " already known? " + (info != null));
            if (shouldAdd) {
                if (_searchJob.add(peer))
                    _newPeers++;
                else
                    _seenPeers++;
            }
    }
}
