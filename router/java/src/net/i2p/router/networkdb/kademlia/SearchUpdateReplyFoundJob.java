package net.i2p.router.networkdb.kademlia;

import java.util.Date;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.ProfileManager;
import net.i2p.router.ReplyJob;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

/**
 * Called after a match to a db search is found
 *
 */
class SearchUpdateReplyFoundJob extends JobImpl implements ReplyJob {
    private Log _log;
    private I2NPMessage _message;
    private Hash _peer;
    private SearchState _state;
    private KademliaNetworkDatabaseFacade _facade;
    private SearchJob _job;
    
    public SearchUpdateReplyFoundJob(RouterContext context, RouterInfo peer, SearchState state, KademliaNetworkDatabaseFacade facade, SearchJob job) {
        super(context);
        _log = context.logManager().getLog(SearchUpdateReplyFoundJob.class);
        _peer = peer.getIdentity().getHash();
        _state = state;
        _facade = facade;
        _job = job;
    }
    
    public String getName() { return "Update Reply Found for Kademlia Search"; }
    public void runJob() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Reply from " + _peer + " with message " + _message.getClass().getName());
        
        if (_message instanceof DatabaseStoreMessage) {
            long timeToReply = _state.dataFound(_peer);
            
            DatabaseStoreMessage msg = (DatabaseStoreMessage)_message;
            if (msg.getValueType() == DatabaseStoreMessage.KEY_TYPE_LEASESET) {
                _facade.store(msg.getKey(), msg.getLeaseSet());
            } else if (msg.getValueType() == DatabaseStoreMessage.KEY_TYPE_ROUTERINFO) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": dbStore received on search containing router " + msg.getKey() + " with publishDate of " + new Date(msg.getRouterInfo().getPublished()));
                _facade.store(msg.getKey(), msg.getRouterInfo());
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error(getJobId() + ": Unknown db store type?!@ " + msg.getValueType());
            }
            
            _context.profileManager().dbLookupSuccessful(_peer, timeToReply);
        } else if (_message instanceof DatabaseSearchReplyMessage) {
            _job.replyFound((DatabaseSearchReplyMessage)_message, _peer);
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": WTF, reply job matched a strange message: " + _message);
            return;
        }
        
        _job.searchNext();
    }
    
    public void setMessage(I2NPMessage message) { _message = message; }
}
