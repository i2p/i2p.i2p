package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Lowest level packet sender, pushes anything on its queue ASAP.
 *
 */
public class UDPSender {
    private RouterContext _context;
    private Log _log;
    private DatagramSocket _socket;
    private String _name;
    private final List _outboundQueue;
    private boolean _keepRunning;
    private Runner _runner;
    
    private static final int MAX_QUEUED = 4;
    
    public UDPSender(RouterContext ctx, DatagramSocket socket, String name) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPSender.class);
        _outboundQueue = new ArrayList(128);
        _socket = socket;
        _runner = new Runner();
        _name = name;
        _context.statManager().createRateStat("udp.pushTime", "How long a UDP packet takes to get pushed out", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendQueueSize", "How many packets are queued on the UDP sender", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendQueueFailed", "How often it was unable to add a new packet to the queue", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendQueueTrimmed", "How many packets were removed from the queue for being too old (duration == remaining)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize", "How large packets sent are", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.socketSendTime", "How long the actual socket.send took", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendBWThrottleTime", "How long the send is blocked by the bandwidth throttle", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendACKTime", "How long an ACK packet is blocked for (duration == lifetime)", "udp", UDPTransport.RATES);
        // used in RouterWatchdog
        _context.statManager().createRateStat("udp.sendException", "How frequently we fail to send a packet (likely due to a windows exception)", "udp", new long[] { 60*1000, 10*60*1000 });

        _context.statManager().createRateStat("udp.sendPacketSize.1", "db store message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.2", "db lookup message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.3", "db search reply message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.6", "tunnel create message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.7", "tunnel create status message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.10", "delivery status message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.11", "garlic message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.16", "date message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.18", "tunnel data message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.19", "tunnel gateway message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.20", "data message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.21", "tunnel build", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.22", "tunnel build reply", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.20", "data message size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.42", "ack-only packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.43", "hole punch packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.44", "relay response packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.45", "relay intro packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.46", "relay request packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.47", "peer test charlie to bob packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.48", "peer test bob to charlie packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.49", "peer test to alice packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.50", "peer test from alice packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.51", "session confirmed packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.52", "session request packet size", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPacketSize.53", "session created packet size", "udp", UDPTransport.RATES);
    }
    
    public void startup() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Starting the runner: " + _name);
        _keepRunning = true;
        I2PThread t = new I2PThread(_runner, _name);
        t.setDaemon(true);
        t.start();
    }
    
    public void shutdown() {
        _keepRunning = false;
        synchronized (_outboundQueue) {
            _outboundQueue.clear();
            _outboundQueue.notifyAll();
        }
    }
    
    public DatagramSocket updateListeningPort(DatagramSocket socket, int newPort) {
        return _runner.updateListeningPort(socket, newPort);
    }

    
    /**
     * Add the packet to the queue.  This may block until there is space
     * available, if requested, otherwise it returns immediately
     *
     * @param blockTime how long to block
     * @return number of packets queued
     */
    public int add(UDPPacket packet, int blockTime) {
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
                    if (_outboundQueue.size() > 0) {
                        head = (UDPPacket)_outboundQueue.get(0);
                        while (head.getLifetime() > MAX_HEAD_LIFETIME) {
                            _outboundQueue.remove(0);
                            removed++;
                            if (_outboundQueue.size() > 0)
                                head = (UDPPacket)_outboundQueue.get(0);
                            else
                                break;
                        }
                    }
                    
                    //if (true || (_outboundQueue.size() < MAX_QUEUED)) {
                        lifetime = packet.getLifetime();
                        _outboundQueue.add(packet);
                        added = true;
                        remaining = _outboundQueue.size();
                        _outboundQueue.notifyAll();
                    /*****
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
                    *****/
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
    }
    
    private static final int MAX_HEAD_LIFETIME = 1000;
    
    /**
     *
     * @return number of packets in the queue
     */
    public int add(UDPPacket packet) {
        if (packet == null) return 0;
        int size = 0;
        long lifetime = -1;
        int removed = 0;
        synchronized (_outboundQueue) {
            lifetime = packet.getLifetime();
            UDPPacket head = null;
            if (_outboundQueue.size() > 0) {
                head = (UDPPacket)_outboundQueue.get(0);
                while (head.getLifetime() > MAX_HEAD_LIFETIME) {
                    _outboundQueue.remove(0);
                    removed++;
                    if (_outboundQueue.size() > 0)
                        head = (UDPPacket)_outboundQueue.get(0);
                    else
                        break;
                }
            }
            _outboundQueue.add(packet);
            size = _outboundQueue.size();
            _outboundQueue.notifyAll();
        }
        _context.statManager().addRateData("udp.sendQueueSize", size, lifetime);
        if (removed > 0)
            _context.statManager().addRateData("udp.sendQueueTrimmed", removed, size);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Added the packet onto the queue with " + size + " remaining and a lifetime of " + lifetime);
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
        
        private UDPPacket getNextPacket() {
            UDPPacket packet = null;
            while ( (_keepRunning) && (packet == null) ) {
                try {
                    synchronized (_outboundQueue) {
                        if (_outboundQueue.size() <= 0) {
                            _outboundQueue.notifyAll();
                            _outboundQueue.wait();
                        } else {
                            packet = (UDPPacket)_outboundQueue.remove(0);
                            _outboundQueue.notifyAll();
                        }
                    }
                } catch (InterruptedException ie) {}
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
