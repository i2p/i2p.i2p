package net.i2p.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.util.Log;
import net.i2p.util.Clock;

import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.data.i2cp.SendMessageMessage;
import net.i2p.data.i2cp.CreateSessionMessage;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.DisconnectMessage;
import net.i2p.data.i2cp.ReceiveMessageBeginMessage;
import net.i2p.data.i2cp.ReceiveMessageEndMessage;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.data.i2cp.SessionId;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;

import net.i2p.data.Destination;
import net.i2p.data.Payload;
import net.i2p.data.TunnelId;
import net.i2p.data.RouterIdentity;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.Certificate;

import net.i2p.crypto.KeyGenerator;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Date;

import java.net.Socket;

import java.io.OutputStream;
import java.io.IOException;

/**
 * Run the server side of a connection as part of the TestServer.  This class 
 * actually manages the state of that system too, but this is a very, very, very
 * rudimentary implementation.  And not a very clean one at that. 
 *
 * @author jrandom
 */
class ConnectionRunner implements I2CPMessageReader.I2CPMessageEventListener {
    private final static Log _log = new Log(ConnectionRunner.class);
    /** 
     * static mapping of Destination to ConnectionRunner, allowing connections to pass 
     * messages to each other
     */
    private static Map _connections = Collections.synchronizedMap(new HashMap());
    /**
     * static mapping of MessageId to Payload, storing messages for retrieval
     *
     */
    private static Map _messages = Collections.synchronizedMap(new HashMap());
    /** socket for this particular peer connection */
    private Socket _socket;
    /** 
     * output stream of the socket that I2CP messages bound to the client 
     * should be written to
     */
    private OutputStream _out;
    /** session ID of the current client */
    private SessionId _sessionId;
    /** next available session id */
    private static int _id = 0;
    /** next available message id */
    private static int _messageId = 0;
    private SessionConfig _config;
    
    private Object _sessionIdLock = new Object();
    private Object _messageIdLock = new Object();
    // this *should* be mod 65536, but UnsignedInteger is still b0rked.  FIXME
    protected int getNextSessionId() { synchronized (_sessionIdLock) { int id = (++_id)%32767; _id = id; return id; } }
    // this *should* be mod 65536, but UnsignedInteger is still b0rked.  FIXME
    protected int getNextMessageId() { synchronized (_messageIdLock) { int id = (++_messageId)%32767; _messageId = id; return id; } }
    protected SessionId getSessionId() { return _sessionId; }
    
    protected ConnectionRunner getRunner(Destination dest) {
	return (ConnectionRunner)_connections.get(dest);
    }
    protected Set getRunnerDestinations() {
	return new HashSet(_connections.keySet());
    }
    
    /**
     * Create a new runner against the given socket
     *
     */
    public ConnectionRunner(Socket socket) {
	_socket = socket;
	_config = null;
    }
    
    /**
     * Actually run the connection - listen for I2CP messages and respond.  This
     * is the main driver for this class, though it gets all its meat from the
     * {@link net.invisiblenet.i2p.data.i2cp.I2CPMessageReader I2CPMessageReader}
     *
     */
    public void doYourThing() throws IOException {
	I2CPMessageReader reader = new I2CPMessageReader(_socket.getInputStream(), this);
	_out = _socket.getOutputStream();
	reader.startReading();
    }
    
    /**
     * Recieve notifiation that the peer disconnected
     */
    public void disconnected(I2CPMessageReader reader) {
	_log.info("Disconnected");
    }

    /**
     * Handle an incoming message and dispatch it to the appropriate handler
     *
     */
    public void messageReceived(I2CPMessageReader reader, I2CPMessage message) {
	_log.info("Message recieved: \n" + message);
	switch (message.getType()) {
	    case CreateSessionMessage.MESSAGE_TYPE:
		handleCreateSession(reader, (CreateSessionMessage)message);
		break;
	    case SendMessageMessage.MESSAGE_TYPE:
		handleSendMessage(reader, (SendMessageMessage)message);
		break;
	    case ReceiveMessageBeginMessage.MESSAGE_TYPE:
		handleReceiveBegin(reader, (ReceiveMessageBeginMessage)message);
		break;
	    case ReceiveMessageEndMessage.MESSAGE_TYPE:
		handleReceiveEnd(reader, (ReceiveMessageEndMessage)message);
		break;
	}
    }
    
