package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.router.Router;
import net.i2p.util.Log;

/**
 * Search for a particular key iteratively until we either find a value, we run 
 * out of peers, or the bucket the key belongs in has sufficient values in it.
 * Well, we're skipping the 'bucket gets filled up' test for now, since it'll never
 * get used (at least for a while).
 *
 */
class ExploreJob extends SearchJob {
    private final Log _log = new Log(ExploreJob.class);
    
    /** how long each exploration should run for (currently a trivial 20 seconds) */
    private final static long MAX_EXPLORE_TIME = 30*1000;

    /** how many of the peers closest to the key being explored do we want to explicitly say "dont send me this"? */
    private final static int NUM_CLOSEST_TO_IGNORE = 3;
        
    /**
     * Create a new search for the routingKey specified
     * 
     */
    public ExploreJob(KademliaNetworkDatabaseFacade facade, Hash key) {
	// note that we're treating the last param (isLease) as *false* since we're just exploring.
	// if this collides with an actual leaseSet's key, neat, but that wouldn't imply we're actually
	// attempting to send that lease a message!
	super(facade, key, null, null, MAX_EXPLORE_TIME, false, false);
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
     * @param replyTunnelId tunnel to receive replies through
     * @param replyGateway gateway for the reply tunnel
     * @param expiration when the search should stop 
     */
    protected DatabaseLookupMessage buildMessage(TunnelId replyTunnelId, RouterInfo replyGateway, long expiration) {
	DatabaseLookupMessage msg = new DatabaseLookupMessage();
	msg.setSearchKey(getState().getTarget());
	msg.setFrom(replyGateway);
	msg.setDontIncludePeers(getState().getAttempted());
	msg.setMessageExpiration(new Date(expiration));
	msg.setReplyTunnel(replyTunnelId);

	Set attempted = getState().getAttempted();
	List peers = PeerSelector.getInstance().selectNearestExplicit(getState().getTarget(), NUM_CLOSEST_TO_IGNORE, attempted, getFacade().getKBuckets());
	Set toSkip = new HashSet(64);
	toSkip.addAll(attempted);
	toSkip.addAll(peers);
	msg.setDontIncludePeers(toSkip);

	if (_log.shouldLog(Log.DEBUG))
	    _log.debug("Peers we don't want to hear about: " + toSkip);

	return msg;
    }
 
    
    /**
     * We're looking for a router, so lets build the lookup message (no need to tunnel route either, so just have
     * replies sent back to us directly).  This uses the similar overrides as the other buildMessage above.
     *
     */
    protected DatabaseLookupMessage buildMessage(long expiration) {
	return buildMessage(null, Router.getInstance().getRouterInfo(), expiration);
    }
    
    
    /*
     * We could override searchNext to see if we actually fill up a kbucket before
     * the search expires, but, c'mon, the keyspace is just too bloody massive, and 
     * buckets wont be filling anytime soon, so might as well just use the SearchJob's
     * searchNext
     *
     */
    
    public String getName() { return "Kademlia NetDb Explore"; }
}
