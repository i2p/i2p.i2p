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
import net.i2p.router.JobQueue;
import net.i2p.router.MessageHistory;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.router.message.SendTunnelMessageJob;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.stat.StatManager;

/**
 * Handle a lookup for a key received from a remote peer.  Needs to be implemented
 * to send back replies, etc
 *
 */
public class HandleDatabaseLookupMessageJob extends JobImpl {
    private final static Log _log = new Log(HandleDatabaseLookupMessageJob.class);
    private DatabaseLookupMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    private final static int MAX_ROUTERS_RETURNED = 3;
    private final static int REPLY_TIMEOUT = 60*1000;
    private final static int MESSAGE_PRIORITY = 300;

    static {
        StatManager.getInstance().createRateStat("netDb.lookupsHandled", "How many netDb lookups have we handled?", "Network Database", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        StatManager.getInstance().createRateStat("netDb.lookupsMatched", "How many netDb lookups did we have the data for?", "Network Database", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
    }
    
    public HandleDatabaseLookupMessageJob(DatabaseLookupMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
    }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling database lookup message for " + _message.getSearchKey());

        Hash fromKey = _message.getFrom().getIdentity().getHash();
	
        if (_message.getReplyTunnel() != null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("dbLookup received with replies going to " + fromKey 
                          + " (tunnel " + _message.getReplyTunnel() + ")");
        }

        NetworkDatabaseFacade.getInstance().store(fromKey, _message.getFrom());
	
        LeaseSet ls = NetworkDatabaseFacade.getInstance().lookupLeaseSetLocally(_message.getSearchKey());
        if (ls != null) {
            // send that lease set to the _message.getFromHash peer
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("We do have key " + _message.getSearchKey().toBase64() 
                           + " locally as a lease set.  sending to " + fromKey.toBase64());
            sendData(_message.getSearchKey(), ls, fromKey, _message.getReplyTunnel());
        } else {
            RouterInfo info = NetworkDatabaseFacade.getInstance().lookupRouterInfoLocally(_message.getSearchKey());
            if (info != null) {
                // send that routerInfo to the _message.getFromHash peer
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("We do have key " + _message.getSearchKey().toBase64() 
                               + " locally as a router info.  sending to " + fromKey.toBase64());
                sendData(_message.getSearchKey(), info, fromKey, _message.getReplyTunnel());
            } else {
                // not found locally - return closest peer routerInfo structs
                Set routerInfoSet = NetworkDatabaseFacade.getInstance().findNearestRouters(_message.getSearchKey(), 
                                                             MAX_ROUTERS_RETURNED, _message.getDontIncludePeers());
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
        StatManager.getInstance().addRateData("netDb.lookupsMatched", 1, 0);
        DatabaseStoreMessage msg = new DatabaseStoreMessage();
        msg.setKey(key);
        if (data instanceof LeaseSet) {
            msg.setLeaseSet((LeaseSet)data);
            msg.setValueType(DatabaseStoreMessage.KEY_TYPE_LEASESET);
        } else if (data instanceof RouterInfo) {
            msg.setRouterInfo((RouterInfo)data);
            msg.setValueType(DatabaseStoreMessage.KEY_TYPE_ROUTERINFO);
        }
        sendMessage(msg, toPeer, replyTunnel);
    }
    
    private void sendClosest(Hash key, Set routerInfoSet, Hash toPeer, TunnelId replyTunnel) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending closest routers to key " + key.toBase64() + ": # peers = " 
                       + routerInfoSet.size() + " tunnel " + replyTunnel);
        DatabaseSearchReplyMessage msg = new DatabaseSearchReplyMessage();
        msg.setFromHash(Router.getInstance().getRouterInfo().getIdentity().getHash());
        msg.setSearchKey(key);
        if (routerInfoSet.size() <= 0) {
            // always include something, so lets toss ourselves in there
            routerInfoSet.add(Router.getInstance().getRouterInfo());
        }
        msg.addReplies(routerInfoSet);
        sendMessage(msg, toPeer, replyTunnel); // should this go via garlic messages instead?
    }
    
    private void sendMessage(I2NPMessage message, Hash toPeer, TunnelId replyTunnel) {
        StatManager.getInstance().addRateData("netDb.lookupsHandled", 1, 0);
        Job send = null;
        if (replyTunnel != null) {
            sendThroughTunnel(message, toPeer, replyTunnel);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending reply directly to " + toPeer);
            send = new SendMessageDirectJob(message, toPeer, REPLY_TIMEOUT+Clock.getInstance().now(), MESSAGE_PRIORITY);
        }

        NetworkDatabaseFacade.getInstance().lookupRouterInfo(toPeer, send, null, REPLY_TIMEOUT);
    }
    
    private void sendThroughTunnel(I2NPMessage message, Hash toPeer, TunnelId replyTunnel) {
        TunnelInfo info = TunnelManagerFacade.getInstance().getTunnelInfo(replyTunnel);
	
        // the sendTunnelMessageJob can't handle injecting into the tunnel anywhere but the beginning
        // (and if we are the beginning, we have the signing key)
        if ( (info == null) || (info.getSigningKey() != null)) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Sending reply through " + replyTunnel + " on " + toPeer);
            JobQueue.getInstance().addJob(new SendTunnelMessageJob(message, replyTunnel, toPeer, null, null, null, null, null, REPLY_TIMEOUT, MESSAGE_PRIORITY));
        } else {
            // its a tunnel we're participating in, but we're NOT the gateway, so 
            if (_log.shouldLog(Log.INFO))
                _log.info("Want to reply to a db request via a tunnel, but we're a participant in the reply!  so send it to the gateway");
	    
            if ( (toPeer == null) || (replyTunnel == null) ) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Someone br0ke us.  where is this message supposed to go again?", getAddedBy());
                return;
            }
	    
            long expiration = REPLY_TIMEOUT + Clock.getInstance().now();
	    
            TunnelMessage msg = new TunnelMessage();
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
                message.writeBytes(baos);
                msg.setData(baos.toByteArray());
                msg.setTunnelId(replyTunnel);
                msg.setMessageExpiration(new Date(expiration));
                JobQueue.getInstance().addJob(new SendMessageDirectJob(msg, toPeer, null, null, null, null, expiration, MESSAGE_PRIORITY));

                String bodyType = message.getClass().getName();
                MessageHistory.getInstance().wrap(bodyType, message.getUniqueId(), TunnelMessage.class.getName(), msg.getUniqueId());
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error writing out the tunnel message to send to the tunnel", ioe);
            } catch (DataFormatException dfe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error writing out the tunnel message to send to the tunnel", dfe);
            }
            return;
        }
    }
    
    public String getName() { return "Handle Database Lookup Message"; }

    public void dropped() {
        MessageHistory.getInstance().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Dropped due to overload");
    }
}
