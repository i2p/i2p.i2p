package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.MessageSelector;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Slightly modified version of FloodOnlyLookupSelector.
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
                // Got a netDb reply pointing us at other floodfills...
                if (_log.shouldLog(Log.INFO)) {
                    Hash from = dsrm.getFromHash();
                    _log.info(_search.getJobId() + ": Processing DSRM via IterativeLookupJob, apparently from " + from);
                }

                // was inline, now in IterativeLookupJob due to deadlocks
                _context.jobQueue().addJob(new IterativeLookupJob(_context, dsrm, _search));

                // fall through, always return false, we do not wish the match job to be called
            }
        }
        return false;
    }   
}
