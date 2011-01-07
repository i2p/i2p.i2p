package net.i2p.router.networkdb.kademlia;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
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
    private Hash _sentTo;
    private FloodfillNetworkDatabaseFacade _facade;
    private long _expiration;
    private long _sendTime;
    private long _published;
    private boolean _isRouterInfo;
    private MessageWrapper.WrappedMessage _wrappedMessage;
    private final Set<Hash> _ignore;
    
    private static final int START_DELAY = 20*1000;
    private static final int VERIFY_TIMEOUT = 15*1000;
    private static final int MAX_PEERS_TO_TRY = 5;
    
    /**
     *  Delay a few seconds, then start the verify
     *  @param sentTo who to give the credit or blame to, can be null
     */
    public FloodfillVerifyStoreJob(RouterContext ctx, Hash key, long published, boolean isRouterInfo, Hash sentTo, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        facade.verifyStarted(key);
        _key = key;
        _published = published;
        _isRouterInfo = isRouterInfo;
        _log = ctx.logManager().getLog(getClass());
        _sentTo = sentTo;
        _facade = facade;
        _ignore = new HashSet(MAX_PEERS_TO_TRY);
        if (sentTo != null) {
            _ignore.add(_sentTo);
        }
        // wait some time before trying to verify the store
        getTiming().setStartAfter(ctx.clock().now() + START_DELAY);
        getContext().statManager().createRateStat("netDb.floodfillVerifyOK", "How long a floodfill verify takes when it succeeds", "NetworkDatabase", new long[] { 60*60*1000 });
        getContext().statManager().createRateStat("netDb.floodfillVerifyFail", "How long a floodfill verify takes when it fails", "NetworkDatabase", new long[] { 60*60*1000 });
        getContext().statManager().createRateStat("netDb.floodfillVerifyTimeout", "How long a floodfill verify takes when it times out", "NetworkDatabase", new long[] { 60*60*1000 });
    }
    public String getName() { return "Verify netdb store"; }

    /**
     *  Query a random floodfill for the leaseset or routerinfo
     *  that we just stored to a (hopefully different) floodfill peer.
     *
     *  If it fails (after a timeout period), resend the data.
     *  If the queried data is older than what we stored, that counts as a fail.
     **/
    public void runJob() { 
        _target = pickTarget();
        if (_target == null) {
            _facade.verifyFinished(_key);
            return;
        }        

        DatabaseLookupMessage lookup = buildLookup();
        if (lookup == null) {
            _facade.verifyFinished(_key);
            return;
        }        
 
        // If we are verifying a leaseset, use the destination's own tunnels,
        // to avoid association by the exploratory tunnel OBEP.
        // Unless it is an encrypted leaseset.
        TunnelInfo outTunnel;
        if (_isRouterInfo || getContext().keyRing().get(_key) != null)
            outTunnel = getContext().tunnelManager().selectOutboundTunnel();
        else
            outTunnel = getContext().tunnelManager().selectOutboundTunnel(_key);
        if (outTunnel == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No outbound tunnels to verify a store");
            _facade.verifyFinished(_key);
            return;
        }
        
        // garlic encrypt to hide contents from the OBEP
        RouterInfo peer = _facade.lookupRouterInfoLocally(_target);
        if (peer == null) {
             if (_log.shouldLog(Log.WARN))
                 _log.warn("Fail finding target RI");
            _facade.verifyFinished(_key);
            return;
        }
        Hash fromKey;
        if (_isRouterInfo)
            fromKey = null;
        else
            fromKey = _key;
        _wrappedMessage = MessageWrapper.wrap(getContext(), lookup, fromKey, peer);
        if (_wrappedMessage == null) {
             if (_log.shouldLog(Log.WARN))
                _log.warn("Fail Garlic encrypting");
            _facade.verifyFinished(_key);
            return;
        }
        I2NPMessage sent = _wrappedMessage.getMessage();

        if (_log.shouldLog(Log.INFO))
            _log.info("Starting verify (stored " + _key + " to " + _sentTo + "), asking " + _target);
        _sendTime = getContext().clock().now();
        _expiration = _sendTime + VERIFY_TIMEOUT;
        getContext().messageRegistry().registerPending(new VerifyReplySelector(), new VerifyReplyJob(getContext()), new VerifyTimeoutJob(getContext()), VERIFY_TIMEOUT);
        getContext().tunnelDispatcher().dispatchOutbound(sent, outTunnel.getSendTunnelId(0), _target);
    }
    
    /**
     *  Pick a responsive floodfill close to the key, but not the one we sent to
     */
    private Hash pickTarget() {
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(_key);
        FloodfillPeerSelector sel = (FloodfillPeerSelector)_facade.getPeerSelector();
        List<Hash> peers = sel.selectFloodfillParticipants(rkey, 1, _ignore, _facade.getKBuckets());
        if (!peers.isEmpty())
            return peers.get(0);
        
        if (_log.shouldLog(Log.WARN))
            _log.warn("No other peers to verify floodfill with, using the one we sent to");
        return _sentTo;
    }
    
    private DatabaseLookupMessage buildLookup() {
        // If we are verifying a leaseset, use the destination's own tunnels,
        // to avoid association by the exploratory tunnel OBEP.
        // Unless it is an encrypted leaseset.
        TunnelInfo replyTunnelInfo;
        if (_isRouterInfo || getContext().keyRing().get(_key) != null)
            replyTunnelInfo = getContext().tunnelManager().selectInboundTunnel();
        else
            replyTunnelInfo = getContext().tunnelManager().selectInboundTunnel(_key);
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
            if (_wrappedMessage != null)
                _wrappedMessage.acked();
            _facade.verifyFinished(_key);
            if (_message instanceof DatabaseStoreMessage) {
                // Verify it's as recent as the one we sent
                boolean success = false;
                DatabaseStoreMessage dsm = (DatabaseStoreMessage)_message;
                success = dsm.getEntry().getDate() >= _published;
                if (success) {
                    // store ok, w00t!
                    getContext().profileManager().dbLookupSuccessful(_target, delay);
                    if (_sentTo != null)
                        getContext().profileManager().dbStoreSuccessful(_sentTo);
                    getContext().statManager().addRateData("netDb.floodfillVerifyOK", delay, 0);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Verify success for " + _key);
                    return;
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Verify failed (older) for " + _key);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Rcvd older lease: " + dsm.getEntry());
            } else if (_message instanceof DatabaseSearchReplyMessage) {
                // assume 0 old, all new, 0 invalid, 0 dup
                getContext().profileManager().dbLookupReply(_target,  0,
                                ((DatabaseSearchReplyMessage)_message).getNumReplies(), 0, 0, delay);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Verify failed (DSRM) for " + _key);
            }
            // store failed, boo, hiss!
            // For now, blame the sent-to peer, but not the verify peer
            if (_sentTo != null)
                getContext().profileManager().dbStoreFailed(_sentTo);
            getContext().statManager().addRateData("netDb.floodfillVerifyFail", delay, 0);
            resend();
        }        
        public void setMessage(I2NPMessage message) { _message = message; }
    }
    
    /**
     *  the netDb store failed to verify, so resend it to a random floodfill peer
     *  Fixme - since we now store closest-to-the-key, this is likely to store to the
     *  very same ff as last time, until the stats get bad enough to switch.
     *  Therefore, pass the failed ff through as a don't-store-to.
     *  Let's also add the one we just tried to verify with, as they could be a pair of no-flooders.
     *  So at least we'll try THREE ffs round-robin if things continue to fail...
     */
    private void resend() {
        DatabaseEntry ds = _facade.lookupLocally(_key);
        if (ds != null) {
            Set<Hash> toSkip = new HashSet(2);
            if (_sentTo != null)
                toSkip.add(_sentTo);
            if (_target != null)
                toSkip.add(_target);
            _facade.sendStore(_key, ds, null, null, FloodfillNetworkDatabaseFacade.PUBLISH_TIMEOUT, toSkip);
        }
    }
    
    private class VerifyTimeoutJob extends JobImpl {
        public VerifyTimeoutJob(RouterContext ctx) {
            super(ctx);
        }
        public String getName() { return "Floodfill verification timeout"; }
        public void runJob() { 
            if (_wrappedMessage != null)
                _wrappedMessage.fail();
            // Only blame the verify peer
            getContext().profileManager().dbLookupFailed(_target);
            //if (_sentTo != null)
            //    getContext().profileManager().dbStoreFailed(_sentTo);
            getContext().statManager().addRateData("netDb.floodfillVerifyTimeout", getContext().clock().now() - _sendTime, 0);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Verify timed out for: " + _key);
            if (_ignore.size() < MAX_PEERS_TO_TRY) {
                // Don't resend, simply rerun FVSJ.this inline and
                // chose somebody besides _target for verification
                _ignore.add(_target);
                FloodfillVerifyStoreJob.this.runJob();
            } else {
                _facade.verifyFinished(_key);
                resend(); 
            }
        }
    }
}
