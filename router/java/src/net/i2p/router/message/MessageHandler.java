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

import net.i2p.data.Hash;
import net.i2p.data.Payload;
import net.i2p.data.RouterIdentity;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.SourceRouteBlock;
import net.i2p.data.i2np.TunnelMessage;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.ClientMessage;
import net.i2p.router.ClientMessagePool;
import net.i2p.router.InNetMessage;
import net.i2p.router.InNetMessagePool;
import net.i2p.router.Job;
import net.i2p.router.JobQueue;
import net.i2p.router.MessageHistory;
import net.i2p.router.MessageReceptionInfo;
import net.i2p.router.MessageValidator;
import net.i2p.router.Router;
import net.i2p.util.Log;
import net.i2p.util.Clock;

/**
 * Implement the inbound message processing logic to forward based on delivery instructions and
 * send acks.
 *
 */
class MessageHandler {
    private final static Log _log = new Log(MessageHandler.class);
    private static MessageHandler _instance = new MessageHandler();
    public static MessageHandler getInstance() { return _instance; }

    public void handleMessage(DeliveryInstructions instructions, I2NPMessage message, boolean requestAck, SourceRouteBlock replyBlock, 
				 long replyId, RouterIdentity from, Hash fromHash, long expiration, int priority) {
	switch (instructions.getDeliveryMode()) {
	    case DeliveryInstructions.DELIVERY_MODE_LOCAL:
		_log.debug("Instructions for LOCAL DELIVERY");
		if (message.getType() == DataMessage.MESSAGE_TYPE) {
		    handleLocalDestination(instructions, message, fromHash);
		} else {
		    handleLocalRouter(message, from, fromHash, replyBlock, requestAck);
		}
		break;
	    case DeliveryInstructions.DELIVERY_MODE_ROUTER:
		_log.debug("Instructions for ROUTER DELIVERY to " + instructions.getRouter().toBase64());
		if (Router.getInstance().getRouterInfo().getIdentity().getHash().equals(instructions.getRouter())) {
		    handleLocalRouter(message, from, fromHash, replyBlock, requestAck);
		} else {
		    handleRemoteRouter(message, instructions, expiration, priority);
		}
		break;
	    case DeliveryInstructions.DELIVERY_MODE_DESTINATION:
		_log.debug("Instructions for DESTINATION DELIVERY to " + instructions.getDestination().toBase64());
		if (ClientManagerFacade.getInstance().isLocal(instructions.getDestination())) {
		    handleLocalDestination(instructions, message, fromHash);
		} else {
		    _log.error("Instructions requests forwarding on to a non-local destination.  Not yet supported");
		}
		break;
	    case DeliveryInstructions.DELIVERY_MODE_TUNNEL:
		_log.debug("Instructions for TUNNEL DELIVERY to" + instructions.getTunnelId().getTunnelId() + " on " + instructions.getRouter().toBase64());
		handleTunnel(instructions, expiration, message, priority);
		break;
	    default:
		_log.error("Message has instructions that are not yet implemented: mode = " + instructions.getDeliveryMode());
	}
	
	if (requestAck) {
	    _log.debug("SEND ACK REQUESTED");
	    sendAck(replyBlock, replyId);
	} else {
	    _log.debug("No ack requested");
	}
    }
    
    private void sendAck(SourceRouteBlock replyBlock, long replyId) {
	_log.info("Queueing up ack job via reply block " + replyBlock);
	Job ackJob = new SendMessageAckJob(replyBlock, replyId);
	JobQueue.getInstance().addJob(ackJob);
    }
    
    private void handleLocalRouter(I2NPMessage message, RouterIdentity from, Hash fromHash, SourceRouteBlock replyBlock, boolean ackUsed) {
	_log.info("Handle " + message.getClass().getName() + " to a local router - toss it on the inbound network pool");
	InNetMessage msg = new InNetMessage();
	msg.setFromRouter(from);
	msg.setFromRouterHash(fromHash);
	msg.setMessage(message);
	if (!ackUsed)
	    msg.setReplyBlock(replyBlock);
	InNetMessagePool.getInstance().add(msg);
    }
    
