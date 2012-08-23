package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramSocket;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Lowest level component to pull raw UDP datagrams off the wire as fast
 * as possible, controlled by both the bandwidth limiter and the router's
 * throttle.  If the inbound queue gets too large or packets have been
 * waiting around too long, they are dropped.  Packets should be pulled off
 * from the queue ASAP by a {@link PacketHandler}
 *
 */
class UDPReceiver {
    private final RouterContext _context;
    private final Log _log;
    private final DatagramSocket _socket;
    private String _name;
    private final BlockingQueue<UDPPacket> _inboundQueue;
    private volatile boolean _keepRunning;
    private final Runner _runner;
    private final UDPTransport _transport;
    private static int __id;
    private final int _id;

    private static final boolean _isAndroid = System.getProperty("java.vendor").contains("Android");

    private static final int TYPE_POISON = -99999;
    private static final int MIN_QUEUE_SIZE = 16;
    private static final int MAX_QUEUE_SIZE = 192;

    public UDPReceiver(RouterContext ctx, UDPTransport transport, DatagramSocket socket, String name) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPReceiver.class);
        _id = ++__id;
        _name = name;
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory == Long.MAX_VALUE)
            maxMemory = 96*1024*1024l;
        int qsize = (int) Math.max(MIN_QUEUE_SIZE, Math.min(MAX_QUEUE_SIZE, maxMemory / (2*1024*1024)));
        _inboundQueue = new LinkedBlockingQueue(qsize);
        _socket = socket;
        _transport = transport;
        _runner = new Runner();
        _context.statManager().createRateStat("udp.receivePacketSize", "How large packets received are", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveRemaining", "How many packets are left sitting on the receiver's queue", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInbound", "How many packet are queued up but not yet received when we drop", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveHolePunch", "How often we receive a NAT hole punch", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.ignorePacketFromDroplist", "Packet lifetime for those dropped on the drop list", "udp", UDPTransport.RATES);
    }
    
    public void startup() {
        //adjustDropProbability();
        _keepRunning = true;
        I2PThread t = new I2PThread(_runner, _name + '.' + _id, true);
        t.start();
    }
    
    public void shutdown() {
        _keepRunning = false;
        _inboundQueue.clear();
        for (int i = 0; i < _transport.getPacketHandlerCount(); i++) {
            UDPPacket poison = UDPPacket.acquire(_context, false);
            poison.setMessageType(TYPE_POISON);
            _inboundQueue.offer(poison);
        }
        for (int i = 1; i <= 5 && !_inboundQueue.isEmpty(); i++) {
            try {
                Thread.sleep(i * 50);
            } catch (InterruptedException ie) {}
        }
        _inboundQueue.clear();
    }
    
/*********
    private void adjustDropProbability() {
        String p = _context.getProperty("i2np.udp.dropProbability");
        if (p != null) {
            try { 
                ARTIFICIAL_DROP_PROBABILITY = Integer.parseInt(p);
            } catch (NumberFormatException nfe) {}
            if (ARTIFICIAL_DROP_PROBABILITY < 0) ARTIFICIAL_DROP_PROBABILITY = 0;
        } else {
            //ARTIFICIAL_DROP_PROBABILITY = 0;
        }
    }
**********/
    
    /**
     * Replace the old listen port with the new one, returning the old. 
     * NOTE: this closes the old socket so that blocking calls unblock!
     *
     */
/*********
    public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
        return _runner.updateListeningPort(socket, newPort);
    }
**********/

    /** if a packet been sitting in the queue for a full second (meaning the handlers are overwhelmed), drop subsequent packets */
    private static final long MAX_QUEUE_PERIOD = 2*1000;
    
