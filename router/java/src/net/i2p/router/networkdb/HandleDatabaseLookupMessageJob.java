package net.i2p.router.networkdb;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.util.Log;

/**
 * Handle a lookup for a key received from a remote peer.  Needs to be implemented
 * to send back replies, etc
 * Unused directly - see kademlia/ for extension
 */
public class HandleDatabaseLookupMessageJob extends JobImpl {
    private Log _log;
    private DatabaseLookupMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    private final static int MAX_ROUTERS_RETURNED = 3;
    private final static int CLOSENESS_THRESHOLD = 8; // FNDF.MAX_TO_FLOOD + 1
    private final static int REPLY_TIMEOUT = 60*1000;
    private final static int MESSAGE_PRIORITY = 300;
    
    /**
     * If a routerInfo structure isn't this recent, don't send it out.
     * Equal to KNDF.ROUTER_INFO_EXPIRATION_FLOODFILL.
     */
    public final static long EXPIRE_DELAY = 60*60*1000;
    
    public HandleDatabaseLookupMessageJob(RouterContext ctx, DatabaseLookupMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        super(ctx);
        _log = getContext().logManager().getLog(HandleDatabaseLookupMessageJob.class);
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
    }
    
    protected boolean answerAllQueries() { return false; }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling database lookup message for " + _message.getSearchKey());

        Hash fromKey = _message.getFrom();

        if (_log.shouldLog(Log.DEBUG)) {
            if (_message.getReplyTunnel() != null)
                _log.debug("dbLookup received with replies going to " + fromKey 
                          + " (tunnel " + _message.getReplyTunnel() + ")");
        }

        // If we are hidden we should not get queries, log and return
        if (getContext().router().isHidden()) {
            if (_log.shouldLog(Log.ERROR)) {
                _log.error("Uninvited dbLookup received with replies going to " + fromKey
                           + " (tunnel " + _message.getReplyTunnel() + ")");
            }
            return;
        }

