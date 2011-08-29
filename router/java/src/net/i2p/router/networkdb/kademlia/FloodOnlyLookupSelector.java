package net.i2p.router.networkdb.kademlia;

import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.MessageSelector;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Mostly replaced by IterativeLookupSelector
 */
class FloodOnlyLookupSelector implements MessageSelector {
    private final RouterContext _context;
    private final FloodOnlySearchJob _search;
    private boolean _matchFound;
    private final Log _log;

    public FloodOnlyLookupSelector(RouterContext ctx, FloodOnlySearchJob search) {
        _context = ctx;
        _search = search;
        _log = ctx.logManager().getLog(getClass());
    }

    public boolean continueMatching() { 
        return _search.getLookupsRemaining() > 0 && !_matchFound && _context.clock().now() < getExpiration(); 
    }

    public long getExpiration() { return (_matchFound ? -1 : _search.getExpiration()); }

    public boolean isMatch(I2NPMessage message) {
        if (message == null) return false;
        if (message instanceof DatabaseStoreMessage) {
            DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
            // is it worth making sure the reply came in on the right tunnel?
            if (_search.getKey().equals(dsm.getKey())) {
                _search.decrementRemaining();
                _matchFound = true;
                return true;
            }
        } else if (message instanceof DatabaseSearchReplyMessage) {
            DatabaseSearchReplyMessage dsrm = (DatabaseSearchReplyMessage)message;
            if (_search.getKey().equals(dsrm.getSearchKey())) {

                // TODO - dsrm.getFromHash() can't be trusted - check against the list of
                // those we sent the search to in _search ?

                // assume 0 new, all old, 0 invalid, 0 dup
                _context.profileManager().dbLookupReply(dsrm.getFromHash(),  0, dsrm.getNumReplies(), 0, 0,
                                                        System.currentTimeMillis()-_search.getCreated());

                // Moved from FloodOnlyLookupMatchJob so it is called for all replies
                // rather than just the last one
                // Got a netDb reply pointing us at other floodfills...
                // Only process if we don't know enough floodfills or are starting up
                if (_search.shouldProcessDSRM()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(_search.getJobId() + ": Processing DSRM via SingleLookupJob, apparently from " + dsrm.getFromHash());
                    // Chase the hashes from the reply
                    _context.jobQueue().addJob(new SingleLookupJob(_context, dsrm));
                } else if (_log.shouldLog(Log.INFO)) {
                    int remaining = _search.getLookupsRemaining();
                    _log.info(_search.getJobId() + ": got a DSRM apparently from " + dsrm.getFromHash() + " when we were looking for " 
                              + _search.getKey() + ", with " + remaining + " outstanding searches");
                }

                // if no more left, time to fail
                int remaining = _search.decrementRemaining(dsrm.getFromHash());
                return remaining <= 0;
            }
        }
        return false;
    }   
}
