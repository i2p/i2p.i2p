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
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.CoreVersion;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.data.i2cp.SetDateMessage;
import net.i2p.internal.I2CPMessageQueue;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.ClientMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 * Coordinate connections and various tasks
 *
 * @author jrandom
 */
class ClientManager {
    private final Log _log;
    protected final List<ClientListenerRunner> _listeners;
    // Destination --> ClientConnectionRunner
    // Locked for adds/removes but not lookups
    // If a runner has multiple sessions it will be in here multiple times, one for each dest
    private final Map<Destination, ClientConnectionRunner>  _runners;
    // Same as what's in _runners, but for fast lookup by Hash
    // Locked for adds/removes but not lookups
    // If a runner has multiple sessions it will be in here multiple times, one for each dest
    private final Map<Hash, ClientConnectionRunner>  _runnersByHash;
    // ClientConnectionRunner for clients w/out a Dest yet
    private final Set<ClientConnectionRunner> _pendingRunners;
    private final Set<SessionId> _runnerSessionIds;
    protected final RouterContext _ctx;
    protected final int _port;
    protected volatile boolean _isStarted;
    private final SimpleTimer2.TimedEvent _clientTimestamper;

    /** Disable external interface, allow internal clients only @since 0.8.3 */
    private static final String PROP_DISABLE_EXTERNAL = "i2cp.disableInterface";
    /** SSL interface (only) @since 0.8.3 */
    private static final String PROP_ENABLE_SSL = "i2cp.SSL";

    private static final int INTERNAL_QUEUE_SIZE = 256;

    private static final long REQUEST_LEASESET_TIMEOUT = 60*1000;

    /** 2 bytes, save 65535 for unknown */
    private static final int MAX_SESSION_ID = 65534;
    private static final String PROP_MAX_SESSIONS = "i2cp.maxSessions";
    private static final int DEFAULT_MAX_SESSIONS = 100;
    /** 65535 */
    public static final SessionId UNKNOWN_SESSION_ID = new SessionId(MAX_SESSION_ID + 1);


    /**
     *  Does not start the listeners.
     *  Caller must call start()
     */
    public ClientManager(RouterContext context, int port) {
        _ctx = context;
        _log = context.logManager().getLog(ClientManager.class);
        //_ctx.statManager().createRateStat("client.receiveMessageSize", 
        //                                      "How large are messages received by the client?", 
        //                                      "ClientMessages", 
        //                                      new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _listeners = new ArrayList<ClientListenerRunner>();
        _runners = new ConcurrentHashMap<Destination, ClientConnectionRunner>();
        _runnersByHash = new ConcurrentHashMap<Hash, ClientConnectionRunner>();
        _pendingRunners = new HashSet<ClientConnectionRunner>();
        _runnerSessionIds = new HashSet<SessionId>();
        _port = port;
        _clientTimestamper = new ClientTimestamper();
        // following are for RequestLeaseSetJob
        _ctx.statManager().createRateStat("client.requestLeaseSetSuccess", "How frequently the router requests successfully a new leaseSet?", "ClientMessages", new long[] { 60*60*1000 });
        _ctx.statManager().createRateStat("client.requestLeaseSetTimeout", "How frequently the router requests a new leaseSet but gets no reply?", "ClientMessages", new long[] { 60*60*1000 });
        _ctx.statManager().createRateStat("client.requestLeaseSetDropped", "How frequently the router requests a new leaseSet but the client drops?", "ClientMessages", new long[] { 60*60*1000 });
    }

    /** @since 0.9.8 */
    public synchronized void start() {
        startListeners();
    }