        LeaseSet ls = getContext().netDb().lookupLeaseSetLocally(_message.getSearchKey());
        if (ls != null) {
            // We have to be very careful here to decide whether or not to send out the leaseSet,
            // to avoid anonymity vulnerabilities.
            // As this is complex, lots of comments follow...

            boolean isLocal = getContext().clientManager().isLocal(ls.getDestination());
            boolean shouldPublishLocal = isLocal && getContext().clientManager().shouldPublishLeaseSet(_message.getSearchKey());
        
            // Only answer a request for a LeaseSet if it has been published
            // to us, or, if its local, if we would have published to ourselves

            // answerAllQueries: We are floodfill
            // getReceivedAsPublished:
            //    false for local
            //    false for received over a tunnel
            //    false for received in response to our lookups
            //    true for received in a DatabaseStoreMessage unsolicited
            if (ls.getReceivedAsPublished()) {
                // Answer anything that was stored to us directly
                // (i.e. "received as published" - not the result of a query, or received
                // over a client tunnel).
                // This is probably because we are floodfill, but also perhaps we used to be floodfill,
                // so we don't check the answerAllQueries() flag.
                // Local leasesets are not handled here
                if (_log.shouldLog(Log.INFO))
                    _log.info("We have the published LS " + _message.getSearchKey().toBase64() + ", answering query");
                getContext().statManager().addRateData("netDb.lookupsMatchedReceivedPublished", 1, 0);
                sendData(_message.getSearchKey(), ls, fromKey, _message.getReplyTunnel());
            } else if (shouldPublishLocal && answerAllQueries()) {
                // We are floodfill, and this is our local leaseset, and we publish it.
                // Only send it out if it is in our estimated keyspace.
                // For this, we do NOT use their dontInclude list as it can't be trusted
                // (i.e. it could mess up the closeness calculation)
                Set<Hash> closestHashes = getContext().netDb().findNearestRouters(_message.getSearchKey(), 
                                                                            CLOSENESS_THRESHOLD, null);
                if (weAreClosest(closestHashes)) {
                    // It's in our keyspace, so give it to them
                    if (_log.shouldLog(Log.INFO))
                        _log.info("We have local LS " + _message.getSearchKey().toBase64() + ", answering query, in our keyspace");
                    getContext().statManager().addRateData("netDb.lookupsMatchedLocalClosest", 1, 0);
                    sendData(_message.getSearchKey(), ls, fromKey, _message.getReplyTunnel());
                } else {
                    // Lie, pretend we don't have it
                    if (_log.shouldLog(Log.INFO))
                        _log.info("We have local LS " + _message.getSearchKey().toBase64() + ", NOT answering query, out of our keyspace");
                    getContext().statManager().addRateData("netDb.lookupsMatchedLocalNotClosest", 1, 0);
                    Set<Hash> routerHashSet = getNearestRouters();
                    sendClosest(_message.getSearchKey(), routerHashSet, fromKey, _message.getReplyTunnel());
                }
            } else {
                // It was not published to us (we looked it up, for example)
                // or it's local and we aren't floodfill,
                // or it's local and we don't publish it.
                // Lie, pretend we don't have it
                if (_log.shouldLog(Log.INFO))
                    _log.info("We have LS " + _message.getSearchKey().toBase64() +
                               ", NOT answering query - local? " + isLocal + " shouldPublish? " + shouldPublishLocal +
                               " RAP? " + ls.getReceivedAsPublished() + " RAR? " + ls.getReceivedAsReply());
                getContext().statManager().addRateData("netDb.lookupsMatchedRemoteNotClosest", 1, 0);
                Set<Hash> routerHashSet = getNearestRouters();
                sendClosest(_message.getSearchKey(), routerHashSet, fromKey, _message.getReplyTunnel());
            }
        } else {
            RouterInfo info = getContext().netDb().lookupRouterInfoLocally(_message.getSearchKey());
            if ( (info != null) && (info.isCurrent(EXPIRE_DELAY)) ) {
                if ( (info.getIdentity().isHidden()) || (isUnreachable(info) && !publishUnreachable()) ) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Not answering a query for a netDb peer who isn't reachable");
                    Set<Hash> us = new HashSet<Hash>(1);
                    us.add(getContext().routerHash());
                    sendClosest(_message.getSearchKey(), us, fromKey, _message.getReplyTunnel());
                //} else if (info.isHidden()) {
                //    // Don't return hidden nodes
                //    ERR: we don't want to explicitly reject lookups for hidden nodes, since they
                //         may have just sent the hidden mode to only us and bundled a lookup with
                //         a payload targetting some hidden destination (and if we refused to answer,
                //         yet answered the bundled data message [e.g. HTTP GET], they'd know that
                //         *we* were hosting that destination).  To operate safely,
                //         perhaps we should refuse to honor lookups bundled down client tunnels?
                } else {
                    // send that routerInfo to the _message.getFromHash peer
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("We do have key " + _message.getSearchKey().toBase64() 
                                   + " locally as a router info.  sending to " + fromKey.toBase64());
                    sendData(_message.getSearchKey(), info, fromKey, _message.getReplyTunnel());
                }
            } else {
                // not found locally - return closest peer hashes
                Set<Hash> routerHashSet = getNearestRouters();

                // ERR: see above
                // // Remove hidden nodes from set..
                // for (Iterator iter = routerInfoSet.iterator(); iter.hasNext();) {
                //     RouterInfo peer = (RouterInfo)iter.next();
                //     if (peer.isHidden()) {
                //         iter.remove();
                //     }
                // }

                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("We do not have key " + _message.getSearchKey().toBase64() + 
                               " locally.  sending back " + routerHashSet.size() + " peers to " + fromKey.toBase64());
                sendClosest(_message.getSearchKey(), routerHashSet, fromKey, _message.getReplyTunnel());
            }
        }
    }
    
    /**
     *  Closest to the message's search key,
     *  honoring the message's dontInclude set.
     *  Will not include us.
     *  Side effect - adds us to the message's dontInclude set.
     */
    private Set<Hash> getNearestRouters() {
        Set<Hash> dontInclude = _message.getDontIncludePeers();
        if (dontInclude == null)
            dontInclude = new HashSet(1);
        dontInclude.add(getContext().routerHash());
        // Honor flag to exclude all floodfills
        //if (dontInclude.contains(Hash.FAKE_HASH)) {
        // This is handled in FloodfillPeerSelector
        return getContext().netDb().findNearestRouters(_message.getSearchKey(), 
                                                       MAX_ROUTERS_RETURNED, 
                                                       dontInclude);
    }

    private static boolean isUnreachable(RouterInfo info) {
        if (info == null) return true;
        String cap = info.getCapabilities();
        if (cap == null) return false;
        return cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
    }
    
    public static final String PROP_PUBLISH_UNREACHABLE = "router.publishUnreachableRouters";
    public static final boolean DEFAULT_PUBLISH_UNREACHABLE = true;
    
    private boolean publishUnreachable() {
        return getContext().getProperty(PROP_PUBLISH_UNREACHABLE, DEFAULT_PUBLISH_UNREACHABLE);
    }
    
    private boolean weAreClosest(Set<Hash> routerHashSet) {
        return routerHashSet.contains(getContext().routerHash());
    }
    
    private void sendData(Hash key, DatabaseEntry data, Hash toPeer, TunnelId replyTunnel) {
        if (!key.equals(data.getHash())) {
            _log.error("Hash mismatch HDLMJ");
            return;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending data matching key " + key.toBase64() + " to peer " + toPeer.toBase64() 
                       + " tunnel " + replyTunnel);
        DatabaseStoreMessage msg = new DatabaseStoreMessage(getContext());
        if (data.getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
            getContext().statManager().addRateData("netDb.lookupsMatchedLeaseSet", 1, 0);
        }
        msg.setEntry(data);
        getContext().statManager().addRateData("netDb.lookupsMatched", 1, 0);
        getContext().statManager().addRateData("netDb.lookupsHandled", 1, 0);
        sendMessage(msg, toPeer, replyTunnel);
    }
    
    protected void sendClosest(Hash key, Set<Hash> routerHashes, Hash toPeer, TunnelId replyTunnel) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending closest routers to key " + key.toBase64() + ": # peers = " 
                       + routerHashes.size() + " tunnel " + replyTunnel);
        DatabaseSearchReplyMessage msg = new DatabaseSearchReplyMessage(getContext());
        msg.setFromHash(getContext().routerHash());
        msg.setSearchKey(key);
        int i = 0;
        for (Hash h : routerHashes) {
            msg.addReply(h);
            if (++i >= MAX_ROUTERS_RETURNED)
                break;
        }
        getContext().statManager().addRateData("netDb.lookupsHandled", 1, 0);
        sendMessage(msg, toPeer, replyTunnel); // should this go via garlic messages instead?
    }
    
    protected void sendMessage(I2NPMessage message, Hash toPeer, TunnelId replyTunnel) {
        if (replyTunnel != null) {
            sendThroughTunnel(message, toPeer, replyTunnel);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending reply directly to " + toPeer);
            Job send = new SendMessageDirectJob(getContext(), message, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY);
            send.runJob();
            //getContext().netDb().lookupRouterInfo(toPeer, send, null, REPLY_TIMEOUT);
        }
    }
    
    private void sendThroughTunnel(I2NPMessage message, Hash toPeer, TunnelId replyTunnel) {
        if (getContext().routerHash().equals(toPeer)) {
            // if we are the gateway, act as if we received it
            TunnelGatewayMessage m = new TunnelGatewayMessage(getContext());
            m.setMessage(message);
            m.setTunnelId(replyTunnel);
            m.setMessageExpiration(message.getMessageExpiration());
            getContext().tunnelDispatcher().dispatch(m);
        } else {
            // if we aren't the gateway, forward it on
            TunnelGatewayMessage m = new TunnelGatewayMessage(getContext());
            m.setMessage(message);
            m.setMessageExpiration(message.getMessageExpiration());
            m.setTunnelId(replyTunnel);
            SendMessageDirectJob j = new SendMessageDirectJob(getContext(), m, toPeer, 10*1000, 100);
            j.runJob();
            //getContext().jobQueue().addJob(j);
        }
    }
    
    public String getName() { return "Handle Database Lookup Message"; }

    @Override
    public void dropped() {
        getContext().messageHistory().messageProcessingError(_message.getUniqueId(), 
                                                         _message.getClass().getName(), 
                                                         "Dropped due to overload");
    }
}
