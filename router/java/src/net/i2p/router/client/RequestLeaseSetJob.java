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
    
    public RequestLeaseSetJob(RouterContext ctx, ClientConnectionRunner runner, LeaseSet set, long expiration, Job onCreate, Job onFail, LeaseRequestState state) {
        super(ctx);
        _log = ctx.logManager().getLog(RequestLeaseSetJob.class);
        _runner = runner;
        _requestState = state;
        ctx.statManager().createRateStat("client.requestLeaseSetSuccess", "How frequently the router requests successfully a new leaseSet?", "ClientMessages", new long[] { 60*60*1000 });
        ctx.statManager().createRateStat("client.requestLeaseSetTimeout", "How frequently the router requests a new leaseSet but gets no reply?", "ClientMessages", new long[] { 60*60*1000 });
        ctx.statManager().createRateStat("client.requestLeaseSetDropped", "How frequently the router requests a new leaseSet but the client drops?", "ClientMessages", new long[] { 60*60*1000 });
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
            msg.addEndpoint(_requestState.getRequested().getLease(i).getGateway(), _requestState.getRequested().getLease(i).getTunnelId());
        }
        
        try {
            //_runner.setLeaseRequest(state);
            _runner.doSend(msg);
            getContext().jobQueue().addJob(new CheckLeaseRequestStatus(getContext(), _requestState));
            return;
        } catch (I2CPMessageException ime) {
            getContext().statManager().addRateData("client.requestLeaseSetDropped", 1, 0);
            _log.error("Error sending I2CP message requesting the lease set", ime);
            _requestState.setIsSuccessful(false);
            _runner.setLeaseRequest(null);
            _runner.disconnectClient("I2CP error requesting leaseSet");
            return;
        }
    }
    
    /**
     * Schedule this job to be run after the request's expiration, so that if
     * it wasn't yet successful, we fire off the failure job and disconnect the
     * client (but if it was, noop)
     *
     */
    private class CheckLeaseRequestStatus extends JobImpl {
        private final LeaseRequestState _req;
        private final long _start;
        
        public CheckLeaseRequestStatus(RouterContext enclosingContext, LeaseRequestState state) {
            super(enclosingContext);
            _req = state;
            _start = System.currentTimeMillis();
            getTiming().setStartAfter(state.getExpiration());
        }
        
        public void runJob() {
            if (_runner.isDead()) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Already dead, dont try to expire the leaseSet lookup");
                return;
            }
            if (_req.getIsSuccessful()) {
                // we didn't fail
                RequestLeaseSetJob.CheckLeaseRequestStatus.this.getContext().statManager().addRateData("client.requestLeaseSetSuccess", 1, 0);
                return;
            } else {
                RequestLeaseSetJob.CheckLeaseRequestStatus.this.getContext().statManager().addRateData("client.requestLeaseSetTimeout", 1, 0);
                if (_log.shouldLog(Log.ERROR)) {
                    long waited = System.currentTimeMillis() - _start;
                    _log.error("Failed to receive a leaseSet in the time allotted (" + waited + "): " + _req + " for " 
                             + _runner.getConfig().getDestination().calculateHash().toBase64());
                }
                _runner.disconnectClient("Took too long to request leaseSet");
                if (_req.getOnFailed() != null)
                    RequestLeaseSetJob.this.getContext().jobQueue().addJob(_req.getOnFailed());
            }
        }
        public String getName() { return "Check LeaseRequest Status"; }
    }
}
