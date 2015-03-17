package net.i2p.router.transport.udp;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import net.i2p.data.ByteArray;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 *
 */
public class UDPEndpointTest {
    private RouterContext _context;
    private Log _log;
    private UDPEndpoint _endpoints[];
    private boolean _beginTest;
    private List _sentNotReceived;
    
    public UDPEndpointTest(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPEndpointTest.class);
        _sentNotReceived = Collections.synchronizedList(new ArrayList(1000));
    }
    
    public void runTest(int numPeers) {
        _log.debug("Run test("+numPeers+")");
        try {
            _endpoints = new UDPEndpoint[numPeers];
            int base = 2000 + _context.random().nextInt(10000);
            for (int i = 0; i < numPeers; i++) {
                _log.debug("Building " + i);
                UDPEndpoint endpoint = new UDPEndpoint(_context, null, base + i, null);
                _endpoints[i] = endpoint;
                endpoint.startup();
                I2PThread read = new I2PThread(new TestRead(endpoint), "Test read " + i);
                I2PThread write = new I2PThread(new TestWrite(endpoint), "Test write " + i);
                //read.setDaemon(true);
                read.start();
                //write.setDaemon(true);
                write.start();
            }
        } catch (SocketException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error initializing", se);
            return;
        }
        _beginTest = true;
        _log.debug("Test begin");
    }
    
    private class TestRead implements Runnable {
        private UDPEndpoint _endpoint;
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
        private UDPEndpoint _endpoint;
        public TestWrite(UDPEndpoint peer) {
            _endpoint = peer;
        }
        public void run() {
            while (!_beginTest) {
                try { Thread.sleep(2000); } catch (InterruptedException ie) {}
            }
                try { Thread.sleep(2000); } catch (InterruptedException ie) {}
            _log.debug("Beginning to write");
            for (int curPacket = 0; curPacket < 10000; curPacket++) {
                byte data[] = new byte[1024];
                _context.random().nextBytes(data);
                int curPeer = (curPacket % _endpoints.length);
                if (_endpoints[curPeer] == _endpoint)
                    curPeer++;
                if (curPeer >= _endpoints.length)
                    curPeer = 0;
                short priority = 1;
                long expiration = -1;
                UDPPacket packet = UDPPacket.acquire(_context, true);
                //try {
                    if (true) throw new RuntimeException("fixme");
                    //packet.initialize(priority, expiration, InetAddress.getLocalHost(), _endpoints[curPeer].getListenPort());
                    packet.writeData(data, 0, 1024);
                    packet.getPacket().setLength(1024);
                    int outstanding = _sentNotReceived.size() + 1;
                    _sentNotReceived.add(new ByteArray(data, 0, 1024));
                    _log.debug("Sending packet " + curPacket + " with outstanding " + outstanding);
                    _endpoint.send(packet);
                    //try { Thread.sleep(10); } catch (InterruptedException ie) {}
                //} catch (UnknownHostException uhe) {
                //    _log.error("foo!", uhe);
                //}
                //if (_log.shouldLog(Log.DEBUG)) {
                //    _log.debug("Sent to " + _endpoints[curPeer].getListenPort() + " from " + _endpoint.getListenPort());
                //}
            }
            try { Thread.sleep(10*1000); } catch (InterruptedException e) {}
            System.exit(0);
        }
    }
    
    public static void main(String args[]) {
        try { System.out.println("Current dir: " + new java.io.File(".").getCanonicalPath()); } catch (Exception e) {}
        new java.io.File("udpEndpointTest.stats").delete();
        Properties props = new Properties();
        props.setProperty("stat.logFile", "udpEndpointTest.stats");
        props.setProperty("stat.logFilters", "*");
        UDPEndpointTest test = new UDPEndpointTest(new RouterContext(null, props));
        test.runTest(2);
    }
}