    /** 
     * Handle a CreateSessionMessage
     *
     */
    protected void handleCreateSession(I2CPMessageReader reader, CreateSessionMessage message) {
	if (message.getSessionConfig().verifySignature()) {
	    _log.debug("Signature verified correctly on create session message");
	} else {
	    _log.error("Signature verification *FAILED* on a create session message.  Hijack attempt?");
	    DisconnectMessage msg = new DisconnectMessage();
	    msg.setReason("Invalid signature on CreateSessionMessage");
	    try {
		doSend(msg);
	    } catch (I2CPMessageException ime) {
		_log.error("Error writing out the disconnect message", ime);
	    } catch (IOException ioe) {
		_log.error("Error writing out the disconnect message", ioe);
	    }
	    return;
	}
	SessionStatusMessage msg = new SessionStatusMessage();
	SessionId id = new SessionId();
	id.setSessionId(getNextSessionId()); // should be mod 65535, but UnsignedInteger isn't fixed yet.  FIXME.
	_sessionId = id;
	msg.setSessionId(id);
	msg.setStatus(SessionStatusMessage.STATUS_CREATED);
	try {
	    doSend(msg);
	    _connections.put(message.getSessionConfig().getDestination(), this);
	    _config = message.getSessionConfig();
	    sessionCreated();
	} catch (I2CPMessageException ime) {
	    _log.error("Error writing out the session status message", ime);
	} catch (IOException ioe) {
	    _log.error("Error writing out the session status message", ioe);
	}
	
	// lets also request a new fake lease
	RequestLeaseSetMessage rlsm = new RequestLeaseSetMessage();
	rlsm.setEndDate(new Date(Clock.getInstance().now() + 60*60*1000));
	rlsm.setSessionId(id);
	RouterIdentity ri = new RouterIdentity();
	Object rikeys[] = KeyGenerator.getInstance().generatePKIKeypair();
	Object riSigningkeys[] = KeyGenerator.getInstance().generateSigningKeypair();
	ri.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
	ri.setPublicKey((PublicKey)rikeys[0]);
	ri.setSigningPublicKey((SigningPublicKey)riSigningkeys[0]);
	TunnelId tunnel = new TunnelId();
	tunnel.setTunnelId(42);
	rlsm.addEndpoint(ri, tunnel);
	try {
	    doSend(rlsm);
	} catch (I2CPMessageException ime) {
	    _log.error("Error writing out the request for a lease set", ime);
	} catch (IOException ioe) {
	    _log.error("Error writing out the request for a lease set", ioe);
	}
	
    }
    
    protected void sessionCreated() { }
    protected SessionConfig getConfig() { return _config; }
    
    /**
     * Handle a SendMessageMessage
     *
     */
    protected void handleSendMessage(I2CPMessageReader reader, SendMessageMessage message) {
	_log.debug("handleSendMessage called");
	Payload payload = message.getPayload();
	Destination dest = message.getDestination();
	MessageId id = new MessageId();
	id.setMessageId(getNextMessageId()); 
	_log.debug("** Recieving message [" + id.getMessageId() + "] with payload: " + "[" + payload + "]");
	_messages.put(id, payload);
	MessageStatusMessage status = new MessageStatusMessage();
	status.setMessageId(id); 
	status.setSessionId(message.getSessionId());
	status.setSize(0L);
	status.setNonce(message.getNonce());
	status.setStatus(MessageStatusMessage.STATUS_SEND_ACCEPTED);
	try {
	    doSend(status);
	} catch (I2CPMessageException ime) {
	    _log.error("Error writing out the message status message", ime);
	} catch (IOException ioe) {
	    _log.error("Error writing out the message status message", ioe);
	}
	distributeMessageToPeer(status, dest, id);
    }
    
    /** 
     * distribute the message to the destination, passing on the appropriate status
     * messages to the sender of the SendMessageMessage
     *
     */
    private void distributeMessageToPeer(MessageStatusMessage status, Destination dest, MessageId id) {
	ConnectionRunner runner = (ConnectionRunner)_connections.get(dest);
	if (runner == null) {
	    distributeNonLocal(status, dest, id);
	} else {
	    distributeLocal(runner, status, dest, id);
	}
	_log.debug("Done handling send message");
    }
    
