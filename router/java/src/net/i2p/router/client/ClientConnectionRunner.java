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
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.DisconnectMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.SendMessageMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Bridge the router and the client - managing state for a client.
 *
 * @author jrandom
 */
public class ClientConnectionRunner {
    private Log _log;
    private RouterContext _context;
    private ClientManager _manager;
    /** socket for this particular peer connection */
    private Socket _socket;
    /** output stream of the socket that I2CP messages bound to the client should be written to */
    private OutputStream _out;
    /** session ID of the current client */
    private SessionId _sessionId;
    /** user's config */
    private SessionConfig _config;
    /** static mapping of MessageId to Payload, storing messages for retrieval */
    private static Map _messages; 
    /** lease set request state, or null if there is no request pending on at the moment */
    private LeaseRequestState _leaseRequest;
    /** currently allocated leaseSet, or null if none is allocated */
    private LeaseSet _currentLeaseSet;
    /** set of messageIds created but not yet ACCEPTED */
    private Set _acceptedPending;
    /** thingy that does stuff */
    private I2CPMessageReader _reader;
    /** 
     * This contains the last 10 MessageIds that have had their (non-ack) status 
     * delivered to the client (so that we can be sure only to update when necessary)
     */
    private List _alreadyProcessed;
    /** are we, uh, dead */
    private boolean _dead;
    
    /**
     * Create a new runner against the given socket
     *
     */
    public ClientConnectionRunner(RouterContext context, ClientManager manager, Socket socket) {
        _context = context;
        _log = _context.logManager().getLog(ClientConnectionRunner.class);
        _manager = manager;
        _socket = socket;
        _config = null;
        _messages = new HashMap();
        _alreadyProcessed = new LinkedList();
        _acceptedPending = new HashSet();
        _dead = false;
    }
    
    /**
     * Actually run the connection - listen for I2CP messages and respond.  This
     * is the main driver for this class, though it gets all its meat from the
     * {@link net.i2p.data.i2cp.I2CPMessageReader I2CPMessageReader}
     *
     */
    public void startRunning() {
        try {
            _reader = new I2CPMessageReader(_socket.getInputStream(), new ClientMessageEventListener(_context, this));
            _out = _socket.getOutputStream();
            _reader.startReading();
        } catch (IOException ioe) {
            _log.error("Error starting up the runner", ioe);
        }
    }
    
    /** die a horrible death */
    void stopRunning() {
        if (_dead) return;
        if (_context.router().isAlive()) 
            _log.error("Stop the I2CP connection!  current leaseSet: " 
                       + _currentLeaseSet, new Exception("Stop client connection"));
        _dead = true;
        // we need these keys to unpublish the leaseSet
        if (_reader != null) _reader.stopReading();
        if (_socket != null) try { _socket.close(); } catch (IOException ioe) { }
        synchronized (_messages) {
            _messages.clear();
        }
        _manager.unregisterConnection(this);
        if (_currentLeaseSet != null)
            _context.netDb().unpublish(_currentLeaseSet);
        _leaseRequest = null;
        synchronized (_alreadyProcessed) {
            _alreadyProcessed.clear();
        }
        _config = null;
        _manager = null;
        _context = null;
    }
    
    /** current client's config */
    public SessionConfig getConfig() { return _config; }
    /** currently allocated leaseSet */
    public LeaseSet getLeaseSet() { return _currentLeaseSet; }
    void setLeaseSet(LeaseSet ls) { _currentLeaseSet = ls; }
    
    /** current client's sessionId */
    SessionId getSessionId() { return _sessionId; }
    void setSessionId(SessionId id) { _sessionId = id; }
    /** data for the current leaseRequest, or null if there is no active leaseSet request */
    LeaseRequestState getLeaseRequest() { return _leaseRequest; }
    void setLeaseRequest(LeaseRequestState req) { _leaseRequest = req; }
    /** already closed? */
    boolean isDead() { return _dead; }
    /** message body */
    Payload getPayload(MessageId id) { synchronized (_messages) { return (Payload)_messages.get(id); } }
    void setPayload(MessageId id, Payload payload) { synchronized (_messages) { _messages.put(id, payload); } }
    void removePayload(MessageId id) { synchronized (_messages) { _messages.remove(id); } }
    
    void sessionEstablished(SessionConfig config) {
        _config = config;
        _manager.destinationEstablished(this);
    }
    
