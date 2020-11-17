package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.crypto.EncType;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Search for a particular key iteratively until we either find a value, we run
 * out of peers, or the bucket the key belongs in has sufficient values in it.
 * Well, we're skipping the 'bucket gets filled up' test for now, since it'll never
 * get used (at least for a while).
 *
 */
class ExploreJob extends SearchJob {
    private final FloodfillPeerSelector _peerSelector;
    private final boolean _isRealExplore;
    
    /** how long each exploration should run for
     *  The exploration won't "succeed" so we make it long so we query several peers */
    private static final long MAX_EXPLORE_TIME = 30*1000;
    
    /** how many peers to explore through concurrently */
    private static final int EXPLORE_BREDTH = 1;

    /** only send the closest "dont tell me about" refs...
     *  Override to make this bigger because we want to include both the
     *  floodfills and the previously-queried peers */
    static final int MAX_CLOSEST = 20; // LINT -- field hides another field, this isn't an override.
    
    /** Override to make this shorter, since we don't sort out the
     *  unresponsive ff peers like we do in FloodOnlySearchJob */
    static final int PER_FLOODFILL_PEER_TIMEOUT = 5*1000; // LINT -- field hides another field, this isn't an override.

    /**
     * Create a new search for the routingKey specified
     *
     * @param isRealExplore if true, a standard exploration (no floodfills will be returned)
     *                      if false, a standard lookup (floodfills will be returned, use if low on floodfills)
     */
    public ExploreJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, boolean isRealExplore) {
        // note that we're treating the last param (isLease) as *false* since we're just exploring.
        // if this collides with an actual leaseSet's key, neat, but that wouldn't imply we're actually
        // attempting to send that lease a message!
        super(context, facade, key, null, null, MAX_EXPLORE_TIME, false, false);
        _peerSelector = (FloodfillPeerSelector) (_facade.getPeerSelector());
        _isRealExplore = isRealExplore;
    }
    
    /**
     * Build the database search message, but unlike the normal searches, we're more explicit in
     * what we /dont/ want.  We don't just ask them to ignore the peers we've already searched
     * on, but to ignore a number of the peers we already know about (in the target key's bucket) as well.
     *
     * Perhaps we may want to ignore other keys too, such as the ones in nearby
     * buckets, but we probably don't want the dontIncludePeers set to get too
     * massive (aka sending the entire routing table as 'dont tell me about these
     * guys').  but maybe we do.  dunno.  lots of implications.
     *
     * FloodfillPeerSelector would add only the floodfill peers,
     * and PeerSelector doesn't include the floodfill peers,
     * so we add the ff peers ourselves and then use the regular PeerSelector.
     *
     * @param replyTunnelId tunnel to receive replies through, or our router hash if replyGateway is null
     * @param replyGateway gateway for the reply tunnel, if null, we are sending direct, do not encrypt
     * @param expiration when the search should stop
     * @param peer the peer to send it to
     *
     * @return a DatabaseLookupMessage or GarlicMessage or null on error
     */
    @Override
    protected I2NPMessage buildMessage(TunnelId replyTunnelId, Hash replyGateway, long expiration, RouterInfo peer) {
        final RouterContext ctx = getContext();
        DatabaseLookupMessage msg = new DatabaseLookupMessage(ctx, true);
        msg.setSearchKey(getState().getTarget());
        msg.setFrom(replyGateway);
        // Moved below now that DLM makes a copy
        //msg.setDontIncludePeers(getState().getClosestAttempted(MAX_CLOSEST));
        Set<Hash> dontIncludePeers = getState().getClosestAttempted(MAX_CLOSEST);
        msg.setMessageExpiration(expiration);
        if (replyTunnelId != null)
            msg.setReplyTunnel(replyTunnelId);
        
        int available = MAX_CLOSEST - dontIncludePeers.size();
        if (_isRealExplore) {
            // supported as of 0.9.16. We don't add "fake hash" any more.
            msg.setSearchType(DatabaseLookupMessage.Type.EXPL);
        } else {
            msg.setSearchType(DatabaseLookupMessage.Type.RI);
        }

        KBucketSet<Hash> ks = _facade.getKBuckets();
        Hash rkey = ctx.routingKeyGenerator().getRoutingKey(getState().getTarget());
        // in a few releases, we can (and should) remove this,
        // as routers will honor the above flag, and we want the table to include
        // only non-floodfills.
        // Removed in 0.8.8, good thing, as we had well over MAX_CLOSEST floodfills.
        //if (available > 0 && ks != null) {
        //    List peers = _peerSelector.selectFloodfillParticipants(rkey, available, ks);
        //    int len = peers.size();
        //    if (len > 0)
        //        msg.getDontIncludePeers().addAll(peers);
        //}
        
        available = MAX_CLOSEST - dontIncludePeers.size();
        if (available > 0) {
            // selectNearestExplicit adds our hash to the dontInclude set (3rd param) ...
            // And we end up with MAX_CLOSEST+1 entries.
            // We don't want our hash in the message's don't-include list though.
            // We're just exploring, but this could give things away, and tie our exploratory tunnels to our router,
            // so let's not put our hash in there.
            Set<Hash> dontInclude = new HashSet<Hash>(dontIncludePeers);
            List<Hash> peers = _peerSelector.selectNearestExplicit(rkey, available, dontInclude, ks);
            dontIncludePeers.addAll(peers);
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Peers we don't want to hear about: " + dontIncludePeers);
        
        msg.setDontIncludePeers(dontIncludePeers);

        // Now encrypt if we can
        RouterIdentity ident = peer.getIdentity();
        EncType type = ident.getPublicKey().getType();
        boolean encryptElG = ctx.getProperty(IterativeSearchJob.PROP_ENCRYPT_RI, IterativeSearchJob.DEFAULT_ENCRYPT_RI);
        I2NPMessage outMsg;
        if (replyTunnelId != null &&
            ((encryptElG && type == EncType.ELGAMAL_2048) || (type == EncType.ECIES_X25519 && DatabaseLookupMessage.USE_ECIES_FF))) {
            EncType ourType = ctx.keyManager().getPublicKey().getType();
            boolean ratchet1 = ourType.equals(EncType.ECIES_X25519);
            boolean ratchet2 = DatabaseLookupMessage.supportsRatchetReplies(peer);
            // request encrypted reply?
            if (DatabaseLookupMessage.supportsEncryptedReplies(peer) &&
                (ratchet2 || !ratchet1)) {
                boolean supportsRatchet = ratchet1 && ratchet2;
                MessageWrapper.OneTimeSession sess;
                sess = MessageWrapper.generateSession(ctx, ctx.sessionKeyManager(), MAX_EXPLORE_TIME, !supportsRatchet);
                if (sess != null) {
                    if (sess.tag != null) {
                        if (_log.shouldInfo())
                            _log.info(getJobId() + ": Requesting AES reply from " + ident.calculateHash() + " with: " + sess.key + ' ' + sess.tag);
                        msg.setReplySession(sess.key, sess.tag);
                    } else {
                        if (_log.shouldInfo())
                            _log.info(getJobId() + ": Requesting AEAD reply from " + ident.calculateHash() + " with: " + sess.key + ' ' + sess.rtag);
                        msg.setReplySession(sess.key, sess.rtag);
                    }
                } else {
                    if (_log.shouldWarn())
                        _log.warn(getJobId() + ": Failed encrypt to " + peer);
                    // client went away, but send it anyway
                }
            }
            // may be null
            outMsg = MessageWrapper.wrap(ctx, msg, peer);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Encrypted exploratory DLM for " + getState().getTarget() + " to " +
                           ident.calculateHash());
        } else {
            outMsg = msg;
        }
        return outMsg;
    }
    
    /** max # of concurrent searches */
    @Override
    protected int getBredth() { return EXPLORE_BREDTH; }
    

    /**
     * We've gotten a search reply that contained the specified
     * number of peers that we didn't know about before.
     *
     */
    @Override
    protected void newPeersFound(int numNewPeers) {
        // who cares about how many new peers.  well, maybe we do.  but for now,
        // we'll do the simplest thing that could possibly work.
        _facade.setLastExploreNewDate(getContext().clock().now());
    }
    
    /*
     * We could override searchNext to see if we actually fill up a kbucket before
     * the search expires, but, c'mon, the keyspace is just too bloody massive, and
     * buckets wont be filling anytime soon, so might as well just use the SearchJob's
     * searchNext
     *
     */
    
    @Override
    public String getName() { return "Kademlia NetDb Explore"; }
}
