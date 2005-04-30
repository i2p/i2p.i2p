package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;

import java.util.ArrayList;
import java.util.List;
import net.i2p.data.Base64;
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
    private List _outboundQueue;
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
        _context.statManager().createRateStat("udp.pushTime", "How long a UDP packet takes to get pushed out", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.sendQueueSize", "How many packets are queued on the UDP sender", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.sendPacketSize", "How large packets sent are", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.socketSendTime", "How long the actual socket.send took", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.sendBWThrottleTime", "How long the send is blocked by the bandwidth throttle", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.sendACKTime", "How long an ACK packet is blocked for (duration == lifetime)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
    }
    
    public void startup() {
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
        long expiration = _context.clock().now() + blockTime;
        int remaining = -1;
        while ( (_keepRunning) && (remaining < 0) ) {
            try {
                synchronized (_outboundQueue) {
                    if (_outboundQueue.size() < MAX_QUEUED) {
                        _outboundQueue.add(packet);
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
                    }
                }
            } catch (InterruptedException ie) {}
        }
        _context.statManager().addRateData("udp.sendQueueSize", remaining, packet.getLifetime());
        return remaining;
    }
    
    /**
     *
     * @return number of packets in the queue
     */
    public int add(UDPPacket packet) {
        int size = 0;
        synchronized (_outboundQueue) {
            _outboundQueue.add(packet);
            size = _outboundQueue.size();
            _outboundQueue.notifyAll();
        }
        _context.statManager().addRateData("udp.sendQueueSize", size, packet.getLifetime());
        return size;
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
                
                UDPPacket packet = getNextPacket();
                if (packet != null) {
                    long acquireTime = _context.clock().now();
                    int size = packet.getPacket().getLength();
                    if (size > 0) {
                        FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestOutbound(size, "UDP sender");
                        while (req.getPendingOutboundRequested() > 0)
                            req.waitForNextAllocation();
                    }
                    
                    long afterBW = _context.clock().now();
                    
                    if (_log.shouldLog(Log.DEBUG)) {
                        int len = packet.getPacket().getLength();
                        //if (len > 128)
                        //    len = 128;
                        _log.debug("Sending packet: \nraw: " + Base64.encode(packet.getPacket().getData(), 0, len));
                    }
                    
                    try {
                        long before = _context.clock().now();
                        synchronized (Runner.this) {
                            // synchronization lets us update safely
                            _socket.send(packet.getPacket());
                        }
                        long sendTime = _context.clock().now() - before;
                        _context.statManager().addRateData("udp.socketSendTime", sendTime, packet.getLifetime());
                        _context.statManager().addRateData("udp.sendBWThrottleTime", afterBW - acquireTime, acquireTime - packet.getBegin());
                        if (packet.getMarkedType() == 1)
                            _context.statManager().addRateData("udp.sendACKTime", afterBW - acquireTime, packet.getLifetime());
                        _context.statManager().addRateData("udp.pushTime", packet.getLifetime(), packet.getLifetime());
                        _context.statManager().addRateData("udp.sendPacketSize", packet.getPacket().getLength(), packet.getLifetime());
                    } catch (IOException ioe) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("Error sending", ioe);
                    }
                    
                    // back to the cache
                    packet.release();
                }
            }
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