    /**
     *  Call from synchronized method
     *  Todo: Start a 3rd listener for IPV6?
     */
    protected void startListeners() {
        ClientListenerRunner listener;
        if (SystemVersion.isAndroid()) {
            try {
                Class<? extends ClientListenerRunner> clazz = Class.forName(
                        "net.i2p.router.client.DomainClientListenerRunner"
                    ).asSubclass(ClientListenerRunner.class);
                Constructor<? extends ClientListenerRunner> ctor =
                    clazz.getDeclaredConstructor(RouterContext.class,
                                                 ClientManager.class);
                listener = ctor.newInstance(_ctx, this);
                Thread t = new I2PThread(listener, "DomainClientListener", true);
                t.start();
                _listeners.add(listener);
            } catch (ClassNotFoundException e) {
                _log.warn("Could not find DomainClientListenerRunner class", e);
            } catch (ClassCastException e) {
                _log.error("Error creating DomainClientListenerRunner", e);
            } catch (NoSuchMethodException e) {
                _log.error("Error creating DomainClientListenerRunner", e);
            } catch (InstantiationException e) {
                _log.error("Error creating DomainClientListenerRunner", e);
            } catch (IllegalAccessException e) {
                _log.error("Error creating DomainClientListenerRunner", e);
            } catch (InvocationTargetException e) {
                _log.error("Error creating DomainClientListenerRunner", e);
            }
        }
        if (!_ctx.getBooleanProperty(PROP_DISABLE_EXTERNAL)) {
            // there's no option to start both an SSL and non-SSL listener
            if (_ctx.getBooleanProperty(PROP_ENABLE_SSL))
                listener = new SSLClientListenerRunner(_ctx, this, _port);
            else
                listener = new ClientListenerRunner(_ctx, this, _port);
            Thread t = new I2PThread(listener, "ClientListener:" + _port, true);
            t.start();
            _listeners.add(listener);
            _clientTimestamper.schedule(ClientTimestamper.LOOP_TIME);
        }
        _isStarted = true;
    }
    
    public synchronized void restart() {
        shutdown("Router restart");
        
        // to let the old listener die
        try { Thread.sleep(2*1000); } catch (InterruptedException ie) {}
        
        startListeners();
    }
    
    /**
     *  @param msg message to send to the clients
     */
    public synchronized void shutdown(String msg) {
        _isStarted = false;
        _log.info("Shutting down the ClientManager");
        for (ClientListenerRunner listener : _listeners)
            listener.stopListening();
        _listeners.clear();
        Set<ClientConnectionRunner> runners = new HashSet<ClientConnectionRunner>();
        synchronized (_runners) {
            for (ClientConnectionRunner runner : _runners.values()) {
                runners.add(runner);
            }
        }
        synchronized (_pendingRunners) {
            for (ClientConnectionRunner runner : _pendingRunners) {
                runners.add(runner);
            }
        }
        for (ClientConnectionRunner runner : runners) {
            runner.disconnectClient(msg, Log.WARN);
        }
        _runnersByHash.clear();
        _clientTimestamper.cancel();
    }
    
    /**
     *  The InternalClientManager interface.
     *  Connects to the router, receiving a message queue to talk to the router with.
     *  @throws I2PSessionException if the router isn't ready
     *  @since 0.8.3
     */
    public I2CPMessageQueue internalConnect() throws I2PSessionException {
        if (!_isStarted)
            throw new I2PSessionException("Router client manager is shut down");
        LinkedBlockingQueue<I2CPMessage> in = new LinkedBlockingQueue<I2CPMessage>(INTERNAL_QUEUE_SIZE);
        LinkedBlockingQueue<I2CPMessage> out = new LinkedBlockingQueue<I2CPMessage>(INTERNAL_QUEUE_SIZE);
        I2CPMessageQueue myQueue = new I2CPMessageQueueImpl(in, out);
        I2CPMessageQueue hisQueue = new I2CPMessageQueueImpl(out, in);
        ClientConnectionRunner runner = new QueuedClientConnectionRunner(_ctx, this, myQueue);
        registerConnection(runner);
        return hisQueue;
    }

    public synchronized boolean isAlive() {
        boolean listening = true;
        if (!_listeners.isEmpty()) {
            for (ClientListenerRunner listener : _listeners)
                listening = listening && listener.isListening();
        }
        return _isStarted && (_listeners.isEmpty() || listening);
    }

    public void registerConnection(ClientConnectionRunner runner) {
        try {
            runner.startRunning();
            synchronized (_pendingRunners) {
                _pendingRunners.add(runner);
            }
        } catch (IOException ioe) {
            _log.error("Error starting up the runner", ioe);
            runner.stopRunning();
        }
    }
    
