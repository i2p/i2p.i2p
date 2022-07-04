package net.i2p.router.networkdb;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.crypto.ratchet.RatchetSessionTag;
import net.i2p.router.networkdb.kademlia.MessageWrapper;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.util.Log;

/**
 * Handle a lookup for a key received from a remote peer.  Needs to be implemented
 * to send back replies, etc
 * Unused directly - see kademlia/ for extension
 */
public class HandleDatabaseLookupMessageJob extends JobImpl {
    private final Log _log;
    private final DatabaseLookupMessage _message;
    private boolean _replyKeyConsumed;
    private final Hash _us;

    private final static int MAX_ROUTERS_RETURNED = 3;
    private final static int CLOSENESS_THRESHOLD = 8; // FNDF.MAX_TO_FLOOD + 1
    private final static int REPLY_TIMEOUT = 60*1000;
    private final static int MESSAGE_PRIORITY = OutNetMessage.PRIORITY_NETDB_REPLY;
    
    /**
     * If a routerInfo structure isn't this recent, don't send it out.
     * Equal to KNDF.ROUTER_INFO_EXPIRATION_FLOODFILL.
     */
    public final static long EXPIRE_DELAY = 60*60*1000;
    
    public HandleDatabaseLookupMessageJob(RouterContext ctx, DatabaseLookupMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        super(ctx);
        _log = ctx.logManager().getLog(HandleDatabaseLookupMessageJob.class);
        _message = receivedMessage;
        _us = ctx.routerHash();
    }
    
    protected boolean answerAllQueries() { return false; }
    
