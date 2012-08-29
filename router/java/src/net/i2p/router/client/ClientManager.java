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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.client.I2PSessionException;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.internal.I2CPMessageQueue;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.ClientMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Coordinate connections and various tasks
 *
 * @author jrandom
 */
class ClientManager {
    private final Log _log;
    private ClientListenerRunner _listener;
    private final HashMap<Destination, ClientConnectionRunner>  _runners;        // Destination --> ClientConnectionRunner
    private final Set<ClientConnectionRunner> _pendingRunners; // ClientConnectionRunner for clients w/out a Dest yet
    private final RouterContext _ctx;
    private volatile boolean _isStarted;

    /** Disable external interface, allow internal clients only @since 0.8.3 */
    private static final String PROP_DISABLE_EXTERNAL = "i2cp.disableInterface";
    /** SSL interface (only) @since 0.8.3 */
    private static final String PROP_ENABLE_SSL = "i2cp.SSL";

    public ClientManager(RouterContext context, int port) {
        _ctx = context;
        _log = context.logManager().getLog(ClientManager.class);
        //_ctx.statManager().createRateStat("client.receiveMessageSize", 
        //                                      "How large are messages received by the client?", 
        //                                      "ClientMessages", 
        //                                      new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _runners = new HashMap();
        _pendingRunners = new HashSet();
        startListeners(port);
    }

    /** Todo: Start a 3rd listener for IPV6? */
    private void startListeners(int port) {
        if (!_ctx.getBooleanProperty(PROP_DISABLE_EXTERNAL)) {
            // there's no option to start both an SSL and non-SSL listener
            if (_ctx.getBooleanProperty(PROP_ENABLE_SSL))
                _listener = new SSLClientListenerRunner(_ctx, this, port);
            else
                _listener = new ClientListenerRunner(_ctx, this, port);
            Thread t = new I2PThread(_listener, "ClientListener:" + port, true);
            t.start();
        }
        _isStarted = true;
    }
    
    public void restart() {
        shutdown("Router restart");
        
        // to let the old listener die
        try { Thread.sleep(2*1000); } catch (InterruptedException ie) {}
        
        int port = _ctx.getProperty(ClientManagerFacadeImpl.PROP_CLIENT_PORT,
                                    ClientManagerFacadeImpl.DEFAULT_PORT);
        startListeners(port);
    }
    
    /**
     *  @param msg message to send to the clients
     */
    public void shutdown(String msg) {
        _isStarted = false;
        _log.info("Shutting down the ClientManager");
        if (_listener != null)
            _listener.stopListening();
        Set<ClientConnectionRunner> runners = new HashSet();
        synchronized (_runners) {
            for (Iterator<ClientConnectionRunner> iter = _runners.values().iterator(); iter.hasNext();) {
                ClientConnectionRunner runner = iter.next();
                runners.add(runner);
            }
        }
        synchronized (_pendingRunners) {
            for (Iterator<ClientConnectionRunner> iter = _pendingRunners.iterator(); iter.hasNext();) {
                ClientConnectionRunner runner = iter.next();
                runners.add(runner);
            }
        }
        for (Iterator<ClientConnectionRunner> iter = runners.iterator(); iter.hasNext(); ) {
            ClientConnectionRunner runner = iter.next();
            runner.disconnectClient(msg, Log.WARN);
        }
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
        // for now we make these unlimited size
        LinkedBlockingQueue<I2CPMessage> in = new LinkedBlockingQueue();
        LinkedBlockingQueue<I2CPMessage> out = new LinkedBlockingQueue();
        I2CPMessageQueue myQueue = new I2CPMessageQueueImpl(in, out);
        I2CPMessageQueue hisQueue = new I2CPMessageQueueImpl(out, in);
        ClientConnectionRunner runner = new QueuedClientConnectionRunner(_ctx, this, myQueue);
        registerConnection(runner);
        return hisQueue;
    }

    public boolean isAlive() {
        return _isStarted && (_listener == null || _listener.isListening());
    }

    public void registerConnection(ClientConnectionRunner runner) {
        synchronized (_pendingRunners) {
            _pendingRunners.add(runner);
        }
        runner.startRunning();
    }
    
    public void unregisterConnection(ClientConnectionRunner runner) {
        _log.warn("Unregistering (dropping) a client connection");
        synchronized (_pendingRunners) {
            _pendingRunners.remove(runner);
        }
        if ( (runner.getConfig() != null) && (runner.getConfig().getDestination() != null) ) {
            // after connection establishment
            synchronized (_runners) {
                _runners.remove(runner.getConfig().getDestination());
            }
        }
    }
    
