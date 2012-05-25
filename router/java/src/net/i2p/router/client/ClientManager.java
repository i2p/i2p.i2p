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
import java.util.Map;
import java.util.Set;

import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.TunnelId;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.ClientMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Coordinate connections and various tasks
 *
 * @author jrandom
 */
public class ClientManager {
    private Log _log;
    private ClientListenerRunner _listener;
    private final HashMap _runners;        // Destination --> ClientConnectionRunner
    private final Set _pendingRunners; // ClientConnectionRunner for clients w/out a Dest yet
    private RouterContext _ctx;

    /** ms to wait before rechecking for inbound messages to deliver to clients */
    private final static int INBOUND_POLL_INTERVAL = 300;
    
    public ClientManager(RouterContext context, int port) {
        _ctx = context;
        _log = context.logManager().getLog(ClientManager.class);
        _ctx.statManager().createRateStat("client.receiveMessageSize", 
                                              "How large are messages received by the client?", 
                                              "ClientMessages", 
                                              new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _runners = new HashMap();
        _pendingRunners = new HashSet();
        _listener = new ClientListenerRunner(_ctx, this, port);
        Thread t = new I2PThread(_listener);
        t.setName("ClientListener:" + port);
        t.setDaemon(true);
        t.start();
    }
    
    public void restart() {
        shutdown();
        
        // to let the old listener die
        try { Thread.sleep(2*1000); } catch (InterruptedException ie) {}
        
        int port = ClientManagerFacadeImpl.DEFAULT_PORT;
        String portStr = _ctx.router().getConfigSetting(ClientManagerFacadeImpl.PROP_CLIENT_PORT);
        if (portStr != null) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException nfe) {
                _log.error("Error setting the port: " + portStr + " is not valid", nfe);
            }
        } 
        _listener = new ClientListenerRunner(_ctx, this, port);
        Thread t = new I2PThread(_listener);
        t.setName("ClientListener:" + port);
        t.setDaemon(true);
        t.start();
    }
    
    public void shutdown() {
        _log.info("Shutting down the ClientManager");
        _listener.stopListening();
        Set runners = new HashSet();
        synchronized (_runners) {
            for (Iterator iter = _runners.values().iterator(); iter.hasNext();) {
                ClientConnectionRunner runner = (ClientConnectionRunner)iter.next();
                runners.add(runner);
            }
        }
        synchronized (_pendingRunners) {
            for (Iterator iter = _pendingRunners.iterator(); iter.hasNext();) {
                ClientConnectionRunner runner = (ClientConnectionRunner)iter.next();
                runners.add(runner);
            }
        }
        for (Iterator iter = runners.iterator(); iter.hasNext(); ) {
            ClientConnectionRunner runner = (ClientConnectionRunner)iter.next();
            runner.stopRunning();
        }
    }
    
    public boolean isAlive() { return _listener.isListening(); }

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
    
    public void destinationEstablished(ClientConnectionRunner runner) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("DestinationEstablished called for destination " + runner.getConfig().getDestination().calculateHash().toBase64());

        synchronized (_pendingRunners) {
            _pendingRunners.remove(runner);
        }
        synchronized (_runners) {
            _runners.put(runner.getConfig().getDestination(), runner);
        }
    }
    
    void distributeMessage(Destination fromDest, Destination toDest, Payload payload, MessageId msgId, long expiration) { 
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
            msg.setReceptionInfo(null);
            msg.setSenderConfig(runner.getConfig());
            msg.setFromDestination(runner.getConfig().getDestination());
            msg.setMessageId(msgId);
            msg.setExpiration(expiration);
            _ctx.clientMessagePool().add(msg, true);
        }
    }
    
    private class DistributeLocal extends JobImpl {
        private Destination _toDest;
        private ClientConnectionRunner _to;
        private ClientConnectionRunner _from;
        private Destination _fromDest;
        private Payload _payload;
        private MessageId _msgId;
        
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
    
    public boolean shouldPublishLeaseSet(Hash destHash) { 
        if (false) return true;
        if (destHash == null) return true;
        ClientConnectionRunner runner = getRunner(destHash);
        if (runner == null) return true;
        String dontPublish = runner.getConfig().getOptions().getProperty(ClientManagerFacade.PROP_CLIENT_ONLY);
        if ( (dontPublish != null) && ("true".equals(dontPublish)) ) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Not publishing the leaseSet for " + destHash.toBase64());
            return false;
        } 
        return true;
    }

    public Set listClients() {
        Set rv = new HashSet();
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
            rv = (ClientConnectionRunner)_runners.get(dest);
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
     *
     */
    public SessionKeyManager getClientSessionKeyManager(Destination dest) {
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
            for (Iterator iter = _runners.values().iterator(); iter.hasNext(); ) {
                ClientConnectionRunner cur = (ClientConnectionRunner)iter.next();
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
    
    Set getRunnerDestinations() {
        Set dests = new HashSet();
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
    
    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(8*1024);
        buf.append("<u><b>Local destinations</b></u><br />");
        
        Map runners = null;
        synchronized (_runners) {
            runners = (Map)_runners.clone();
        }
        for (Iterator iter = runners.keySet().iterator(); iter.hasNext(); ) {
            Destination dest = (Destination)iter.next();
            ClientConnectionRunner runner = (ClientConnectionRunner)runners.get(dest);
            buf.append("<b>*</b> ").append(dest.calculateHash().toBase64().substring(0,6)).append("<br />\n");
            LeaseSet ls = runner.getLeaseSet();
            if (ls == null) {
                buf.append("<font color=\"red\"><i>No lease</i></font><br />\n");
            } else { 
                long leaseAge = ls.getEarliestLeaseDate() - _ctx.clock().now();
                if (leaseAge <= 0) { 
                    buf.append("<font color=\"red\"><i>Lease expired ");
                    buf.append(DataHelper.formatDuration(0-leaseAge)).append(" ago</i></font><br />\n");
                } else {
                    int count = ls.getLeaseCount();
                    if (count <= 0) {
                        buf.append("<font color=\"red\"><i>No tunnels</i></font><br />\n");
                    } else {
                        TunnelId id = ls.getLease(0).getTunnelId();
                        TunnelInfo info = _ctx.tunnelManager().getTunnelInfo(id);
                        if (info == null) {
                            buf.append("<font color=\"red\"><i>Failed tunnels</i></font><br />\n");
                        } else {
                            buf.append(count).append(" x ");
                            buf.append(info.getLength() - 1).append(" hop tunnel");
                            if (count != 1)
                                buf.append('s');
                            buf.append("<br />\n");
                            buf.append("Expiring in ").append(DataHelper.formatDuration(leaseAge));
                            buf.append("<br />\n");
                        }
                    }
                }
            }
        }
        
        buf.append("\n<hr />\n");
        out.write(buf.toString());
        out.flush();
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
                _ctx.statManager().addRateData("client.receiveMessageSize", 
                                                   _msg.getPayload().getSize(), 0);
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
