package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Build a TunnelCreateMessage that is sent to the target requesting that they 
 * participate in the tunnel.  If they reply back saying they will, fire off the
 * onCreateSuccessful job, otherwise fire off the onCreateFailed job after a timeout.
 * The test message is sent at the specified priority.
 *
 * The message algorithm is:
 * = check to see if we have working outbound tunnels
 *   - if true, send a tunnel message out the tunnel containing a garlic aimed directly at the peer in question.
 *   - if false, send a message garlic'ed through a few routers before reaching the peer in question.
 *
 * the source route block will always point at an inbound tunnel - even if there aren't any real ones (in
 * which case, the tunnel gateway is the local router)
 *	
 */
class BuildCreateTunnelMessageJob extends JobImpl {
    private final static Log _log = new Log(BuildCreateTunnelMessageJob.class);
    private RouterInfo _target;
    private Hash _replyTo;
    private TunnelInfo _tunnelConfig;
    private Job _onCreateSuccessful;
    private Job _onCreateFailed;
    private long _timeoutMs;
    private int _priority;

    /**
     *
     * @param target router to participate in the tunnel
     * @param replyTo our address
     * @param info data regarding the tunnel configuration
     * @param onCreateSuccessfulJob after the peer replies back saying they'll participate
     * @param onCreateFailedJob after the peer replies back saying they won't participate, or timeout
     * @param timeoutMs how long to wait before timing out
     * @param priority how high priority to send this test
     */
    public BuildCreateTunnelMessageJob(RouterInfo target, Hash replyTo, TunnelInfo info, Job onCreateSuccessfulJob, Job onCreateFailedJob, long timeoutMs, int priority) {
	super();
	_target = target;
	_replyTo = replyTo;
	_tunnelConfig = info;
	_onCreateSuccessful = onCreateSuccessfulJob;
	_onCreateFailed = onCreateFailedJob;
	_timeoutMs = timeoutMs;
	_priority = priority;
    }
    
    public String getName() { return "Build Create Tunnel Message"; }
    public void runJob() {}
}

