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
import java.util.Set;

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
 *
 */
public class HandleFloodfillDatabaseStoreMessageJob extends JobImpl {
    private Log _log;
    private DatabaseStoreMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    private FloodfillNetworkDatabaseFacade _facade;

    private static final int ACK_TIMEOUT = 15*1000;
    private static final int ACK_PRIORITY = 100;
    
    public HandleFloodfillDatabaseStoreMessageJob(RouterContext ctx, DatabaseStoreMessage receivedMessage, RouterIdentity from, Hash fromHash, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
        _facade = facade;
    }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling database store message");

        long recvBegin = System.currentTimeMillis();
        
        String invalidMessage = null;
        boolean wasNew = false;
        RouterInfo prevNetDb = null;
        Hash key = _message.getKey();
        if (_message.getValueType() == DatabaseStoreMessage.KEY_TYPE_LEASESET) {
            getContext().statManager().addRateData("netDb.storeLeaseSetHandled", 1, 0);
            if (_log.shouldLog(Log.INFO))
                _log.info("Handling dbStore of leaseset " + _message);
                //_log.info("Handling dbStore of leasset " + key + " with expiration of " 
                //          + new Date(_message.getLeaseSet().getEarliestLeaseDate()));
   
            try {
                // Never store a leaseSet for a local dest received from somebody else.
                // This generally happens from a FloodfillVerifyStoreJob.
                // If it is valid, it shouldn't be newer than what we have - unless
                // somebody has our keys... 
                // This could happen with multihoming - where it's really important to prevent
                // storing the other guy's leaseset, it will confuse us badly.
                if (getContext().clientManager().isLocal(key)) {
                    //getContext().statManager().addRateData("netDb.storeLocalLeaseSetAttempt", 1, 0);
                    // throw rather than return, so that we send the ack below (prevent easy attack)
                    throw new IllegalArgumentException("Peer attempted to store local leaseSet: " +
                                                       key.toBase64().substring(0, 4));
                }
                LeaseSet ls = _message.getLeaseSet();
                // mark it as something we received, so we'll answer queries 
                // for it.  this flag does NOT get set on entries that we 
                // receive in response to our own lookups.
                ls.setReceivedAsPublished(true);
                LeaseSet match = getContext().netDb().store(key, _message.getLeaseSet());
                if ( (match == null) || (match.getEarliestLeaseDate() < _message.getLeaseSet().getEarliestLeaseDate()) ) {
                    wasNew = true;
                } else {
                    wasNew = false;
                    match.setReceivedAsPublished(true);
                }
            } catch (IllegalArgumentException iae) {
                invalidMessage = iae.getMessage();
            }
        } else if (_message.getValueType() == DatabaseStoreMessage.KEY_TYPE_ROUTERINFO) {
            getContext().statManager().addRateData("netDb.storeRouterInfoHandled", 1, 0);
            if (_log.shouldLog(Log.INFO))
                _log.info("Handling dbStore of router " + key + " with publishDate of " 
                          + new Date(_message.getRouterInfo().getPublished()));
            try {
                // Never store our RouterInfo received from somebody else.
                // This generally happens from a FloodfillVerifyStoreJob.
                // If it is valid, it shouldn't be newer than what we have - unless
                // somebody has our keys... 
                if (getContext().routerHash().equals(key)) {
                    //getContext().statManager().addRateData("netDb.storeLocalRouterInfoAttempt", 1, 0);
                    // throw rather than return, so that we send the ack below (prevent easy attack)
                    throw new IllegalArgumentException("Peer attempted to store our RouterInfo");
                }
                prevNetDb = getContext().netDb().store(key, _message.getRouterInfo());
                wasNew = ((null == prevNetDb) || (prevNetDb.getPublished() < _message.getRouterInfo().getPublished()));
                // Check new routerinfo address against blocklist
                if (wasNew) {
                    if (prevNetDb == null) {
                        if ((!getContext().shitlist().isShitlistedForever(key)) &&
                            getContext().blocklist().isBlocklisted(key) &&
                            _log.shouldLog(Log.WARN))
                                _log.warn("Blocklisting new peer " + key);
                    } else {
                        Set oldAddr = prevNetDb.getAddresses();
                        Set newAddr = _message.getRouterInfo().getAddresses();
                        if (newAddr != null && (!newAddr.equals(oldAddr)) &&
                            (!getContext().shitlist().isShitlistedForever(key)) &&
                            getContext().blocklist().isBlocklisted(key) &&
                            _log.shouldLog(Log.WARN))
                                _log.warn("New address received, Blocklisting old peer " + key);
                    }
                }
                getContext().profileManager().heardAbout(key);
            } catch (IllegalArgumentException iae) {
                invalidMessage = iae.getMessage();
            }
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid DatabaseStoreMessage data type - " + _message.getValueType() 
                           + ": " + _message);
        }
        
        long recvEnd = System.currentTimeMillis();
        getContext().statManager().addRateData("netDb.storeRecvTime", recvEnd-recvBegin, 0);
        
        if (_message.getReplyToken() > 0) 
            sendAck();
        long ackEnd = System.currentTimeMillis();
        
        if (_from != null)
            _fromHash = _from.getHash();
        if (_fromHash != null) {
            if (invalidMessage == null) {
                getContext().profileManager().dbStoreReceived(_fromHash, wasNew);
                getContext().statManager().addRateData("netDb.storeHandled", ackEnd-recvEnd, 0);
            } else {
                // Should we record in the profile?
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Peer " + _fromHash.toBase64() + " sent bad data: " + invalidMessage);
            }
        } else if (invalidMessage != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unknown peer sent bad data: " + invalidMessage);
        }

        // flood it
        if (invalidMessage == null &&
            FloodfillNetworkDatabaseFacade.floodfillEnabled(getContext()) &&
            _message.getReplyToken() > 0) {
            if (wasNew) {
                // DOS prevention
                // Note this does not throttle the ack above
                if (_facade.shouldThrottleFlood(key)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Too many recent stores, not flooding key: " + key);
                    getContext().statManager().addRateData("netDb.floodThrottled", 1, 0);
                    return;
                }
                long floodBegin = System.currentTimeMillis();
                if (_message.getValueType() == DatabaseStoreMessage.KEY_TYPE_LEASESET)
                    _facade.flood(_message.getLeaseSet());
                // ERR: see comment in HandleDatabaseLookupMessageJob regarding hidden mode
                //else if (!_message.getRouterInfo().isHidden())
                else
                    _facade.flood(_message.getRouterInfo());
                long floodEnd = System.currentTimeMillis();
                getContext().statManager().addRateData("netDb.storeFloodNew", floodEnd-floodBegin, 0);
            } else {
                // don't flood it *again*
                getContext().statManager().addRateData("netDb.storeFloodOld", 1, 0);
            }
        }
    }
    
    private void sendAck() {
        DeliveryStatusMessage msg = new DeliveryStatusMessage(getContext());
        msg.setMessageId(_message.getReplyToken());
        msg.setArrival(getContext().clock().now());
        /*
        if (FloodfillNetworkDatabaseFacade.floodfillEnabled(getContext())) {
            // no need to do anything but send it where they ask
            TunnelGatewayMessage tgm = new TunnelGatewayMessage(getContext());
            tgm.setMessage(msg);
            tgm.setTunnelId(_message.getReplyTunnel());
            tgm.setMessageExpiration(msg.getMessageExpiration());
            
            getContext().jobQueue().addJob(new SendMessageDirectJob(getContext(), tgm, _message.getReplyGateway(), 10*1000, 200));
        } else {
         */
            TunnelInfo outTunnel = selectOutboundTunnel();
            if (outTunnel == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("No outbound tunnel could be found");
                return;
            } else {
                getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnel.getSendTunnelId(0), _message.getReplyTunnel(), _message.getReplyGateway());
            }
        //}
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
