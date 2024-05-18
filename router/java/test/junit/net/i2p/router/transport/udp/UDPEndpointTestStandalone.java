package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.ByteArray;
import net.i2p.router.RouterContext;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 *
 * Note: this is a standalone test, not a JUnit test.
 * At some point someone may want to convert it to a JUnit test.
 * --zab
 */
public class UDPEndpointTestStandalone {
    private final RouterContext _context;
    private final Log _log;
    private UDPEndpoint _endpoints[];
    private volatile boolean _beginTest;
    private final Set<ByteArray> _sentNotReceived;
    
    public UDPEndpointTestStandalone(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPEndpointTestStandalone.class);
        _sentNotReceived = new ConcurrentHashSet<ByteArray>(128);
    }
    
    public void runTest(int numPeers) {
        _log.debug("Run test("+numPeers+")");
        _endpoints = new UDPEndpoint[numPeers];
        int base = 44000 + _context.random().nextInt(10000);
        for (int i = 0; i < numPeers; i++) {
            _log.debug("Building " + i);
            UDPEndpoint endpoint = new UDPEndpoint(_context, null, base + i, null);
            _endpoints[i] = endpoint;
            try {
                endpoint.startup();
            } catch (SocketException se) {
                _log.error("die", se);
                throw new RuntimeException(se);
            }
            I2PThread read = new I2PThread(new TestRead(endpoint), "Test read " + i);
            I2PThread write = new I2PThread(new TestWrite(endpoint), "Test write " + i);
            //read.setDaemon(true);
            read.start();
            //write.setDaemon(true);
            write.start();
        }
        _beginTest = true;
        _log.debug("Test begin");
    }
    
    private class TestRead implements Runnable {
        private final UDPEndpoint _endpoint;
        public TestRead(UDPEndpoint peer) {
            _endpoint = peer;
        }
        public void run() {
            while (!_beginTest) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            }
            _log.debug("Beginning to read");
            long start = System.currentTimeMillis();
            int received = 0;
            while (true) {
                UDPPacket packet = _endpoint.receive();
                ByteArray ba = new ByteArray(packet.getPacket().getData(), 0, packet.getPacket().getLength());
                boolean removed = _sentNotReceived.remove(ba);
                int outstanding = _sentNotReceived.size();
                if (!removed) {
                    _log.error("Received a packet that we weren't expecting: " + packet);
                } else {
                    _log.debug("Received an expected packet (" + received + ") with outstanding: " + outstanding);
                    received++;
                }
                if ((received % 10000) == 0) {
                    long time = System.currentTimeMillis() - start;
                    _log.debug("Received "+received+" in " + time);
                }
                packet.release();
            }
        }
    }
    
    private class TestWrite implements Runnable {
        private final UDPEndpoint _endpoint;
        public TestWrite(UDPEndpoint peer) {
            _endpoint = peer;
        }
        public void run() {
            System.out.println("rewrite me for SSU2");
            throw new UnsupportedOperationException("rewrite me for SSU2");
/*
            while (!_beginTest) {
                try { Thread.sleep(2000); } catch (InterruptedException ie) {}
            }
                try { Thread.sleep(2000); } catch (InterruptedException ie) {}
            PacketBuilder builder = new PacketBuilder(_context, null);
            InetAddress localhost = null;
            try {
                localhost = InetAddress.getLocalHost();
            } catch (UnknownHostException uhe) {
                _log.error("die", uhe);
                System.exit(0);
            }
            int MIN = 1 + UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
            int MAX = PeerState.LARGE_MTU - PacketBuilder.IP_HEADER_SIZE;
            _log.debug("Beginning to write");
            for (int curPacket = 0; curPacket < 2000; curPacket++) {
                int sz = MIN + _context.random().nextInt(MAX - MIN - 1);
                byte data[] = new byte[sz];
                _context.random().nextBytes(data);
                int curPeer = (curPacket % _endpoints.length);
                if (_endpoints[curPeer] == _endpoint)
                    curPeer++;
                if (curPeer >= _endpoints.length)
                    curPeer = 0;
                UDPPacket packet = builder.buildPacket(data, localhost, _endpoints[curPeer].getListenPort());
                int outstanding = _sentNotReceived.size() + 1;
                _sentNotReceived.add(new ByteArray(data));
                _log.debug("Sending packet " + curPacket + " with " + sz + " byte payload, outstanding " + outstanding);
                try {
                    _endpoint.send(packet);
                } catch (Exception e) {
                    _log.error("die", e);
                    break;
                }
                int batch = 32 + _context.random().nextInt(32);
                if (curPacket % batch == 0 || _sentNotReceived.size() > 100) {
                    try { Thread.sleep(3); } catch (InterruptedException ie) {}
                }
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Sent to " + _endpoints[curPeer].getListenPort() + " from " + _endpoint.getListenPort());
            }
            _log.debug("Done sending packets");
            for (int i = 0; i < 20; i++) {
                if (_sentNotReceived.isEmpty())
                    break;
                try { Thread.sleep(500); } catch (InterruptedException e) {}
            }
            if (_sentNotReceived.isEmpty()) {
                _log.info("Test passed");
                System.exit(0);
            } else {
                _log.error("Test failed, " + _sentNotReceived.size() + " not received");
                System.exit(1);
            }
*/
        }
    }
    
    public static void main(String args[]) {
        try { System.out.println("Current dir: " + new java.io.File(".").getCanonicalPath()); } catch (Exception e) {}
        new java.io.File("udpEndpointTest.stats").delete();
        Properties props = new Properties();
        props.setProperty("stat.logFile", "udpEndpointTest.stats");
        props.setProperty("stat.logFilters", "*");
        props.setProperty("i2p.dummyClientFacade", "true");
        props.setProperty("i2p.dummyNetDb", "true");
        props.setProperty("i2p.dummyPeerManager", "true");
        props.setProperty("i2p.dummyTunnelManager", "true");
        props.setProperty("i2p.vmCommSystem", "true");
        props.setProperty("i2np.bandwidth.inboundKBytesPerSecond", "9999");
        props.setProperty("i2np.bandwidth.outboundKBytesPerSecond", "9999");
        RouterContext ctx = new RouterContext(null, props);
        ctx.initAll();
        UDPEndpointTestStandalone test = new UDPEndpointTestStandalone(ctx);
        test.runTest(2);
    }
}
