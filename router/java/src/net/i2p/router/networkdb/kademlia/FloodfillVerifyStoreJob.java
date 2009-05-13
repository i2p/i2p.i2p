package net.i2p.router.networkdb.kademlia;

import java.util.Collections;
import java.util.List;

import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * send a netDb lookup to a random floodfill peer - if it is found, great,
 * but if they reply back saying they dont know it, queue up a store of the
 * key to a random floodfill peer again (via FloodfillStoreJob)
 *
 */
public class FloodfillVerifyStoreJob extends JobImpl {
    private Log _log;
    private Hash _key;
    private Hash _target;
    private FloodfillNetworkDatabaseFacade _facade;
    private long _expiration;
    private long _sendTime;
    private long _published;
    private boolean _isRouterInfo;
    
    private static final int VERIFY_TIMEOUT = 10*1000;
    
    public FloodfillVerifyStoreJob(RouterContext ctx, Hash key, long published, boolean isRouterInfo, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _key = key;
        _published = published;
        _isRouterInfo = isRouterInfo;
        _log = ctx.logManager().getLog(getClass());
        _facade = facade;
        // wait 10 seconds before trying to verify the store
        getTiming().setStartAfter(ctx.clock().now() + VERIFY_TIMEOUT);
        getContext().statManager().createRateStat("netDb.floodfillVerifyOK", "How long a floodfill verify takes when it succeeds", "NetworkDatabase", new long[] { 60*60*1000 });
        getContext().statManager().createRateStat("netDb.floodfillVerifyFail", "How long a floodfill verify takes when it fails", "NetworkDatabase", new long[] { 60*60*1000 });
        getContext().statManager().createRateStat("netDb.floodfillVerifyTimeout", "How long a floodfill verify takes when it times out", "NetworkDatabase", new long[] { 60*60*1000 });
    }
    public String getName() { return "Verify netdb store"; }

    /**
     *  Wait 10 seconds, then query a random floodfill for the leaseset or routerinfo
     *  that we just stored to a (hopefully different) floodfill peer.
     *
     *  If it fails (after waiting up to another 10 seconds), resend the data.
     *  If the queried data is older than what we stored, that counts as a fail.
     **/
    public void runJob() { 
        _target = pickTarget();
        if (_target == null) return;
        
        DatabaseLookupMessage lookup = buildLookup();
        if (lookup == null) return;
 
        TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundTunnel();
        if (outTunnel == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No outbound tunnels to verify a store");
            return;
        }
        
        _sendTime = getContext().clock().now();
        _expiration = _sendTime + VERIFY_TIMEOUT;
        getContext().messageRegistry().registerPending(new VerifyReplySelector(), new VerifyReplyJob(getContext()), new VerifyTimeoutJob(getContext()), VERIFY_TIMEOUT);
        getContext().tunnelDispatcher().dispatchOutbound(lookup, outTunnel.getSendTunnelId(0), _target);
    }
    
    private Hash pickTarget() {
        FloodfillPeerSelector sel = (FloodfillPeerSelector)_facade.getPeerSelector();
        List peers = sel.selectFloodfillParticipants(_facade.getKBuckets());
        Collections.shuffle(peers, getContext().random());
        if (peers.size() > 0)
            return (Hash)peers.get(0);
        
        if (_log.shouldLog(Log.WARN))
            _log.warn("No peers to verify floodfill with");
        return null;
    }
    
    private DatabaseLookupMessage buildLookup() {
        TunnelInfo replyTunnelInfo = getContext().tunnelManager().selectInboundTunnel();
        if (replyTunnelInfo == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No inbound tunnels to get a reply from!");
            return null;
        }
        DatabaseLookupMessage m = new DatabaseLookupMessage(getContext(), true);
        m.setMessageExpiration(getContext().clock().now() + VERIFY_TIMEOUT);
        m.setReplyTunnel(replyTunnelInfo.getReceiveTunnelId(0));
        m.setFrom(replyTunnelInfo.getPeer(0));
        m.setSearchKey(_key);
        return m;
    }
    
    private class VerifyReplySelector implements MessageSelector {
        public boolean continueMatching() { 
            return false; // only want one match
        }
        
        public long getExpiration() { return _expiration; }
        public boolean isMatch(I2NPMessage message) {
            if (message instanceof DatabaseStoreMessage) {
                DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
                return _key.equals(dsm.getKey());
            } else if (message instanceof DatabaseSearchReplyMessage) {
                DatabaseSearchReplyMessage dsrm = (DatabaseSearchReplyMessage)message;
                return _key.equals(dsrm.getSearchKey());
            }
            return false;
        }
    }
    
    private class VerifyReplyJob extends JobImpl implements ReplyJob {
        private I2NPMessage _message;
        public VerifyReplyJob(RouterContext ctx) {
            super(ctx);
        }
        public String getName() { return "Handle floodfill verification reply"; }
        public void runJob() {
            long delay = getContext().clock().now() - _sendTime;
            if (_message instanceof DatabaseStoreMessage) {
                // Verify it's as recent as the one we sent
                boolean success = false;
                DatabaseStoreMessage dsm = (DatabaseStoreMessage)_message;
                if (_isRouterInfo && dsm.getValueType() == DatabaseStoreMessage.KEY_TYPE_ROUTERINFO)
                    success = dsm.getRouterInfo().getPublished() >= _published;
                else if ((!_isRouterInfo) && dsm.getValueType() == DatabaseStoreMessage.KEY_TYPE_LEASESET)
                    success = dsm.getLeaseSet().getEarliestLeaseDate() >= _published;
                if (success) {
                    // store ok, w00t!
                    getContext().profileManager().dbLookupSuccessful(_target, delay);
                    getContext().statManager().addRateData("netDb.floodfillVerifyOK", delay, 0);
                    return;
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Verify failed - older");
            } else if (_message instanceof DatabaseSearchReplyMessage) {
                // assume 0 old, all new, 0 invalid, 0 dup
                getContext().profileManager().dbLookupReply(_target,  0,
                                ((DatabaseSearchReplyMessage)_message).getNumReplies(), 0, 0, delay);
            }
            // store failed, boo, hiss!
            getContext().statManager().addRateData("netDb.floodfillVerifyFail", delay, 0);
            resend();
        }        
        public void setMessage(I2NPMessage message) { _message = message; }
    }
    
    /** the netDb store failed to verify, so resend it to a random floodfill peer */
    private void resend() {
        DataStructure ds = null;
        ds = _facade.lookupLeaseSetLocally(_key);
        if (ds == null)
            ds = _facade.lookupRouterInfoLocally(_key);
        if (ds != null)
            _facade.sendStore(_key, ds, null, null, VERIFY_TIMEOUT, null);
    }
    
    private class VerifyTimeoutJob extends JobImpl {
        public VerifyTimeoutJob(RouterContext ctx) {
            super(ctx);
        }
        public String getName() { return "Floodfill verification timeout"; }
        public void runJob() { 
            getContext().profileManager().dbLookupFailed(_target);
            getContext().statManager().addRateData("netDb.floodfillVerifyTimeout", getContext().clock().now() - _sendTime, 0);
            resend(); 
        }
    }
}
