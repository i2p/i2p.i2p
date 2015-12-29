package net.i2p.router.tunnel;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.data.i2np.VariableTunnelBuildReplyMessage;
import net.i2p.router.ClientMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.message.GarlicMessageReceiver;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.util.Log;

/**
 * When a message arrives at the inbound tunnel endpoint, this distributor
 * honors the instructions (safely)
 */
class InboundMessageDistributor implements GarlicMessageReceiver.CloveReceiver {
    private final RouterContext _context;
    private final Log _log;
    private final Hash _client;
    private final GarlicMessageReceiver _receiver;
    
    /**
     *  @param client null for router tunnel
     */
    public InboundMessageDistributor(RouterContext ctx, Hash client) {
        _context = ctx;
        _client = client;
        _log = ctx.logManager().getLog(InboundMessageDistributor.class);
        _receiver = new GarlicMessageReceiver(ctx, this, client);
        // all createRateStat in TunnelDispatcher
    }
    
    public void distribute(I2NPMessage msg, Hash target) {
        distribute(msg, target, null);
    }

    public void distribute(I2NPMessage msg, Hash target, TunnelId tunnel) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("IBMD for " + _client + " to " + target + " / " + tunnel + " : " + msg);

        // allow messages on client tunnels even after client disconnection, as it may
        // include e.g. test messages, etc.  DataMessages will be dropped anyway
        /*
        if ( (_client != null) && (!_context.clientManager().isLocal(_client)) ) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Not distributing a message, as it came down a client's tunnel (" 
                          + _client.toBase64() + ") after the client disconnected: " + msg);
            return;
        }
        */
        
        int type = msg.getType();

        // if the message came down a client tunnel:
        if (_client != null) {
            switch (type) {
                 case DatabaseSearchReplyMessage.MESSAGE_TYPE:
                     // FVSJ or client lookups could also result in a DSRM.
                     // Since there's some code that replies directly to this to gather new ff RouterInfos,
                     // sanitize it

                     // TODO: Strip in IterativeLookupJob etc. instead, depending on
                     // LS or RI and client or expl., so that we can safely follow references
                     // in a reply to a LS lookup over client tunnels.
                     // ILJ would also have to follow references via client tunnels
                  /****
                     DatabaseSearchReplyMessage orig = (DatabaseSearchReplyMessage) msg;
                     if (orig.getNumReplies() > 0) {
                         if (_log.shouldLog(Log.INFO))
                             _log.info("Removing replies from a DSRM down a tunnel for " + _client + ": " + msg);
                         DatabaseSearchReplyMessage newMsg = new DatabaseSearchReplyMessage(_context);
                         newMsg.setFromHash(orig.getFromHash());
                         newMsg.setSearchKey(orig.getSearchKey());
                         msg = newMsg;
                     }
                   ****/
                     break;

                case DatabaseStoreMessage.MESSAGE_TYPE:
                    DatabaseStoreMessage dsm = (DatabaseStoreMessage) msg;
                    if (dsm.getEntry().getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                        // FVSJ may result in an unsolicited RI store if the peer went non-ff.
                        // We handle this safely, so we don't ask him again.
                        // Todo: if peer was ff and RI is not ff, queue for exploration in netdb (but that isn't part of the facade now)
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping DSM down a tunnel for " + _client + ": " + msg);
                        // Handle safely by just updating the caps table, after doing basic validation
                        Hash key = dsm.getKey();
                        if (_context.routerHash().equals(key))
                            return;
                        RouterInfo ri = (RouterInfo) dsm.getEntry();
                        if (!key.equals(ri.getIdentity().getHash()))
                            return;
                        if (!ri.isValid())
                            return;
                        RouterInfo oldri = _context.netDb().lookupRouterInfoLocally(key);
                        // only update if RI is newer and non-ff
                        if (oldri != null && oldri.getPublished() < ri.getPublished() &&
                            !FloodfillNetworkDatabaseFacade.isFloodfill(ri)) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Updating caps for RI " + key + " from \"" +
                                          oldri.getCapabilities() + "\" to \"" + ri.getCapabilities() + '"');
                            _context.peerManager().setCapabilities(key, ri.getCapabilities());
                        }
                        return;
                    } else if (dsm.getReplyToken() != 0) {
                        _context.statManager().addRateData("tunnel.dropDangerousClientTunnelMessage", 1, type);
                        _log.error("Dropping LS DSM w/ reply token down a tunnel for " + _client + ": " + msg);
                        return;
                    } else {
                        // allow DSM of our own key (used by FloodfillVerifyStoreJob)
                        // or other keys (used by IterativeSearchJob)
                        // as long as there's no reply token (we will never set a reply token but an attacker might)
                        ((LeaseSet)dsm.getEntry()).setReceivedAsReply();
                    }
                    break;

