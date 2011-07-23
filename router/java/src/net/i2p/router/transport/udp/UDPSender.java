package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Lowest level packet sender, pushes anything on its queue ASAP.
 *
 */
class UDPSender {
    private final RouterContext _context;
    private final Log _log;
    private DatagramSocket _socket;
    private String _name;
    private final BlockingQueue<UDPPacket> _outboundQueue;
    private boolean _keepRunning;
    private final Runner _runner;
    private static final int TYPE_POISON = 99999;
    
    //private static final int MAX_QUEUED = 4;
    
    public UDPSender(RouterContext ctx, DatagramSocket socket, String name) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPSender.class);
        _outboundQueue = new LinkedBlockingQueue();
        _socket = socket;
        _runner = new Runner();
        _name = name;
        _context.statManager().createRateStat("udp.pushTime", "How long a UDP packet takes to get pushed out", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendQueueSize", "How many packets are queued on the UDP sender", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendQueueFailed", "How often it was unable to add a new packet to the queue", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendQueueTrimmed", "How many packets were removed from the queue for being too old (duration == remaining)", "udp", UDPTransport.RATES);
        _context.statManager().createRequiredRateStat("udp.sendPacketSize", "Size of sent packets (bytes)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.socketSendTime", "How long the actual socket.send took", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendBWThrottleTime", "How long the send is blocked by the bandwidth throttle", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendACKTime", "How long an ACK packet is blocked for (duration == lifetime)", "udp", UDPTransport.RATES);
        // used in RouterWatchdog
        _context.statManager().createRequiredRateStat("udp.sendException", "Send fails (Windows exception?)", "udp", new long[] { 60*1000, 10*60*1000 });

        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_ACK, "ack-only packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_PUNCH, "hole punch packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_RESP, "relay response packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_INTRO, "relay intro packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_RREQ, "relay request packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_TCB, "peer test charlie to bob packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_TBC, "peer test bob to charlie packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_TTA, "peer test to alice packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_TFA, "peer test from alice packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_CONF, "session confirmed packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_SREQ, "session request packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize." + PacketBuilder.TYPE_CREAT, "session created packet size", "udp", UDPTransport.RATES);
    }
    
    public void startup() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Starting the runner: " + _name);
        _keepRunning = true;
        I2PThread t = new I2PThread(_runner, _name, true);
        t.start();
    }
    
    public void shutdown() {
        _keepRunning = false;
        _outboundQueue.clear();
        UDPPacket poison = UDPPacket.acquire(_context, false);
        poison.setMessageType(TYPE_POISON);
        _outboundQueue.offer(poison);
        for (int i = 1; i <= 5 && !_outboundQueue.isEmpty(); i++) {
            try {
                Thread.sleep(i * 50);
            } catch (InterruptedException ie) {}
        }
        _outboundQueue.clear();
    }
    
    public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
        return _runner.updateListeningPort(socket, newPort);
    }

    
    /**
     * Add the packet to the queue.  This may block until there is space
     * available, if requested, otherwise it returns immediately
     *
     * @param blockTime how long to block IGNORED
     * @return number of packets queued
     * @deprecated use add(packet)
     */
    public int add(UDPPacket packet, int blockTime) {
     /********
        //long expiration = _context.clock().now() + blockTime;
        int remaining = -1;
        long lifetime = -1;
        boolean added = false;
        int removed = 0;
        while ( (_keepRunning) && (remaining < 0) ) {
            //try {
                synchronized (_outboundQueue) {
                    // clear out any too-old packets
                    UDPPacket head = null;
                    if (!_outboundQueue.isEmpty()) {
                        head = (UDPPacket)_outboundQueue.get(0);
                        while (head.getLifetime() > MAX_HEAD_LIFETIME) {
                            _outboundQueue.remove(0);
                            removed++;
                            if (!_outboundQueue.isEmpty())
                                head = (UDPPacket)_outboundQueue.get(0);
                            else
                                break;
                        }
                    }
                    
                    if (true || (_outboundQueue.size() < MAX_QUEUED)) {
                        lifetime = packet.getLifetime();
                        _outboundQueue.add(packet);
                        added = true;
                        remaining = _outboundQueue.size();
                        _outboundQueue.notifyAll();
                    } else {
                        long remainingTime = expiration - _context.clock().now();
                        if (remainingTime > 0) {
                            _outboundQueue.wait(remainingTime);
                        } else {
                            remaining = _outboundQueue.size();
                            _outboundQueue.notifyAll();
                        }
                        lifetime = packet.getLifetime();
                    }
                }
            //} catch (InterruptedException ie) {}
        }
        _context.statManager().addRateData("udp.sendQueueSize", remaining, lifetime);
        if (!added)
            _context.statManager().addRateData("udp.sendQueueFailed", remaining, lifetime);
        if (removed > 0)
            _context.statManager().addRateData("udp.sendQueueTrimmed", removed, remaining);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Added the packet onto the queue with " + remaining + " remaining and a lifetime of " + lifetime);
        return remaining;
     ********/
        return add(packet);
    }
    
    private static final int MAX_HEAD_LIFETIME = 1000;
    
    /**
     * Put it on the queue
     * @return ZERO (used to be number of packets in the queue)
     */
    public int add(UDPPacket packet) {
        if (packet == null || !_keepRunning) return 0;
        int size = 0;
        _outboundQueue.offer(packet);
        //size = _outboundQueue.size();
        //_context.statManager().addRateData("udp.sendQueueSize", size, lifetime);
        if (_log.shouldLog(Log.DEBUG)) {
            size = _outboundQueue.size();
            _log.debug("Added the packet onto the queue with " + size + " remaining and a lifetime of " + packet.getLifetime());
        }
        return size;
    }
    
    private class Runner implements Runnable {
        private boolean _socketChanged;
        FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().createRequest();
        public void run() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Running the UDP sender");
            _socketChanged = false;
            while (_keepRunning) {
                if (_socketChanged) {
                    Thread.currentThread().setName(_name);
                    _socketChanged = false;
                }
                
                UDPPacket packet = getNextPacket();
                if (packet != null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Packet to send known: " + packet);
                    long acquireTime = _context.clock().now();
                    int size = packet.getPacket().getLength();
                    int size2 = packet.getPacket().getLength();
                    if (size > 0) {
                        //_context.bandwidthLimiter().requestOutbound(req, size, "UDP sender");
                        req = _context.bandwidthLimiter().requestOutbound(size, "UDP sender");
                        while (req.getPendingOutboundRequested() > 0)
                            req.waitForNextAllocation();
                    }
                    
                    long afterBW = _context.clock().now();
                    
                    if (_log.shouldLog(Log.DEBUG)) {
                        //if (len > 128)
                        //    len = 128;
                        //_log.debug("Sending packet: (size="+size + "/"+size2 +")\nraw: " + Base64.encode(packet.getPacket().getData(), 0, size));
                    }
                    
                    if (packet.getMessageType() >= PacketBuilder.TYPE_FIRST)
                        _context.statManager().addRateData("udp.sendPacketSize." + packet.getMessageType(), size, packet.getFragmentCount());
                    
                    //packet.getPacket().setLength(size);
                    try {
                        long before = _context.clock().now();
                        synchronized (Runner.this) {
                            // synchronization lets us update safely
                            //_log.debug("Break out datagram for " + packet);
                            DatagramPacket dp = packet.getPacket();
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Just before socket.send of " + packet);
                            _socket.send(dp);
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Just after socket.send of " + packet);
                        }
                        long sendTime = _context.clock().now() - before;
                        _context.statManager().addRateData("udp.socketSendTime", sendTime, packet.getLifetime());
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Sent the packet " + packet);
                        long throttleTime = afterBW - acquireTime;
                        if (throttleTime > 10)
                            _context.statManager().addRateData("udp.sendBWThrottleTime", throttleTime, acquireTime - packet.getBegin());
                        if (packet.getMarkedType() == 1)
                            _context.statManager().addRateData("udp.sendACKTime", throttleTime, packet.getLifetime());
                        _context.statManager().addRateData("udp.pushTime", packet.getLifetime(), packet.getLifetime());
                        _context.statManager().addRateData("udp.sendPacketSize", size, packet.getLifetime());
                    } catch (IOException ioe) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Error sending", ioe);
                        _context.statManager().addRateData("udp.sendException", 1, packet.getLifetime());
                    }
                    
                    // back to the cache
                    packet.release();
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Stop sending...");
        }
        
        /** @return next packet in queue. Will discard any packet older than MAX_HEAD_LIFETIME */
        private UDPPacket getNextPacket() {
            UDPPacket packet = null;
            while ( (_keepRunning) && (packet == null || packet.getLifetime() > MAX_HEAD_LIFETIME) ) {
                if (packet != null)
                    _context.statManager().addRateData("udp.sendQueueTrimmed", 1, 0);
                try {
                    packet = _outboundQueue.take();
                } catch (InterruptedException ie) {}
                if (packet != null && packet.getMessageType() == TYPE_POISON)
                    return null;
            }
            return packet;
        }
        public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
            _name = "UDPSend on " + newPort;
            DatagramSocket old = null;
            synchronized (Runner.this) {
                old = _socket;
                _socket = socket;
            }
            _socketChanged = true;
            return old;
        }
    }
}
