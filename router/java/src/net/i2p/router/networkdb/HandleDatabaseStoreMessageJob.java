package net.i2p.router.networkdb;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Receive DatabaseStoreMessage data and store it in the local net db
 * Unused - see kademlia/ for replacement
 */
public class HandleDatabaseStoreMessageJob extends JobImpl {
    private Log _log;
    private DatabaseStoreMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;

    private static final int ACK_TIMEOUT = 15*1000;
    private static final int ACK_PRIORITY = 100;
    
    public HandleDatabaseStoreMessageJob(RouterContext ctx, DatabaseStoreMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        super(ctx);
        _log = ctx.logManager().getLog(HandleDatabaseStoreMessageJob.class);
        ctx.statManager().createRateStat("netDb.storeHandled", "How many netDb store messages have we handled?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("netDb.storeLeaseSetHandled", "How many leaseSet store messages have we handled?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("netDb.storeRouterInfoHandled", "How many routerInfo store messages have we handled?", "NetworkDatabase", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
    }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling database store message");

        // ERR: see comments regarding hidden mode in HandleDatabaseLookupMessageJob.  
        // // If we are a hidden peer, log and return
        // if (getContext().router().getRouterInfo().isHidden()) {
        //     if (_log.shouldLog(Log.ERROR)) {
        //         _log.error("Uninvited dbStore received (tunnel " + _message.getReplyTunnel() + ")");
        //     }
        //     return;
        // }

        String invalidMessage = null;
        boolean wasNew = false;
        DatabaseEntry entry = _message.getEntry();
        if (entry.getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
            getContext().statManager().addRateData("netDb.storeLeaseSetHandled", 1, 0);
   
            try {
                LeaseSet ls = (LeaseSet) entry;
                // mark it as something we received, so we'll answer queries 
                // for it.  this flag does NOT get set on entries that we 
                // receive in response to our own lookups.
                ls.setReceivedAsPublished(true);
                LeaseSet match = getContext().netDb().store(_message.getKey(), ls);
                if (match == null) {
                    wasNew = true;
                } else {
                    wasNew = false;
                    match.setReceivedAsPublished(true);
                }
            } catch (IllegalArgumentException iae) {
                invalidMessage = iae.getMessage();
            }
        } else if (entry.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            RouterInfo ri = (RouterInfo) entry;
            getContext().statManager().addRateData("netDb.storeRouterInfoHandled", 1, 0);
            if (_log.shouldLog(Log.INFO))
                _log.info("Handling dbStore of router " + _message.getKey() + " with publishDate of " 
                          + new Date(ri.getPublished()));
            try {
                Object match = getContext().netDb().store(_message.getKey(), ri);
                wasNew = (null == match);
                getContext().profileManager().heardAbout(_message.getKey());
            } catch (IllegalArgumentException iae) {
                invalidMessage = iae.getMessage();
            }
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid DatabaseStoreMessage data type - " + entry.getType() 
                           + ": " + _message);
        }
        
        if (_message.getReplyToken() > 0) 
            sendAck();
        
        if (_from != null)
            _fromHash = _from.getHash();
        if (_fromHash != null) {
            if (invalidMessage == null) {
                getContext().profileManager().dbStoreReceived(_fromHash, wasNew);
                getContext().statManager().addRateData("netDb.storeHandled", 1, 0);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Peer " + _fromHash.toBase64() + " sent bad data: " + invalidMessage);
            }
        }
    }
    
    private void sendAck() {
        DeliveryStatusMessage msg = new DeliveryStatusMessage(getContext());
        msg.setMessageId(_message.getReplyToken());
        msg.setArrival(getContext().clock().now());
        TunnelInfo outTunnel = selectOutboundTunnel();
        if (outTunnel == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No outbound tunnel could be found");
            return;
        } else {
            getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnel.getSendTunnelId(0), _message.getReplyTunnel(), _message.getReplyGateway());
        }
    }

    private TunnelInfo selectOutboundTunnel() {
        return getContext().tunnelManager().selectOutboundTunnel();
    }
 
    public String getName() { return "Handle Database Store Message"; }
    
    @Override
    public void dropped() {
        getContext().messageHistory().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Dropped due to overload");
    }
}
