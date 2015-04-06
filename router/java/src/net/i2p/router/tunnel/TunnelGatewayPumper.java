package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;

/**
 * Run through the tunnel gateways that have had messages added to them and push
 * those messages through the preprocessing and sending process.
 *
 * TODO do we need this many threads?
 * TODO this combines IBGWs and OBGWs, do we wish to separate the two
 * and/or prioritize OBGWs (i.e. our outbound traffic) over IBGWs (participating)?
 */
class TunnelGatewayPumper implements Runnable {
    private final RouterContext _context;
    private final Set<PumpedTunnelGateway> _wantsPumping;
    private final Set<PumpedTunnelGateway> _backlogged;
    private volatile boolean _stop;
    private static final int MIN_PUMPERS = 1;
    private static final int MAX_PUMPERS = 4;
    private final int _pumpers;

    /**
     *  Wait just a little, but this lets the pumper queue back up.
     *  See additional comments in PTG.
     */
    private static final long REQUEUE_TIME = 50;
    
    /** Creates a new instance of TunnelGatewayPumper */
    public TunnelGatewayPumper(RouterContext ctx) {
        _context = ctx;
        _wantsPumping = new LinkedHashSet<PumpedTunnelGateway>(16);
        _backlogged = new HashSet<PumpedTunnelGateway>(16);
        if (ctx.getBooleanProperty("i2p.dummyTunnelManager")) {
            _pumpers = 1;
        } else {
            long maxMemory = SystemVersion.getMaxMemory();
            _pumpers = (int) Math.max(MIN_PUMPERS, Math.min(MAX_PUMPERS, 1 + (maxMemory / (32*1024*1024))));
        }
        for (int i = 0; i < _pumpers; i++)
            new I2PThread(this, "Tunnel GW pumper " + (i+1) + '/' + _pumpers, true).start();
    }

    public void stopPumping() {
        _stop=true;
        _wantsPumping.clear();
        for (int i = 0; i < _pumpers; i++) {
            PumpedTunnelGateway poison = new PoisonPTG(_context);
            wantsPumping(poison);
        }
        for (int i = 1; i <= 5 && !_wantsPumping.isEmpty(); i++) {
            try {
                Thread.sleep(i * 50);
            } catch (InterruptedException ie) {}
        }
        _wantsPumping.clear();
    }
    
    public void wantsPumping(PumpedTunnelGateway gw) {
        if (!_stop) {
            synchronized (_wantsPumping) {
                if ((!_backlogged.contains(gw)) && _wantsPumping.add(gw))
                    _wantsPumping.notify();
            }
        }
    }
    
    public void run() {
        PumpedTunnelGateway gw = null;
        List<PendingGatewayMessage> queueBuf = new ArrayList<PendingGatewayMessage>(32);
        boolean requeue = false;
        while (!_stop) {
            try {
                synchronized (_wantsPumping) {
                    if (requeue && gw != null) {
                        // in case another packet came in
                        _wantsPumping.remove(gw);
                        if (_backlogged.add(gw))
                            _context.simpleTimer2().addEvent(new Requeue(gw), REQUEUE_TIME);
                    }
                    gw = null;
                    if (_wantsPumping.isEmpty()) {
                        _wantsPumping.wait();
                    } else {
                        Iterator<PumpedTunnelGateway> iter = _wantsPumping.iterator();
                        gw = iter.next();
                        iter.remove();
                    }
                }
            } catch (InterruptedException ie) {}
            if (gw != null) {
                if (gw.getMessagesSent() == POISON_PTG)
                    break;
                requeue = gw.pump(queueBuf);
            }
        }
    }

    private class Requeue implements SimpleTimer.TimedEvent {
        private final PumpedTunnelGateway _ptg;

        public Requeue(PumpedTunnelGateway ptg) {
            _ptg = ptg;
        }

        public void timeReached() {
            synchronized (_wantsPumping) {
                _backlogged.remove(_ptg);
                if (_wantsPumping.add(_ptg))
                    _wantsPumping.notify();
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
