package net.i2p.router.networkdb;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Iterator;
import java.util.Set;

import net.i2p.data.DataStructure;
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
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.util.Log;

/**
 * Handle a lookup for a key received from a remote peer.  Needs to be implemented
 * to send back replies, etc
 *
 */
public class HandleDatabaseLookupMessageJob extends JobImpl {
    private Log _log;
    private DatabaseLookupMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    private final static int MAX_ROUTERS_RETURNED = 3;
    private final static int REPLY_TIMEOUT = 60*1000;
    private final static int MESSAGE_PRIORITY = 300;
    
    public HandleDatabaseLookupMessageJob(RouterContext ctx, DatabaseLookupMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        super(ctx);
        _log = getContext().logManager().getLog(HandleDatabaseLookupMessageJob.class);
        getContext().statManager().createRateStat("netDb.lookupsHandled", "How many netDb lookups have we handled?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.lookupsMatched", "How many netDb lookups did we have the data for?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
    }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling database lookup message for " + _message.getSearchKey());

        Hash fromKey = _message.getFrom();

        if (_log.shouldLog(Log.DEBUG)) {
            if (_message.getReplyTunnel() != null)
                _log.debug("dbLookup received with replies going to " + fromKey 
                          + " (tunnel " + _message.getReplyTunnel() + ")");
        }

        if (getContext().netDb().lookupRouterInfoLocally(_message.getFrom()) == null) {
            // hmm, perhaps don't always send a lookup for this...
            // but for now, wtf, why not.  we may even want to adjust it so that 
            // we penalize or benefit peers who send us that which we can or
            // cannot lookup
            getContext().netDb().lookupRouterInfo(_message.getFrom(), null, null, REPLY_TIMEOUT);
        }

        // whatdotheywant?
        handleRequest(fromKey);
    }
    
    private void handleRequest(Hash fromKey) {
        LeaseSet ls = getContext().netDb().lookupLeaseSetLocally(_message.getSearchKey());
        if (ls != null) {
            // send that lease set to the _message.getFromHash peer
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("We do have key " + _message.getSearchKey().toBase64() 
                           + " locally as a lease set.  sending to " + fromKey.toBase64());
            sendData(_message.getSearchKey(), ls, fromKey, _message.getReplyTunnel());
        } else {
            RouterInfo info = getContext().netDb().lookupRouterInfoLocally(_message.getSearchKey());
            if (info != null) {
                // send that routerInfo to the _message.getFromHash peer
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("We do have key " + _message.getSearchKey().toBase64() 
                               + " locally as a router info.  sending to " + fromKey.toBase64());
                sendData(_message.getSearchKey(), info, fromKey, _message.getReplyTunnel());
            } else {
                // not found locally - return closest peer routerInfo structs
                Set routerInfoSet = getContext().netDb().findNearestRouters(_message.getSearchKey(), 
                                                                        MAX_ROUTERS_RETURNED, 
                                                                        _message.getDontIncludePeers());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("We do not have key " + _message.getSearchKey().toBase64() + 
                               " locally.  sending back " + routerInfoSet.size() + " peers to " + fromKey.toBase64());
                sendClosest(_message.getSearchKey(), routerInfoSet, fromKey, _message.getReplyTunnel());
            }
        }
    }
    
    private void sendData(Hash key, DataStructure data, Hash toPeer, TunnelId replyTunnel) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending data matching key key " + key.toBase64() + " to peer " + toPeer.toBase64() 
                       + " tunnel " + replyTunnel);
        DatabaseStoreMessage msg = new DatabaseStoreMessage(getContext());
        msg.setKey(key);
        if (data instanceof LeaseSet) {
            msg.setLeaseSet((LeaseSet)data);
            msg.setValueType(DatabaseStoreMessage.KEY_TYPE_LEASESET);
        } else if (data instanceof RouterInfo) {
            msg.setRouterInfo((RouterInfo)data);
            msg.setValueType(DatabaseStoreMessage.KEY_TYPE_ROUTERINFO);
        }
        getContext().statManager().addRateData("netDb.lookupsMatched", 1, 0);
        getContext().statManager().addRateData("netDb.lookupsHandled", 1, 0);
        sendMessage(msg, toPeer, replyTunnel);
    }
    
    private void sendClosest(Hash key, Set routerInfoSet, Hash toPeer, TunnelId replyTunnel) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending closest routers to key " + key.toBase64() + ": # peers = " 
                       + routerInfoSet.size() + " tunnel " + replyTunnel);
        DatabaseSearchReplyMessage msg = new DatabaseSearchReplyMessage(getContext());
        msg.setFromHash(getContext().router().getRouterInfo().getIdentity().getHash());
        msg.setSearchKey(key);
        for (Iterator iter = routerInfoSet.iterator(); iter.hasNext(); ) {
            RouterInfo peer = (RouterInfo)iter.next();
            msg.addReply(peer.getIdentity().getHash());
        }
        getContext().statManager().addRateData("netDb.lookupsHandled", 1, 0);
        sendMessage(msg, toPeer, replyTunnel); // should this go via garlic messages instead?
    }
    
    private void sendMessage(I2NPMessage message, Hash toPeer, TunnelId replyTunnel) {
        Job send = null;
        if (replyTunnel != null) {
            sendThroughTunnel(message, toPeer, replyTunnel);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending reply directly to " + toPeer);
            send = new SendMessageDirectJob(getContext(), message, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY);
        }

        getContext().netDb().lookupRouterInfo(toPeer, send, null, REPLY_TIMEOUT);
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
            getContext().jobQueue().addJob(j);
        }
    }
    
    private void sendToGateway(I2NPMessage message, Hash toPeer, TunnelId replyTunnel, TunnelInfo info) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Want to reply to a db request via a tunnel, but we're a participant in the reply!  so send it to the gateway");

        if ( (toPeer == null) || (replyTunnel == null) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Someone br0ke us.  where is this message supposed to go again?", getAddedBy());
            return;
        }

        long expiration = REPLY_TIMEOUT + getContext().clock().now();

        TunnelGatewayMessage msg = new TunnelGatewayMessage(getContext());
        msg.setMessage(message);
        msg.setTunnelId(replyTunnel);
        msg.setMessageExpiration(expiration);
        getContext().jobQueue().addJob(new SendMessageDirectJob(getContext(), msg, toPeer, null, null, null, null, REPLY_TIMEOUT, MESSAGE_PRIORITY));

        String bodyType = message.getClass().getName();
        getContext().messageHistory().wrap(bodyType, message.getUniqueId(), TunnelGatewayMessage.class.getName(), msg.getUniqueId());
    }
    
    public String getName() { return "Handle Database Lookup Message"; }

    public void dropped() {
        getContext().messageHistory().messageProcessingError(_message.getUniqueId(), 
                                                         _message.getClass().getName(), 
                                                         "Dropped due to overload");
    }
}
