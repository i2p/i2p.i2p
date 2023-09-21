package net.i2p.router.tunnel;

import java.util.List;

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
import net.i2p.data.i2np.OutboundTunnelBuildReplyMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.data.i2np.VariableTunnelBuildReplyMessage;
import net.i2p.router.ClientMessage;
import net.i2p.router.Job;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.message.GarlicMessageReceiver;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseSegmentor;
import net.i2p.router.networkdb.kademlia.FloodfillDatabaseStoreMessageHandler;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * When a message arrives at the inbound tunnel endpoint, this distributor
 * honors the instructions (safely)
 */
class InboundMessageDistributor implements GarlicMessageReceiver.CloveReceiver {
    private final RouterContext _context;
    private final Log _log;
    private final Hash _client;
    private final GarlicMessageReceiver _receiver;
    private final String _clientNickname;
    private final long _msgIDBloomXor;
    /**
     *  @param client null for router tunnel
     */
    public InboundMessageDistributor(RouterContext ctx, Hash client) {
        _context = ctx;
        _client = client;
        _log = ctx.logManager().getLog(InboundMessageDistributor.class);
        _receiver = new GarlicMessageReceiver(ctx, this, client);
        // all createRateStat in TunnelDispatcher

        if (_client != null) {
            TunnelPoolSettings clienttps = _context.tunnelManager().getInboundSettings(_client);
            if (_log.shouldLog(Log.DEBUG)){
                _log.debug("Initializing client for " + _client.toBase32());
                _log.debug("Initializing client (nickname: "
                           + clienttps.getDestinationNickname()
                           + " b32: " + _client.toBase32()
                           + ") InboundMessageDistributor with tunnel pool settings: " + clienttps);
            }
            _clientNickname = clienttps.getDestinationNickname();
            _msgIDBloomXor = clienttps.getMsgIdBloomXor();
        } else {
            _clientNickname = "NULL/Expl";
            _msgIDBloomXor = RandomSource.getInstance().nextLong(I2NPMessage.MAX_ID_VALUE);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Initializing null or exploratory InboundMessageDistributor");
        }

    }
    
    public void distribute(I2NPMessage msg, Hash target) {
        distribute(msg, target, null);
    }

    public void distribute(I2NPMessage msg, Hash target, TunnelId tunnel) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("IBMD for " + _clientNickname + " ("
                       + ((_client != null) ? _client.toBase32() : "null")
                       + ") to " + target + " / " + tunnel + " : " + msg);

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
                     DatabaseSearchReplyMessage orig = (DatabaseSearchReplyMessage) msg;
                     if (orig.getNumReplies() > 0) {
                         if (_log.shouldLog(Log.INFO))
                             _log.info("Removing replies from a DSRM down a tunnel for " + _client + ": " + msg);
                         DatabaseSearchReplyMessage newMsg = new DatabaseSearchReplyMessage(_context);
                         newMsg.setFromHash(orig.getFromHash());
                         newMsg.setSearchKey(orig.getSearchKey());
                         msg = newMsg;
                     }
                     break;

