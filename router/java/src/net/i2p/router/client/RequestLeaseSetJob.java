package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.Properties;

import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.data.i2cp.RequestVariableLeaseSetMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Async job to walk the client through generating a lease set.  First sends it
 * to the client and then queues up a CheckLeaseRequestStatus job for
 * processing after the expiration.  When that CheckLeaseRequestStatus is run,
 * if the client still hasn't provided the signed leaseSet, fire off the onFailed
 * job from the intermediary LeaseRequestState and drop the client.
 *
 */
class RequestLeaseSetJob extends JobImpl {
    private final Log _log;
    private final ClientConnectionRunner _runner;
    private final LeaseRequestState _requestState;
    
    private static final long MAX_FUDGE = 2*1000;

    public RequestLeaseSetJob(RouterContext ctx, ClientConnectionRunner runner, LeaseRequestState state) {
        super(ctx);
        _log = ctx.logManager().getLog(RequestLeaseSetJob.class);
        _runner = runner;
        _requestState = state;
        // all createRateStat in ClientManager
    }
    
    public String getName() { return "Request Lease Set"; }

    public void runJob() {
        if (_runner.isDead()) return;
        
        boolean isLS2 = false;
        SessionConfig cfg = _runner.getPrimaryConfig();
        if (cfg != null) {
            Properties props = cfg.getOptions();
            if (props != null) {
                String lsType = props.getProperty("i2cp.leaseSetType");
                if (lsType != null && !lsType.equals("1"))
                    isLS2 = true;
            }
        }
        
        LeaseSet requested = _requestState.getRequested();
        long endTime = requested.getEarliestLeaseDate();
        // Add a small number of ms (0 to MAX_FUDGE) that increases as we approach the expire time.
        // Since the earliest date functions as a version number,
        // this will force the floodfill to flood each new version;
        // otherwise it won't if the earliest time hasn't changed.

        if (isLS2) {
            // fix for 0.9.38 floodfills,
            // adding some ms doesn't work since the dates are truncated,
            // and 0.9.38 did not use LeaseSet2.getPublished()
            long earliest = 1000 + _requestState.getCurrentEarliestLeaseDate();
            if (endTime < earliest)
                endTime = earliest;
        } else {
            long diff = endTime - getContext().clock().now();
            long fudge = MAX_FUDGE - (diff / (10*60*1000 / MAX_FUDGE));
            endTime += fudge;
        }
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Adding fudge " + fudge);

        SessionId id = _runner.getSessionId(requested.getDestination().calculateHash());
        if (id == null) {
            _runner.failLeaseRequest(_requestState);
            return;
        }
        I2CPMessage msg;
        if (_runner instanceof QueuedClientConnectionRunner ||
             RequestVariableLeaseSetMessage.isSupported(_runner.getClientVersion())) {
            // new style - leases will have individual expirations
            RequestVariableLeaseSetMessage rmsg = new RequestVariableLeaseSetMessage();
            rmsg.setSessionId(id);
            for (int i = 0; i < requested.getLeaseCount(); i++) {
                Lease lease = requested.getLease(i);
                if (lease.getEndTime() < endTime) {
                    // don't modify old object, we don't know where it came from
                    Lease nl = new Lease();
                    nl.setGateway(lease.getGateway());
                    nl.setTunnelId(lease.getTunnelId());
                    nl.setEndDate(endTime);
                    lease = nl;
                    //if (_log.shouldLog(Log.INFO))
                    //    _log.info("Adjusted end date to " + endTime + " for " + lease);
                }
                rmsg.addEndpoint(lease);
            }
            msg = rmsg;
        } else {
            // old style - all leases will have same expiration
            RequestLeaseSetMessage rmsg = new RequestLeaseSetMessage();
            Date end = new Date(endTime);
            rmsg.setEndDate(end);
            rmsg.setSessionId(id);
            for (int i = 0; i < requested.getLeaseCount(); i++) {
                Lease lease = requested.getLease(i);
                rmsg.addEndpoint(lease.getGateway(),
                                 lease.getTunnelId());
            }
            msg = rmsg;
        }
        
        try {
            //_runner.setLeaseRequest(state);
            _runner.doSend(msg);
            getContext().jobQueue().addJob(new CheckLeaseRequestStatus());
        } catch (I2CPMessageException ime) {
            getContext().statManager().addRateData("client.requestLeaseSetDropped", 1);
            _log.error("Error sending I2CP message requesting the lease set", ime);
            _requestState.setIsSuccessful(false);
            if (_requestState.getOnFailed() != null)
                RequestLeaseSetJob.this.getContext().jobQueue().addJob(_requestState.getOnFailed());
            _runner.failLeaseRequest(_requestState);
            // Don't disconnect, the tunnel will retry
            //_runner.disconnectClient("I2CP error requesting leaseSet");
        }
    }
    
    /**
     * Schedule this job to be run after the request's expiration, so that if
     * it wasn't yet successful, we fire off the failure job and disconnect the
     * client (but if it was, noop)
     *
     */
    private class CheckLeaseRequestStatus extends JobImpl {
        private final long _start;
        
        public CheckLeaseRequestStatus() {
            super(RequestLeaseSetJob.this.getContext());
            _start = System.currentTimeMillis();
            getTiming().setStartAfter(_requestState.getExpiration());
        }
        
        public void runJob() {
            if (_runner.isDead()) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Already dead, dont try to expire the leaseSet lookup");
                return;
            }
            if (_requestState.getIsSuccessful()) {
                // we didn't fail
                CheckLeaseRequestStatus.this.getContext().statManager().addRateData("client.requestLeaseSetSuccess", 1);
                return;
            } else {
                CheckLeaseRequestStatus.this.getContext().statManager().addRateData("client.requestLeaseSetTimeout", 1);
                if (_log.shouldLog(Log.ERROR)) {
                    long waited = System.currentTimeMillis() - _start;
                    _log.error("Failed to receive a leaseSet in the time allotted (" + waited + "): " + _requestState);
                }
                if (_requestState.getOnFailed() != null)
                    RequestLeaseSetJob.this.getContext().jobQueue().addJob(_requestState.getOnFailed());
                _runner.failLeaseRequest(_requestState);
                // Don't disconnect, the tunnel will retry
                //_runner.disconnectClient("Took too long to request leaseSet");
            }
        }
        public String getName() { return "Check LeaseRequest Status"; }
    }
}
