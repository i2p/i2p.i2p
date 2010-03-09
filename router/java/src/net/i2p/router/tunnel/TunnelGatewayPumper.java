package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;

/**
 * run through the tunnel gateways that have had messages added to them and push
 * those messages through the preprocessing and sending process
 */
public class TunnelGatewayPumper implements Runnable {
    private RouterContext _context;
    private final BlockingQueue<PumpedTunnelGateway> _wantsPumping;
    private boolean _stop;
    private static final int PUMPERS = 4;
    
    /** Creates a new instance of TunnelGatewayPumper */
    public TunnelGatewayPumper(RouterContext ctx) {
        _context = ctx;
        _wantsPumping = new LinkedBlockingQueue();
        _stop = false;
        for (int i = 0; i < PUMPERS; i++)
            new I2PThread(this, "Tunnel GW pumper " + i + '/' + PUMPERS, true).start();
    }

    public void stopPumping() {
        _stop=true;
        _wantsPumping.clear();
        PumpedTunnelGateway poison = new PoisonPTG(_context);
        for (int i = 0; i < PUMPERS; i++)
            _wantsPumping.offer(poison);
        for (int i = 1; i <= 5 && !_wantsPumping.isEmpty(); i++) {
            try {
                Thread.sleep(i * 50);
            } catch (InterruptedException ie) {}
        }
        _wantsPumping.clear();
    }
    
    public void wantsPumping(PumpedTunnelGateway gw) {
        if (!_stop)
            _wantsPumping.offer(gw);
    }
    
    public void run() {
        PumpedTunnelGateway gw = null;
        List<TunnelGateway.Pending> queueBuf = new ArrayList(32);
        while (!_stop) {
            try {
                gw = _wantsPumping.take();
            } catch (InterruptedException ie) {}
            if (gw != null) {
                if (gw.getMessagesSent() == POISON_PTG)
                    break;
                gw.pump(queueBuf);
                gw = null;
            }
        }
    }

    private static final int POISON_PTG = -99999;

    private static class PoisonPTG extends PumpedTunnelGateway {
        public PoisonPTG(RouterContext ctx) {
            super(ctx, null, null, null, null);
        }

        @Override
        public int getMessagesSent() { return POISON_PTG; }
    }
}
