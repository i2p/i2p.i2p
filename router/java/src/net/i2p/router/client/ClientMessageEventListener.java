package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;

import net.i2p.data.Payload;
import net.i2p.data.i2cp.CreateLeaseSetMessage;
import net.i2p.data.i2cp.CreateSessionMessage;
import net.i2p.data.i2cp.DestroySessionMessage;
import net.i2p.data.i2cp.GetDateMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.ReceiveMessageBeginMessage;
import net.i2p.data.i2cp.ReceiveMessageEndMessage;
import net.i2p.data.i2cp.SendMessageMessage;
import net.i2p.data.i2cp.SessionId;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.data.i2cp.SetDateMessage;
import net.i2p.router.JobQueue;
import net.i2p.router.KeyManager;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;
import net.i2p.router.RouterContext;

/**
 * Receive events from the client and handle them accordingly (updating the runner when
 * necessary)
 *
 */
class ClientMessageEventListener implements I2CPMessageReader.I2CPMessageEventListener {
    private Log _log;
    private RouterContext _context;
    private ClientConnectionRunner _runner;
    
    public ClientMessageEventListener(RouterContext context, ClientConnectionRunner runner) {
        _context = context;
        _log = _context.logManager().getLog(ClientMessageEventListener.class);
        _runner = runner;
    }
    
    /**
     * Handle an incoming message and dispatch it to the appropriate handler
     *
     */
    public void messageReceived(I2CPMessageReader reader, I2CPMessage message) {
        if (_runner.isDead()) return;
        if (_log.shouldLog(Log.INFO))
            _log.info("Message recieved: \n" + message);
        switch (message.getType()) {
            case GetDateMessage.MESSAGE_TYPE:
                handleGetDate(reader, (GetDateMessage)message);
                break;
            case SetDateMessage.MESSAGE_TYPE:
                handleSetDate(reader, (SetDateMessage)message);
                break;
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
            case CreateLeaseSetMessage.MESSAGE_TYPE:
                handleCreateLeaseSet(reader, (CreateLeaseSetMessage)message);
                break;
            case DestroySessionMessage.MESSAGE_TYPE:
                handleDestroySession(reader, (DestroySessionMessage)message);
                break;
            default:
                _log.warn("Unhandled I2CP type received: " + message.getType());
        }
    }

    /**
     * Handle notifiation that there was an error
     *
     */
    public void readError(I2CPMessageReader reader, Exception error) {
        if (_runner.isDead()) return;
        _log.error("Error occurred", error);
        _runner.stopRunning();
    }
    
    public void disconnected(I2CPMessageReader reader) {
        if (_runner.isDead()) return;
        _runner.disconnected();
    }
    
    private void handleGetDate(I2CPMessageReader reader, GetDateMessage message) {
        try {
            _runner.doSend(new SetDateMessage());
        } catch (I2CPMessageException ime) {
            _log.error("Error writing out the setDate message", ime);
        } catch (IOException ioe) {
            _log.error("Error writing out the setDate message", ioe);
        }
    }
    private void handleSetDate(I2CPMessageReader reader, SetDateMessage message) {
        _context.clock().setNow(message.getDate().getTime());
    }
	
    
    /** 
     * Handle a CreateSessionMessage
     *
     */
    private void handleCreateSession(I2CPMessageReader reader, CreateSessionMessage message) {
        if (message.getSessionConfig().verifySignature()) {
            _log.debug("Signature verified correctly on create session message");
        } else {
            _log.error("Signature verification *FAILED* on a create session message.  Hijack attempt?");
            _runner.disconnectClient("Invalid signature on CreateSessionMessage");
            return;
        }
	
        SessionStatusMessage msg = new SessionStatusMessage();
        SessionId sessionId = new SessionId();
        sessionId.setSessionId(getNextSessionId()); 
        _runner.setSessionId(sessionId);
        msg.setSessionId(sessionId);
        msg.setStatus(SessionStatusMessage.STATUS_CREATED);
        try {
            _runner.doSend(msg);
            _runner.sessionEstablished(message.getSessionConfig());
        } catch (I2CPMessageException ime) {
            _log.error("Error writing out the session status message", ime);
        } catch (IOException ioe) {
            _log.error("Error writing out the session status message", ioe);
        }

        _context.jobQueue().addJob(new CreateSessionJob(_context, _runner));
    }
    
    
    /**
     * Handle a SendMessageMessage: give it a message Id, have the ClientManager distribute
     * it, and send the client an ACCEPTED message
     *
     */
    private void handleSendMessage(I2CPMessageReader reader, SendMessageMessage message) {
        _log.debug("handleSendMessage called");
        MessageId id = _runner.distributeMessage(message);
        _runner.ackSendMessage(id, message.getNonce());
    }

    
    /**
     * The client asked for a message, so we send it to them.  
     *
     */
    private void handleReceiveBegin(I2CPMessageReader reader, ReceiveMessageBeginMessage message) {
        if (_runner.isDead()) return;
        _log.debug("Handling recieve begin: id = " + message.getMessageId());
        MessagePayloadMessage msg = new MessagePayloadMessage();
        msg.setMessageId(message.getMessageId());
        msg.setSessionId(_runner.getSessionId());
        Payload payload = _runner.getPayload(message.getMessageId());
        if (payload == null) {
            _log.error("Payload for message id [" + message.getMessageId() 
                       + "] is null!  Unknown message id?");
            return;
        }
        msg.setPayload(payload);
        try {
            _runner.doSend(msg);
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
    private void handleReceiveEnd(I2CPMessageReader reader, ReceiveMessageEndMessage message) {
        _runner.removePayload(message.getMessageId());
    }
    
    private void handleDestroySession(I2CPMessageReader reader, DestroySessionMessage message) {
        _log.info("Destroying client session " + _runner.getSessionId());
        _runner.stopRunning();
    }
    
    private void handleCreateLeaseSet(I2CPMessageReader reader, CreateLeaseSetMessage message) {	
        if ( (message.getLeaseSet() == null) || (message.getPrivateKey() == null) || (message.getSigningPrivateKey() == null) ) {
            _log.error("Null lease set granted: " + message);
            return;
        }

        _log.info("New lease set granted for destination " + message.getLeaseSet().getDestination().calculateHash().toBase64());
        _context.keyManager().registerKeys(message.getLeaseSet().getDestination(), message.getSigningPrivateKey(), message.getPrivateKey());
        _context.netDb().publish(message.getLeaseSet());

        // leaseSetCreated takes care of all the LeaseRequestState stuff (including firing any jobs)
        _runner.leaseSetCreated(message.getLeaseSet());
    }

    // this *should* be mod 65536, but UnsignedInteger is still b0rked.  FIXME
    private final static int MAX_SESSION_ID = 32767;

    private static volatile int _id = RandomSource.getInstance().nextInt(MAX_SESSION_ID); // sessionId counter
    private final static Object _sessionIdLock = new Object();
    
    /** generate a new sessionId */
    private final static int getNextSessionId() { 
        synchronized (_sessionIdLock) {
            int id = (++_id)%MAX_SESSION_ID;
            if (_id >= MAX_SESSION_ID)
                _id = 0; 
            return id; 
        }
    }
}
