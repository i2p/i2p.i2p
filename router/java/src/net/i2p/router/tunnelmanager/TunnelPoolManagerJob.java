package net.i2p.router.tunnelmanager;

import java.util.Iterator;
import java.util.Set;

import net.i2p.data.TunnelId;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Request new tunnels to be created if insufficient free inbound tunnels or
 * valid outbound tunnels exist.
 *
 */
class TunnelPoolManagerJob extends JobImpl {
    private final static Log _log = new Log(TunnelPoolManagerJob.class);
    private TunnelPool _pool;
    
    /** whether we built tunnels on the last run */
    private boolean _builtOnLastRun;
    
    /**
     * How frequently to check the pool (and fire appropriate refill jobs)
     *
     */
    private final static long POOL_CHECK_DELAY = 30*1000;

    /**
     * treat tunnels that are going to expire in the next minute as pretty much
     * expired (for the purpose of building new ones)
     */
    private final static long EXPIRE_FUDGE_PERIOD = 60*1000;
        
    public TunnelPoolManagerJob(TunnelPool pool) {
	super();
	_pool = pool;
    }
    
    public String getName() { return "Manage Tunnel Pool"; }
    public void runJob() {
	try { 
	    if (!_pool.isLive())
		return;

	    boolean built = false;

	    int targetClients = _pool.getTargetClients();
	    int targetInboundTunnels = targetClients*_pool.getPoolSettings().getNumInboundTunnels() + 3;
	    int targetOutboundTunnels = targetClients*_pool.getPoolSettings().getNumOutboundTunnels() + 3;

	    int curFreeInboundTunnels = getFreeTunnelCount();
	    if (curFreeInboundTunnels < targetInboundTunnels) {
		_log.info("Insufficient free inbound tunnels (" + curFreeInboundTunnels + ", not " + targetInboundTunnels + "), requesting more");
		requestInboundTunnels(targetInboundTunnels - curFreeInboundTunnels);
		//requestFakeInboundTunnels(1);
		built = true;
	    } else {
		if (_builtOnLastRun) {
		    // all good, no need for more inbound tunnels
		    _log.debug("Sufficient inbound tunnels (" + curFreeInboundTunnels + ")");
		} else {
		    _log.info("Building another inbound tunnel, cuz tunnels r k00l");
		    requestInboundTunnels(1);
		    built = true;
		}
	    }

	    int curOutboundTunnels = getOutboundTunnelCount();
	    if (curOutboundTunnels < targetOutboundTunnels) {
		_log.info("Insufficient outbound tunnels (" + curOutboundTunnels  + ", not " + targetOutboundTunnels + "), requesting more");
		requestOutboundTunnels(targetOutboundTunnels - curOutboundTunnels);
		//requestFakeOutboundTunnels(1);
		built = true;
	    } else {
		if (_builtOnLastRun) {
		    // all good, no need for more outbound tunnels
		    _log.debug("Sufficient outbound tunnels (" + curOutboundTunnels + ")");
		} else {
		    _log.info("Building another outbound tunnel, since gravity still works");
		    requestOutboundTunnels(1);
		    built = true;
		}
	    }

	    _pool.buildFakeTunnels();
	    _builtOnLastRun = built;
	} catch (Throwable t) {
	    _log.log(Log.CRIT, "Unhandled exception managing the tunnel pool", t);
	}
	
	requeue(POOL_CHECK_DELAY);
    }
    
    /**
     * How many free inbound tunnels are available for use (safely)
     *
     */
    private int getFreeTunnelCount() {
	Set freeTunnels = _pool.getFreeTunnels();
	int free = 0;
	int minLength = _pool.getPoolSettings().getDepthInbound();
	long mustExpireAfter = Clock.getInstance().now() + EXPIRE_FUDGE_PERIOD;
	for (Iterator iter = freeTunnels.iterator(); iter.hasNext(); ) {
	    TunnelId id = (TunnelId)iter.next();
	    TunnelInfo info = _pool.getFreeTunnel(id);
	    if ( (info != null) && (info.getIsReady()) ) {
		if (info.getSettings().getExpiration() > mustExpireAfter) {
		    if (info.getLength() >= minLength) {
			if (info.getDestination() == null) {
			    free++;
			} else {
			    // already alloc'ed
			    _log.error("Why is a free inbound tunnel allocated to a destination? [" + info.getTunnelId().getTunnelId() + " to " + info.getDestination().toBase64() + "]");
			}
		    } else {
			// its valid, sure, but its not long enough *cough*
			
			// for the moment we'll keep these around so that we can use them
			// for tunnel management and db messages, rather than force all
			// tunnels to be the 2+ hop length as required for clients
			free++;
		    }
		} else {
		    _log.info("Inbound tunnel " + id + " is expiring in the upcoming period, consider it not-free");
		}
	    }
	}
	return free;
    }
    
    /**
     * How many outbound tunnels are available for use (safely)
     *
     */
    private int getOutboundTunnelCount() {
	Set outboundTunnels = _pool.getOutboundTunnels();
	int outbound = 0;
	long mustExpireAfter = Clock.getInstance().now() + EXPIRE_FUDGE_PERIOD;
	for (Iterator iter = outboundTunnels.iterator(); iter.hasNext(); ) {
	    TunnelId id = (TunnelId)iter.next();
	    TunnelInfo info = _pool.getOutboundTunnel(id);
	    if ( (info != null) && (info.getIsReady()) ) {
		if (info.getSettings().getExpiration() > mustExpireAfter) {
		    outbound++;
		} else {
		    _log.info("Outbound tunnel " + id + " is expiring in the upcoming period, consider it not-free");
		}
	    }
	}
	return outbound;
    }
    
    private void requestInboundTunnels(int numTunnelsToRequest) {
	_log.info("Requesting " + numTunnelsToRequest + " inbound tunnels");
	for (int i = 0; i < numTunnelsToRequest; i++)
	    JobQueue.getInstance().addJob(new RequestInboundTunnelJob(_pool, false));
    }
    
    private void requestOutboundTunnels(int numTunnelsToRequest) {
	_log.info("Requesting " + numTunnelsToRequest + " outbound tunnels");
	for (int i = 0; i < numTunnelsToRequest; i++)
	    JobQueue.getInstance().addJob(new RequestOutboundTunnelJob(_pool, false));
    }        
}