    void updateMessageDeliveryStatus(MessageId id, boolean delivered) {
        if (_dead) return;
        _context.jobQueue().addJob(new MessageDeliveryStatusUpdate(id, delivered));
    }
    /** 
     * called after a new leaseSet is granted by the client, the NetworkDb has been
     * updated.  This takes care of all the LeaseRequestState stuff (including firing any jobs)
     */
    void leaseSetCreated(LeaseSet ls) {
        if (_leaseRequest == null) {
            _log.error("LeaseRequest is null and we've received a new lease?! WTF");
            return;
        } else {
            _leaseRequest.setIsSuccessful(true);
            if (_leaseRequest.getOnGranted() != null) 
                _context.jobQueue().addJob(_leaseRequest.getOnGranted());
            _leaseRequest = null;
            _currentLeaseSet = ls;
        }
    }
    
    void disconnectClient(String reason) {
        _log.error("Disconnecting the client: " + reason, new Exception("Disconnecting!"));
        DisconnectMessage msg = new DisconnectMessage();
        msg.setReason(reason);
        try {
            doSend(msg);
        } catch (I2CPMessageException ime) {
            _log.error("Error writing out the disconnect message", ime);
        } catch (IOException ioe) {
            _log.error("Error writing out the disconnect message", ioe);
        }
        stopRunning();
    }
    
