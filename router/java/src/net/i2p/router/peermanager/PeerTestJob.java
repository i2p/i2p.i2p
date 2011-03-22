package net.i2p.router.peermanager;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.PeerSelectionCriteria;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Grab some peers that we want to test and probe them briefly to get some 
 * more accurate and up to date performance data.  This delegates the peer
 * selection to the peer manager and tests the peer by sending it a useless
 * database store message
 *
 */
public class PeerTestJob extends JobImpl {
    private final Log _log;
    private PeerManager _manager;
    private boolean _keepTesting;
    private static final long DEFAULT_PEER_TEST_DELAY = 5*60*1000;
    private static final int TEST_PRIORITY = 100;
    
    /** Creates a new instance of PeerTestJob */
    public PeerTestJob(RouterContext context) {
        super(context);
        _log = context.logManager().getLog(PeerTestJob.class);
        _keepTesting = false;
        getContext().statManager().createRateStat("peer.testOK", "How long a successful test takes", "Peers", new long[] { 60*1000, 10*60*1000 });
        getContext().statManager().createRateStat("peer.testTooSlow", "How long a too-slow (yet successful) test takes", "Peers", new long[] { 60*1000, 10*60*1000 });
        getContext().statManager().createRateStat("peer.testTimeout", "How often a test times out without a reply", "Peers", new long[] { 60*1000, 10*60*1000 });
    }
    
    /** how long should we wait before firing off new tests?  */
    private long getPeerTestDelay() { return DEFAULT_PEER_TEST_DELAY; } 
    /** how long to give each peer before marking them as unresponsive? */
    private int getTestTimeout() { return 30*1000; }
    /** number of peers to test each round */
    private int getTestConcurrency() { return 1; }
    
    // FIXME Exporting non-public type through public API FIXME
    public void startTesting(PeerManager manager) {
        _manager = manager;
        _keepTesting = true;
        this.getTiming().setStartAfter(getContext().clock().now() + DEFAULT_PEER_TEST_DELAY);
        getContext().jobQueue().addJob(this); 
        if (_log.shouldLog(Log.INFO))
            _log.info("Start testing peers");
    }
    public void stopTesting() { 
        _keepTesting = false;
        if (_log.shouldLog(Log.INFO))
            _log.info("Stop testing peers");
    }
    
