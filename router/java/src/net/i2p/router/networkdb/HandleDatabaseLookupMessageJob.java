package net.i2p.router.networkdb;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.Set;

import net.i2p.data.DataFormatException;
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
import net.i2p.data.i2np.TunnelMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.router.message.SendTunnelMessageJob;
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
        _log = _context.logManager().getLog(HandleDatabaseLookupMessageJob.class);
        _context.statManager().createRateStat("netDb.lookupsHandled", "How many netDb lookups have we handled?", "Network Database", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatched", "How many netDb lookups did we have the data for?", "Network Database", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
    }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling database lookup message for " + _message.getSearchKey());

        Hash fromKey = _message.getFrom().getIdentity().getHash();

        if (_log.shouldLog(Log.DEBUG)) {
            if (_message.getReplyTunnel() != null)
                _log.debug("dbLookup received with replies going to " + fromKey 
                          + " (tunnel " + _message.getReplyTunnel() + ")");
        }

        // might as well grab what they sent us
        _context.netDb().store(fromKey, _message.getFrom());

        // whatdotheywant?
        handleRequest(fromKey);
    }
    
    private void handleRequest(Hash fromKey) {
        LeaseSet ls = _context.netDb().lookupLeaseSetLocally(_message.getSearchKey());
        if (ls != null) {
            // send that lease set to the _message.getFromHash peer
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("We do have key " + _message.getSearchKey().toBase64() 
                           + " locally as a lease set.  sending to " + fromKey.toBase64());
            sendData(_message.getSearchKey(), ls, fromKey, _message.getReplyTunnel());
        } else {
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(_message.getSearchKey());
            if (info != null) {
                // send that routerInfo to the _message.getFromHash peer
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("We do have key " + _message.getSearchKey().toBase64() 
                               + " locally as a router info.  sending to " + fromKey.toBase64());
                sendData(_message.getSearchKey(), info, fromKey, _message.getReplyTunnel());
            } else {
                // not found locally - return closest peer routerInfo structs
                Set routerInfoSet = _context.netDb().findNearestRouters(_message.getSearchKey(), 
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
        DatabaseStoreMessage msg = new DatabaseStoreMessage(_context);
        msg.setKey(key);
        if (data instanceof LeaseSet) {
            msg.setLeaseSet((LeaseSet)data);
            msg.setValueType(DatabaseStoreMessage.KEY_TYPE_LEASESET);
        } else if (data instanceof RouterInfo) {
            msg.setRouterInfo((RouterInfo)data);
            msg.setValueType(DatabaseStoreMessage.KEY_TYPE_ROUTERINFO);
        }
        _context.statManager().addRateData("netDb.lookupsMatched", 1, 0);
        _context.statManager().addRateData("netDb.lookupsHandled", 1, 0);
        sendMessage(msg, toPeer, replyTunnel);
    }
    
    private void sendClosest(Hash key, Set routerInfoSet, Hash toPeer, TunnelId replyTunnel) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending closest routers to key " + key.toBase64() + ": # peers = " 
                       + routerInfoSet.size() + " tunnel " + replyTunnel);
        DatabaseSearchReplyMessage msg = new DatabaseSearchReplyMessage(_context);
        msg.setFromHash(_context.router().getRouterInfo().getIdentity().getHash());
        msg.setSearchKey(key);
        if (routerInfoSet.size() <= 0) {
            // always include something, so lets toss ourselves in there
            routerInfoSet.add(_context.router().getRouterInfo());
        }
        msg.addReplies(routerInfoSet);
        _context.statManager().addRateData("netDb.lookupsHandled", 1, 0);
        sendMessage(msg, toPeer, replyTunnel); // should this go via garlic messages instead?
    }
    
    private void sendMessage(I2NPMessage message, Hash toPeer, TunnelId replyTunnel) {
        Job send = null;
        if (replyTunnel != null) {
            sendThroughTunnel(message, toPeer, replyTunnel);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending reply directly to " + toPeer);
            send = new SendMessageDirectJob(_context, message, toPeer, REPLY_TIMEOUT+_context.clock().now(), MESSAGE_PRIORITY);
        }

        _context.netDb().lookupRouterInfo(toPeer, send, null, REPLY_TIMEOUT);
    }
    
    private void sendThroughTunnel(I2NPMessage message, Hash toPeer, TunnelId replyTunnel) {
        TunnelInfo info = _context.tunnelManager().getTunnelInfo(replyTunnel);
	
        // the sendTunnelMessageJob can't handle injecting into the tunnel anywhere but the beginning
        // (and if we are the beginning, we have the signing key)
        if ( (info == null) || (info.getSigningKey() != null)) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending reply through " + replyTunnel + " on " + toPeer);
            _context.jobQueue().addJob(new SendTunnelMessageJob(_context, message, replyTunnel, toPeer, null, null, null, null, null, REPLY_TIMEOUT, MESSAGE_PRIORITY));
        } else {
            // its a tunnel we're participating in, but we're NOT the gateway, so 
            sendToGateway(message, toPeer, replyTunnel, info);
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

        long expiration = REPLY_TIMEOUT + _context.clock().now();

        TunnelMessage msg = new TunnelMessage(_context);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            message.writeBytes(baos);
            msg.setData(baos.toByteArray());
            msg.setTunnelId(replyTunnel);
            msg.setMessageExpiration(new Date(expiration));
            _context.jobQueue().addJob(new SendMessageDirectJob(_context, msg, toPeer, null, null, null, null, expiration, MESSAGE_PRIORITY));

            String bodyType = message.getClass().getName();
            _context.messageHistory().wrap(bodyType, message.getUniqueId(), TunnelMessage.class.getName(), msg.getUniqueId());
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing out the tunnel message to send to the tunnel", ioe);
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing out the tunnel message to send to the tunnel", dfe);
        }
    }
    
    public String getName() { return "Handle Database Lookup Message"; }

    public void dropped() {
        _context.messageHistory().messageProcessingError(_message.getUniqueId(), 
                                                         _message.getClass().getName(), 
                                                         "Dropped due to overload");
    }
}
