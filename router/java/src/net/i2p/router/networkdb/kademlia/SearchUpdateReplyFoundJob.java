package net.i2p.router.networkdb.kademlia;

import java.util.Date;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

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
    private TunnelInfo _outTunnel;
    private TunnelInfo _replyTunnel;
    private boolean _isFloodfillPeer;
    private long _sentOn;
    
    public SearchUpdateReplyFoundJob(RouterContext context, RouterInfo peer, 
                                     SearchState state, KademliaNetworkDatabaseFacade facade, 
                                     SearchJob job) {
        this(context, peer, state, facade, job, null, null);
    }
    public SearchUpdateReplyFoundJob(RouterContext context, RouterInfo peer, 
                                     SearchState state, KademliaNetworkDatabaseFacade facade, 
                                     SearchJob job, TunnelInfo outTunnel, TunnelInfo replyTunnel) {
        super(context);
        _log = context.logManager().getLog(SearchUpdateReplyFoundJob.class);
        _peer = peer.getIdentity().getHash();
        _isFloodfillPeer = FloodfillNetworkDatabaseFacade.isFloodfill(peer);
        _state = state;
        _facade = facade;
        _job = job;
        _outTunnel = outTunnel;
        _replyTunnel = replyTunnel;
        _sentOn = System.currentTimeMillis();
    }
    
    public String getName() { return "Update Reply Found for Kademlia Search"; }
    public void runJob() {
        if (_isFloodfillPeer)
            _job.decrementOutstandingFloodfillSearches();

        I2NPMessage message = _message;
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Reply from " + _peer.toBase64() 
                      + " with message " + message.getClass().getName());
        
        long howLong = System.currentTimeMillis() - _sentOn;
        // assume requests are 1KB (they're almost always much smaller, but tunnels have a fixed size)
        int msgSize = 1024;
        
        if (_replyTunnel != null) {
            for (int i = 0; i < _replyTunnel.getLength(); i++)
                getContext().profileManager().tunnelDataPushed(_replyTunnel.getPeer(i), howLong, msgSize);
            _replyTunnel.incrementVerifiedBytesTransferred(msgSize);
        }
        if (_outTunnel != null) {
            for (int i = 0; i < _outTunnel.getLength(); i++)
                getContext().profileManager().tunnelDataPushed(_outTunnel.getPeer(i), howLong, msgSize);
            _outTunnel.incrementVerifiedBytesTransferred(msgSize);
        }
        
        if (message instanceof DatabaseStoreMessage) {
            long timeToReply = _state.dataFound(_peer);
            
            DatabaseStoreMessage msg = (DatabaseStoreMessage)message;
            DatabaseEntry entry = msg.getEntry();
            if (entry.getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
                try {
                    _facade.store(msg.getKey(), (LeaseSet) entry);
                    getContext().profileManager().dbLookupSuccessful(_peer, timeToReply);
                } catch (IllegalArgumentException iae) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.warn("Peer " + _peer + " sent us an invalid leaseSet: " + iae.getMessage());
                    getContext().profileManager().dbLookupReply(_peer, 0, 0, 1, 0, timeToReply);
                }
            } else if (entry.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": dbStore received on search containing router " 
                              + msg.getKey() + " with publishDate of " 
                              + new Date(entry.getDate()));
                try {
                    _facade.store(msg.getKey(), (RouterInfo) entry);
                    getContext().profileManager().dbLookupSuccessful(_peer, timeToReply);
                } catch (IllegalArgumentException iae) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.warn("Peer " + _peer + " sent us an invalid routerInfo: " + iae.getMessage());
                    getContext().profileManager().dbLookupReply(_peer, 0, 0, 1, 0, timeToReply);
                }
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error(getJobId() + ": Unknown db store type?!@ " + entry.getType());
            }
        } else if (message instanceof DatabaseSearchReplyMessage) {
            _job.replyFound((DatabaseSearchReplyMessage)message, _peer);
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": WTF, reply job matched a strange message: " + message);
            return;
        }
        
        _job.searchNext();
    }
    
    public void setMessage(I2NPMessage message) { _message = message; }
}