    public String getName() { return "Initiate some peer tests"; }
    public void runJob() {
        if (!_keepTesting) return;
        Set peers = selectPeersToTest();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Testing " + peers.size() + " peers");
        
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            RouterInfo peer = (RouterInfo)iter.next();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Testing peer " + peer.getIdentity().getHash().toBase64());
            testPeer(peer);
        }
        requeue(getPeerTestDelay());
    }    
    
    /**
     * Retrieve a group of 0 or more peers that we want to test. 
     *
     * @return set of RouterInfo structures
     */
    private Set selectPeersToTest() {
        PeerSelectionCriteria criteria = new PeerSelectionCriteria();
        criteria.setMinimumRequired(getTestConcurrency());
        criteria.setMaximumRequired(getTestConcurrency());
        criteria.setPurpose(PeerSelectionCriteria.PURPOSE_TEST);
        List peerHashes = _manager.selectPeers(criteria);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Peer selection found " + peerHashes.size() + " peers");
        
        Set peers = new HashSet(peerHashes.size());
        for (Iterator iter = peerHashes.iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            RouterInfo peerInfo = getContext().netDb().lookupRouterInfoLocally(peer);
            if (peerInfo != null) {
                peers.add(peerInfo);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Test peer " + peer.toBase64() + " had no local routerInfo?");
            }
        }
        return peers;
    }
    
    /**
     * Fire off the necessary jobs and messages to test the given peer
     *
     */
    private void testPeer(RouterInfo peer) {
        TunnelInfo inTunnel = getInboundTunnelId(); 
        if (inTunnel == null) {
            _log.warn("No tunnels to get peer test replies through!  wtf!");
            return;
        }
        TunnelId inTunnelId = inTunnel.getReceiveTunnelId(0);
	
        RouterInfo inGateway = getContext().netDb().lookupRouterInfoLocally(inTunnel.getPeer(0));
        if (inGateway == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("We can't find the gateway to our inbound tunnel?! wtf");
            return;
        }
	
        int timeoutMs = getTestTimeout();
        long expiration = getContext().clock().now() + timeoutMs;

        long nonce = getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        DatabaseStoreMessage msg = buildMessage(peer, inTunnelId, inGateway.getIdentity().getHash(), nonce, expiration);
	
        TunnelInfo outTunnel = getOutboundTunnelId();
        if (outTunnel == null) {
            _log.warn("No tunnels to send search out through!  wtf!");
            return;
        }
        
        TunnelId outTunnelId = outTunnel.getSendTunnelId(0);
	
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Sending peer test to " + peer.getIdentity().getHash().toBase64() 
                       + " out " + outTunnel + " w/ replies through " + inTunnel);

        ReplySelector sel = new ReplySelector(peer.getIdentity().getHash(), nonce, expiration);
        PeerReplyFoundJob reply = new PeerReplyFoundJob(getContext(), peer, inTunnel, outTunnel);
        PeerReplyTimeoutJob timeoutJob = new PeerReplyTimeoutJob(getContext(), peer, inTunnel, outTunnel, sel);
        
        getContext().messageRegistry().registerPending(sel, reply, timeoutJob, timeoutMs);
        getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnelId, null, peer.getIdentity().getHash());
    }
    
    /** 
     * what tunnel will we send the test out through? 
     *
     * @return tunnel id (or null if none are found)
     */
    private TunnelInfo getOutboundTunnelId() {
        return getContext().tunnelManager().selectOutboundTunnel();
    }
    
    /**
     * what tunnel will we get replies through?
     *
     * @return tunnel id (or null if none are found)
     */
    private TunnelInfo getInboundTunnelId() {
        return getContext().tunnelManager().selectInboundTunnel();
    }

    /**
     * Build a message to test the peer with 
     */
    private DatabaseStoreMessage buildMessage(RouterInfo peer, TunnelId replyTunnel, Hash replyGateway, long nonce, long expiration) {
        DatabaseStoreMessage msg = new DatabaseStoreMessage(getContext());
        msg.setEntry(peer);
        msg.setReplyGateway(replyGateway);
        msg.setReplyTunnel(replyTunnel);
        msg.setReplyToken(nonce);
        msg.setMessageExpiration(expiration);
        return msg;
    }
    
    /**
     * Simple selector looking for a dbStore of the peer specified
     *
     */
    private class ReplySelector implements MessageSelector {
        private long _expiration;
        private long _nonce;
        private Hash _peer;
        private boolean _matchFound;
        public ReplySelector(Hash peer, long nonce, long expiration) {
            _nonce = nonce;
            _expiration = expiration;
            _peer = peer;
            _matchFound = false;
        }
        public boolean continueMatching() { return false; }
        public long getExpiration() { return _expiration; }
        public boolean isMatch(I2NPMessage message) {
            if (message instanceof DeliveryStatusMessage) {
                DeliveryStatusMessage msg = (DeliveryStatusMessage)message;
                if (_nonce == msg.getMessageId()) {
                    long timeLeft = _expiration - getContext().clock().now();
                    if (timeLeft < 0) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Took too long to get a reply from peer " + _peer.toBase64() 
                                      + ": " + (0-timeLeft) + "ms too slow");
                        getContext().statManager().addRateData("peer.testTooSlow", 0-timeLeft, 0);
                    } else {
                        getContext().statManager().addRateData("peer.testOK", getTestTimeout() - timeLeft, 0);
                    }
                    _matchFound = true;
                    return true;
                }
            }
            return false;
        }
        public boolean matchFound() { return _matchFound; }
        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(64);
            buf.append("Test peer ").append(_peer.toBase64().substring(0,4));
            buf.append(" with nonce ").append(_nonce);
            return buf.toString();
        }
    }
    
    /**
     * Called when the peer's response is found
     */
    private class PeerReplyFoundJob extends JobImpl implements ReplyJob {
        private RouterInfo _peer;
        private long _testBegin;
        private TunnelInfo _replyTunnel;
        private TunnelInfo _sendTunnel;
        public PeerReplyFoundJob(RouterContext context, RouterInfo peer, TunnelInfo replyTunnel, TunnelInfo sendTunnel) {
            super(context);
            _peer = peer;
            _replyTunnel = replyTunnel;
            _sendTunnel = sendTunnel;
            _testBegin = context.clock().now();
        }
        public String getName() { return "Peer test successful"; }
        public void runJob() {
            long responseTime = getContext().clock().now() - _testBegin;
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("successful peer test after " + responseTime + " for " 
                           + _peer.getIdentity().getHash().toBase64() + " using outbound tunnel " 
                           + _sendTunnel + " and inbound tunnel " 
                           + _replyTunnel);
            getContext().profileManager().dbLookupSuccessful(_peer.getIdentity().getHash(), responseTime);
            // we know the tunnels are working
            _sendTunnel.testSuccessful((int)responseTime);
            _replyTunnel.testSuccessful((int)responseTime);
        }
        
        public void setMessage(I2NPMessage message) {
            // noop
        }
        
    }
    /** 
     * Called when the peer's response times out
     */
    private class PeerReplyTimeoutJob extends JobImpl {
        private RouterInfo _peer;
        private TunnelInfo _replyTunnel;
        private TunnelInfo _sendTunnel;
        private ReplySelector _selector;
        public PeerReplyTimeoutJob(RouterContext context, RouterInfo peer, TunnelInfo replyTunnel, TunnelInfo sendTunnel, ReplySelector sel) {
            super(context);
            _peer = peer;
            _replyTunnel = replyTunnel;
            _sendTunnel = sendTunnel;
            _selector = sel;
        }
        public String getName() { return "Peer test failed"; }
        private boolean getShouldFailPeer() { return true; }
        public void runJob() {
            if (_selector.matchFound())
                return;
            
            if (getShouldFailPeer())
                getContext().profileManager().dbLookupFailed(_peer.getIdentity().getHash());
                        
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("failed peer test for " 
                           + _peer.getIdentity().getHash().toBase64() + " using outbound tunnel " 
                           + _sendTunnel + " and inbound tunnel " 
                           + _replyTunnel);
            
            // don't fail the tunnels, as the peer might just plain be down, or
            // otherwise overloaded
            getContext().statManager().addRateData("peer.testTimeout", 1, 0);
        }
    }
}