    /**
     * Add to the clients list. Check for a dup destination.
     */
    public void destinationEstablished(ClientConnectionRunner runner) {
        Destination dest = runner.getConfig().getDestination();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("DestinationEstablished called for destination " + dest.calculateHash().toBase64());

        synchronized (_pendingRunners) {
            _pendingRunners.remove(runner);
        }
        boolean fail = false;
        synchronized (_runners) {
            fail = _runners.containsKey(dest);
            if (!fail)
                _runners.put(dest, runner);
        }
        if (fail) {
            _log.log(Log.CRIT, "Client attempted to register duplicate destination " + dest.calculateHash().toBase64());
            runner.disconnectClient("Duplicate destination");
        }
    }
    
    /**
     * Distribute message to a local or remote destination.
     * @param flags ignored for local
     */
    void distributeMessage(Destination fromDest, Destination toDest, Payload payload, MessageId msgId, long expiration, int flags) { 
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
            // TODO can we just run this inline instead?
            _ctx.jobQueue().addJob(new DistributeLocal(toDest, runner, sender, fromDest, payload, msgId));
        } else {
            // remote.  w00t
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Message " + msgId + " is targeting a REMOTE destination!  Added to the client message pool");
            runner = getRunner(fromDest);
            if (runner == null) {
                // sender went away
                return;
            }
            ClientMessage msg = new ClientMessage();
            msg.setDestination(toDest);
            msg.setPayload(payload);
            msg.setSenderConfig(runner.getConfig());
            msg.setFromDestination(runner.getConfig().getDestination());
            msg.setMessageId(msgId);
            msg.setExpiration(expiration);
            msg.setFlags(flags);
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
        
        public DistributeLocal(Destination toDest, ClientConnectionRunner to, ClientConnectionRunner from, Destination fromDest, Payload payload, MessageId id) {
            super(_ctx);
            _toDest = toDest;
            _to = to;
            _from = from;
            _fromDest = fromDest;
            _payload = payload;
            _msgId = id;
        }
        public String getName() { return "Distribute local message"; }
        public void runJob() {
            _to.receiveMessage(_toDest, _fromDest, _payload);
            if (_from != null) {
                _from.updateMessageDeliveryStatus(_msgId, true);
            }
        }
    }
    
    
    /**
     * Request that a particular client authorize the Leases contained in the 
     * LeaseSet, after which the onCreateJob is queued up.  If that doesn't occur
     * within the timeout specified, queue up the onFailedJob.  This call does not
     * block.
     *
     * @param dest Destination from which the LeaseSet's authorization should be requested
     * @param set LeaseSet with requested leases - this object must be updated to contain the 
     *            signed version (as well as any changed/added/removed Leases)
     * @param timeout ms to wait before failing
     * @param onCreateJob Job to run after the LeaseSet is authorized
     * @param onFailedJob Job to run after the timeout passes without receiving authorization
     */
    public void requestLeaseSet(Destination dest, LeaseSet set, long timeout, Job onCreateJob, Job onFailedJob) {
        ClientConnectionRunner runner = getRunner(dest);
        if (runner == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.warn("Cannot request the lease set, as we can't find a client runner for " 
                          + dest.calculateHash().toBase64() + ".  disconnected?");
            _ctx.jobQueue().addJob(onFailedJob);
        } else {
            runner.requestLeaseSet(set, _ctx.clock().now() + timeout, onCreateJob, onFailedJob);
        }
    }

    private static final int REQUEST_LEASESET_TIMEOUT = 120*1000;
    public void requestLeaseSet(Hash dest, LeaseSet ls) {
        ClientConnectionRunner runner = getRunner(dest);
        if (runner != null)  {
            // no need to fire off any jobs...
            runner.requestLeaseSet(ls, REQUEST_LEASESET_TIMEOUT, null, null);
        }
    }
    
    public boolean isLocal(Destination dest) { 
        boolean rv = false;
        long beforeLock = _ctx.clock().now();
        long inLock = 0;
        synchronized (_runners) {
            inLock = _ctx.clock().now();
            rv = _runners.containsKey(dest);
        }
        long afterLock = _ctx.clock().now();

        if (afterLock - beforeLock > 50) {
            _log.warn("isLocal(Destination).locking took too long: " + (afterLock-beforeLock)
                      + " overall, synchronized took " + (inLock - beforeLock));
        }
        return rv;
    }
    public boolean isLocal(Hash destHash) { 
        if (destHash == null) return false;
        synchronized (_runners) {
            for (Iterator iter = _runners.values().iterator(); iter.hasNext(); ) {
                ClientConnectionRunner cur = (ClientConnectionRunner)iter.next();
                if (destHash.equals(cur.getDestHash())) return true;
            }
        }
        return false;
    }
    