    protected void distributeLocal(ConnectionRunner runner, MessageStatusMessage status, Destination dest, MessageId id) {
	if (runner.messageAvailable(id, 0L)) {
	    status.setStatus(MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS);
	    status.setNonce(2);
	    try {
		doSend(status);
	    } catch (I2CPMessageException ime) {
		_log.error("Error writing out the success status message", ime);
	    } catch (IOException ioe) {
		_log.error("Error writing out the success status message", ioe);
	    }
	    _log.debug("Guaranteed success with the status message sent");
	} else {
	    status.setStatus(MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE);
	    try {
		doSend(status);
	    } catch (I2CPMessageException ime) {
		_log.error("Error writing out the failure status message", ime);
	    } catch (IOException ioe) {
		_log.error("Error writing out the failure status message", ioe);
	    }
	    _log.debug("Guaranteed failure since messageAvailable failed");
	}
    }
    
    protected void distributeNonLocal(MessageStatusMessage status, Destination dest, MessageId id) {
	status.setStatus(MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE);
	try {
	    doSend(status);
	} catch (I2CPMessageException ime) {
	    _log.error("Error writing out the failure status message", ime);
	} catch (IOException ioe) {
	    _log.error("Error writing out the failure status message", ioe);
	}
	_log.debug("Guaranteed failure!");
    }
    
    /**
     * The client asked for a message, so we send it to them.  This currently
     * does not do any security checking (like making sure they're the one to
     * whom the message ID is destined, but its encrypted, so why not...
     * (bad attitude, I know.  consider this a bug to be fixed)
     *
     */
    public void handleReceiveBegin(I2CPMessageReader reader, ReceiveMessageBeginMessage message) {
	_log.debug("Handling recieve begin: id = " + message.getMessageId());
	MessagePayloadMessage msg = new MessagePayloadMessage();
	msg.setMessageId(message.getMessageId());
	msg.setSessionId(_sessionId);
	Payload payload = (Payload)_messages.get(message.getMessageId());
	if (payload == null) {
	    _log.error("Payload for message id [" + message.getMessageId() + "] is null!  Unknown message id?", new Exception("Error, null payload"));
	    StringBuffer buf = new StringBuffer();
	    for (Iterator iter = _messages.keySet().iterator(); iter.hasNext(); ) {
		buf.append("messageId: ").append(iter.next()).append(", ");
	    }
	    _log.error("Known message IDs: " + buf.toString());
	    return;
	}
	msg.setPayload(payload);
	try {
	    doSend(msg);
	} catch (IOException ioe) {
	    _log.error("Error delivering the payload", ioe);
	} catch (I2CPMessageException ime) {
	    _log.error("Error delivering the payload", ime);
	}
    }
    
    /**
     * The client told us that the message has been recieved completely.  This currently
     * does not do any security checking prior to removing the message from the 
     * pending queue, though it should.
     *
     */
    public void handleReceiveEnd(I2CPMessageReader reader, ReceiveMessageEndMessage message) {
	_messages.remove(message.getMessageId());
    }
    
    /**
     * Deliver notification to the client that the given message is available.
     * This is called from the ConnectionRunner the message was sent from.
     *
     */
    public boolean messageAvailable(MessageId id, long size) {
	MessageStatusMessage msg = new MessageStatusMessage();
	msg.setMessageId(id);
	msg.setSessionId(_sessionId);
	msg.setSize(size);
	msg.setNonce(1);
	msg.setStatus(MessageStatusMessage.STATUS_AVAILABLE);
	try {
	    doSend(msg);
	    return true;
	} catch (I2CPMessageException ime) {
	    _log.error("Error writing out the message status message", ime);
	} catch (IOException ioe) {
	    _log.error("Error writing out the message status message", ioe);
	}
	return false;
    }
    
    /**
     * Handle notifiation that there was an error
     *
     */
    public void readError(I2CPMessageReader reader, Exception error) {
	_log.info("Error occurred", error);
    }

    private Object _sendLock = new Object();
    protected void doSend(I2CPMessage msg) throws I2CPMessageException, IOException {
	synchronized (_sendLock) {
	    msg.writeMessage(_out);
	    _out.flush();
	}
    }
}
