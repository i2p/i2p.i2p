package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Properties;

import net.i2p.CoreVersion;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.BandwidthLimitsMessage;
import net.i2p.data.i2cp.CreateLeaseSetMessage;
import net.i2p.data.i2cp.CreateSessionMessage;
import net.i2p.data.i2cp.DestLookupMessage;
import net.i2p.data.i2cp.DestroySessionMessage;
import net.i2p.data.i2cp.GetBandwidthLimitsMessage;
import net.i2p.data.i2cp.GetDateMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.ReceiveMessageBeginMessage;
import net.i2p.data.i2cp.ReceiveMessageEndMessage;
import net.i2p.data.i2cp.ReconfigureSessionMessage;
import net.i2p.data.i2cp.SendMessageMessage;
import net.i2p.data.i2cp.SendMessageExpiresMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.data.i2cp.SetDateMessage;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Receive events from the client and handle them accordingly (updating the runner when
 * necessary)
 *
 */
class ClientMessageEventListener implements I2CPMessageReader.I2CPMessageEventListener {
    private final Log _log;
    private final RouterContext _context;
    private final ClientConnectionRunner _runner;
    private final boolean  _enforceAuth;
    
    /**
     *  @param enforceAuth set false for in-JVM, true for socket access
     */
    public ClientMessageEventListener(RouterContext context, ClientConnectionRunner runner, boolean enforceAuth) {
        _context = context;
        _log = _context.logManager().getLog(ClientMessageEventListener.class);
        _runner = runner;
        _enforceAuth = enforceAuth;
        _context.statManager().createRateStat("client.distributeTime", "How long it took to inject the client message into the router", "ClientMessages", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
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
            case SendMessageExpiresMessage.MESSAGE_TYPE:
                handleSendMessage(reader, (SendMessageExpiresMessage)message);
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
            case DestLookupMessage.MESSAGE_TYPE:
                handleDestLookup(reader, (DestLookupMessage)message);
                break;
            case ReconfigureSessionMessage.MESSAGE_TYPE:
                handleReconfigureSession(reader, (ReconfigureSessionMessage)message);
                break;
            case GetBandwidthLimitsMessage.MESSAGE_TYPE:
                handleGetBWLimits(reader, (GetBandwidthLimitsMessage)message);
                break;
            default:
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Unhandled I2CP type received: " + message.getType());
        }
    }

    /**
     * Handle notification that there was an error
     *
     */
    public void readError(I2CPMessageReader reader, Exception error) {
        if (_runner.isDead()) return;
        if (_log.shouldLog(Log.ERROR))
            _log.error("Error occurred", error);
        // Is this is a little drastic for an unknown message type?
        _runner.stopRunning();
    }
  
    public void disconnected(I2CPMessageReader reader) {
        if (_runner.isDead()) return;
        _runner.disconnected();
    }
    
    private void handleGetDate(I2CPMessageReader reader, GetDateMessage message) {
        // sent by clients >= 0.8.7
        String clientVersion = message.getVersion();
        // TODO - save client's version string for future reference
        try {
            // only send version if the client can handle it (0.8.7 or greater)
            _runner.doSend(new SetDateMessage(clientVersion != null ? CoreVersion.VERSION : null));
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing out the setDate message", ime);
        }
    }

