package net.i2p.router.tunnelmanager;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

class RequestInboundTunnelJob extends JobImpl {
    private Log _log;
    private TunnelPool _pool;
    private boolean _useFake;
    private TunnelBuilder _builder;
    
    public RequestInboundTunnelJob(RouterContext context, TunnelPool pool) {
        this(context, pool, false);
    }
    public RequestInboundTunnelJob(RouterContext context, TunnelPool pool, boolean useFake) {
        super(context);
        _log = context.logManager().getLog(RequestInboundTunnelJob.class);
        _pool = pool;
        _useFake = useFake;
        _builder = new TunnelBuilder(context);
    }
    
    public String getName() { return "Request Inbound Tunnel"; }
    public void runJob() {
        _log.debug("Client pool settings: " + _pool.getPoolSettings().toString());
        TunnelInfo tunnelGateway = _builder.configureInboundTunnel(null, _pool.getPoolSettings(), _useFake);
        RequestTunnelJob reqJob = new RequestTunnelJob(_context, _pool, tunnelGateway, true, _pool.getTunnelCreationTimeout());
        _context.jobQueue().addJob(reqJob);
    }
}
