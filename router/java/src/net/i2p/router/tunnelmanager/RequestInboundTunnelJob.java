package net.i2p.router.tunnelmanager;

import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

class RequestInboundTunnelJob extends JobImpl {
    private final static Log _log = new Log(RequestInboundTunnelJob.class);
    private TunnelPool _pool;
    private boolean _useFake;
    
    public RequestInboundTunnelJob(TunnelPool pool) {
	this(pool, false);
    }
    public RequestInboundTunnelJob(TunnelPool pool, boolean useFake) {
	super();
	_pool = pool;
	_useFake = useFake;
    }
    
    public String getName() { return "Request Inbound Tunnel"; }
    public void runJob() {
	_log.debug("Client pool settings: " + _pool.getPoolSettings().toString());
	TunnelInfo tunnelGateway = TunnelBuilder.getInstance().configureInboundTunnel(null, _pool.getPoolSettings(), _useFake);
	RequestTunnelJob reqJob = new RequestTunnelJob(_pool, tunnelGateway, true, _pool.getTunnelCreationTimeout());
	JobQueue.getInstance().addJob(reqJob);
    }
}