/*********
    private static int ARTIFICIAL_DROP_PROBABILITY = 0; // 4
    
    private static final int ARTIFICIAL_DELAY = 0; // 200;
    private static final int ARTIFICIAL_DELAY_BASE = 0; //600;
**********/
    
    /** @return zero (was queue size) */
    private int receive(UDPPacket packet) {
/*********
        //adjustDropProbability();
        
        if (ARTIFICIAL_DROP_PROBABILITY > 0) { 
            // the first check is to let the compiler optimize away this 
            // random block on the live system when the probability is == 0
            // (not if it isn't final jr)
            int v = _context.random().nextInt(100);
            if (v <= ARTIFICIAL_DROP_PROBABILITY) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Drop with v=" + v + " p=" + ARTIFICIAL_DROP_PROBABILITY + " packet size: " + packet.getPacket().getLength() + ": " + packet);
                _context.statManager().addRateData("udp.droppedInboundProbabalistically", 1, 0);
                return -1;
            } else {
                _context.statManager().addRateData("udp.acceptedInboundProbabalistically", 1, 0);
            }
        }
        
        if ( (ARTIFICIAL_DELAY > 0) || (ARTIFICIAL_DELAY_BASE > 0) ) {
            long delay = ARTIFICIAL_DELAY_BASE + _context.random().nextInt(ARTIFICIAL_DELAY);
            if (_log.shouldLog(Log.INFO))
                _log.info("Delay packet " + packet + " for " + delay);
            SimpleScheduler.getInstance().addEvent(new ArtificiallyDelayedReceive(packet), delay);
            return -1;
        }
**********/
        
        return doReceive(packet);
    }

    /**
     * BLOCKING if queue between here and PacketHandler is full.
     *
     * @return zero (was queue size)
     */
    private final int doReceive(UDPPacket packet) {
        if (!_keepRunning)
            return 0;

        if (_log.shouldLog(Log.INFO))
            _log.info("Received: " + packet);
        
        RemoteHostId from = packet.getRemoteHost();
        if (_transport.isInDropList(from)) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Ignoring packet from the drop-listed peer: " + from);
            _context.statManager().addRateData("udp.ignorePacketFromDroplist", packet.getLifetime(), 0);
            packet.release();
            return 0;
        }

        // drop anything apparently from our IP (any port)
        if (Arrays.equals(from.getIP(), _transport.getExternalIP())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping (spoofed?) packet from ourselves");
            packet.release();
            return 0;
        }

        packet.enqueue();
        boolean rejected = false;
        int queueSize = 0;
        long headPeriod = 0;

            UDPPacket head = _inboundQueue.peek();
            if (head != null) {
                headPeriod = head.getLifetime();
                if (headPeriod > MAX_QUEUE_PERIOD) {
                    rejected = true;
                }
            }
            if (!rejected) {
                try {
                    _inboundQueue.put(packet);
                } catch (InterruptedException ie) {
                    packet.release();
                    _keepRunning = false;
                }
                //return queueSize + 1;
                return 0;
            }
        
        // rejected
        packet.release();
        _context.statManager().addRateData("udp.droppedInbound", queueSize, headPeriod);
        if (_log.shouldLog(Log.WARN)) {
            queueSize = _inboundQueue.size();
            StringBuilder msg = new StringBuilder();
            msg.append("Dropping inbound packet with ");
            msg.append(queueSize);
            msg.append(" queued for ");
            msg.append(headPeriod);
            msg.append(" packet handlers: ").append(_transport.getPacketHandlerStatus());
            _log.warn(msg.toString());
        }
        return queueSize;
    }
    
  /****
    private class ArtificiallyDelayedReceive implements SimpleTimer.TimedEvent {
        private UDPPacket _packet;
        public ArtificiallyDelayedReceive(UDPPacket packet) { _packet = packet; }
        public void timeReached() { doReceive(_packet); }
    }
  ****/
    
    /**
     * Blocking call to retrieve the next inbound packet, or null if we have
     * shut down.
     *
     */
    public UDPPacket receiveNext() {
        UDPPacket rv = null;
        //int remaining = 0;
        while (_keepRunning && rv == null) {
            try {
                rv = _inboundQueue.take();
            } catch (InterruptedException ie) {}
            if (rv != null && rv.getMessageType() == TYPE_POISON)
                return null;
        }
        //_context.statManager().addRateData("udp.receiveRemaining", remaining, 0);
        return rv;
    }
    
    private class Runner implements Runnable {
        //private volatile boolean _socketChanged;

        public void run() {
            //_socketChanged = false;
            FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().createRequest();
            while (_keepRunning) {
                //if (_socketChanged) {
                //    Thread.currentThread().setName(_name + "." + _id);
                //    _socketChanged = false;
                //}
                UDPPacket packet = UDPPacket.acquire(_context, true);

                // Android ICS bug
                // http://code.google.com/p/android/issues/detail?id=24748
                if (_isAndroid)
                    packet.getPacket().setLength(UDPPacket.MAX_PACKET_SIZE);
                
                // block before we read...
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Before throttling receive");
                while (!_context.throttle().acceptNetworkMessage())
                    try { Thread.sleep(10); } catch (InterruptedException ie) {}
                
                try {
                    //if (_log.shouldLog(Log.INFO))
                    //    _log.info("Before blocking socket.receive on " + System.identityHashCode(packet));
                    //synchronized (Runner.this) {
                        _socket.receive(packet.getPacket());
                    //}
                    int size = packet.getPacket().getLength();
                    if (_log.shouldLog(Log.INFO))
                        _log.info("After blocking socket.receive: packet is " + size + " bytes on " + System.identityHashCode(packet));
                    packet.resetBegin();
            
                    // and block after we know how much we read but before
                    // we release the packet to the inbound queue
                    if (size >= UDPPacket.MAX_PACKET_SIZE) {
                        // DatagramSocket javadocs: If the message is longer than the packet's length, the message is truncated.
                        throw new IOException("packet too large! truncated and dropped from: " + packet.getRemoteHost());
                    }
                    if (size > 0) {
                        //FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestInbound(size, "UDP receiver");
                        //_context.bandwidthLimiter().requestInbound(req, size, "UDP receiver");
                        req = _context.bandwidthLimiter().requestInbound(size, "UDP receiver");
                        while (req.getPendingInboundRequested() > 0)
                            req.waitForNextAllocation();
                        
                        int queued = receive(packet);
                        _context.statManager().addRateData("udp.receivePacketSize", size, queued);
                    } else {
                        _context.statManager().addRateData("udp.receiveHolePunch", 1, 0);
                        // nat hole punch packets are 0 bytes
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Received a 0 byte udp packet from " + packet.getPacket().getAddress() + ":" + packet.getPacket().getPort());
                        packet.release();
                    }
                } catch (IOException ioe) {
                    //if (_socketChanged) {
                    //    if (_log.shouldLog(Log.INFO))
                    //        _log.info("Changing ports...");
                    //} else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Error receiving", ioe);
                    //}
                    packet.release();
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Stop receiving...");
        }
        
     /******
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
      *****/
    }
}
