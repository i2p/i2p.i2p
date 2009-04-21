package net.i2p.router.networkdb.kademlia;

import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.MessageSelector;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

class FloodOnlyLookupSelector implements MessageSelector {
    private RouterContext _context;
    private FloodOnlySearchJob _search;
    private boolean _matchFound;
    private Log _log;
    public FloodOnlyLookupSelector(RouterContext ctx, FloodOnlySearchJob search) {
        _context = ctx;
        _search = search;
        _log = ctx.logManager().getLog(getClass());
        _matchFound = false;
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
                _search.decrementRemaining(dsrm.getFromHash());
                // assume 0 old, all new, 0 invalid, 0 dup
                _context.profileManager().dbLookupReply(dsrm.getFromHash(),  0, dsrm.getNumReplies(), 0, 0,
                                                        System.currentTimeMillis()-_search.getCreated());
                if (_search.getLookupsRemaining() <= 0)
                    return true; // ok, no more left, so time to fail
                else
                    return false;
            }
        }
        return false;
    }   
}
