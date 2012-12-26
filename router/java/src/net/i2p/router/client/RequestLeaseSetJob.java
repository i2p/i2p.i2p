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

import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.router.Job;
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
        
        RequestLeaseSetMessage msg = new RequestLeaseSetMessage();
        long endTime = _requestState.getRequested().getEarliestLeaseDate();
        // Add a small number of ms (0-300) that increases as we approach the expire time.
        // Since the earliest date functions as a version number,
        // this will force the floodfill to flood each new version;
        // otherwise it won't if the earliest time hasn't changed.
        long fudge = 300 - ((endTime - getContext().clock().now()) / 2000);
        endTime += fudge;
        Date end = new Date(endTime);

        msg.setEndDate(end);
        msg.setSessionId(_runner.getSessionId());
        
        for (int i = 0; i < _requestState.getRequested().getLeaseCount(); i++) {
            msg.addEndpoint(_requestState.getRequested().getLease(i).getGateway(),
                            _requestState.getRequested().getLease(i).getTunnelId());
        }
        
        try {
            //_runner.setLeaseRequest(state);
            _runner.doSend(msg);
            getContext().jobQueue().addJob(new CheckLeaseRequestStatus());
        } catch (I2CPMessageException ime) {
            getContext().statManager().addRateData("client.requestLeaseSetDropped", 1, 0);
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
                    _log.error("Failed to receive a leaseSet in the time allotted (" + waited + "): " + _requestState + " for " 
                             + _runner.getConfig().getDestination().calculateHash().toBase64());
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
