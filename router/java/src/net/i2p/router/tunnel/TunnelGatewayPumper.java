package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * run through the tunnel gateways that have had messages added to them and push
 * those messages through the preprocessing and sending process
 */
public class TunnelGatewayPumper implements Runnable {
    private RouterContext _context;
    private Log _log;
    private final List _wantsPumping;
    private boolean _stop;
    
    /** Creates a new instance of TunnelGatewayPumper */
    public TunnelGatewayPumper(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _wantsPumping = new ArrayList(64);
        _stop = false;
        for (int i = 0; i < 4; i++)
            new I2PThread(this, "GW pumper " + i, true).start();
    }
    public void stopPumping() {
        _stop=true;
        synchronized (_wantsPumping) { _wantsPumping.notifyAll(); }
    }
    
    public void wantsPumping(PumpedTunnelGateway gw) {
        synchronized (_wantsPumping) {
            _wantsPumping.add(gw);
            _wantsPumping.notify();
        }
    }
    
    public void run() {
        PumpedTunnelGateway gw = null;
        List queueBuf = new ArrayList(32);
        while (!_stop) {
            try {
                synchronized (_wantsPumping) {
                    if (_wantsPumping.size() > 0)
                        gw = (PumpedTunnelGateway)_wantsPumping.remove(0);
                    else
                        _wantsPumping.wait();
                }
            } catch (InterruptedException ie) {}
            if (gw != null) {
                gw.pump(queueBuf);
                gw = null;
            }
        }
    }
}
