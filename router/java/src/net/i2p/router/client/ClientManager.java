package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;
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
public class ClientManager {
    private Log _log;
    private ClientListenerRunner _listener;
    private HashMap _runners;        // Destination --> ClientConnectionRunner
    private Set _pendingRunners; // ClientConnectionRunner for clients w/out a Dest yet
    private RouterContext _context;

    /** ms to wait before rechecking for inbound messages to deliver to clients */
    private final static int INBOUND_POLL_INTERVAL = 300;
    
    public ClientManager(RouterContext context, int port) {
        _context = context;
        _log = context.logManager().getLog(ClientManager.class);
        _context.statManager().createRateStat("client.receiveMessageSize", 
                                              "How large are messages received by the client?", 
                                              "Client Messages", 
                                              new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _runners = new HashMap();
        _pendingRunners = new HashSet();
        _listener = new ClientListenerRunner(_context, this, port);
        Thread t = new I2PThread(_listener);
        t.setName("ClientListener");
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
        synchronized (_pendingRunners) {
            _pendingRunners.remove(runner);
        }
        synchronized (_runners) {
            _runners.put(runner.getConfig().getDestination(), runner);
        }
    }
    
    void distributeMessage(Destination fromDest, Destination toDest, Payload payload, MessageId msgId) { 
        // check if there is a runner for it
        ClientConnectionRunner runner = getRunner(toDest);
        if (runner != null) {
            _log.debug("Message " + msgId + " is targeting a local destination.  distribute it as such");
            runner.receiveMessage(toDest, fromDest, payload);
            if (fromDest != null) {
                ClientConnectionRunner sender = getRunner(fromDest);
                if (sender != null) {
                    sender.updateMessageDeliveryStatus(msgId, true);
                } else {
                    _log.log(Log.CRIT, "Um, wtf, we're sending a local message, but we can't find who sent it?", new Exception("wtf"));
                }
            }
        } else {
            // remote.  w00t
            _log.debug("Message " + msgId + " is targeting a REMOTE destination!  Added to the client message pool");
            runner = getRunner(fromDest);
            ClientMessage msg = new ClientMessage();
            msg.setDestination(toDest);
            msg.setPayload(payload);
            msg.setReceptionInfo(null);
            msg.setSenderConfig(runner.getConfig());
            msg.setFromDestination(runner.getConfig().getDestination());
            msg.setMessageId(msgId);
            _context.clientMessagePool().add(msg);
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
            if (_log.shouldLog(Log.WARN))
                _log.warn("Cannot request the lease set, as we can't find a client runner for " 
                          + dest.calculateHash().toBase64() + ".  disconnected?");
            _context.jobQueue().addJob(onFailedJob);
        } else {
            runner.requestLeaseSet(set, _context.clock().now() + timeout, onCreateJob, onFailedJob);
        }
    }
    
    
    public boolean isLocal(Destination dest) { 
        synchronized (_runners) {
            return (_runners.containsKey(dest));
        }
    }
    public boolean isLocal(Hash destHash) { 
        if (destHash == null) return false;
        Set dests = new HashSet();
        synchronized (_runners) {
            dests.addAll(_runners.keySet());
        }
        for (Iterator iter = dests.iterator(); iter.hasNext();) {
            Destination d = (Destination)iter.next();
            if (d.calculateHash().equals(destHash)) return true;
        }
        return false;
    }
    
    private ClientConnectionRunner getRunner(Destination dest) {
        synchronized (_runners) {
            return (ClientConnectionRunner)_runners.get(dest);
        }
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
    
    private ClientConnectionRunner getRunner(Hash destHash) {
        if (destHash == null) 
            return null;
        Set dests = new HashSet();
        synchronized (_runners) {
            dests.addAll(_runners.keySet());
        }
        for (Iterator iter = dests.iterator(); iter.hasNext(); ) {
            Destination d = (Destination)iter.next();
            if (d.calculateHash().equals(destHash))
                return getRunner(d);
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
    
    private Set getRunnerDestinations() {
        Set dests = new HashSet();
        synchronized (_runners) {
            dests.addAll(_runners.keySet());
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
    
    public String renderStatusHTML() {
        StringBuffer buf = new StringBuffer();
        buf.append("<h2>Clients</h2><ul>");
        Map runners = null;
        synchronized (_runners) {
            runners = (Map)_runners.clone();
        }
        for (Iterator iter = runners.keySet().iterator(); iter.hasNext(); ) {
            Destination dest = (Destination)iter.next();
            ClientConnectionRunner runner = (ClientConnectionRunner)runners.get(dest);
            buf.append("<li>").append(dest.calculateHash().toBase64()).append("</li>\n");
            // toss out some general warnings
            if (runner.getLeaseSet() == null)
                buf.append("<font color=\"red\"><b>No leases! If you didn't just start a client, please restart it (and perhaps check your router's logs for ERROR messages)</b></font><br />\n");
            else if (runner.getLeaseSet().getEarliestLeaseDate() < _context.clock().now()) 
                buf.append("<font color=\"red\"><b>wtf, lease has already expired!  please wait a minute, and if this message remains, restart your client</b></font><br />\n");
            buf.append("<pre>\n");
            buf.append(runner.getLeaseSet()).append("</pre>\n");
        }
        buf.append("</ul>\n");
        return buf.toString();
    }
    
    public void messageReceived(ClientMessage msg) {
        _context.jobQueue().addJob(new HandleJob(msg));
    }

    private class HandleJob extends JobImpl {
        private ClientMessage _msg;
        public HandleJob(ClientMessage msg) {
            super(ClientManager.this._context);
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
                HandleJob.this._context.statManager().addRateData("client.receiveMessageSize", 
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
