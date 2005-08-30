package net.i2p.router.networkdb.kademlia;

import java.util.Collections;
import java.util.List;
import net.i2p.data.*;
import net.i2p.data.i2np.*;
import net.i2p.router.*;
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
    private FloodfillNetworkDatabaseFacade _facade;
    private long _expiration;
    private long _sendTime;
    
    private static final int VERIFY_TIMEOUT = 10*1000;
    
    public FloodfillVerifyStoreJob(RouterContext ctx, Hash key, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _key = key;
        _log = ctx.logManager().getLog(getClass());
        _facade = facade;
        // wait 10 seconds before trying to verify the store
        getTiming().setStartAfter(ctx.clock().now() + VERIFY_TIMEOUT);
        getContext().statManager().createRateStat("netDb.floodfillVerifyOK", "How long a floodfill verify takes when it succeeds", "NetworkDatabase", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        getContext().statManager().createRateStat("netDb.floodfillVerifyFail", "How long a floodfill verify takes when it fails", "NetworkDatabase", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        getContext().statManager().createRateStat("netDb.floodfillVerifyTimeout", "How long a floodfill verify takes when it times out", "NetworkDatabase", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
    }
    public String getName() { return "Verify netdb store"; }
    public void runJob() { 
        Hash target = pickTarget();
        if (target == null) return;
        
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
        getContext().tunnelDispatcher().dispatchOutbound(lookup, outTunnel.getSendTunnelId(0), target);
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
                if (_key.equals(dsm.getKey()))
                    return true;
                else
                    return false;
            } else if (message instanceof DatabaseSearchReplyMessage) {
                DatabaseSearchReplyMessage dsrm = (DatabaseSearchReplyMessage)message;
                if (_key.equals(dsrm.getSearchKey()))
                    return true;
                else
                    return false;
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
            if (_message instanceof DatabaseStoreMessage) {
                // store ok, w00t!
                getContext().statManager().addRateData("netDb.floodfillVerifyOK", getContext().clock().now() - _sendTime, 0);
            } else {
                // store failed, boo, hiss!
                getContext().statManager().addRateData("netDb.floodfillVerifyFail", getContext().clock().now() - _sendTime, 0);
                resend();
            }
        }        
        public void setMessage(I2NPMessage message) { _message = message; }
    }
    
    /** the netDb store failed to verify, so resend it to a random floodfill peer */
    private void resend() {
        DataStructure ds = null;
        ds = _facade.lookupLeaseSetLocally(_key);
        if (ds == null)
            ds = _facade.lookupRouterInfoLocally(_key);
        _facade.sendStore(_key, ds, null, null, VERIFY_TIMEOUT, null);
    }
    
    private class VerifyTimeoutJob extends JobImpl {
        public VerifyTimeoutJob(RouterContext ctx) {
            super(ctx);
        }
        public String getName() { return "Floodfill verification timeout"; }
        public void runJob() { 
            getContext().statManager().addRateData("netDb.floodfillVerifyTimeout", getContext().clock().now() - _sendTime, 0);
            resend(); 
        }
    }
}
