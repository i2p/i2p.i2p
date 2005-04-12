package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

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
    
    public UDPEndpointTest(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPEndpointTest.class);
    }
    
    public void runTest(int numPeers) {
        RouterContext ctx = new RouterContext(null);
        try {
            _endpoints = new UDPEndpoint[numPeers];
            int base = 2000 + ctx.random().nextInt(10000);
            for (int i = 0; i < numPeers; i++) {
                _log.debug("Building " + i);
                UDPEndpoint endpoint = new UDPEndpoint(ctx, base + i);
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
                received++;
                if (received == 10000) {
                    long time = System.currentTimeMillis() - start;
                    _log.debug("Received 10000 in " + time);
                }
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
                try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            }
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
                try {
                    UDPPacket packet = UDPPacket.acquire(_context);
                    packet.initialize(priority, expiration, InetAddress.getLocalHost(), _endpoints[curPeer].getListenPort());
                    packet.writeData(data, 0, 1024);
                    _endpoint.send(packet);
                } catch (UnknownHostException uhe) {
                    _log.error("foo!", uhe);
                }
                //if (_log.shouldLog(Log.DEBUG)) {
                //    _log.debug("Sent to " + _endpoints[curPeer].getListenPort() + " from " + _endpoint.getListenPort());
                //}
            }
        }
    }
    
    public static void main(String args[]) {
        UDPEndpointTest test = new UDPEndpointTest(new RouterContext(null));
        test.runTest(2);
    }
}
