package net.i2p.router.tunnelmanager;

import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.TunnelInfo;

class RequestOutboundTunnelJob extends JobImpl {
    private TunnelPool _pool;
    private boolean _useFake;
    
    public RequestOutboundTunnelJob(TunnelPool pool, boolean useFake) {
	super();
	_pool = pool;
	_useFake = useFake;
    }
    
    public String getName() { return "Request Outbound Tunnel"; }
    public void runJob() {
	TunnelInfo tunnelGateway = TunnelBuilder.getInstance().configureOutboundTunnel(_pool.getPoolSettings(), _useFake);
	RequestTunnelJob reqJob = new RequestTunnelJob(_pool, tunnelGateway, false, _pool.getTunnelCreationTimeout());
	JobQueue.getInstance().addJob(reqJob);
    }
}