    public void runJob() {

        Hash fromKey = _message.getFrom();
        TunnelId toTunnel = _message.getReplyTunnel();
        Hash searchKey = _message.getSearchKey();
        if (toTunnel == null && fromKey.equals(_us)) {
            if (_log.shouldWarn())
                // exploratory, no reply key/tag. i2pd bug?
                //_log.warn("Dropping dbLookup with replies going to us:\n" + _message);
                _log.warn("Dropping dbLookup for " + searchKey + " with replies going to us");
            return;
        }

        // If we are hidden we should not get queries, log and return
        if (getContext().router().isHidden() && !searchKey.equals(_us)) {
            if (_log.shouldLog(Log.WARN)) {
                _log.warn("Uninvited dbLookup received with replies going to " + fromKey
                           + " (tunnel " + toTunnel + ")");
            }
            return;
        }

        // i2pd bug?
        if (searchKey.equals(Hash.FAKE_HASH)) {
            if (_log.shouldWarn())
                 _log.warn("Zero lookup", new Exception());
             getContext().statManager().addRateData("netDb.DLMAllZeros", 1);
            return;
        }

        if (_log.shouldLog(Log.DEBUG)) {
            if (toTunnel != null)
                _log.debug("Handling database lookup message for " + searchKey +
                           " with replies going to " + fromKey +
                           " (tunnel " + toTunnel + ")");
            else
                _log.debug("Handling database lookup message for " + searchKey +
                           " with replies going to " + fromKey);
        }

        DatabaseLookupMessage.Type lookupType = _message.getSearchType();
        // only lookup once, then cast to correct type
        DatabaseEntry dbe = getContext().netDb().lookupLocally(searchKey);
        int type = dbe != null ? dbe.getType() : -1;
        if (DatabaseEntry.isLeaseSet(type) &&
            (lookupType == DatabaseLookupMessage.Type.ANY || lookupType == DatabaseLookupMessage.Type.LS)) {
            LeaseSet ls = (LeaseSet) dbe;
            // We have to be very careful here to decide whether or not to send out the leaseSet,
            // to avoid anonymity vulnerabilities.
            // As this is complex, lots of comments follow...

            boolean isLocal = getContext().clientManager().isLocal(ls.getHash());
            boolean shouldPublishLocal = isLocal && getContext().clientManager().shouldPublishLeaseSet(searchKey);
        
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
                    _log.info("We have the published LS " + searchKey + ", answering query");
                getContext().statManager().addRateData("netDb.lookupsMatchedReceivedPublished", 1);
                sendData(searchKey, ls, fromKey, toTunnel);
            } else if (shouldPublishLocal && answerAllQueries()) {
                // We are floodfill, and this is our local leaseset, and we publish it.
                // Only send it out if it is in our estimated keyspace.
                // For this, we do NOT use their dontInclude list as it can't be trusted
                // (i.e. it could mess up the closeness calculation)
                Set<Hash> closestHashes = getContext().netDb().findNearestRouters(searchKey, 
                                                                            CLOSENESS_THRESHOLD, null);
                if (weAreClosest(closestHashes)) {
                    // It's in our keyspace, so give it to them
                    if (_log.shouldLog(Log.INFO))
                        _log.info("We have local LS " + searchKey + ", answering query, in our keyspace");
                    getContext().statManager().addRateData("netDb.lookupsMatchedLocalClosest", 1);
                    sendData(searchKey, ls, fromKey, toTunnel);
                } else {
                    // Lie, pretend we don't have it
                    if (_log.shouldLog(Log.INFO))
                        _log.info("We have local LS " + searchKey + ", NOT answering query, out of our keyspace");
                    getContext().statManager().addRateData("netDb.lookupsMatchedLocalNotClosest", 1);
                    Set<Hash> routerHashSet = getNearestRouters(lookupType);
                    sendClosest(searchKey, routerHashSet, fromKey, toTunnel);
                }
            } else {
                // It was not published to us (we looked it up, for example)
                // or it's local and we aren't floodfill,
                // or it's local and we don't publish it.
                // Lie, pretend we don't have it
                if (_log.shouldLog(Log.INFO))
                    _log.info("We have LS " + searchKey +
                               ", NOT answering query - local? " + isLocal + " shouldPublish? " + shouldPublishLocal +
                               " RAP? " + ls.getReceivedAsPublished() + " RAR? " + ls.getReceivedAsReply());
                getContext().statManager().addRateData("netDb.lookupsMatchedRemoteNotClosest", 1);
                Set<Hash> routerHashSet = getNearestRouters(lookupType);
                sendClosest(searchKey, routerHashSet, fromKey, toTunnel);
            }
        } else if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO &&
                   lookupType != DatabaseLookupMessage.Type.LS) {
            RouterInfo info = (RouterInfo) dbe;
            if (searchKey.equals(_us)) {
                sendData(searchKey, info, fromKey, toTunnel);
            } else if (info.isCurrent(EXPIRE_DELAY)) {
                if (info.isHidden()) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Not answering a query for a hidden peer");
                    Set<Hash> us = Collections.singleton(_us);
                    sendClosest(searchKey, us, fromKey, toTunnel);
                } else {
                    // send that routerInfo to the _message.getFromHash peer
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("We do have key " + searchKey
                                   + " locally as a router info.  sending to " + fromKey);
                    sendData(searchKey, info, fromKey, toTunnel);
                }
            } else {
                // expired locally - return closest peer hashes
                Set<Hash> routerHashSet = getNearestRouters(lookupType);

                // ERR: see above
                // // Remove hidden nodes from set..
                // for (Iterator iter = routerInfoSet.iterator(); iter.hasNext();) {
                //     RouterInfo peer = (RouterInfo)iter.next();
                //     if (peer.isHidden()) {
                //         iter.remove();
                //     }
                // }

                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Expired " + searchKey + 
                               " locally.  sending back " + routerHashSet.size() + " peers to " + fromKey);
                sendClosest(searchKey, routerHashSet, fromKey, toTunnel);
            }
        } else {
            // not found locally - return closest peer hashes
            Set<Hash> routerHashSet = getNearestRouters(lookupType);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("We do not have key " + searchKey + 
                           " locally.  sending back " + routerHashSet.size() + " peers to " + fromKey);
            sendClosest(searchKey, routerHashSet, fromKey, toTunnel);
        }
    }
    
    /**
     *  Closest to the message's search key,
     *  honoring the message's dontInclude set.
     *  Will not include us.
     *  Side effect - adds us to the message's dontInclude set.
     */
    private Set<Hash> getNearestRouters(DatabaseLookupMessage.Type lookupType) {
        // convert the new EXPL type flag to the old-style FAKE_HASH
        // to pass to findNearestRouters()
        Set<Hash> dontInclude = _message.getDontIncludePeers();
        if (dontInclude == null && lookupType == DatabaseLookupMessage.Type.EXPL) {
            dontInclude = new HashSet<Hash>(2);
            dontInclude.add(_us);
            dontInclude.add(Hash.FAKE_HASH);
        } else if (dontInclude == null) {
            dontInclude = Collections.singleton(_us);
        } else if (lookupType == DatabaseLookupMessage.Type.EXPL) {
            dontInclude.add(_us);
            dontInclude.add(Hash.FAKE_HASH);
        } else {
            dontInclude.add(_us);
        }
        // Honor flag to exclude all floodfills
        //if (dontInclude.contains(Hash.FAKE_HASH)) {
        // This is handled in FloodfillPeerSelector
        return getContext().netDb().findNearestRouters(_message.getSearchKey(), 
                                                       MAX_ROUTERS_RETURNED, 
                                                       dontInclude);
    }

    private boolean weAreClosest(Set<Hash> routerHashSet) {
        return routerHashSet.contains(_us);
    }
    
    private void sendData(Hash key, DatabaseEntry data, Hash toPeer, TunnelId replyTunnel) {
        if (!key.equals(data.getHash())) {
            _log.error("Hash mismatch HDLMJ");
            return;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending data matching key " + key + " to peer " + toPeer
                       + " tunnel " + replyTunnel);
        DatabaseStoreMessage msg = new DatabaseStoreMessage(getContext());
        if (data.isLeaseSet()) {
            getContext().statManager().addRateData("netDb.lookupsMatchedLeaseSet", 1);
        }
        msg.setEntry(data);
        getContext().statManager().addRateData("netDb.lookupsMatched", 1);
        getContext().statManager().addRateData("netDb.lookupsHandled", 1);
        sendMessage(msg, toPeer, replyTunnel);
    }
    
    protected void sendClosest(Hash key, Set<Hash> routerHashes, Hash toPeer, TunnelId replyTunnel) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending closest routers to key " + key + ": # peers = " 
                       + routerHashes.size() + " tunnel " + replyTunnel);
        DatabaseSearchReplyMessage msg = new DatabaseSearchReplyMessage(getContext());
        msg.setFromHash(_us);
        msg.setSearchKey(key);
        int i = 0;
        for (Hash h : routerHashes) {
            msg.addReply(h);
            if (++i >= MAX_ROUTERS_RETURNED)
                break;
        }
        getContext().statManager().addRateData("netDb.lookupsHandled", 1);
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
        if (_us.equals(toPeer)) {
            // if we are the gateway, act as if we received it
            TunnelGatewayMessage m = new TunnelGatewayMessage(getContext());
            m.setMessage(message);
            m.setTunnelId(replyTunnel);
            m.setMessageExpiration(message.getMessageExpiration());
            getContext().tunnelDispatcher().dispatch(m);
        } else {
            // if we aren't the gateway, forward it on
            if (!_replyKeyConsumed) {
                // if we send a followup DSM w/ our RI, don't reuse key
                SessionKey replyKey = _message.getReplyKey();
                if (replyKey != null) {
                    // encrypt the reply
                    SessionTag tag = _message.getReplyTag();
                    if (tag != null) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Sending AES reply to " + toPeer + ' ' + replyKey + ' ' + tag);
                        message = MessageWrapper.wrap(getContext(), message, replyKey, tag);
                    } else {
                        RatchetSessionTag rtag = _message.getRatchetReplyTag();
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Sending AEAD reply to " + toPeer + ' ' + replyKey + ' ' + rtag);
                        message = MessageWrapper.wrap(getContext(), message, replyKey, rtag);
                    }
                    if (message == null) {
                        _log.error("DLM reply encryption error");
                        return;
                    }
                    _replyKeyConsumed = true;
                }
            }
            TunnelGatewayMessage m = new TunnelGatewayMessage(getContext());
            m.setMessage(message);
            m.setMessageExpiration(message.getMessageExpiration());
            m.setTunnelId(replyTunnel);
            SendMessageDirectJob j = new SendMessageDirectJob(getContext(), m, toPeer, 10*1000, MESSAGE_PRIORITY);
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