                case DeliveryStatusMessage.MESSAGE_TYPE:
                case GarlicMessage.MESSAGE_TYPE:
                case TunnelBuildReplyMessage.MESSAGE_TYPE:
                case VariableTunnelBuildReplyMessage.MESSAGE_TYPE:
                    // these are safe, handled below
                    break;

                default:
                    // drop it, since we should only get the above message types down
                    // client tunnels
                    _context.statManager().addRateData("tunnel.dropDangerousClientTunnelMessage", 1, type);
                    _log.error("Dropped dangerous message down a tunnel for " + _client + ": " + msg, new Exception("cause"));
                    return;

            } // switch
        } else {
            // expl. tunnel
            switch (type) {
                case DatabaseStoreMessage.MESSAGE_TYPE:
                    DatabaseStoreMessage dsm = (DatabaseStoreMessage) msg;
                    if (dsm.getReplyToken() != 0) {
                        _context.statManager().addRateData("tunnel.dropDangerousExplTunnelMessage", 1, type);
                        _log.error("Dropping DSM w/ reply token down a expl. tunnel: " + msg);
                        return;
                    }
                    if (dsm.getEntry().getType() == DatabaseEntry.KEY_TYPE_LEASESET)
                        ((LeaseSet)dsm.getEntry()).setReceivedAsReply();
                    break;

                case DatabaseSearchReplyMessage.MESSAGE_TYPE:
                case DeliveryStatusMessage.MESSAGE_TYPE:
                case GarlicMessage.MESSAGE_TYPE:
                case TunnelBuildReplyMessage.MESSAGE_TYPE:
                case VariableTunnelBuildReplyMessage.MESSAGE_TYPE:
                    // these are safe, handled below
                    break;

                default:
                    _context.statManager().addRateData("tunnel.dropDangerousExplTunnelMessage", 1, type);
                    _log.error("Dropped dangerous message down expl tunnel: " + msg, new Exception("cause"));
                    return;
            } // switch
        } // client != null

        if ( (target == null) || ( (tunnel == null) && (_context.routerHash().equals(target) ) ) ) {
            // targetting us either implicitly (no target) or explicitly (no tunnel)
            // make sure we don't honor any remote requests directly (garlic instructions, etc)
            if (type == GarlicMessage.MESSAGE_TYPE) {
                // in case we're looking for replies to a garlic message (cough load tests cough)
                _context.inNetMessagePool().handleReplies(msg);
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("received garlic message in the tunnel, parse it out");
                _receiver.receive((GarlicMessage)msg);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("distributing inbound tunnel message into our inNetMessagePool: " + msg);
                _context.inNetMessagePool().add(msg, null, null);
            }
/****** latency measuring attack?
        } else if (_context.routerHash().equals(target)) {
            // the want to send it to a tunnel, except we are also that tunnel's gateway
            // dispatch it directly
            if (_log.shouldLog(Log.INFO))
                _log.info("distributing inbound tunnel message back out, except we are the gateway");
            TunnelGatewayMessage gw = new TunnelGatewayMessage(_context);
            gw.setMessage(msg);
            gw.setTunnelId(tunnel);
            gw.setMessageExpiration(_context.clock().now()+10*1000);
            gw.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            _context.tunnelDispatcher().dispatch(gw);
******/
        } else {
            // ok, they want us to send it remotely, but that'd bust our anonymity,
            // so we send it out a tunnel first
            // TODO use the OCMOSJ cache to pick OB tunnel we are already using?
            TunnelInfo out = _context.tunnelManager().selectOutboundTunnel(_client, target);
            if (out == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("no outbound tunnel to send the client message for " + _client + ": " + msg);
                return;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("distributing IB tunnel msg type " + type + " back out " + out
                          + " targetting " + target);
            TunnelId outId = out.getSendTunnelId(0);
            if (outId == null) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("strange? outbound tunnel has no outboundId? " + out 
                               + " failing to distribute " + msg);
                return;
            }
            long exp = _context.clock().now() + 20*1000;
            if (msg.getMessageExpiration() < exp)
                msg.setMessageExpiration(exp);
            _context.tunnelDispatcher().dispatchOutbound(msg, outId, tunnel, target);
        }
    }

    /**
     * Handle a clove removed from the garlic message
     *
     */
    public void handleClove(DeliveryInstructions instructions, I2NPMessage data) {
        int type = data.getType();
        switch (instructions.getDeliveryMode()) {
            case DeliveryInstructions.DELIVERY_MODE_LOCAL:
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("local delivery instructions for clove: " + data.getClass().getSimpleName());
                if (type == GarlicMessage.MESSAGE_TYPE) {
                    _receiver.receive((GarlicMessage)data);
                } else if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
                        // Treat db store explicitly here (not in HandleFloodfillDatabaseStoreMessageJob),
                        // since we don't want to republish (or flood)
                        // unnecessarily. Reply tokens ignored.
                        DatabaseStoreMessage dsm = (DatabaseStoreMessage)data;
                        // Ensure the reply info is cleared, just in case
                        dsm.setReplyToken(0);
                        dsm.setReplyTunnel(null);
                        dsm.setReplyGateway(null);

                            if (dsm.getEntry().getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
                                    // Case 1:
                                    // store of our own LS.
                                    // This is almost certainly a response to a FloodfillVerifyStoreJob search.
                                    // We must send to the InNetMessagePool so the message can be matched
                                    // and the verify marked as successful.

                                    // Case 2:
                                    // Store of somebody else's LS.
                                    // This could be an encrypted response to an IterativeSearchJob search.
                                    // We must send to the InNetMessagePool so the message can be matched
                                    // and the search marked as successful.
                                    // Or, it's a normal LS bundled with data and a MessageStatusMessage.

                                    // ... and inject it.
                                    ((LeaseSet)dsm.getEntry()).setReceivedAsReply();
                                    if (_log.shouldLog(Log.INFO))
                                        _log.info("Storing garlic LS down tunnel for: " + dsm.getKey() + " sent to: " + _client);
                                    _context.inNetMessagePool().add(dsm, null, null);
                            } else {                                        
                                if (_client != null) {
                                    // drop it, since the data we receive shouldn't include router 
                                    // references, as that might get us to talk to them (and therefore
                                    // open an attack vector)
                                    _context.statManager().addRateData("tunnel.dropDangerousClientTunnelMessage", 1, 
                                                                       DatabaseStoreMessage.MESSAGE_TYPE);
                                    _log.error("Dropped dangerous message down a tunnel for " + _client + ": " + dsm, new Exception("cause"));
                                    return;
                                }
                                // Case 3:
                                // Store of an RI (ours or somebody else's)
                                // This is almost certainly a response to an IterativeSearchJob search.
                                // We must send to the InNetMessagePool so the message can be matched
                                // and the search marked as successful.
                                // note that encrypted replies to RI lookups is currently disables in ISJ, we won't get here.

                                // ... and inject it.
                                if (_log.shouldLog(Log.INFO))
                                    _log.info("Storing garlic RI down tunnel for: " + dsm.getKey() + " sent to: " + _client);
                                _context.inNetMessagePool().add(dsm, null, null);
                            }
                } else if (_client != null && type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
                    // DSRMs show up here now that replies are encrypted
                    // TODO: Strip in IterativeLookupJob etc. instead, depending on
                    // LS or RI and client or expl., so that we can safely follow references
                    // in a reply to a LS lookup over client tunnels.
                    // ILJ would also have to follow references via client tunnels
                    DatabaseSearchReplyMessage orig = (DatabaseSearchReplyMessage) data;
                  /****
                    if (orig.getNumReplies() > 0) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Removing replies from a garlic DSRM down a tunnel for " + _client + ": " + data);
                        DatabaseSearchReplyMessage newMsg = new DatabaseSearchReplyMessage(_context);
                        newMsg.setFromHash(orig.getFromHash());
                        newMsg.setSearchKey(orig.getSearchKey());
                        orig = newMsg;
                     }
                   ****/
                    _context.inNetMessagePool().add(orig, null, null);
                } else if (type == DataMessage.MESSAGE_TYPE) {
                        // a data message targetting the local router is how we send load tests (real
                        // data messages target destinations)
                        _context.statManager().addRateData("tunnel.handleLoadClove", 1);
                        data = null;
                        //_context.inNetMessagePool().add(data, null, null);
                } else if (_client != null && type != DeliveryStatusMessage.MESSAGE_TYPE) {
                            // drop it, since the data we receive shouldn't include other stuff, 
                            // as that might open an attack vector
                            _context.statManager().addRateData("tunnel.dropDangerousClientTunnelMessage", 1, 
                                                               data.getType());
                            _log.error("Dropped dangerous message down a tunnel for " + _client + ": " + data, new Exception("cause"));
                } else {
                            _context.inNetMessagePool().add(data, null, null);
                }
                return;

            case DeliveryInstructions.DELIVERY_MODE_DESTINATION:
                Hash to = instructions.getDestination();
                // Can we route UnknownI2NPMessages to a destination too?
                if (type != DataMessage.MESSAGE_TYPE) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("cant send a " + data.getClass().getSimpleName() + " to a destination");
                } else if (_client != null && _client.equals(to)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("data message came down a tunnel for " + _client);
                    DataMessage dm = (DataMessage)data;
                    Payload payload = new Payload();
                    payload.setEncryptedData(dm.getData());
                    ClientMessage m = new ClientMessage(_client, payload);
                    _context.clientManager().messageReceived(m);
                } else if (_client != null) {
                    // Shared tunnel?
                    TunnelPoolSettings tgt = _context.tunnelManager().getInboundSettings(to);
                    if (tgt != null && _client.equals(tgt.getAliasOf())) {
                        // same as above, just different log
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("data message came down a tunnel for " 
                                       + _client + " targeting shared " + to);
                        DataMessage dm = (DataMessage)data;
                        Payload payload = new Payload();
                        payload.setEncryptedData(dm.getData());
                        ClientMessage m = new ClientMessage(to, payload);
                        _context.clientManager().messageReceived(m);
                    } else {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Data message came down a tunnel for " 
                                   +  _client + " but targetted " + to);
                    }
                } else {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Data message came down an exploratory tunnel targeting " + to);
                }
                return;

            case DeliveryInstructions.DELIVERY_MODE_ROUTER: // fall through
            case DeliveryInstructions.DELIVERY_MODE_TUNNEL:
                if (_log.shouldLog(Log.INFO))
                    _log.info("clove targetted " + instructions.getRouter() + ":" + instructions.getTunnelId()
                               + ", treat recursively to prevent leakage");
                distribute(data, instructions.getRouter(), instructions.getTunnelId());
                return;

            default:
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Unknown instruction " + instructions.getDeliveryMode() + ": " + instructions);
                return;
        }
    }
}
