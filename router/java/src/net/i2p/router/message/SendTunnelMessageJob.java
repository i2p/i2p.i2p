package net.i2p.router.message;
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

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.Payload;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelMessage;
import net.i2p.data.i2np.TunnelVerificationStructure;
import net.i2p.router.ClientMessage;
import net.i2p.router.InNetMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageReceptionInfo;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Send a message down a tunnel that we are the gateway for
 *
 */
public class SendTunnelMessageJob extends JobImpl {
    private Log _log;
    private I2NPMessage _message;
    private Hash _destRouter;
    private TunnelId _tunnelId;
    private TunnelId _targetTunnelId;
    private Job _onSend;
    private ReplyJob _onReply;
    private Job _onFailure;
    private MessageSelector _selector;
    private long _timeout;
    private long _expiration;
    private int _priority;
    
    public SendTunnelMessageJob(RouterContext ctx, I2NPMessage msg, TunnelId tunnelId, Job onSend, ReplyJob onReply, Job onFailure, MessageSelector selector, long timeoutMs, int priority) {
        this(ctx, msg, tunnelId, null, null, onSend, onReply, onFailure, selector, timeoutMs, priority);
    }
    
    public SendTunnelMessageJob(RouterContext ctx, I2NPMessage msg, TunnelId tunnelId, Hash targetRouter, TunnelId targetTunnelId, Job onSend, ReplyJob onReply, Job onFailure, MessageSelector selector, long timeoutMs, int priority) {
        super(ctx);
        _log = ctx.logManager().getLog(SendTunnelMessageJob.class);
        if (msg == null)
            throw new IllegalArgumentException("wtf, null message?  sod off");
        if (tunnelId == null)
            throw new IllegalArgumentException("wtf, no tunnelId? nuh uh");
        _message = msg;
        _destRouter = targetRouter;
        _tunnelId = tunnelId;
        _targetTunnelId = targetTunnelId;
        _onSend = onSend;
        _onReply = onReply;
        _onFailure = onFailure;
        _selector = selector;
        _timeout = timeoutMs;
        _priority = priority;
        
        if (timeoutMs < 50*1000) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Sending tunnel message to expire in " + timeoutMs 
                          + "ms containing " + msg.getUniqueId() + " (a " 
                          + msg.getClass().getName() + ")", 
                          new Exception("SendTunnel from"));
        }
        //_log.info("Send tunnel message " + msg.getClass().getName() + " to " + _destRouter + " over " + _tunnelId + " targetting tunnel " + _targetTunnelId, new Exception("SendTunnel from"));
        _expiration = getContext().clock().now() + timeoutMs;
    }
    
    public void runJob() {
        TunnelInfo info = getContext().tunnelManager().getTunnelInfo(_tunnelId);
        if (info == null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Message for unknown tunnel [" + _tunnelId 
                           + "] received, forward to " + _destRouter);
            if ( (_tunnelId == null) || (_destRouter == null) ) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Someone br0ke us.  where is this message supposed to go again?", 
                               getAddedBy());
                return;
            } else {
                forwardToGateway();
                return;
            }
        }
        
        info.messageProcessed();
        
        if (isEndpoint(info)) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Tunnel message where we're both the gateway and the endpoint - honor instructions");
            honorInstructions(info);
            return;
        } else if (isGateway(info)) {
            handleAsGateway(info);
            return;
        } else {
            handleAsParticipant(info);
            return;
        }
    }

    /**
     * Forward this job's message to the gateway of the tunnel requested
     *
     */
    private void forwardToGateway() {
        TunnelMessage msg = new TunnelMessage(getContext());
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            _message.writeBytes(baos);
            msg.setData(baos.toByteArray());
            msg.setTunnelId(_tunnelId);
            msg.setMessageExpiration(new Date(_expiration));
            getContext().jobQueue().addJob(new SendMessageDirectJob(getContext(), msg, 
                                                                _destRouter, _onSend, 
                                                                _onReply, _onFailure, 
                                                                _selector, 
                                                                (int)(_expiration-getContext().clock().now()), 
                                                                _priority));

            String bodyType = _message.getClass().getName();
            getContext().messageHistory().wrap(bodyType, _message.getUniqueId(), 
                                           TunnelMessage.class.getName(), msg.getUniqueId());
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing out the tunnel message to send to the tunnel", ioe);
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing out the tunnel message to send to the tunnel", dfe);
        }
        return;
    }
    
    /**
     * We are the gateway for the tunnel this message is bound to,
     * so wrap it accordingly and send it on its way.
     *
     */
    private void handleAsGateway(TunnelInfo info) {
        // since we are the gateway, we don't need to verify the data structures
        TunnelInfo us = getUs(info);
        if (us == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("We are not participating in this /known/ tunnel - was the router reset?");
            if (_onFailure != null)
                getContext().jobQueue().addJob(_onFailure);
        } else {
            // we're the gateway, so sign, encrypt, and forward to info.getNextHop()
            TunnelMessage msg = prepareMessage(info);
            if (msg == null) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("wtf, unable to prepare a tunnel message to the next hop, when we're the gateway and hops remain?  tunnel: " + info);
                if (_onFailure != null)
                    getContext().jobQueue().addJob(_onFailure);
                return;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Tunnel message created: " + msg + " out of encrypted message: " 
                           + _message);
            long now = getContext().clock().now();
            if (_expiration < now + 15*1000) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Adding a tunnel message that will expire shortly [" 
                              + new Date(_expiration) + "]", getAddedBy());
            }
            msg.setMessageExpiration(new Date(_expiration));
            getContext().jobQueue().addJob(new SendMessageDirectJob(getContext(), msg, 
                                                                    info.getNextHop(), _onSend, 
                                                                    _onReply, _onFailure, 
                                                                    _selector, 
                                                                    (int)(_expiration - getContext().clock().now()), 
                                                                    _priority));
        }
    }
    
    /**
     * We are the participant in the tunnel, so verify the signature / data and
     * forward it to the next hop.
     *
     */
    private void handleAsParticipant(TunnelInfo info) {
        // SendTunnelMessageJob shouldn't be used for participants!
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("SendTunnelMessageJob for a participant... ", getAddedBy());
        
        if (!(_message instanceof TunnelMessage)) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Cannot inject non-tunnel messages as a participant!" + _message, getAddedBy());
            if (_onFailure != null)
                getContext().jobQueue().addJob(_onFailure);
            return;
        }
        
        TunnelMessage msg = (TunnelMessage)_message;
        
        TunnelVerificationStructure struct = msg.getVerificationStructure();
        if ( (info.getVerificationKey() == null) || (info.getVerificationKey().getKey() == null) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("No verification key for the participant? tunnel: " + info, getAddedBy());
            if (_onFailure != null)
                getContext().jobQueue().addJob(_onFailure);
            return;
        }
        
        boolean ok = struct.verifySignature(getContext(), info.getVerificationKey().getKey());
        if (!ok) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Failed tunnel verification!  Spoofing / tagging attack?  " + _message, getAddedBy());
            if (_onFailure != null)
                getContext().jobQueue().addJob(_onFailure);
            return;
        } else {
            if (info.getNextHop() != null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Message for tunnel " + info.getTunnelId().getTunnelId() + " received where we're not the gateway and there are remaining hops, so forward it on to "
                              + info.getNextHop().toBase64() + " via SendMessageDirectJob");
                SendMessageDirectJob j = new SendMessageDirectJob(getContext(), msg, info.getNextHop(), _onSend, 
                                                                  null, _onFailure, null, 
                                                                  (int)(_message.getMessageExpiration().getTime() - getContext().clock().now()), 
                                                                  _priority);
                getContext().jobQueue().addJob(j);
                return;
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Should not be reached - participant, but no more hops?!");
                if (_onFailure != null)
                    getContext().jobQueue().addJob(_onFailure);
                return;
            }
        }
    }
    
    
    /** find our place in the tunnel */
    private TunnelInfo getUs(TunnelInfo info) {
        Hash us = getContext().routerHash();
        TunnelInfo lastUs = null;
        while (info != null) {
            if (us.equals(info.getThisHop()))
                lastUs = info;
            info = info.getNextHopInfo();
        }
        return lastUs;
    }
    
    /** are we the endpoint for the tunnel? */
    private boolean isEndpoint(TunnelInfo info) {
        TunnelInfo us = getUs(info);
        if (us == null) return false;
        return (us.getNextHop() == null);
    }
    
    /** are we the gateway for the tunnel? */
    private boolean isGateway(TunnelInfo info) {
        TunnelInfo us = getUs(info);
        if (us == null) return false;
        return (us.getSigningKey() != null); // only the gateway can sign
    }
    
    private static final int INSTRUCTIONS_PADDING = 32;
    private static final int PAYLOAD_PADDING = 32;
    
    /**
     * Build the tunnel message with appropriate instructions for the
     * tunnel endpoint, then encrypt and sign it.
     *
     */
    private TunnelMessage prepareMessage(TunnelInfo info) {
        TunnelMessage msg = new TunnelMessage(getContext());
        
        SessionKey key = getContext().keyGenerator().generateSessionKey();
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDelayRequested(false);
        instructions.setEncrypted(true);
        instructions.setEncryptionKey(key);
        
        // if we aren't told where to send it, have it be processed locally at the endpoint
        // but if we are, have the endpoint forward it appropriately.
        // note that this algorithm does not currently support instructing the endpoint to send to a Destination
        if (_destRouter != null) {
            instructions.setRouter(_destRouter);
            if (_targetTunnelId != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Instructions target tunnel " + _targetTunnelId 
                               + " on router " + _destRouter.calculateHash());
                instructions.setTunnelId(_targetTunnelId);
                instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_TUNNEL);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Instructions target router " + _destRouter.toBase64());
                instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_ROUTER);
            }
        } else {
            if (_message instanceof DataMessage) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Instructions are for local message delivery at the endpoint with a DataMessage to be sent to a Destination");
                instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Instructions are for local delivery at the endpoint targetting the now-local router");
                instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);
            }
        }
        
        if (info == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Tunnel info is null to send message " + _message);
            return null;
        } else if ( (info.getEncryptionKey() == null) || (info.getEncryptionKey().getKey() == null) ) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Tunnel encryption key is null when we're the gateway?! info: " + info);
            return null;
        }
        
        byte encryptedInstructions[] = encrypt(instructions, info.getEncryptionKey().getKey(), INSTRUCTIONS_PADDING);
        byte encryptedMessage[] = encrypt(_message, key, PAYLOAD_PADDING);
        TunnelVerificationStructure verification = createVerificationStructure(encryptedMessage, info);
        
        String bodyType = _message.getClass().getName();
        getContext().messageHistory().wrap(bodyType, _message.getUniqueId(), TunnelMessage.class.getName(), msg.getUniqueId());
 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Tunnel message prepared: instructions = " + instructions);
        
        msg.setData(encryptedMessage);
        msg.setEncryptedDeliveryInstructions(encryptedInstructions);
        msg.setTunnelId(_tunnelId);
        msg.setVerificationStructure(verification);
        return msg;
    }
    
    /**
     * Create and sign the verification structure, using the tunnel's signing key
     *
     */
    private TunnelVerificationStructure createVerificationStructure(byte encryptedMessage[], TunnelInfo info) {
        TunnelVerificationStructure struct = new TunnelVerificationStructure();
        struct.setMessageHash(getContext().sha().calculateHash(encryptedMessage));
        struct.sign(getContext(), info.getSigningKey().getKey());
        return struct;
    }
    
    /**
     * encrypt the structure (the message or instructions) 
     *
     * @param paddedSize minimum size to pad to
     */
    private byte[] encrypt(DataStructure struct, SessionKey key, int paddedSize) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(paddedSize);
            struct.writeBytes(baos);
            
            byte iv[] = new byte[16];
            Hash h = getContext().sha().calculateHash(key.getData());
            System.arraycopy(h.getData(), 0, iv, 0, iv.length);
            return getContext().AESEngine().safeEncrypt(baos.toByteArray(), key, iv, paddedSize);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing out data to encrypt", ioe);
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error formatting data to encrypt", dfe);
        }
        return null;
    }
    
    /**
     * We are both the endpoint and gateway for the tunnel, so honor 
     * what was requested of us (processing the message locally, 
     * forwarding to a router, forwarding to a tunnel, etc)
     *
     */
    private void honorInstructions(TunnelInfo info) {
        if (_selector != null)
            createFakeOutNetMessage();
        
        if (_onSend != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Firing onSend as we're honoring the instructions");
            getContext().jobQueue().addJob(_onSend);
        }
        
        // since we are the gateway, we don't need to decrypt the delivery instructions or the payload
        
        RouterIdentity ident = getContext().router().getRouterInfo().getIdentity();
        
        if (_destRouter != null) {
            honorSendRemote(info, ident);
        } else {
            honorSendLocal(info, ident);
        }
    }
    
    /**
     * We are the gateway and endpoint and we have been asked to forward the
     * message to a remote location (either a tunnel or a router).  
     *
     */
    private void honorSendRemote(TunnelInfo info, RouterIdentity ident) {
        I2NPMessage msg = null;
        if (_targetTunnelId != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Forward " + _message.getClass().getName() 
                           + " message off to remote tunnel " 
                           + _targetTunnelId.getTunnelId() + " on router " 
                           + _destRouter.toBase64());
            TunnelMessage tmsg = new TunnelMessage(getContext());
            tmsg.setEncryptedDeliveryInstructions(null);
            tmsg.setTunnelId(_targetTunnelId);
            tmsg.setVerificationStructure(null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            try {
                _message.writeBytes(baos);
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error writing out the message to be forwarded...??", ioe);
            } catch (DataFormatException dfe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error writing message to be forwarded...???", dfe);
            }
            tmsg.setData(baos.toByteArray());
            msg = tmsg;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Forward " + _message.getClass().getName() 
                           + " message off to remote router " + _destRouter.toBase64());
            msg = _message;
        }
        long now = getContext().clock().now();
        //if (_expiration < now) {
        //_expiration = now + Router.CLOCK_FUDGE_FACTOR;
        //_log.info("Fudging the message send so it expires in the fudge factor...");
        //}

        if (_expiration - 10*1000 < now) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Why are we trying to send a " + _message.getClass().getName() 
                           + " message with " + (_expiration-now) + "ms left?", getAddedBy());
        }

        String bodyType = _message.getClass().getName();
        getContext().messageHistory().wrap(bodyType, _message.getUniqueId(), 
                                       TunnelMessage.class.getName(), msg.getUniqueId());

        //  don't specify a selector, since createFakeOutNetMessage already does that
        SendMessageDirectJob j = new SendMessageDirectJob(getContext(), msg, _destRouter, 
                                                          _onSend, _onReply, _onFailure, 
                                                          null, (int)(_expiration-getContext().clock().now()), 
                                                          _priority);
        getContext().jobQueue().addJob(j);
    }

    /** 
     * We are the gateway and endpoint, and the instructions say to forward the 
     * message to, uh, us.  The message may be a normal network message or they
     * may be a client DataMessage.
     *
     */ 
    private void honorSendLocal(TunnelInfo info, RouterIdentity ident) {
        if ( (info.getDestination() == null) || !(_message instanceof DataMessage) ) {
            // its a network message targeting us...
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Destination is null or its not a DataMessage - pass it off to the InNetMessagePool");
            InNetMessage msg = new InNetMessage(getContext());
            msg.setFromRouter(ident);
            msg.setFromRouterHash(ident.getHash());
            msg.setMessage(_message);
            msg.setReplyBlock(null);
            getContext().inNetMessagePool().add(msg);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Destination is not null and it is a DataMessage - pop it into the ClientMessagePool");
            DataMessage msg = (DataMessage)_message;
            boolean valid = getContext().messageValidator().validateMessage(msg.getUniqueId(), msg.getMessageExpiration().getTime());
            if (!valid) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Duplicate data message received [" + msg.getUniqueId() + " expiring on " + msg.getMessageExpiration() + "]");
                getContext().messageHistory().droppedOtherMessage(msg);
                getContext().messageHistory().messageProcessingError(msg.getUniqueId(), msg.getClass().getName(), "Duplicate");
                return;
            }

            Payload payload = new Payload();
            payload.setEncryptedData(msg.getData());

            MessageReceptionInfo receptionInfo = new MessageReceptionInfo();
            receptionInfo.setFromPeer(ident.getHash());
            receptionInfo.setFromTunnel(_tunnelId);

            ClientMessage clientMessage = new ClientMessage();
            clientMessage.setDestination(info.getDestination());
            clientMessage.setPayload(payload);
            clientMessage.setReceptionInfo(receptionInfo);
            getContext().clientMessagePool().add(clientMessage);
            getContext().messageHistory().receivePayloadMessage(msg.getUniqueId());
        }
    }
    
    private void createFakeOutNetMessage() {
        // now we create a fake outNetMessage to go onto the registry so we can select
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Registering a fake outNetMessage for the message tunneled locally since we have a selector");
        OutNetMessage outM = new OutNetMessage(getContext());
        outM.setExpiration(_expiration);
        outM.setMessage(_message);
        outM.setOnFailedReplyJob(_onFailure);
        outM.setOnFailedSendJob(_onFailure);
        outM.setOnReplyJob(_onReply);
        outM.setOnSendJob(_onSend);
        outM.setPriority(_priority);
        outM.setReplySelector(_selector);
        outM.setTarget(null);
        getContext().messageRegistry().registerPending(outM);
        // we dont really need the data
        outM.discardData();
    }
    
    public String getName() { return "Send Tunnel Message"; }
}
