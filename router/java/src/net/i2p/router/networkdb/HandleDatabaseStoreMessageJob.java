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
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.router.message.SendTunnelMessageJob;
import net.i2p.util.Log;

/**
 * Receive DatabaseStoreMessage data and store it in the local net db
 *
 */
public class HandleDatabaseStoreMessageJob extends JobImpl {
    private Log _log;
    private DatabaseStoreMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;

    private static final long ACK_TIMEOUT = 15*1000;
    private static final int ACK_PRIORITY = 100;
    
    public HandleDatabaseStoreMessageJob(RouterContext ctx, DatabaseStoreMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        super(ctx);
        _log = ctx.logManager().getLog(HandleDatabaseStoreMessageJob.class);
        ctx.statManager().createRateStat("netDb.storeHandled", "How many netDb store messages have we handled?", "Network Database", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
    }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling database store message");

        boolean invalid = false;
        boolean wasNew = false;
        if (_message.getValueType() == DatabaseStoreMessage.KEY_TYPE_LEASESET) {
            try {
                Object match = getContext().netDb().store(_message.getKey(), _message.getLeaseSet());
                wasNew = (null == match);
            } catch (IllegalArgumentException iae) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not storing a leaseSet", iae);
                invalid = true;
            }
        } else if (_message.getValueType() == DatabaseStoreMessage.KEY_TYPE_ROUTERINFO) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Handling dbStore of router " + _message.getKey() + " with publishDate of " 
                          + new Date(_message.getRouterInfo().getPublished()));
            try {
                Object match = getContext().netDb().store(_message.getKey(), _message.getRouterInfo());
                wasNew = (null == match);
                getContext().profileManager().heardAbout(_message.getKey());
            } catch (IllegalArgumentException iae) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not storing a routerInfo", iae);
                invalid = true;
            }
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid DatabaseStoreMessage data type - " + _message.getValueType() 
                           + ": " + _message);
        }
        
        if (_message.getReplyToken() > 0) 
            sendAck();
        
        if (_from != null)
            _fromHash = _from.getHash();
        if (_fromHash != null) {
            if (!invalid) {
                getContext().profileManager().dbStoreReceived(_fromHash, wasNew);
                getContext().statManager().addRateData("netDb.storeHandled", 1, 0);
            }
        }
    }
    
    private void sendAck() {
        DeliveryStatusMessage msg = new DeliveryStatusMessage(getContext());
        msg.setMessageId(_message.getReplyToken());
        msg.setArrival(new Date(getContext().clock().now()));
        TunnelId outTunnelId = selectOutboundTunnel();
        if (outTunnelId == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No outbound tunnel could be found");
            return;
        } else {
            getContext().jobQueue().addJob(new SendTunnelMessageJob(getContext(), msg, outTunnelId, 
                                           _message.getReplyGateway(), _message.getReplyTunnel(), 
                                           null, null, null, null, ACK_TIMEOUT, ACK_PRIORITY));
        }
    }

    private TunnelId selectOutboundTunnel() {
        TunnelSelectionCriteria criteria = new TunnelSelectionCriteria();
        criteria.setAnonymityPriority(80);
        criteria.setLatencyPriority(50);
        criteria.setReliabilityPriority(20);
        criteria.setMaximumTunnelsRequired(1);
        criteria.setMinimumTunnelsRequired(1);
        List tunnelIds = getContext().tunnelManager().selectOutboundTunnelIds(criteria);
        if (tunnelIds.size() <= 0) {
            _log.error("No outbound tunnels?!");
            return null;
        } else {
            return (TunnelId)tunnelIds.get(0);
        }
    }
 
    public String getName() { return "Handle Database Store Message"; }
    
    public void dropped() {
        getContext().messageHistory().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Dropped due to overload");
    }
}