    /**
     *  @return true if we don't know about this destination at all
     */
    public boolean shouldPublishLeaseSet(Hash destHash) { 
        if (destHash == null) return true;
        ClientConnectionRunner runner = getRunner(destHash);
        if (runner == null) return true;
        return !Boolean.valueOf(runner.getConfig().getOptions().getProperty(ClientManagerFacade.PROP_CLIENT_ONLY)).booleanValue();
    }

    public Set<Destination> listClients() {
        Set<Destination> rv = new HashSet();
        synchronized (_runners) {
            rv.addAll(_runners.keySet());
        }
        return rv;
    }

    
    ClientConnectionRunner getRunner(Destination dest) {
        ClientConnectionRunner rv = null;
        long beforeLock = _ctx.clock().now();
        long inLock = 0;
        synchronized (_runners) {
            inLock = _ctx.clock().now();
            rv = _runners.get(dest);
        }
        long afterLock = _ctx.clock().now();
        if (afterLock - beforeLock > 50) {
            _log.warn("getRunner(Dest).locking took too long: " + (afterLock-beforeLock)
                      + " overall, synchronized took " + (inLock - beforeLock));
        }
        return rv;
    }
    
    /**
     * Return the client's current config, or null if not connected
     *
     */
    public SessionConfig getClientSessionConfig(Destination dest) {
        ClientConnectionRunner runner = getRunner(dest);
        if (runner != null)
            return runner.getConfig();
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
    
    private ClientConnectionRunner getRunner(Hash destHash) {
        if (destHash == null) 
            return null;
        synchronized (_runners) {
            for (Iterator<ClientConnectionRunner> iter = _runners.values().iterator(); iter.hasNext(); ) {
                ClientConnectionRunner cur = iter.next();
                if (cur.getDestHash().equals(destHash))
                    return cur;
	    }
        }
        return null;
    }
    
    public void messageDeliveryStatusUpdate(Destination fromDest, MessageId id, boolean delivered) {
        ClientConnectionRunner runner = getRunner(fromDest);
        if (runner != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Delivering status [" + (delivered?"success":"failure") + "] to " 
                           + fromDest.calculateHash().toBase64() + " for message " + id);
            runner.updateMessageDeliveryStatus(id, delivered);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Cannot deliver status [" + (delivered?"success":"failure") + "] to " 
                          + fromDest.calculateHash().toBase64() + " for message " + id);
        }
    }
    
    Set<Destination> getRunnerDestinations() {
        Set<Destination> dests = new HashSet();
        long beforeLock = _ctx.clock().now();
        long inLock = 0;
        synchronized (_runners) {
            inLock = _ctx.clock().now();
            dests.addAll(_runners.keySet());
        }
        long afterLock = _ctx.clock().now();
        if (afterLock - beforeLock > 50) {
            _log.warn("getRunnerDestinations().locking took too long: " + (afterLock-beforeLock)
                      + " overall, synchronized took " + (inLock - beforeLock));
        }
        
        return dests;
    }
    
    public void reportAbuse(Destination dest, String reason, int severity) {
        if (dest != null) {
            ClientConnectionRunner runner = getRunner(dest);
            if (runner != null) {
                runner.reportAbuse(reason, severity);
            }
        } else {
            Set dests = getRunnerDestinations();
            for (Iterator iter = dests.iterator(); iter.hasNext(); ) {
                Destination d = (Destination)iter.next();
                reportAbuse(d, reason, severity);
            }
        }
    }
    
    /** @deprecated unused */
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
        _ctx.jobQueue().addJob(new HandleJob(msg));
    }

    private class HandleJob extends JobImpl {
        private ClientMessage _msg;
        public HandleJob(ClientMessage msg) {
            super(_ctx);
            _msg = msg;
        }
        public String getName() { return "Handle Inbound Client Messages"; }
        public void runJob() {
            ClientConnectionRunner runner = null;
            if (_msg.getDestination() != null) 
                runner = getRunner(_msg.getDestination());
            else 
                runner = getRunner(_msg.getDestinationHash());

            if (runner != null) {
                //_ctx.statManager().addRateData("client.receiveMessageSize", 
                //                                   _msg.getPayload().getSize(), 0);
                runner.receiveMessage(_msg.getDestination(), null, _msg.getPayload());
            } else {
                // no client connection...
                // we should pool these somewhere...
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Message received but we don't have a connection to " 
                              + _msg.getDestination() + "/" + _msg.getDestinationHash() 
                              + " currently.  DROPPED");
            }
        }
    }
}