                case DatabaseStoreMessage.MESSAGE_TYPE:
                    DatabaseStoreMessage dsm = (DatabaseStoreMessage) msg;
                    if (dsm.getEntry().getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                        // FVSJ may result in an unsolicited RI store if the peer went non-ff.
                        // We handle this safely, so we don't ask him again.
                        // Todo: if peer was ff and RI is not ff, queue for exploration in netdb (but that isn't part of the facade now)
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Inbound DSM received down a tunnel for " + _clientNickname
                                      + " (" + _client.toBase32() + "): " + msg);
                        // Handle safely by just updating the caps table, after doing basic validation
                        Hash key = dsm.getKey();
                        if (_context.routerHash().equals(key))
                            return;
                        RouterInfo ri = (RouterInfo) dsm.getEntry();
                        ri.setReceivedBy(_client);
                        if (!key.equals(ri.getIdentity().getHash()))
                            return;
                        if (!ri.isValid())
                            return;
                        RouterInfo oldri = null;
                        if (_client != null)
                            oldri = _context.clientNetDb(_client).lookupRouterInfoLocally(key);
                        else
                            oldri = _context.netDb().lookupRouterInfoLocally(key);
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
                        _log.error("Dropping LS DSM w/ reply token down a tunnel for " + _client.toBase32() + ": " + msg);
                        return;
                    } else {
                        // allow DSM of our own key (used by FloodfillVerifyStoreJob)
                        // or other keys (used by IterativeSearchJob)
                        // as long as there's no reply token (we will never set a reply token but an attacker might)
                        ((LeaseSet)dsm.getEntry()).setReceivedBy(_client);
                    }
                    break;

                case DeliveryStatusMessage.MESSAGE_TYPE:
                case GarlicMessage.MESSAGE_TYPE:
                case OutboundTunnelBuildReplyMessage.MESSAGE_TYPE:
                case TunnelBuildReplyMessage.MESSAGE_TYPE:
                case VariableTunnelBuildReplyMessage.MESSAGE_TYPE:
                    // these are safe, handled below
                    break;

                default:
                    // drop it, since we should only get the above message types down
                    // client tunnels
                    _context.statManager().addRateData("tunnel.dropDangerousClientTunnelMessage", 1, type);
                    _log.error("Dropped dangerous message down a tunnel for " + _client.toBase32() + ": " + msg, new Exception("cause"));
                    return;

            } // switch
        } else { // client == null/exploratory
            // expl. tunnel
            switch (type) {
                case DatabaseStoreMessage.MESSAGE_TYPE:
                    DatabaseStoreMessage dsm = (DatabaseStoreMessage) msg;
                    if (dsm.getReplyToken() != 0) {
                        _context.statManager().addRateData("tunnel.dropDangerousExplTunnelMessage", 1, type);
                        _log.error("Dropping DSM w/ reply token down a expl. tunnel: " + msg);
                        return;
                    }
                    if (dsm.getEntry().isLeaseSet())
                        ((LeaseSet)dsm.getEntry()).setReceivedBy(_client);
                    break;

                case DatabaseSearchReplyMessage.MESSAGE_TYPE:
                case DeliveryStatusMessage.MESSAGE_TYPE:
                case GarlicMessage.MESSAGE_TYPE:
                case OutboundTunnelBuildReplyMessage.MESSAGE_TYPE:
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

        if ( (target == null) && (tunnel == null) ) {
            // Since the InboundMessageDistributor handles messages for the endpoint,
            // most messages that arrive here have both target==null and tunnel==null.
            // Messages with targeting instructions need careful handling, and will
            // typically be dropped because we're the endpoint.  Especially when they
            // specifically target this router (_context.routerHash().equals(target)).
            if (type == GarlicMessage.MESSAGE_TYPE) {
                // in case we're looking for replies to a garlic message (cough load tests cough)
                _context.inNetMessagePool().handleReplies(msg);
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("received garlic message in the tunnel, parse it out");
                _receiver.receive((GarlicMessage)msg);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("distributing inbound tunnel message into our inNetMessagePool"
                              + " (for client " + _clientNickname + " ("
                              + ((_client != null) ? _client.toBase32() : "null")
                              + ") to target=NULL/tunnel=NULL " + msg);
                // Tunnel Build Messages and Delivery Status Messages (used for tunnel
                // testing) need to go back to the inNetMessagePool, whether or not
                // they came through a client tunnel.
                if ( (type == OutboundTunnelBuildReplyMessage.MESSAGE_TYPE) ||
                     (type == TunnelBuildReplyMessage.MESSAGE_TYPE) ||
                     (type == VariableTunnelBuildReplyMessage.MESSAGE_TYPE) ||
                     (type == DeliveryStatusMessage.MESSAGE_TYPE)) {
                    _context.inNetMessagePool().add(msg, null, null, _msgIDBloomXor);
                    return;
                }

                // Handling of client tunnel messages need explicit handling
                // in the context of the client subDb.
                if (_client != null) {
                    String dbid = _context.netDbSegmentor().getDbidByHash(_client);
                    if (dbid == null) {
                        // This error shouldn't occur.  All clients should have their own netDb.
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Error, client (" + _clientNickname + ") dbid not found while processing messages in the IBMD.");
                            return;
                    }
                    // For now, the only client message we know how to handle here is a DSM.
                    // There aren't normally DSM messages here, but it should be safe to store
                    // them in the client netDb.
                    if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
                        DatabaseStoreMessage dsm = (DatabaseStoreMessage)msg;
                        // Ensure the reply info is cleared, just in case
                        dsm.setReplyToken(0);
                        dsm.setReplyTunnel(null);
                        dsm.setReplyGateway(null);

                        // We need to replicate some of the handling that was previously
                        // performed when these types of messages were passed back to
                        // the inNetMessagePool.
                        // There's important inline handling made when fetching the original messages.
                        List<OutNetMessage> origMessages = _context.messageRegistry().getOriginalMessages(msg);
                        int sz = origMessages.size();
                        if (sz > 0) {
                            dsm.setReceivedAsReply();
                        }
                        if (dsm.getEntry().isLeaseSet()) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info("[client: " + _clientNickname + "] Saving LS DSM from client tunnel.");
                            FloodfillDatabaseStoreMessageHandler _FDSMH = new FloodfillDatabaseStoreMessageHandler(_context, _context.clientNetDb(_client));
                            Job j = _FDSMH.createJob(msg, null, null);
                            j.runJob();
                            if (sz > 0) {
                                for (int i = 0; i < sz; i++) {
                                    OutNetMessage omsg = origMessages.get(i);
                                    ReplyJob job = omsg.getOnReplyJob();
                                    if (job != null) {
                                        if (_log.shouldLog(Log.DEBUG))
                                            _log.debug("Setting ReplyJob ("
                                                       + job + ") for original message:"
                                                       + omsg + "; with reply message [id: "
                                                       + msg.getUniqueId()
                                                       + " Class: "
                                                       + msg.getClass().getSimpleName()
                                                       + "] full message: " + msg);
                                        else if  (_log.shouldLog(Log.INFO))
                                            _log.info("Setting a ReplyJob ("
                                                      + job + ") for original message class "
                                                      + omsg.getClass().getSimpleName()
                                                      + " with reply message class "
                                                      + msg.getClass().getSimpleName());
                                         job.setMessage(msg);
                                         _context.jobQueue().addJob(job);
                                    }
                                }
                            }
                            return;
                        } else {
                            // drop it, since the data we receive shouldn't include router references.
                            _context.statManager().addRateData("tunnel.dropDangerousClientTunnelMessage", 1,
                                                               DatabaseStoreMessage.MESSAGE_TYPE);
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Dropped dangerous RI DSM message from a tunnel for " + _clientNickname
                                           + " ("+ _client.toBase32() + ") : " + dsm, new Exception("cause"));
                            return;
                        }
                    }
                    // Don't know what to do with other message types here.
                    // But, in testing, it is uncommon to end up here.
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("[client: " + _clientNickname + "] Dropping a client message from a tunnel due to lack of delivery handling instructions. Message: " + msg);
                    return;
                } else {
                    // These messages came down a exploratory tunnel since client == null.
                    _context.inNetMessagePool().add(msg, null, null, _msgIDBloomXor);
                }
            }
        } else if (_context.routerHash().equals(target)) {
            if (type == GarlicMessage.MESSAGE_TYPE)
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping inbound garlic message TARGETED TO OUR ROUTER for client "
                              + _clientNickname + " ("
                              + ((_client != null) ? _client.toBase32() : "null")
                              + ") to " + target + " / " + tunnel);
            else
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping inbound message TARGETED TO OUR ROUTER for client "
                              + _clientNickname + " (" + ((_client != null) ? _client.toBase32() : "null")
                              + ") to " + target + " / " + tunnel + " : " + msg);
            return;
        } else {
            if (type == GarlicMessage.MESSAGE_TYPE)
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping targeted inbound garlic message for client "
                              + _clientNickname + " ("
                              + ((_client != null) ? _client.toBase32() : "null")
                              + ") to " + target + " / " + tunnel);
            else
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping targeted inbound message for client " + _clientNickname
                              + " (" + ((_client != null) ? _client.toBase32() : "null")
                              + " to " + target + " / " + tunnel + " : " + msg);
            return;
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

                            if (dsm.getEntry().isLeaseSet()) {
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
                                    ((LeaseSet)dsm.getEntry()).setReceivedBy(_client);
                                    if (_log.shouldLog(Log.INFO))
                                        _log.info("Storing garlic LS down tunnel for: " + dsm.getKey() + " sent to: "
                                                  + _clientNickname + " ("
                                                  + (_client != null ? _client.toBase32() : ") router"));
                                    if (_client.toBase32() != null) {
                                        // We need to replicate some of the handling that was previously
                                        // performed when these types of messages were passed back to
                                        // the inNetMessagePool.
                                        // There's important inline handling made when fetching the original messages.
                                        List<OutNetMessage> origMessages = _context.messageRegistry().getOriginalMessages(data);
                                        int sz = origMessages.size();
                                        if (sz > 0)
                                            dsm.setReceivedAsReply();
                                        // ToDo: This should actually have a try and catch.
                                        if (_log.shouldLog(Log.INFO))
                                            _log.info("Store the LS in the correct dbid subDb: " + _client.toBase32());
                                        FloodfillDatabaseStoreMessageHandler _FDSMH = new FloodfillDatabaseStoreMessageHandler(_context, _context.clientNetDb(_client));
                                        Job j = _FDSMH.createJob(data, null, null);
                                        j.runJob();
                                        if (sz > 0) {
                                            for (int i = 0; i < sz; i++) {
                                                OutNetMessage omsg = origMessages.get(i);
                                                ReplyJob job = omsg.getOnReplyJob();
                                                if (job != null) {
                                                    if (_log.shouldLog(Log.DEBUG))
                                                        _log.debug("Setting ReplyJob ("
                                                                   + job + ") for original message:"
                                                                   + omsg + "; with reply message [id: "
                                                                   + data.getUniqueId()
                                                                   + " Class: "
                                                                   + data.getClass().getSimpleName()
                                                                   + "] full message: " + data);
                                                    else if  (_log.shouldLog(Log.INFO))
                                                        _log.info("Setting a ReplyJob ("
                                                                  + job + ") for original message class "
                                                                  + omsg.getClass().getSimpleName()
                                                                  + " with reply message class "
                                                                  + data.getClass().getSimpleName());
                                                    job.setMessage(data);
                                                    _context.jobQueue().addJob(job);
                                                }
                                            }
                                        }
                                    } else if (_client == null) {
                                        if (_log.shouldLog(Log.DEBUG))
                                            _log.info("Routing Exploratory Tunnel message back to the inNetMessagePool.");
                                        _context.inNetMessagePool().add(dsm, null, null, _msgIDBloomXor);
                                    } else {
                                        if (_log.shouldLog(Log.ERROR))
                                            _log.error("No handling provisions for message: " + data);
                                    }
                            } else {                                        
                                if (_client != null) {
                                    // drop it, since the data we receive shouldn't include router 
                                    // references, as that might get us to talk to them (and therefore
                                    // open an attack vector)
                                    _context.statManager().addRateData("tunnel.dropDangerousClientTunnelMessage", 1, 
                                                                       DatabaseStoreMessage.MESSAGE_TYPE);
                                    _log.error("Dropped dangerous message down a tunnel for " + _clientNickname
                                               + " ("+ _client.toBase32() + ") : " + dsm, new Exception("cause"));
                                    return;
                                }
                                // Case 3:
                                // Store of an RI (ours or somebody else's)
                                // This is almost certainly a response to an IterativeSearchJob search.
                                // We must send to the InNetMessagePool so the message can be matched
                                // and the search marked as successful.
                                // note that encrypted replies to RI lookups is currently disables in ISJ, we won't get here.

                                // ... and inject it.
                                _context.statManager().addRateData("tunnel.inboundI2NPGarlicRIDSM", 1);
                                if (_log.shouldLog(Log.INFO))
                                    _log.info("Storing garlic RI from exploratory tunnel for: "
                                              + dsm.getKey()
                                              + " dsm: " + dsm);
                                _context.inNetMessagePool().add(dsm, null, null, _msgIDBloomXor);
                            }
                } else if (_client != null && type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
                    // DSRMs show up here now that replies are encrypted
                    // TODO: Strip in IterativeLookupJob etc. instead, depending on
                    // LS or RI and client or expl., so that we can safely follow references
                    // in a reply to a LS lookup over client tunnels.
                    // ILJ would also have to follow references via client tunnels
                    DatabaseSearchReplyMessage orig = (DatabaseSearchReplyMessage) data;
                    if (orig.getNumReplies() > 0) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Removing replies from a garlic DSRM down a tunnel for " + _client + ": " + data);
                        DatabaseSearchReplyMessage newMsg = new DatabaseSearchReplyMessage(_context);
                        newMsg.setFromHash(orig.getFromHash());
                        newMsg.setSearchKey(orig.getSearchKey());
                        orig = newMsg;
                     }
                     // Client DSRM are safe to pass back to the inNetMessagePool when
                     // the replies are stripped.
                     // Even though the inNetMessagePool will lack information to understand
                     // the client context, DSRM will be matched against their search,
                     // which will place the handling back in the client context.
                     if (_log.shouldLog(Log.DEBUG))
                         _log.debug("Passing inbound garlic DSRM back to inNetMessagePool for client " + _clientNickname
                                   + "; msg: " + orig);
                     _context.inNetMessagePool().add(orig, null, null, _msgIDBloomXor);
                } else if (type == DataMessage.MESSAGE_TYPE) {
                        // a data message targetting the local router is how we send load tests (real
                        // data messages target destinations)
                        _context.statManager().addRateData("tunnel.handleLoadClove", 1);
                        data = null;
                        //_context.inNetMessagePool().add(data, null, null);
                } else if (_client != null && type != DeliveryStatusMessage.MESSAGE_TYPE &&
                           type != OutboundTunnelBuildReplyMessage.MESSAGE_TYPE) {
                            // drop it, since the data we receive shouldn't include other stuff, 
                            // as that might open an attack vector
                            _context.statManager().addRateData("tunnel.dropDangerousClientTunnelMessage", 1, 
                                                               data.getType());
                            _log.error("Dropped dangerous message received down a tunnel for "
                                       + _clientNickname + " (" + _client.toBase32() + ") : "
                                       + data, new Exception("cause"));
                } else {
                    if ((type == OutboundTunnelBuildReplyMessage.MESSAGE_TYPE) ||
                        (type == TunnelBuildReplyMessage.MESSAGE_TYPE) ||
                        (type == VariableTunnelBuildReplyMessage.MESSAGE_TYPE) ||
                        (type == DeliveryStatusMessage.MESSAGE_TYPE)) {
                        _context.inNetMessagePool().add(data, null, null, _msgIDBloomXor);
                    } else if (_client != null) {
                        _log.warn("Dropping inbound Message for client " + _clientNickname
                                  + " due to lack of handling instructions. Msg: " + data);
                    } else {
                        _context.inNetMessagePool().add(data, null, null, _msgIDBloomXor);
                    }
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
                        _log.debug("data message came down a tunnel for " + _client.toBase32());
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
                                       + _client.toBase32() + " targeting shared " + to.toBase32());
                        DataMessage dm = (DataMessage)data;
                        Payload payload = new Payload();
                        payload.setEncryptedData(dm.getData());
                        ClientMessage m = new ClientMessage(to, payload);
                        _context.clientManager().messageReceived(m);
                    } else {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Data message came down a tunnel for " 
                                   +  _client.toBase32() + " but targetted " + to.toBase32());
                    }
                } else {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Data message came down an exploratory tunnel targeting " + to);
                }
                return;

            case DeliveryInstructions.DELIVERY_MODE_ROUTER: // fall through
            case DeliveryInstructions.DELIVERY_MODE_TUNNEL:
                // Targeted messages are usually dropped, but it is safe to
                // allow distribute() to evaluate the message.
                if (_log.shouldLog(Log.INFO))
                    _log.info("Recursively handling message from targeted clove (for client:"
                              + _clientNickname + " " + ((_client != null) ? _client.toBase32() : "null")
                              + ", msg type: " + data.getClass().getSimpleName() + "): " + instructions.getRouter()
                              + ":" + instructions.getTunnelId() + " msg: " + data);

                distribute(data, instructions.getRouter(), instructions.getTunnelId());
                return;

            default:
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Unknown instruction " + instructions.getDeliveryMode() + ": " + instructions);
                return;
        }
    }
}
