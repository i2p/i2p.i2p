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

import net.i2p.crypto.AESEngine;
import net.i2p.crypto.SHA256Generator;
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
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.ClientMessage;
import net.i2p.router.ClientMessagePool;
import net.i2p.router.InNetMessage;
import net.i2p.router.InNetMessagePool;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.MessageReceptionInfo;
import net.i2p.router.Router;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.MessageHistory;
import net.i2p.router.MessageValidator;
import net.i2p.util.Log;
import net.i2p.util.Clock;

import net.i2p.stat.StatManager;

public class HandleTunnelMessageJob extends JobImpl {
    private final static Log _log = new Log(HandleTunnelMessageJob.class);
    private TunnelMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    private final static I2NPMessageHandler _handler = new I2NPMessageHandler();
    
    private final static long FORWARD_TIMEOUT = 60*1000;
    private final static int FORWARD_PRIORITY = 400;
    
    static {
	StatManager.getInstance().createFrequencyStat("tunnel.unknownTunnelFrequency", "How often do we receive tunnel messages for unknown tunnels?", "Tunnels", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
	StatManager.getInstance().createRateStat("tunnel.gatewayMessageSize", "How large are the messages we are forwarding on as an inbound gateway?", "Tunnels", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
	StatManager.getInstance().createRateStat("tunnel.relayMessageSize", "How large are the messages we are forwarding on as a participant in a tunnel?", "Tunnels", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
	StatManager.getInstance().createRateStat("tunnel.endpointMessageSize", "How large are the messages we are forwarding in as an outbound endpoint?", "Tunnels", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
    }
    
    public HandleTunnelMessageJob(TunnelMessage msg, RouterIdentity from, Hash fromHash) {
	super();
	_message = msg;
	_from = from;
	_fromHash = fromHash;
    }
    
    public String getName() { return "Handle Inbound Tunnel Message"; }
    public void runJob() {
	TunnelId id = _message.getTunnelId();
	TunnelInfo info = TunnelManagerFacade.getInstance().getTunnelInfo(id);
	
	if (info == null) {
	    Hash from = _fromHash;
	    if (_from != null)
		from = _from.getHash();
	    MessageHistory.getInstance().droppedTunnelMessage(id, from);
	    _log.error("Received a message for an unknown tunnel [" + id.getTunnelId() + "], dropping it: " + _message, getAddedBy());
	    StatManager.getInstance().updateFrequency("tunnel.unknownTunnelFrequency");
	    return;
	}
	
	info = getUs(info);
	if (info == null) {
	    _log.error("We are not part of a known tunnel?? wtf!  drop.", getAddedBy());
	    StatManager.getInstance().updateFrequency("tunnel.unknownTunnelFrequency");
	    return;
	} else {
	    _log.debug("Tunnel message received for tunnel: \n" + info);
	}
	
	//if ( (_message.getVerificationStructure() == null) && (info.getSigningKey() != null) ) {
	if (_message.getVerificationStructure() == null) {
	    if (info.getSigningKey() != null) {
		if (info.getNextHop() != null) {
		    if (_log.shouldLog(Log.DEBUG))
		        _log.debug("We are the gateway to tunnel " + id.getTunnelId());
		    byte data[] = _message.getData();
		    I2NPMessage msg = getBody(data);
		    JobQueue.getInstance().addJob(new HandleGatewayMessageJob(msg, info, data.length));
		    return;
		} else {
		    if (_log.shouldLog(Log.WARN))
			_log.debug("We are the gateway and the endpoint for tunnel " + id.getTunnelId());
		    if (_log.shouldLog(Log.WARN))
			_log.debug("Process locally");
		    if (info.getDestination() != null) {
			if (!ClientManagerFacade.getInstance().isLocal(info.getDestination())) {
			    if (_log.shouldLog(Log.WARN))
				_log.warn("Received a message on a tunnel allocated to a client that has disconnected - dropping it!");
			    if (_log.shouldLog(Log.DEBUG))
				_log.debug("Dropping message for disconnected client: " + _message);
			    
			    MessageHistory.getInstance().droppedOtherMessage(_message);
			    MessageHistory.getInstance().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Disconnected client");
			    return;
			}
		    }
		    
		    I2NPMessage body = getBody(_message.getData());
		    if (body != null) {
			JobQueue.getInstance().addJob(new HandleLocallyJob(body, info));
			return;
		    } else {
			if (_log.shouldLog(Log.ERROR))
			    _log.error("Body is null!  content of message.getData() = [" + DataHelper.toString(_message.getData()) + "]", getAddedBy());
			if (_log.shouldLog(Log.DEBUG))
			    _log.debug("Message that failed: " + _message, getAddedBy());
			return;
		    }
		}
	    } else {
		if (_log.shouldLog(Log.ERROR))
		    _log.error("Received a message that we are not the gateway for on tunnel " 
		               + id.getTunnelId() + " without a verification structure: " + _message, getAddedBy());
		return;
	    }
	} else {
	    // participant
	    TunnelVerificationStructure struct = _message.getVerificationStructure();
	    boolean ok = struct.verifySignature(info.getVerificationKey().getKey());
	    if (!ok) {
		if (_log.shouldLog(Log.WARN))
		    _log.warn("Failed tunnel verification!  Spoofing / tagging attack?  " + _message, getAddedBy());
		return;
	    } else {
		if (info.getNextHop() != null) {
		    if (_log.shouldLog(Log.INFO))
			_log.info("Message for tunnel " + id.getTunnelId() + " received where we're not the gateway and there are remaining hops, so forward it on to " 
			          + info.getNextHop().toBase64() + " via SendTunnelMessageJob");
		    
		    StatManager.getInstance().addRateData("tunnel.relayMessageSize", _message.getData().length, 0);

		    JobQueue.getInstance().addJob(new SendMessageDirectJob(_message, info.getNextHop(), Clock.getInstance().now() + FORWARD_TIMEOUT, FORWARD_PRIORITY));
		    return;
		} else {
		    if (_log.shouldLog(Log.DEBUG))
			_log.debug("No more hops, unwrap and follow the instructions");
		    JobQueue.getInstance().addJob(new HandleEndpointJob(info));
		    return;
		}
	    }
	}
    }
    
    private void processLocally(TunnelInfo ourPlace) {
	if (ourPlace.getEncryptionKey() == null) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Argh, somehow we don't have the decryption key and we have no more steps", getAddedBy());
	    return;
	}
	DeliveryInstructions instructions = getInstructions(_message.getEncryptedDeliveryInstructions(), ourPlace.getEncryptionKey().getKey());
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
		    _log.debug("Body in the tunnel is NOT encrypted: " + instructions + "\n" + _message, new Exception("Hmmm..."));
		body = getBody(_message.getData());
	    }
	    
	    if (body == null) {
		if (_log.shouldLog(Log.ERROR))
		    _log.error("Unable to recover the body from the tunnel", getAddedBy());
		return;
	    } else {
		JobQueue.getInstance().addJob(new ProcessBodyLocallyJob(body, instructions, ourPlace));
	    }
	}
    }
    
    private void honorInstructions(DeliveryInstructions instructions, I2NPMessage body) {
	StatManager.getInstance().addRateData("tunnel.endpointMessageSize", _message.getData().length, 0);

	switch (instructions.getDeliveryMode()) {
	    case DeliveryInstructions.DELIVERY_MODE_LOCAL:
		sendToLocal(body);
		break;
	    case DeliveryInstructions.DELIVERY_MODE_ROUTER:
		if (Router.getInstance().getRouterInfo().getIdentity().getHash().equals(instructions.getRouter())) {
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
	    boolean isLocal = ClientManagerFacade.getInstance().isLocal(dest);
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
	    _log.debug("Sending on to requested tunnel " + id.getTunnelId() + " on router " + router.toBase64());
	TunnelMessage msg = new TunnelMessage();
	msg.setTunnelId(id);
	try {
	    ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
	    body.writeBytes(baos);
	    msg.setData(baos.toByteArray());
	    JobQueue.getInstance().addJob(new SendMessageDirectJob(msg, router, Clock.getInstance().now() + FORWARD_TIMEOUT, FORWARD_PRIORITY));
	    
	    String bodyType = body.getClass().getName();
	    MessageHistory.getInstance().wrap(bodyType, body.getUniqueId(), TunnelMessage.class.getName(), msg.getUniqueId());	
	} catch (DataFormatException dfe) {
	    _log.error("Error writing out the message to forward to the tunnel", dfe);
	} catch (IOException ioe) {
	    _log.error("Error writing out the message to forward to the tunnel", ioe);
	}
    }
    
    private void sendToRouter(Hash router, I2NPMessage body) {
	// TODO: we may want to send it via a tunnel later on, but for now, direct will do.
	if (_log.shouldLog(Log.DEBUG))
	    _log.debug("Sending on to requested router " + router.toBase64());
	JobQueue.getInstance().addJob(new SendMessageDirectJob(body, router, Clock.getInstance().now() + FORWARD_TIMEOUT, FORWARD_PRIORITY));
    }
    
    private void sendToLocal(I2NPMessage body) {
	InNetMessage msg = new InNetMessage();
	msg.setMessage(body);
	msg.setFromRouter(_from);
	msg.setFromRouterHash(_fromHash);
	InNetMessagePool.getInstance().add(msg);
    }
    
    private void deliverMessage(Destination dest, Hash destHash, DataMessage msg) {
	boolean valid = MessageValidator.getInstance().validateMessage(msg.getUniqueId(), msg.getMessageExpiration().getTime());
	if (!valid) {
	    if (_log.shouldLog(Log.WARN))
		_log.warn("Duplicate data message received [" + msg.getUniqueId() + " expiring on " + msg.getMessageExpiration() + "]");
	    MessageHistory.getInstance().droppedOtherMessage(msg);
	    MessageHistory.getInstance().messageProcessingError(msg.getUniqueId(), msg.getClass().getName(), "Duplicate payload");
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
	
	MessageHistory.getInstance().receivePayloadMessage(msg.getUniqueId());
	// if the destination isn't local, the ClientMessagePool forwards it off as an OutboundClientMessageJob
	ClientMessagePool.getInstance().add(cmsg);
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
	Hash h = SHA256Generator.getInstance().calculateHash(key.getData());
	System.arraycopy(h.getData(), 0, iv, 0, iv.length);
	byte decrypted[] = AESEngine.getInstance().safeDecrypt(encryptedMessage, key, iv);
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
	    Hash h = SHA256Generator.getInstance().calculateHash(key.getData());
	    System.arraycopy(h.getData(), 0, iv, 0, iv.length);
	    byte decrypted[] = AESEngine.getInstance().safeDecrypt(encryptedInstructions, key, iv);
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
	Hash us = Router.getInstance().getRouterInfo().getIdentity().getHash();
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
	
	if (!vstruct.verifySignature(info.getVerificationKey().getKey())) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Received a tunnel message with an invalid signature!");
	    // shitlist the sender?
	    return false;
	}
	
	// now validate the message
	Hash msgHash = SHA256Generator.getInstance().calculateHash(_message.getData());
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
	MessageHistory.getInstance().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Dropped due to overload");
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
	    _body = body;
	    _length = length;
	    _info = tunnel;
	}
	public void runJob() {
	    if (_body != null) {
		StatManager.getInstance().addRateData("tunnel.gatewayMessageSize", _length, 0);
		if (_log.shouldLog(Log.INFO))
		    _log.info("Message for tunnel " + _info.getTunnelId() + " received at the gateway (us), and since its > 0 length, forward the " 
			      + _body.getClass().getName() + " message on to " + _info.getNextHop().toBase64() + " via SendTunnelMessageJob");
		JobQueue.getInstance().addJob(new SendTunnelMessageJob(_body, _info.getTunnelId(), null, null, null, null, FORWARD_TIMEOUT, FORWARD_PRIORITY));
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
			      " received at the gateway (us), but its a 0 length tunnel though it is a " + _body.getClass().getName() + ", so process it locally");
		InNetMessage msg = new InNetMessage();
		msg.setFromRouter(_from);
		msg.setFromRouterHash(_fromHash);
		msg.setMessage(_body);
		InNetMessagePool.getInstance().add(msg);
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
		//TunnelMonitor.endpointReceive(ourPlace.getTunnelId(), body.getClass().getName(), instructions, ourPlace.getDestination());
		if (_log.shouldLog(Log.INFO))
		    _log.info("Message for tunnel " + _ourPlace.getTunnelId().getTunnelId() + " received where we're the endpoint containing a " 
			      + _body.getClass().getName() + " message, so honor the delivery instructions: " + _instructions.toString());
		honorInstructions(_instructions, _body);
		return;
	    }
	}
	public String getName() { return "Handle Tunnel Message  (outbound endpoint)"; }
    }
}