    /**
     *  Remove all sessions for this runner.
     */
    public void unregisterConnection(ClientConnectionRunner runner) {
        synchronized (_pendingRunners) {
            _pendingRunners.remove(runner);
        }

        List<SessionId> ids = runner.getSessionIds();
        List<Destination> dests = runner.getDestinations();
        if (_log.shouldLog(Log.WARN))
            _log.warn("Unregistering (dropping) a client connection with ids: " + ids);
        synchronized (_runners) {
            for (SessionId id : ids) {
                _runnerSessionIds.remove(id);
            }
            for (Destination dest : dests) {
                _runners.remove(dest);
                _runnersByHash.remove(dest.calculateHash());
            }
            // just in case
            for (Iterator<ClientConnectionRunner> iter = _runners.values().iterator(); iter.hasNext(); ) {
                ClientConnectionRunner r = iter.next();
                if (r.equals(runner))
                    iter.remove();
            }
            for (Iterator<ClientConnectionRunner> iter = _runnersByHash.values().iterator(); iter.hasNext(); ) {
                ClientConnectionRunner r = iter.next();
                if (r.equals(runner))
                    iter.remove();
            }
        }
    }
    
    /**
     *  Remove only the following session. Does not remove the runner if it has more.
     *
     *  @since 0.9.21
     */
    public void unregisterSession(SessionId id, Destination dest) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Unregistering client session "  + id);
        synchronized (_runners) {
            _runnerSessionIds.remove(id);
            _runners.remove(dest);
            _runnersByHash.remove(dest.calculateHash());
        }
    }
    
    /**
     *  Add to the clients list. Check for a dup destination.
     *  Side effect: Sets the session ID of the runner.
     *  Caller must call runner.disconnectClient() on failure.
     *
     *  @return SessionStatusMessage return code, 1 for success, != 1 for failure
     */
    public int destinationEstablished(ClientConnectionRunner runner, Destination dest) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("DestinationEstablished called for destination " + dest.toBase32());

        synchronized (_pendingRunners) {
            _pendingRunners.remove(runner);
        }
        int rv;
        synchronized (_runners) {
            boolean fail = _runnersByHash.containsKey(dest.calculateHash());
            if (fail) {
                rv = SessionStatusMessage.STATUS_INVALID;
            } else {
                SessionId id = locked_getNextSessionId();
                if (id != null) {
                    Hash h = dest.calculateHash();
                    runner.setSessionId(h, id);
                    _runners.put(dest, runner);
                    _runnersByHash.put(h, runner);
                    rv = SessionStatusMessage.STATUS_CREATED;
                } else {
                    rv = SessionStatusMessage.STATUS_REFUSED;
                }
            }
        }
        if (rv == SessionStatusMessage.STATUS_INVALID) {
            _log.log(Log.CRIT, "Client attempted to register duplicate destination " + dest.toBase32());
        } else if (rv == SessionStatusMessage.STATUS_REFUSED) {
            _log.error("Max sessions exceeded " + dest.toBase32());
        }
        return rv;
    }
    
    /**
     *  Generate a new random, unused sessionId. Caller must synch on _runners.
     *  @return null on failure
     *  @since 0.9.12
     */
    private SessionId locked_getNextSessionId() { 
        int max = Math.max(1, Math.min(2048, _ctx.getProperty(PROP_MAX_SESSIONS, DEFAULT_MAX_SESSIONS)));
        if (_runnerSessionIds.size() >= max) {
            _log.logAlways(Log.WARN, "Session refused, max is " + max + ", increase " + PROP_MAX_SESSIONS);
            return null;
        }
        for (int i = 0; i < 100; i++) {
            SessionId id = new SessionId(_ctx.random().nextInt(MAX_SESSION_ID + 1));
            if (_runnerSessionIds.add(id))
                return id; 
        }
        _log.logAlways(Log.WARN, "Session refused, can't find id slot");
        return null;
    }

    /**
     * Distribute message to a local or remote destination.
     * @param msgId the router's ID for this message
     * @param messageNonce the client's ID for this message
     * @param flags ignored for local
     */
    void distributeMessage(Destination fromDest, Destination toDest, Payload payload,
                           MessageId msgId, long messageNonce, long expiration, int flags) { 
        // check if there is a runner for it
        ClientConnectionRunner runner = getRunner(toDest);
        if (runner != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Message " + msgId + " is targeting a local destination.  distribute it as such");
            ClientConnectionRunner sender = getRunner(fromDest);
            if (sender == null) {
                // sender went away
                return;
            }
            // run this inline so we don't clog up the job queue
            Job j = new DistributeLocal(toDest, runner, sender, fromDest, payload, msgId, messageNonce);
            //_ctx.jobQueue().addJob(j);
            j.runJob();
        } else {
            // remote.  w00t
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Message " + msgId + " is targeting a REMOTE destination!  Added to the client message pool");
            runner = getRunner(fromDest);
            if (runner == null) {
                // sender went away
                return;
            }
            SessionConfig config = runner.getConfig(fromDest.calculateHash());
            if (config == null)
                return;
            ClientMessage msg = new ClientMessage(toDest, payload, config,
                                                  fromDest, msgId,
                                                  messageNonce, expiration, flags);
            _ctx.clientMessagePool().add(msg, true);
        }
    }
    
    private class DistributeLocal extends JobImpl {
        private final Destination _toDest;
        private final ClientConnectionRunner _to;
        private final ClientConnectionRunner _from;
        private final Destination _fromDest;
        private final Payload _payload;
        private final MessageId _msgId;
        private final long _messageNonce;
        
        /**
         * @param msgId the router's ID for this message
         * @param messageNonce the client's ID for this message
         */
        public DistributeLocal(Destination toDest, ClientConnectionRunner to, ClientConnectionRunner from,
                               Destination fromDest, Payload payload, MessageId id, long messageNonce) {
            super(_ctx);
            _toDest = toDest;
            _to = to;
            _from = from;
            _fromDest = fromDest;
            _payload = payload;
            _msgId = id;
            _messageNonce = messageNonce;
        }

        public String getName() { return "Distribute local message"; }

        public void runJob() {
            boolean ok = _to.receiveMessage(_toDest, _fromDest, _payload);
            if (_from != null) {
                int rc = ok ? MessageStatusMessage.STATUS_SEND_SUCCESS_LOCAL : MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL;
                _from.updateMessageDeliveryStatus(_fromDest, _msgId, _messageNonce, rc);
            }
        }
    }
    
    
    /**
     * Request that a particular client authorize the Leases contained in the 
     * LeaseSet, after which the onCreateJob is queued up.  If that doesn't occur
     * within the timeout specified, queue up the onFailedJob.  This call does not
     * block.
     *
     * UNUSED, the call below without jobs is always used.
     *
     * @param dest Destination from which the LeaseSet's authorization should be requested
     * @param set LeaseSet with requested leases - this object must be updated to contain the 
     *            signed version (as well as any changed/added/removed Leases).
     *            The LeaseSet contains Leases only; it is unsigned and does not have the destination set.
     * @param timeout ms to wait before failing
     * @param onCreateJob Job to run after the LeaseSet is authorized
     * @param onFailedJob Job to run after the timeout passes without receiving authorization
     */
    public void requestLeaseSet(Destination dest, LeaseSet set, long timeout, Job onCreateJob, Job onFailedJob) {
        ClientConnectionRunner runner = getRunner(dest);
        if (runner == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Cannot request the lease set, as we can't find a client runner for " 
                          + dest.toBase32() + ".  disconnected?");
            _ctx.jobQueue().addJob(onFailedJob);
        } else {
            runner.requestLeaseSet(dest.calculateHash(), set, timeout, onCreateJob, onFailedJob);
        }
    }

    /**
     * Request that a particular client authorize the Leases contained in the 
     * LeaseSet.
     *
     * @param dest Destination from which the LeaseSet's authorization should be requested
     * @param ls  LeaseSet with requested leases - this object must be updated to contain the 
     *            signed version (as well as any changed/added/removed Leases).
     *            The LeaseSet contains Leases only; it is unsigned and does not have the destination set.
     */
    public void requestLeaseSet(Hash dest, LeaseSet ls) {
        ClientConnectionRunner runner = getRunner(dest);
        if (runner != null)  {
            // no need to fire off any jobs...
            runner.requestLeaseSet(dest, ls, REQUEST_LEASESET_TIMEOUT, null, null);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Cannot request the lease set, as we can't find a client runner for " 
                          + dest + ".  disconnected?");
        }
    }
    
    /**
     *  Unsynchronized
     */
    public boolean isLocal(Destination dest) { 
        return _runners.containsKey(dest);
    }

    /**
     *  Unsynchronized
     */
    public boolean isLocal(Hash destHash) { 
        if (destHash == null) return false;
        return _runnersByHash.containsKey(destHash);
    }
    
    /**
     *  @return true if we don't know about this destination at all
     */
    public boolean shouldPublishLeaseSet(Hash destHash) { 
        if (destHash == null) return true;
        ClientConnectionRunner runner = getRunner(destHash);
        if (runner == null) return true;
        SessionConfig config = runner.getConfig(destHash);
        if (config == null) return true;
        return !Boolean.parseBoolean(config.getOptions().getProperty(ClientManagerFacade.PROP_CLIENT_ONLY));
    }

    /**
     *  Unsynchronized
     */
    public Set<Destination> listClients() {
        Set<Destination> rv = new HashSet<Destination>();
        rv.addAll(_runners.keySet());
        return rv;
    }

    
    /**
     *  Unsynchronized
     */
    ClientConnectionRunner getRunner(Destination dest) {
        return _runners.get(dest);
    }
    
    /**
     * Return the client's current config, or null if not connected
     *
     */
    public SessionConfig getClientSessionConfig(Destination dest) {
        ClientConnectionRunner runner = getRunner(dest);
        if (runner != null)
            return runner.getConfig(dest.calculateHash());
        else
            return null;
    }
    
    /**
     * Return the client's SessionKeyManager
     * Use this instead of the RouterContext.sessionKeyManager()
     * to prevent correlation attacks across destinations
     */
    public SessionKeyManager getClientSessionKeyManager(Hash dest) {
        ClientConnectionRunner runner = getRunner(dest);
        if (runner != null)
            return runner.getSessionKeyManager();
        else
            return null;
    }
    
    /**
     *  Unsynchronized
     */
    private ClientConnectionRunner getRunner(Hash destHash) {
        if (destHash == null) 
            return null;
        return _runnersByHash.get(destHash);
    }
    
    /**
     *  @param id the router's ID for this message
     *  @param messageNonce the client's ID for this message
     *  @param status see I2CP MessageStatusMessage for success/failure codes
     */
    public void messageDeliveryStatusUpdate(Destination fromDest, MessageId id, long messageNonce, int status) {
        ClientConnectionRunner runner = getRunner(fromDest);
        if (runner != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Delivering status " + status + " to " 
                           + fromDest.calculateHash() + " for message " + id);
            runner.updateMessageDeliveryStatus(fromDest, id, messageNonce, status);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Cannot deliver status " + status + " to " 
                          + fromDest.calculateHash() + " for message " + id);
        }
    }
    
    /**
     *  @return unmodifiable, not a copy
     */
    Set<Destination> getRunnerDestinations() {
        return Collections.unmodifiableSet(_runners.keySet());
    }
    
    /**
     *  Unused
     *
     *  @param dest null for all local destinations
     */
    public void reportAbuse(Destination dest, String reason, int severity) {
        if (dest != null) {
            ClientConnectionRunner runner = getRunner(dest);
            if (runner != null) {
                runner.reportAbuse(dest, reason, severity);
            }
        } else {
            for (Destination d : _runners.keySet()) {
                reportAbuse(d, reason, severity);
            }
        }
    }
    
    /** @deprecated unused */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {
/******
        StringBuilder buf = new StringBuilder(8*1024);
        buf.append("<u><b>Local destinations</b></u><br>");
        
        Map<Destination, ClientConnectionRunner> runners = null;
        synchronized (_runners) {
            runners = (Map)_runners.clone();
        }
        for (Iterator<Destination> iter = runners.keySet().iterator(); iter.hasNext(); ) {
            Destination dest = iter.next();
            ClientConnectionRunner runner = runners.get(dest);
            buf.append("<b>*</b> ").append(dest.calculateHash().toBase64().substring(0,6)).append("<br>\n");
            LeaseSet ls = runner.getLeaseSet();
            if (ls == null) {
                buf.append("<font color=\"red\"><i>No lease</i></font><br>\n");
            } else { 
                long leaseAge = ls.getEarliestLeaseDate() - _ctx.clock().now();
                if (leaseAge <= 0) { 
                    buf.append("<font color=\"red\"><i>Lease expired ");
                    buf.append(DataHelper.formatDuration(0-leaseAge)).append(" ago</i></font><br>\n");
                } else {
                    int count = ls.getLeaseCount();
                    if (count <= 0) {
                        buf.append("<font color=\"red\"><i>No tunnels</i></font><br>\n");
                    } else {
                        TunnelId id = ls.getLease(0).getTunnelId();
                        TunnelInfo info = _ctx.tunnelManager().getTunnelInfo(id);
                        if (info == null) {
                            buf.append("<font color=\"red\"><i>Failed tunnels</i></font><br>\n");
                        } else {
                            buf.append(count).append(" x ");
                            buf.append(info.getLength() - 1).append(" hop tunnel");
                            if (count != 1)
                                buf.append('s');
                            buf.append("<br>\n");
                            buf.append("Expiring in ").append(DataHelper.formatDuration(leaseAge));
                            buf.append("<br>\n");
                        }
                    }
                }
            }
        }
        
        buf.append("\n<hr>\n");
        out.write(buf.toString());
        out.flush();
******/
    }
    
    public void messageReceived(ClientMessage msg) {
        // This is fast and non-blocking, run in-line
        //_ctx.jobQueue().addJob(new HandleJob(msg));
        (new HandleJob(msg)).runJob();
    }

    private class HandleJob extends JobImpl {
        private final ClientMessage _msg;

        public HandleJob(ClientMessage msg) {
            super(_ctx);
            _msg = msg;
        }

        public String getName() { return "Handle Inbound Client Messages"; }

        public void runJob() {
            ClientConnectionRunner runner;
            Destination dest = _msg.getDestination();
            if (dest != null) 
                runner = getRunner(dest);
            else 
                runner = getRunner(_msg.getDestinationHash());

            if (runner != null) {
                //_ctx.statManager().addRateData("client.receiveMessageSize", 
                //                                   _msg.getPayload().getSize(), 0);
                if (dest != null)
                    runner.receiveMessage(dest, null, _msg.getPayload());
                else
                    runner.receiveMessage(_msg.getDestinationHash(), null, _msg.getPayload());
            } else {
                // no client connection...
                // we should pool these somewhere...
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Message received but we don't have a connection to " 
                              + dest + "/" + _msg.getDestinationHash() 
                              + " currently.  DROPPED");
            }
        }
    }

    /**
     *  Tell external clients the time periodically
     *
     *  @since 0.9.20
     */
    private class ClientTimestamper extends SimpleTimer2.TimedEvent {

        public static final long LOOP_TIME = 10*60*1000;

        /** must call schedule() later */
        public ClientTimestamper() {
            super(_ctx.simpleTimer2());
        }

        public void timeReached() {
            if (!_isStarted)
                return;
            for (ClientConnectionRunner runner : _runners.values()) {
                if (runner instanceof QueuedClientConnectionRunner)
                    continue;
                if (runner.isDead())
                    continue;
                SessionConfig cfg = runner.getPrimaryConfig();
                if (cfg == null)
                    continue;  // simple session or no session yet
                if (runner.getLeaseSet(cfg.getDestination().calculateHash()) == null)
                    continue;  // don't confuse client while waiting for CreateLeaseSet msg
                try {
                    // only send version if the client can handle it (0.8.7 or greater)
                    runner.doSend(new SetDateMessage(runner.getClientVersion() != null ?
                                                     CoreVersion.VERSION : null));
                } catch (I2CPMessageException ime) {}
            }
            if (_isStarted)
                schedule(LOOP_TIME);
        }
    }
}