    /**
     * Distribute the message.  If the dest is local, it blocks until its passed 
     * to the target ClientConnectionRunner (which then fires it into a MessageReceivedJob).
     * If the dest is remote, it blocks until it is added into the ClientMessagePool
     *
     */
    MessageId distributeMessage(SendMessageMessage message) {
        Payload payload = message.getPayload();
        Destination dest = message.getDestination();
        MessageId id = new MessageId();
        id.setMessageId(getNextMessageId()); 
        synchronized (_acceptedPending) {
            _acceptedPending.add(id);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("** Recieving message [" + id.getMessageId() + "] with payload of size [" 
                       + payload.getSize() + "]" + " for session [" + _sessionId.getSessionId() 
                       + "]");
        // the following blocks as described above
        _manager.distributeMessage(_config.getDestination(), message.getDestination(), message.getPayload(), id);
        return id;
    }
    
    /** 
     * Send a notification to the client that their message (id specified) was accepted 
     * for delivery (but not necessarily delivered)
     *
     */
    void ackSendMessage(MessageId id, long nonce) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Acking message send [accepted]" + id + " / " + nonce + " for sessionId " 
                       + _sessionId, new Exception("sendAccepted"));
        MessageStatusMessage status = new MessageStatusMessage();
        status.setMessageId(id); 
        status.setSessionId(_sessionId);
        status.setSize(0L);
        status.setNonce(nonce);
        status.setStatus(MessageStatusMessage.STATUS_SEND_ACCEPTED);
        try {
            doSend(status);
            synchronized (_acceptedPending) {
                _acceptedPending.remove(id);
            }
        } catch (I2CPMessageException ime) {
            _log.error("Error writing out the message status message", ime);
        } catch (IOException ioe) {
            _log.error("Error writing out the message status message", ioe);
        }
    }
    
    /**
     * Asynchronously deliver the message to the current runner
     *
     */ 
    void receiveMessage(Destination toDest, Destination fromDest, Payload payload) {
        if (_dead) return;
        _context.jobQueue().addJob(new MessageReceivedJob(_context, this, toDest, fromDest, payload));
    }
    
    /**
     * Send async abuse message to the client
     *
     */
    public void reportAbuse(String reason, int severity) {
        if (_dead) return;
        _context.jobQueue().addJob(new ReportAbuseJob(_context, this, reason, severity));
    }
        
    /**
     * Request that a particular client authorize the Leases contained in the 
     * LeaseSet, after which the onCreateJob is queued up.  If that doesn't occur
     * within the timeout specified, queue up the onFailedJob.  This call does not
     * block.
     *
     * @param set LeaseSet with requested leases - this object must be updated to contain the 
     *            signed version (as well as any changed/added/removed Leases)
     * @param expirationTime ms to wait before failing
     * @param onCreateJob Job to run after the LeaseSet is authorized
     * @param onFailedJob Job to run after the timeout passes without receiving authorization
     */
    void requestLeaseSet(LeaseSet set, long expirationTime, Job onCreateJob, Job onFailedJob) {
        if (_dead) return;
        _context.jobQueue().addJob(new RequestLeaseSetJob(_context, this, set, expirationTime, onCreateJob, onFailedJob));
    }

    void disconnected() {
        _log.error("Disconnected", new Exception("Disconnected?"));
        stopRunning();
    }
    
    ////
    ////
       
    /**
     * Actually send the I2CPMessage to the peer through the socket
     *
     */
    void doSend(I2CPMessage msg) throws I2CPMessageException, IOException {
        if (_out == null) throw new I2CPMessageException("Output stream is not initialized");
        long before = _context.clock().now();
        try {
            synchronized (_out) {
                msg.writeMessage(_out);
                _out.flush();
            }
        } catch (I2CPMessageException ime) {
            _log.error("Message exception sending I2CP message", ime);
            throw ime;
        } catch (IOException ioe) {
            _log.error("IO exception sending I2CP message", ioe);
            throw ioe;
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Unhandled exception sending I2CP message", t);
            throw new IOException("Unhandled exception sending I2CP message: " + t.getMessage());
        } finally {
            long after = _context.clock().now();
            long lag = after - before;
            if (lag > 300) {
                _log.error("synchronization on the i2cp message send took too long (" + lag 
                           + "ms): " + msg, new Exception("I2CP Lag"));
            }
        }
    }
    
    // this *should* be mod 65536, but UnsignedInteger is still b0rked.  FIXME
    private final static int MAX_MESSAGE_ID = 32767;
    private static volatile int _messageId = RandomSource.getInstance().nextInt(MAX_MESSAGE_ID); // messageId counter
    private static Object _messageIdLock = new Object();
    
    static int getNextMessageId() { 
        synchronized (_messageIdLock) {
            int messageId = (++_messageId)%MAX_MESSAGE_ID;
            if (_messageId >= MAX_MESSAGE_ID)
                _messageId = 0;
            return messageId; 
        }
    }
    
    /**
     * True if the client has already been sent the ACCEPTED state for the given
     * message id, false otherwise.
     *
     */
    private boolean alreadyAccepted(MessageId id) {
        if (_dead) return false;
        boolean isPending = false;
        int pending = 0;
        String buf = null;
        synchronized (_acceptedPending) {
            if (_acceptedPending.contains(id))
                isPending = true;
            pending = _acceptedPending.size();
            buf = _acceptedPending.toString();
        }
        if (pending >= 1) {
            _log.warn("Pending acks: " + pending + ": " + buf);
        }
        return !isPending;
    }
    
    /**
     * If the message hasn't been state=ACCEPTED yet, we shouldn't send an update
     * since the client doesn't know the message id (and we don't know the nonce).
     * So, we just wait REQUEUE_DELAY ms before trying again.
     *
     */
    private final static long REQUEUE_DELAY = 500;
    
    private class MessageDeliveryStatusUpdate extends JobImpl {
        private MessageId _messageId;
        private boolean _success;
        private long _lastTried;
        public MessageDeliveryStatusUpdate(MessageId id, boolean success) {
            super(ClientConnectionRunner.this._context);
            _messageId = id;
            _success = success;
            _lastTried = 0;
        }

        public String getName() { return "Update Delivery Status"; }
        public void runJob() {
            if (_dead) return;

            MessageStatusMessage msg = new MessageStatusMessage();
            msg.setMessageId(_messageId);
            msg.setSessionId(_sessionId);
            msg.setNonce(2);
            msg.setSize(0);
            if (_success) 
                msg.setStatus(MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS);
            else
                msg.setStatus(MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE);	

            if (!alreadyAccepted(_messageId)) {
                _log.warn("Almost send an update for message " + _messageId + " to " 
                          + MessageStatusMessage.getStatusString(msg.getStatus()) 
                          + " for session [" + _sessionId.getSessionId() 
                          + "] before they knew the messageId!  delaying .5s");
                _lastTried = ClientConnectionRunner.this._context.clock().now();
                requeue(REQUEUE_DELAY);
                return;
            }

            synchronized (_alreadyProcessed) {
                if (_alreadyProcessed.contains(_messageId)) {
                    _log.warn("Status already updated");
                    return;
                } else {
                    _alreadyProcessed.add(_messageId);
                    while (_alreadyProcessed.size() > 10)
                        _alreadyProcessed.remove(0);
                }
            }

            if (_lastTried > 0) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.info("Updating message status for message " + _messageId + " to " 
                              + MessageStatusMessage.getStatusString(msg.getStatus()) 
                              + " for session [" + _sessionId.getSessionId() 
                              + "] (with nonce=2), retrying after [" 
                              + (ClientConnectionRunner.this._context.clock().now() - _lastTried) 
                              + "]", getAddedBy());
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating message status for message " + _messageId + " to " 
                               + MessageStatusMessage.getStatusString(msg.getStatus()) 
                               + " for session [" + _sessionId.getSessionId() + "] (with nonce=2)");
            }

            try {
                doSend(msg);
            } catch (I2CPMessageException ime) {
                _log.warn("Error updating the status for message ID " + _messageId, ime);
            } catch (IOException ioe) {
                _log.warn("Error updating the status for message ID " + _messageId, ioe);
            }
        }
    }
}
