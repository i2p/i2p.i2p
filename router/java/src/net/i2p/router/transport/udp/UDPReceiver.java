package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;

import java.util.ArrayList;
import java.util.List;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Lowest level component to pull raw UDP datagrams off the wire as fast
 * as possible, controlled by both the bandwidth limiter and the router's
 * throttle.  If the inbound queue gets too large or packets have been
 * waiting around too long, they are dropped.  Packets should be pulled off
 * from the queue ASAP by a {@link PacketHandler}
 *
 */
public class UDPReceiver {
    private RouterContext _context;
    private Log _log;
    private DatagramSocket _socket;
    private String _name;
    private List _inboundQueue;
    private boolean _keepRunning;
    private Runner _runner;
    private UDPTransport _transport;
    
    public UDPReceiver(RouterContext ctx, UDPTransport transport, DatagramSocket socket, String name) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPReceiver.class);
        _name = name;
        _inboundQueue = new ArrayList(128);
        _socket = socket;
        _transport = transport;
        _runner = new Runner();
        _context.statManager().createRateStat("udp.receivePacketSize", "How large packets received are", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.droppedInbound", "How many packet are queued up but not yet received when we drop", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
    }
    
    public void startup() {
        _keepRunning = true;
        I2PThread t = new I2PThread(_runner, _name);
        t.setDaemon(true);
        t.start();
    }
    
    public void shutdown() {
        _keepRunning = false;
        synchronized (_inboundQueue) {
            _inboundQueue.clear();
            _inboundQueue.notifyAll();
        }
    }
    
    /**
     * Replace the old listen port with the new one, returning the old. 
     * NOTE: this closes the old socket so that blocking calls unblock!
     *
     */
    public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
        return _runner.updateListeningPort(socket, newPort);
    }

    /** if a packet been sitting in the queue for a full second (meaning the handlers are overwhelmed), drop subsequent packets */
    private static final long MAX_QUEUE_PERIOD = 1*1000;
    
    private static final float ARTIFICIAL_DROP_PROBABILITY = 0.0f; // 0.02f; // 0.0f;
    
    private static final int ARTIFICIAL_DELAY = 0; // 100;
    private static final int ARTIFICIAL_DELAY_BASE = 0; //100;
    
    private int receive(UDPPacket packet) {
        if (ARTIFICIAL_DROP_PROBABILITY > 0) { 
            // the first check is to let the compiler optimize away this 
            // random block on the live system when the probability is == 0
            if (_context.random().nextFloat() <= ARTIFICIAL_DROP_PROBABILITY)
                return -1;
        }
        
        if ( (ARTIFICIAL_DELAY > 0) || (ARTIFICIAL_DELAY_BASE > 0) ) {
            SimpleTimer.getInstance().addEvent(new ArtificiallyDelayedReceive(packet), ARTIFICIAL_DELAY_BASE + _context.random().nextInt(ARTIFICIAL_DELAY));
        }
        
        return doReceive(packet);
    }
    private final int doReceive(UDPPacket packet) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Received: " + packet);

        boolean rejected = false;
        int queueSize = 0;
        long headPeriod = 0;
        synchronized (_inboundQueue) {
            queueSize = _inboundQueue.size();
            if (queueSize > 0) {
                headPeriod = ((UDPPacket)_inboundQueue.get(0)).getLifetime();
                if (headPeriod > MAX_QUEUE_PERIOD) {
                    rejected = true;
                    _inboundQueue.notifyAll();
                }
            }
            if (!rejected) {
                _inboundQueue.add(packet);
                _inboundQueue.notifyAll();
                return queueSize + 1;
            }
        }
        
        // rejected
        _context.statManager().addRateData("udp.droppedInbound", queueSize, headPeriod);
        if (_log.shouldLog(Log.WARN)) {
            StringBuffer msg = new StringBuffer();
            msg.append("Dropping inbound packet with ");
            msg.append(queueSize);
            msg.append(" queued for ");
            msg.append(headPeriod);
            if (_transport != null)
                msg.append(" packet handlers: ").append(_transport.getPacketHandlerStatus());
            _log.warn(msg.toString());
        }
        return queueSize;
    }
    
    private class ArtificiallyDelayedReceive implements SimpleTimer.TimedEvent {
        private UDPPacket _packet;
        public ArtificiallyDelayedReceive(UDPPacket packet) { _packet = packet; }
        public void timeReached() { doReceive(_packet); }
    }
    
    /**
     * Blocking call to retrieve the next inbound packet, or null if we have
     * shut down.
     *
     */
    public UDPPacket receiveNext() {
        while (_keepRunning) {
            try {
                synchronized (_inboundQueue) {
                    if (_inboundQueue.size() > 0) {
                        UDPPacket rv = (UDPPacket)_inboundQueue.remove(0);
                        _inboundQueue.notifyAll();
                        return rv;
                    } else {
                        _inboundQueue.wait(500);
                    }
                }
            } catch (InterruptedException ie) {}
        }
        return null;
    }
    
    private class Runner implements Runnable {
        private boolean _socketChanged;
        public void run() {
            _socketChanged = false;
            while (_keepRunning) {
                if (_socketChanged) {
                    Thread.currentThread().setName(_name);
                    _socketChanged = false;
                }
                UDPPacket packet = UDPPacket.acquire(_context);
                
                // block before we read...
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Before throttling receive");
                while (!_context.throttle().acceptNetworkMessage())
                    try { Thread.sleep(10); } catch (InterruptedException ie) {}
                
                try {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Before blocking socket.receive");
                    synchronized (Runner.this) {
                        _socket.receive(packet.getPacket());
                    }
                    int size = packet.getPacket().getLength();
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("After blocking socket.receive: packet is " + size + " bytes!");
                    packet.resetBegin();
            
                    // and block after we know how much we read but before
                    // we release the packet to the inbound queue
                    if (size > 0) {
                        FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestInbound(size, "UDP receiver");
                        while (req.getPendingInboundRequested() > 0)
                            req.waitForNextAllocation();
                    }
                    
                    int queued = receive(packet);
                    _context.statManager().addRateData("udp.receivePacketSize", size, queued);
                } catch (IOException ioe) {
                    if (_socketChanged) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Changing ports...");
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Error receiving", ioe);
                    }
                    packet.release();
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Stop receiving...");
        }
        
        public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
            _name = "UDPReceive on " + newPort;
            DatagramSocket old = null;
            synchronized (Runner.this) {
                old = _socket;
                _socket = socket;
            }
            _socketChanged = true;
            // ok, its switched, now lets break any blocking calls
            old.close();
            return old;
        }
    }
    
}
