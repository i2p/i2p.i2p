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
 * Check to see the message is a reply from the peer regarding the current
 * search
 *
 */
class SearchMessageSelector implements MessageSelector {
    private Log _log;
    private RouterContext _context;
    private static int __searchSelectorId = 0;
    private Hash _peer;
    private boolean _found;
    private int _id;
    private long _exp;
    private SearchState _state;
    
    public SearchMessageSelector(RouterContext context, RouterInfo peer, long expiration, SearchState state) {
        _context = context;
        _log = context.logManager().getLog(SearchMessageSelector.class);
        _peer = peer.getIdentity().getHash();
        _found = false;
        _exp = expiration;
        _state = state;
        _id = ++__searchSelectorId;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("[" + _id + "] Created: " + toString());
    }
    
    @Override
    public String toString() { 
        return "Search selector [" + _id + "] looking for a reply from " + _peer 
               + " with regards to " + _state.getTarget(); 
    }
    
    public boolean continueMatching() {
        boolean expired = _context.clock().now() > _exp;
        if (expired) return false;
        
        // so we dont drop outstanding replies after receiving the value
        // > 1 to account for the 'current' match
        if (_state.getPending().size() > 1) 
            return true;
        
        if (_found) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("[" + _id + "] Dont continue matching! looking for a reply from " 
                           + _peer + " with regards to " + _state.getTarget());
            return false;
        } else {
            return true;
        }
    }
    public long getExpiration() { return _exp; }
    public boolean isMatch(I2NPMessage message) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("[" + _id + "] isMatch("+message.getClass().getName() 
                       + ") [want dbStore or dbSearchReply from " + _peer 
                       + " for " + _state.getTarget() + "]");
        if (message instanceof DatabaseStoreMessage) {
            DatabaseStoreMessage msg = (DatabaseStoreMessage)message;
            if (msg.getKey().equals(_state.getTarget())) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("[" + _id + "] Was a DBStore of the key we're looking for.  " 
                               + "May not have been from who we're checking against though, "
                               + "but DBStore doesn't include that info");
                _found = true;
                return true;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("[" + _id + "] DBStore of a key we're not looking for");
                return false;
            }
        } else if (message instanceof DatabaseSearchReplyMessage) {
            DatabaseSearchReplyMessage msg = (DatabaseSearchReplyMessage)message;
            if (_peer.equals(msg.getFromHash())) {
                if (msg.getSearchKey().equals(_state.getTarget())) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("[" + _id + "] Was a DBSearchReply from who we're "
                                   + "checking with for a key we're looking for");
                    _found = true;
                    return true;
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("[" + _id + "] Was a DBSearchReply from who we're checking "
                                   + "with but NOT for the key we're looking for");
                    return false;
                }
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("[" + _id + "] DBSearchReply from someone we are not checking with [" 
                               + msg.getFromHash() + ", not " + _state.getTarget() + "]");
                return false;
            }
        } else {
            //_log.debug("Not a DbStore or DbSearchReply");
            return false;
        }
    }
}
