package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.List;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 *
 */
class UDPFlooder implements Runnable {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    private List _peers;
    private boolean _alive;
    private static final byte _floodData[] = new byte[4096];
    
    public UDPFlooder(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPFlooder.class);
        _transport = transport;
        _peers = new ArrayList(4);
        ctx.random().nextBytes(_floodData);
    }
    
    public void addPeer(PeerState peer) {
        synchronized (_peers) {
            _peers.add(peer);
            _peers.notifyAll();
        }
    }
    public void removePeer(PeerState peer) {
        synchronized (_peers) {
            _peers.remove(peer);
            _peers.notifyAll();
        }
    }
    
    public void startup() {
        _alive = true;
        I2PThread t = new I2PThread(this, "flooder");
        t.setDaemon(true);
        t.start();
    }
    
    public void shutdown() { 
        _alive = false;
        synchronized (_peers) {
            _peers.notifyAll();
        }
    }
    
    public void run() {
        while (_alive) {
            try {
                synchronized (_peers) {
                    if (_peers.size() <= 0)
                        _peers.wait();
                }
            } catch (InterruptedException ie) {}
            
            // peers always grows, so this is fairly safe
            for (int i = 0; i < _peers.size(); i++) {
                PeerState peer = (PeerState)_peers.get(i);
                DataMessage m = new DataMessage(_context);
                byte data[] = _floodData; // new byte[4096];
                //_context.random().nextBytes(data);
                m.setData(data);
                m.setMessageExpiration(_context.clock().now() + 10*1000);
                m.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
                if (true) {
                    OutNetMessage msg = new OutNetMessage(_context);
                    msg.setMessage(m);
                    msg.setExpiration(m.getMessageExpiration());
                    msg.setPriority(500);
                    RouterInfo to = _context.netDb().lookupRouterInfoLocally(peer.getRemotePeer());
                    if (to == null)
                        continue;
                    msg.setTarget(to);
                    _context.statManager().getStatLog().addData(peer.getRemotePeer().toBase64().substring(0,6), "udp.floodDataSent", 1, 0);

                    _transport.send(msg);
                } else {
                    _transport.send(m, peer);
                }
            }
            long floodDelay = calcFloodDelay();
            try { Thread.sleep(floodDelay); } catch (InterruptedException ie) {}
        }
    }
    
    private long calcFloodDelay() {
        try {
            return Long.parseLong(_context.getProperty("udp.floodDelay", "30000"));
        } catch (Exception e) {
            return 30*1000;
        }
    }
}
