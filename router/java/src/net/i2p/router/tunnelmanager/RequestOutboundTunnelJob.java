package net.i2p.router.tunnelmanager;

import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.TunnelInfo;
import net.i2p.router.RouterContext;

class RequestOutboundTunnelJob extends JobImpl {
    private TunnelPool _pool;
    private boolean _useFake;
    private TunnelBuilder _builder;
    
    public RequestOutboundTunnelJob(RouterContext context, TunnelPool pool, boolean useFake) {
        super(context);
        _pool = pool;
        _useFake = useFake;
        _builder = new TunnelBuilder(context);
    }
    
    public String getName() { return "Request Outbound Tunnel"; }
    public void runJob() {
        TunnelInfo tunnelGateway = _builder.configureOutboundTunnel(_pool.getPoolSettings(), _useFake);
        RequestTunnelJob reqJob = new RequestTunnelJob(_context, _pool, tunnelGateway, false, _pool.getTunnelCreationTimeout());
        _context.jobQueue().addJob(reqJob);
    }
}