    /**
     *  As of 0.8.7, does nothing. Do not allow a client to set the router's clock.
     */
    private void handleSetDate(I2CPMessageReader reader, SetDateMessage message) {
        //_context.clock().setNow(message.getDate().getTime());
    }
	
    
    /** 
     * Handle a CreateSessionMessage.
     * On errors, we could perhaps send a SessionStatusMessage with STATUS_INVALID before
     * sending the DisconnectMessage... but right now the client will send _us_ a
     * DisconnectMessage in return, and not wait around for our DisconnectMessage.
     * So keep it simple.
     */
    private void handleCreateSession(I2CPMessageReader reader, CreateSessionMessage message) {
        SessionConfig in = message.getSessionConfig();
        if (in.verifySignature()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Signature verified correctly on create session message");
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Signature verification *FAILED* on a create session message.  Hijack attempt?");
            _runner.disconnectClient("Invalid signature on CreateSessionMessage");
            return;
        }

        // Auth, since 0.8.2
        if (_enforceAuth && Boolean.valueOf(_context.getProperty("i2cp.auth")).booleanValue()) {
            String configUser = _context.getProperty("i2cp.username");
            String configPW = _context.getProperty("i2cp.password");
            if (configUser != null && configPW != null) {
                Properties props = in.getOptions();
                String user = props.getProperty("i2cp.username");
                String pw = props.getProperty("i2cp.password");
                if (user == null || pw == null) {
                    _log.error("I2CP auth failed for client: " + props.getProperty("inbound.nickname"));
                    _runner.disconnectClient("Authorization required to create session, specify i2cp.username and i2cp.password in session options");
                    return;
                }
                if ((!user.equals(configUser)) || (!pw.equals(configPW))) {
                    _log.error("I2CP auth failed for client: " + props.getProperty("inbound.nickname") + " user: " + user);
                    _runner.disconnectClient("Authorization failed for Create Session, user = " + user);
                    return;
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info("I2CP auth success for client: " + props.getProperty("inbound.nickname") + " user: " + user);
            }
        }

        SessionId sessionId = new SessionId();
        sessionId.setSessionId(getNextSessionId()); 
        _runner.setSessionId(sessionId);
        sendStatusMessage(SessionStatusMessage.STATUS_CREATED);

        // Copy over the whole config structure so we don't later corrupt it on
        // the client side if we change settings or later get a
        // ReconfigureSessionMessage
        SessionConfig cfg = new SessionConfig(in.getDestination());
        cfg.setSignature(in.getSignature());
        Properties props = new Properties();
        props.putAll(in.getOptions());
        cfg.setOptions(props);
        _runner.sessionEstablished(cfg);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("after sessionEstablished for " + message.getSessionConfig().getDestination().calculateHash().toBase64());

        _context.jobQueue().addJob(new CreateSessionJob(_context, _runner));
    }
    
    
    /**
     * Handle a SendMessageMessage: give it a message Id, have the ClientManager distribute
     * it, and send the client an ACCEPTED message
     *
     */
    private void handleSendMessage(I2CPMessageReader reader, SendMessageMessage message) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("handleSendMessage called");
        long beforeDistribute = _context.clock().now();
        MessageId id = _runner.distributeMessage(message);
        long timeToDistribute = _context.clock().now() - beforeDistribute;
        _runner.ackSendMessage(id, message.getNonce());
        _context.statManager().addRateData("client.distributeTime", timeToDistribute, timeToDistribute);
        if ( (timeToDistribute > 50) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Took too long to distribute the message (which holds up the ack): " + timeToDistribute);
    }

    
    /**
     * The client asked for a message, so we send it to them.  
     *
     */
    private void handleReceiveBegin(I2CPMessageReader reader, ReceiveMessageBeginMessage message) {
        if (_runner.isDead()) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling recieve begin: id = " + message.getMessageId());
        MessagePayloadMessage msg = new MessagePayloadMessage();
        msg.setMessageId(message.getMessageId());
        msg.setSessionId(_runner.getSessionId().getSessionId());
        Payload payload = _runner.getPayload(new MessageId(message.getMessageId()));
        if (payload == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Payload for message id [" + message.getMessageId() 
                           + "] is null!  Unknown message id?");
            return;
        }
        msg.setPayload(payload);
        try {
            _runner.doSend(msg);
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
        _runner.removePayload(new MessageId(message.getMessageId()));
    }
    
    private void handleDestroySession(I2CPMessageReader reader, DestroySessionMessage message) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Destroying client session " + _runner.getSessionId());
        _runner.stopRunning();
    }
    
    private void handleCreateLeaseSet(I2CPMessageReader reader, CreateLeaseSetMessage message) {	
        if ( (message.getLeaseSet() == null) || (message.getPrivateKey() == null) || (message.getSigningPrivateKey() == null) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Null lease set granted: " + message);
            return;
        }

        if (_log.shouldLog(Log.INFO))
            _log.info("New lease set granted for destination " 
                      + message.getLeaseSet().getDestination().calculateHash().toBase64());
        _context.keyManager().registerKeys(message.getLeaseSet().getDestination(), message.getSigningPrivateKey(), message.getPrivateKey());
        _context.netDb().publish(message.getLeaseSet());

        // leaseSetCreated takes care of all the LeaseRequestState stuff (including firing any jobs)
        _runner.leaseSetCreated(message.getLeaseSet());
    }

    private void handleDestLookup(I2CPMessageReader reader, DestLookupMessage message) {
        _context.jobQueue().addJob(new LookupDestJob(_context, _runner, message.getHash()));
    }

    /**
     * Message's Session ID ignored. This doesn't support removing previously set options.
     * Nor do we bother with message.getSessionConfig().verifySignature() ... should we?
     * Nor is the Date checked.
     *
     * Note that this does NOT update the few options handled in
     * ClientConnectionRunner.sessionEstablished(). Those can't be changed later.
     */
    private void handleReconfigureSession(I2CPMessageReader reader, ReconfigureSessionMessage message) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Updating options - old: " + _runner.getConfig() + " new: " + message.getSessionConfig());
        if (!message.getSessionConfig().getDestination().equals(_runner.getConfig().getDestination())) {
            _log.error("Dest mismatch");
            sendStatusMessage(SessionStatusMessage.STATUS_INVALID);
            _runner.stopRunning();
            return;
        }
        _runner.getConfig().getOptions().putAll(message.getSessionConfig().getOptions());
        ClientTunnelSettings settings = new ClientTunnelSettings();
        Properties props = new Properties();
        props.putAll(_runner.getConfig().getOptions());
        settings.readFromProperties(props);
        _context.tunnelManager().setInboundSettings(_runner.getConfig().getDestination().calculateHash(),
                                                    settings.getInboundSettings());
        _context.tunnelManager().setOutboundSettings(_runner.getConfig().getDestination().calculateHash(),
                                                     settings.getOutboundSettings());
        sendStatusMessage(SessionStatusMessage.STATUS_UPDATED);
    }
    
    private void sendStatusMessage(int status) {
        SessionStatusMessage msg = new SessionStatusMessage();
        msg.setSessionId(_runner.getSessionId());
        msg.setStatus(status);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            _log.error("Error writing out the session status message", ime);
        }
    }

    /**
     * Divide router limit by 1.75 for overhead.
     * This could someday give a different answer to each client.
     * But it's not enforced anywhere.
     */
    private void handleGetBWLimits(I2CPMessageReader reader, GetBandwidthLimitsMessage message) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Got BW Limits request");
        int in = _context.bandwidthLimiter().getInboundKBytesPerSecond() * 4 / 7;
        int out = _context.bandwidthLimiter().getOutboundKBytesPerSecond() * 4 / 7;
        BandwidthLimitsMessage msg = new BandwidthLimitsMessage(in, out);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            _log.error("Error writing out the session status message", ime);
        }
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