    private void handleRemoteRouter(I2NPMessage message, DeliveryInstructions instructions, long expiration, int priority) {

	boolean valid = MessageValidator.getInstance().validateMessage(message.getUniqueId(), message.getMessageExpiration().getTime());
	if (!valid) {
	    if (_log.shouldLog(Log.WARN))
		_log.warn("Duplicate / expired message received to remote router [" + message.getUniqueId() + " expiring on " + message.getMessageExpiration() + "]");
	    MessageHistory.getInstance().droppedOtherMessage(message);
	    MessageHistory.getInstance().messageProcessingError(message.getUniqueId(), message.getClass().getName(), "Duplicate/expired to remote router");
	    return;
	}

	_log.info("Handle " + message.getClass().getName() + " to a remote router " + instructions.getRouter().toBase64() + " - fire a SendMessageDirectJob");
	SendMessageDirectJob j = new SendMessageDirectJob(message, instructions.getRouter(), expiration, priority);
	JobQueue.getInstance().addJob(j);
    }
    
    private void handleTunnel(DeliveryInstructions instructions, long expiration, I2NPMessage message, int priority) {
	Hash to = instructions.getRouter();
	long timeoutMs = expiration - Clock.getInstance().now();
	TunnelId tunnelId = instructions.getTunnelId();
	
	if (!Router.getInstance().getRouterInfo().getIdentity().getHash().equals(to)) {
	    // don't validate locally targetted tunnel messages, since then we'd have to tweak
	    // around message validation thats already in place for SendMessageDirectJob
	    boolean valid = MessageValidator.getInstance().validateMessage(message.getUniqueId(), message.getMessageExpiration().getTime());
	    if (!valid) {
		if (_log.shouldLog(Log.WARN))
		    _log.warn("Duplicate / expired tunnel message received [" + message.getUniqueId() + " expiring on " + message.getMessageExpiration() + "]");
		MessageHistory.getInstance().droppedOtherMessage(message);
		MessageHistory.getInstance().messageProcessingError(message.getUniqueId(), message.getClass().getName(), "Duplicate/expired");
		return;
	    }
	}

	_log.info("Handle " + message.getClass().getName() + " to send to remote tunnel " + tunnelId.getTunnelId() + " on router " + to.toBase64());
	TunnelMessage msg = new TunnelMessage();
	ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
	try {
	    message.writeBytes(baos);
	    msg.setData(baos.toByteArray());
	    msg.setTunnelId(tunnelId);
	    _log.debug("Placing message of type " + message.getClass().getName() + " into the new tunnel message bound for " + tunnelId.getTunnelId() + " on " + to.toBase64());
	    JobQueue.getInstance().addJob(new SendMessageDirectJob(msg, to, expiration, priority));
	    
	    String bodyType = message.getClass().getName();
	    MessageHistory.getInstance().wrap(bodyType, message.getUniqueId(), TunnelMessage.class.getName(), msg.getUniqueId());	
	} catch (Exception e) {
	    _log.warn("Unable to forward on according to the instructions to the remote tunnel", e);
	}
    }
    
    private void handleLocalDestination(DeliveryInstructions instructions, I2NPMessage message, Hash fromHash) {
	boolean valid = MessageValidator.getInstance().validateMessage(message.getUniqueId(), message.getMessageExpiration().getTime());
	if (!valid) {
	    if (_log.shouldLog(Log.WARN))
		_log.warn("Duplicate / expired client message received [" + message.getUniqueId() + " expiring on " + message.getMessageExpiration() + "]");
	    MessageHistory.getInstance().droppedOtherMessage(message);
	    MessageHistory.getInstance().messageProcessingError(message.getUniqueId(), message.getClass().getName(), "Duplicate/expired client message");
	    return;
	}

	_log.debug("Handle " + message.getClass().getName() + " to a local destination - build a ClientMessage and pool it");
	ClientMessage msg = new ClientMessage();
	msg.setDestinationHash(instructions.getDestination());
	Payload payload = new Payload();
	payload.setEncryptedData(((DataMessage)message).getData());
	msg.setPayload(payload);
	MessageReceptionInfo info = new MessageReceptionInfo();
	info.setFromPeer(fromHash);
	msg.setReceptionInfo(info);
	MessageHistory.getInstance().receivePayloadMessage(message.getUniqueId());
	ClientMessagePool.getInstance().add(msg);
    }
}
