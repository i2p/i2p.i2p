package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Payload;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.data.i2np.TunnelMessage;
import net.i2p.data.i2np.TunnelVerificationStructure;
import net.i2p.router.ClientMessage;
import net.i2p.router.InNetMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageReceptionInfo;
import net.i2p.router.MessageSelector;
import net.i2p.router.ReplyJob;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

public class HandleTunnelMessageJob extends JobImpl {
    private Log _log;
    private TunnelMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    private I2NPMessageHandler _handler;
    
    private final static int FORWARD_TIMEOUT = 60*1000;
    private final static int FORWARD_PRIORITY = 400;
    
    public HandleTunnelMessageJob(RouterContext ctx, TunnelMessage msg, RouterIdentity from, Hash fromHash) {
        super(ctx);
        _log = ctx.logManager().getLog(HandleTunnelMessageJob.class);
        _handler = new I2NPMessageHandler(ctx);
        ctx.statManager().createRateStat("tunnel.unknownTunnelTimeLeft", "How much time is left on tunnel messages we receive that are for unknown tunnels?", "Tunnels", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.gatewayMessageSize", "How large are the messages we are forwarding on as an inbound gateway?", "Tunnels", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.relayMessageSize", "How large are the messages we are forwarding on as a participant in a tunnel?", "Tunnels", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.endpointMessageSize", "How large are the messages we are forwarding in as an outbound endpoint?", "Tunnels", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.expiredAfterAcceptTime", "How long after expiration do we finally start running an expired tunnel message?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _message = msg;
        _from = from;
        _fromHash = fromHash;
    }
    
    private TunnelInfo validate(TunnelId id) {
        long excessLag = getContext().clock().now() - _message.getMessageExpiration().getTime();
        if (excessLag > Router.CLOCK_FUDGE_FACTOR) {
            // expired while on the queue
            if (_log.shouldLog(Log.WARN))
                _log.warn("Accepted message (" + _message.getUniqueId() + ") expired on the queue for tunnel " 
                           + id.getTunnelId() + " expiring " 
                           + excessLag
                           + "ms ago");
            getContext().statManager().addRateData("tunnel.expiredAfterAcceptTime", excessLag, excessLag);
            getContext().messageHistory().messageProcessingError(_message.getUniqueId(), 
                                                             TunnelMessage.class.getName(), 
                                                             "tunnel message expired on the queue");
            return null;
        } else if (excessLag > 0) {
            // almost expired while on the queue
            if (_log.shouldLog(Log.WARN))
                _log.warn("Accepted message (" + _message.getUniqueId() + ") *almost* expired on the queue for tunnel " 
                           + id.getTunnelId() + " expiring " 
                           + excessLag
                           + "ms ago");
        } else {
            // not expired
        }
        
        TunnelInfo info = getContext().tunnelManager().getTunnelInfo(id);
	
        if (info == null) {
            Hash from = _fromHash;
            if (_from != null)
                from = _from.getHash();
            getContext().messageHistory().droppedTunnelMessage(id, _message.getUniqueId(), 
                                                               _message.getMessageExpiration(), 
                                                               from);
            if (_log.shouldLog(Log.ERROR))
                _log.error("Received a message for an unknown tunnel [" + id.getTunnelId() 
                           + "], dropping it: " + _message, getAddedBy());
            long timeRemaining = _message.getMessageExpiration().getTime() - getContext().clock().now();
            getContext().statManager().addRateData("tunnel.unknownTunnelTimeLeft", timeRemaining, 0);
            long lag = getTiming().getActualStart() - getTiming().getStartAfter();
            if (_log.shouldLog(Log.ERROR))
                _log.error("Lag processing a dropped tunnel message: " + lag);
            return null;
        }
        
        info = getUs(info);
        if (info == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("We are not part of a known tunnel?? wtf!  drop.", getAddedBy());
            long timeRemaining = _message.getMessageExpiration().getTime() - getContext().clock().now();
            getContext().statManager().addRateData("tunnel.unknownTunnelTimeLeft", timeRemaining, 0);
            return null;
        }

        return info;
    }

    /**
     * The current router may be the gateway to the tunnel since there is no
     * verification data, or it could be a b0rked message.
     *
     */
    private void receiveUnverified(TunnelInfo info) {
        if (info.getSigningKey() != null) {
            if (info.getNextHop() != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("We are the gateway to tunnel " + info.getTunnelId().getTunnelId());
                byte data[] = _message.getData();
                I2NPMessage msg = getBody(data);
                getContext().jobQueue().addJob(new HandleGatewayMessageJob(msg, info, data.length));
                return;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("We are the gateway and the endpoint for tunnel " + info.getTunnelId().getTunnelId());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Process locally");
                if (info.getDestination() != null) {
                    if (!getContext().clientManager().isLocal(info.getDestination())) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Received a message on a tunnel allocated to a client that has disconnected - dropping it!");
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Dropping message for disconnected client: " + _message);

                        getContext().messageHistory().droppedOtherMessage(_message);
                        getContext().messageHistory().messageProcessingError(_message.getUniqueId(), 
                                                                         _message.getClass().getName(), 
                                                                         "Disconnected client");
                        return;
                    }
                }

                I2NPMessage body = getBody(_message.getData());
                if (body != null) {
                    getContext().jobQueue().addJob(new HandleLocallyJob(body, info));
                    return;
                } else {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Body is null!  content of message.getData() = [" + 
                                   DataHelper.toString(_message.getData()) + "]", getAddedBy());
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Message that failed: " + _message, getAddedBy());
                    return;
                }
            }
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Received a message that we are not the gateway for on tunnel " 
                           + info.getTunnelId().getTunnelId() 
                           + " without a verification structure: " + _message, getAddedBy());
            return;
        }
    }

    /**
     * We may be a participant in the tunnel, as there is a verification structure.
     *
     */
    private void receiveParticipant(TunnelInfo info) {
        // participant
        TunnelVerificationStructure struct = _message.getVerificationStructure();
        boolean ok = struct.verifySignature(getContext(), info.getVerificationKey().getKey());
        if (!ok) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Failed tunnel verification!  Spoofing / tagging attack?  " + _message, getAddedBy());
            return;
        } else {
            if (info.getNextHop() != null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Message for tunnel " + info.getTunnelId().getTunnelId() 
                              + " received where we're not the gateway and there are remaining hops, so forward it on to " 
                              + info.getNextHop().toBase64() + " via SendTunnelMessageJob");

                getContext().statManager().addRateData("tunnel.relayMessageSize", 
                                                       _message.getData().length, 0);

                TunnelMessage msg = new TunnelMessage(getContext());
                msg.setData(_message.getData());
                msg.setEncryptedDeliveryInstructions(_message.getEncryptedDeliveryInstructions());
                msg.setTunnelId(info.getNextHopId());
                msg.setVerificationStructure(_message.getVerificationStructure());
                msg.setMessageExpiration(_message.getMessageExpiration());
                
                int timeoutMs = (int)(_message.getMessageExpiration().getTime() - getContext().clock().now());
                if (timeoutMs < 1000) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Message " + _message.getUniqueId() + " is valid and we would pass it on through tunnel "
                                   + info.getTunnelId().getTunnelId() + ", but its too late (expired " + timeoutMs + "ms ago)");
                    return;
                }
                
                SendMessageDirectJob j = new SendMessageDirectJob(getContext(), msg, 
                                                                  info.getNextHop(), 
                                                                  timeoutMs,
                                                                  FORWARD_PRIORITY);
                getContext().jobQueue().addJob(j);
                return;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("No more hops, unwrap and follow the instructions");
                getContext().jobQueue().addJob(new HandleEndpointJob(info));
                return;
            }
        }
    }
    
    public String getName() { return "Handle Inbound Tunnel Message"; }
    public void runJob() {
        TunnelId id = _message.getTunnelId();

        TunnelInfo info = validate(id);
        if (info == null) 
            return;
        
        info.messageProcessed();
        
        //if ( (_message.getVerificationStructure() == null) && (info.getSigningKey() != null) ) {
        if (_message.getVerificationStructure() == null) {
            receiveUnverified(info);
        } else {
            receiveParticipant(info);
        }
    }
    
    private void processLocally(TunnelInfo ourPlace) {
        if (ourPlace.getEncryptionKey() == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Argh, somehow we don't have the decryption key and we have no more steps", getAddedBy());
            return;
        }
        DeliveryInstructions instructions = getInstructions(_message.getEncryptedDeliveryInstructions(), 
                                                            ourPlace.getEncryptionKey().getKey());
        if (instructions == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("We are the endpoint of a non-zero length tunnel and we don't have instructions.  DROP.", getAddedBy());
            return;
        } else {
            I2NPMessage body = null;
            if (instructions.getEncrypted()) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Body in the tunnel IS encrypted");
                body = decryptBody(_message.getData(), instructions.getEncryptionKey());
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Body in the tunnel is NOT encrypted: " + instructions 
                               + "\n" + _message, new Exception("Hmmm..."));
                body = getBody(_message.getData());
            }

            if (body == null) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Unable to recover the body from the tunnel", getAddedBy());
                return;
            } else {
                getContext().jobQueue().addJob(new ProcessBodyLocallyJob(body, instructions, ourPlace));
            }
        }
    }
    
    private void honorInstructions(DeliveryInstructions instructions, I2NPMessage body) {
        getContext().statManager().addRateData("tunnel.endpointMessageSize", _message.getData().length, 0);

        switch (instructions.getDeliveryMode()) {
            case DeliveryInstructions.DELIVERY_MODE_LOCAL:
                sendToLocal(body);
                break;
            case DeliveryInstructions.DELIVERY_MODE_ROUTER:
                if (getContext().routerHash().equals(instructions.getRouter())) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Delivery instructions point at a router, but we're that router, so send to local");
                    sendToLocal(body);
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Delivery instructions point at a router, and we're not that router, so forward it off");
                    sendToRouter(instructions.getRouter(), body);
                }
                break;
            case DeliveryInstructions.DELIVERY_MODE_TUNNEL:
                sendToTunnel(instructions.getRouter(), instructions.getTunnelId(), body);
                break;
            case DeliveryInstructions.DELIVERY_MODE_DESTINATION:
                sendToDest(instructions.getDestination(), body);
                break;
        }
    }
    
    private void sendToDest(Hash dest, I2NPMessage body) {
        if (body instanceof DataMessage) {
            boolean isLocal = getContext().clientManager().isLocal(dest);
            if (isLocal) {
                deliverMessage(null, dest, (DataMessage)body);
                return;
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Delivery to remote destinations is not yet supported", getAddedBy());
                return;
            }
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Deliver something other than a DataMessage to a Destination?  I don't think so.");
            return;
        }
    }
    
    private void sendToTunnel(Hash router, TunnelId id, I2NPMessage body) {
        // TODO: we may want to send it via a tunnel later on, but for now, direct will do.
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending on to requested tunnel " + id.getTunnelId() + " on router " 
                       + router.toBase64());
        TunnelMessage msg = new TunnelMessage(getContext());
        msg.setTunnelId(id);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            body.writeBytes(baos);
            msg.setData(baos.toByteArray());
            getContext().jobQueue().addJob(new SendMessageDirectJob(getContext(), msg, router, FORWARD_TIMEOUT, FORWARD_PRIORITY));

            String bodyType = body.getClass().getName();
            getContext().messageHistory().wrap(bodyType, body.getUniqueId(), TunnelMessage.class.getName(), msg.getUniqueId());	
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing out the message to forward to the tunnel", dfe);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing out the message to forward to the tunnel", ioe);
        }
    }
    
    private void sendToRouter(Hash router, I2NPMessage body) {
        // TODO: we may want to send it via a tunnel later on, but for now, direct will do.
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending on to requested router " + router.toBase64());
        getContext().jobQueue().addJob(new SendMessageDirectJob(getContext(), body, router, FORWARD_TIMEOUT, FORWARD_PRIORITY));
    }
    
    private void sendToLocal(I2NPMessage body) {
        InNetMessage msg = new InNetMessage(getContext());
        msg.setMessage(body);
        msg.setFromRouter(_from);
        msg.setFromRouterHash(_fromHash);
        getContext().inNetMessagePool().add(msg);
    }
    
    private void deliverMessage(Destination dest, Hash destHash, DataMessage msg) {
        boolean valid = getContext().messageValidator().validateMessage(msg.getUniqueId(), msg.getMessageExpiration().getTime());
        if (!valid) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Duplicate data message received [" + msg.getUniqueId() 
                          + " expiring on " + msg.getMessageExpiration() + "]");
            getContext().messageHistory().droppedOtherMessage(msg);
            getContext().messageHistory().messageProcessingError(msg.getUniqueId(), msg.getClass().getName(), 
                                                             "Duplicate payload");
            return;
        }

        ClientMessage cmsg = new ClientMessage();

        Payload payload = new Payload();
        payload.setEncryptedData(msg.getData());

        MessageReceptionInfo info = new MessageReceptionInfo();
        info.setFromPeer(_fromHash);
        info.setFromTunnel(_message.getTunnelId());
	
        cmsg.setDestination(dest);
        cmsg.setDestinationHash(destHash);
        cmsg.setPayload(payload);
        cmsg.setReceptionInfo(info);
	
        getContext().messageHistory().receivePayloadMessage(msg.getUniqueId());
        // if the destination isn't local, the ClientMessagePool forwards it off as an OutboundClientMessageJob
        getContext().clientMessagePool().add(cmsg);
    }
    
    private I2NPMessage getBody(byte body[]) {
        try {
            return _handler.readMessage(new ByteArrayInputStream(body));
        } catch (I2NPMessageException ime) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error parsing the message body", ime);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the message body", ioe);
        } 
        return null;
    }
    
    private I2NPMessage decryptBody(byte encryptedMessage[], SessionKey key) {
        byte iv[] = new byte[16];
        Hash h = getContext().sha().calculateHash(key.getData());
        System.arraycopy(h.getData(), 0, iv, 0, iv.length);
        byte decrypted[] = getContext().AESEngine().safeDecrypt(encryptedMessage, key, iv);
        if (decrypted == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error decrypting the message", getAddedBy());
            return null;
        }
        return getBody(decrypted);
    }
    
    private DeliveryInstructions getInstructions(byte encryptedInstructions[], SessionKey key) {
        try {
            byte iv[] = new byte[16];
            Hash h = getContext().sha().calculateHash(key.getData());
            System.arraycopy(h.getData(), 0, iv, 0, iv.length);
            byte decrypted[] = getContext().AESEngine().safeDecrypt(encryptedInstructions, key, iv);
            if (decrypted == null) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error decrypting the instructions", getAddedBy());
                return null;
            }
            DeliveryInstructions instructions = new DeliveryInstructions();
            instructions.readBytes(new ByteArrayInputStream(decrypted));
            return instructions;
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error parsing the decrypted instructions", dfe);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the decrypted instructions", ioe);
        }
        return null;
    }
    
    private TunnelInfo getUs(TunnelInfo info) {
        Hash us = getContext().routerHash();
        while (info != null) {
            if (us.equals(info.getThisHop()))
                return info;
            info = info.getNextHopInfo();
        }
        return null;
    }
    
    private boolean validateMessage(TunnelMessage msg, TunnelInfo info) {
        TunnelVerificationStructure vstruct = _message.getVerificationStructure();
        if (vstruct == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Verification structure missing.  invalid");
            return false;
        }
	
        if ( (info.getVerificationKey() == null) || (info.getVerificationKey().getKey() == null) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("wtf, no verification key for the tunnel? " + info, getAddedBy());
            return false;
        }
	
        if (!vstruct.verifySignature(getContext(), info.getVerificationKey().getKey())) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Received a tunnel message with an invalid signature!");
            // shitlist the sender?
            return false;
        }
	
        // now validate the message
        Hash msgHash = getContext().sha().calculateHash(_message.getData());
        if (msgHash.equals(vstruct.getMessageHash())) {
            // hash matches.  good.
            return true;
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("validateMessage: Signed hash does not match real hash.  Data has been tampered with!");
            // shitlist the sender!
            return false;
        }		    
    }
 
    public void dropped() {
        getContext().messageHistory().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), 
                                                         "Dropped due to overload");
    }
    
    ////
    // series of subjobs for breaking this task into smaller components
    ////
    
    /** we're the gateway, lets deal */
    private class HandleGatewayMessageJob extends JobImpl {
        private I2NPMessage _body;
        private int _length;
        private TunnelInfo _info;

        public HandleGatewayMessageJob(I2NPMessage body, TunnelInfo tunnel, int length) {
            super(HandleTunnelMessageJob.this.getContext());
            _body = body;
            _length = length;
            _info = tunnel;
        }
        public void runJob() {
            RouterContext ctx = HandleTunnelMessageJob.this.getContext();
            if (_body != null) {
                long expiration = _body.getMessageExpiration().getTime();
                long timeout = expiration - ctx.clock().now();
                ctx.statManager().addRateData("tunnel.gatewayMessageSize", _length, 0);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Message for tunnel " + _info.getTunnelId() 
                              + " received at the gateway (us), and since its > 0 length, forward the " 
                              + _body.getClass().getName() + " message on to " 
                              + _info.getNextHop().toBase64() + " via SendTunnelMessageJob expiring in " 
                              + timeout + "ms");
                
                MessageSelector selector = null;
                Job onFailure = null;
                Job onSuccess = null;
                ReplyJob onReply = null;
                Hash targetRouter = null;
                TunnelId targetTunnelId = null;
                SendTunnelMessageJob j = new SendTunnelMessageJob(ctx, _body, _info.getNextHopId(), targetRouter, targetTunnelId, onSuccess, onReply, onFailure, selector, timeout, FORWARD_PRIORITY);
                ctx.jobQueue().addJob(j);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Body of the message for the tunnel could not be parsed");
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Message that failed: " + _message);
            }
        }
        public String getName() { return "Handle Tunnel Message (gateway)"; }
    }
    
    /** zero hop tunnel */
    private class HandleLocallyJob extends JobImpl {
        private I2NPMessage _body;
        private TunnelInfo _info;
	
        public HandleLocallyJob(I2NPMessage body, TunnelInfo tunnel) {
            super(HandleTunnelMessageJob.this.getContext());
            _body = body;
            _info = tunnel;
        }
	
        public void runJob() {
            if (_body instanceof DataMessage) {
                // we know where to send it and its something a client can handle, so lets send 'er to the client
                if (_log.shouldLog(Log.WARN))
                    _log.debug("Deliver the message to a local client, as its a payload message and we know the destination");
                if (_log.shouldLog(Log.INFO))
                    _log.info("Message for tunnel " + _info.getTunnelId() + " received at the gateway (us), but its a 0 length tunnel and the message is a DataMessage, so send it to " 
                              + _info.getDestination().calculateHash().toBase64());
                deliverMessage(_info.getDestination(), null, (DataMessage)_body);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Message for tunnel " + _info.getTunnelId() + 
                              " received at the gateway (us), but its a 0 length tunnel though it is a " 
                              + _body.getClass().getName() + ", so process it locally");
                InNetMessage msg = new InNetMessage(HandleLocallyJob.this.getContext());
                msg.setFromRouter(_from);
                msg.setFromRouterHash(_fromHash);
                msg.setMessage(_body);
                HandleLocallyJob.this.getContext().inNetMessagePool().add(msg);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Message added to Inbound network pool for local processing: " + _message);
            }
        }
        public String getName() { return "Handle Tunnel Message (0 hop)"; }
    }
    
    /** we're the endpoint of the inbound tunnel */
    private class HandleEndpointJob extends JobImpl {
        private TunnelInfo _info;
        public HandleEndpointJob(TunnelInfo info) {
            super(HandleTunnelMessageJob.this.getContext());
            _info = info;
        }
        public void runJob() {
            processLocally(_info);
        }
        public String getName() { return "Handle Tunnel Message (inbound endpoint)"; }
    }
    
    /** endpoint of outbound 1+ hop tunnel with instructions */
    private class ProcessBodyLocallyJob extends JobImpl {
        private I2NPMessage _body;
        private TunnelInfo _ourPlace;
        private DeliveryInstructions _instructions;
        public ProcessBodyLocallyJob(I2NPMessage body, DeliveryInstructions instructions, TunnelInfo ourPlace) {
            super(HandleTunnelMessageJob.this.getContext());
            _body = body;
            _instructions = instructions;
            _ourPlace = ourPlace;
        }
        public void runJob() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Body read: " + _body);
            if ( (_ourPlace.getDestination() != null) && (_body instanceof DataMessage) ) {
                // we know where to send it and its something a client can handle, so lets send 'er to the client
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Deliver the message to a local client, as its a payload message and we know the destination");
                if (_log.shouldLog(Log.INFO))
                    _log.info("Message for tunnel " + _ourPlace.getTunnelId().getTunnelId() 
                              + " received where we're the endpoint containing a DataMessage message, so deliver it to " 
                              + _ourPlace.getDestination().calculateHash().toBase64());
                deliverMessage(_ourPlace.getDestination(), null, (DataMessage)_body);
                return;
            } else {
                // Honor the delivery instructions
                if (_log.shouldLog(Log.INFO))
                    _log.info("Message for tunnel " + _ourPlace.getTunnelId().getTunnelId() 
                              + " received where we're the endpoint containing a " 
                              + _body.getClass().getName() + " message, so honor the delivery instructions: " 
                              + _instructions.toString());
                honorInstructions(_instructions, _body);
                return;
            }
        }
        public String getName() { return "Handle Tunnel Message  (outbound endpoint)"; }
    }
}
