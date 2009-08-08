package net.i2p.client.streaming;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.data.Destination;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Sit around on a destination, receiving lots of data and sending lots of
 * data to whomever talks to us.
 *
 * Usage: TestSwarm myKeyFile [peerDestFile ]*
 *
 */
public class TestSwarm {
    private I2PAppContext _context;
    private Log _log;
    private String _destFile;
    private String _peerDestFiles[];
    private I2PSocketManager _manager;
    private String _conOptions; // unused? used elsewhere?
    private boolean _dead; // unused? used elsewhere?
    
    public static void main(String args[]) {
        if (args.length < 1) {
            System.err.println("Usage: TestSwarm myDestFile [peerDestFile ]*");
            return;
        }
        I2PAppContext ctx = new I2PAppContext();
        String files[] = new String[args.length - 1];
        System.arraycopy(args, 1, files, 0, files.length);
        TestSwarm swarm = new TestSwarm(ctx, args[0], files);
        swarm.startup();
    }
    
    public TestSwarm(I2PAppContext ctx, String destFile, String peerDestFiles[]) {
        _context = ctx;
        _log = ctx.logManager().getLog(TestSwarm.class);
        _dead = false;
        _destFile = destFile;
        _peerDestFiles = peerDestFiles;
        _conOptions = "";
    }
    
    public void startup() {
        _log.debug("Starting up");
        File keys = new File(_destFile);
        if (!keys.exists()) {
            try {
                I2PClientFactory.createClient().createDestination(new FileOutputStream(keys));
            } catch (Exception e) {
                _log.error("Error creating a new destination on " + keys, e);
                return;
            }
        }
        try {
            _manager = I2PSocketManagerFactory.createManager(new FileInputStream(_destFile), null, -1, null);
        } catch (Exception e) {
            _log.error("Error creatign the manager", e);
            return;
        }

        I2PThread listener = new I2PThread(new Listener(), "Listener");
        listener.start();
        
        connectWithPeers();
    }
    
    
    private void connectWithPeers() {
        if (_peerDestFiles != null) {
            for (int i = 0; i < _peerDestFiles.length; i++) {
                try {
                    FileInputStream fin = new FileInputStream(_peerDestFiles[i]);
                    Destination dest = new Destination();
                    dest.readBytes(fin);
                    
                    I2PThread flooder = new I2PThread(new Flooder(dest), "Flooder+" + dest.calculateHash().toBase64().substring(0,4));
                    flooder.start();
                } catch (Exception e) {
                    _log.error("Unable to read the peer from " + _peerDestFiles[i], e);
                }
            }
        }
    }
    
    private class Listener implements Runnable {
        public void run() {
            try {
                I2PServerSocket ss = _manager.getServerSocket();
                I2PSocket s = null;
                while ( (s = ss.accept()) != null) {
                    I2PThread flooder = new I2PThread(new Flooder(s), "Flooder-" + s.getPeerDestination().calculateHash().toBase64().substring(0,4));
                    flooder.start();
                }
            } catch (Exception e) {
                _log.error("Error listening", e);
            }
        }
    }
    
    private static volatile long __conId = 0;
    private class Flooder implements Runnable {
        private Destination _remoteDestination;
        private I2PSocket _socket;
        private boolean _closed;
        private long _started;
        private long _totalSent;
        private long _totalReceived;
        private long _lastReceived;
        private long _lastReceivedOn;
        private long _connectionId;
        
        public Flooder(Destination dest) {
            _socket = null;
            _remoteDestination = dest;
            _connectionId = ++__conId;
            _closed = false;
            _lastReceived = -1;
            _lastReceivedOn = _context.clock().now();
            _context.statManager().createRateStat("swarm." + _connectionId + ".totalReceived", "Data size received", "swarm", new long[] { 30*1000, 60*1000, 5*60*1000 });
            _context.statManager().createRateStat("swarm." + _connectionId + ".totalSent", "Data size sent", "swarm", new long[] { 30*1000, 60*1000, 5*60*1000 });
            _context.statManager().createRateStat("swarm." + _connectionId + ".started", "When we start", "swarm", new long[] { 5*60*1000 });
            _context.statManager().createRateStat("swarm." + _connectionId + ".lifetime", "How long we talk to a peer", "swarm", new long[] { 5*60*1000 });
        }
        
        public Flooder(I2PSocket socket) {
            _socket = socket;
            _remoteDestination = socket.getPeerDestination();
            _connectionId = ++__conId;
            _closed = false;
            _lastReceived = -1;
            _lastReceivedOn = _context.clock().now();
            _context.statManager().createRateStat("swarm." + _connectionId + ".totalReceived", "Data size received", "swarm", new long[] { 30*1000, 60*1000, 5*60*1000 });
            _context.statManager().createRateStat("swarm." + _connectionId + ".totalSent", "Data size sent", "swarm", new long[] { 30*1000, 60*1000, 5*60*1000 });
            _context.statManager().createRateStat("swarm." + _connectionId + ".started", "When we start", "swarm", new long[] { 5*60*1000 });
            _context.statManager().createRateStat("swarm." + _connectionId + ".lifetime", "How long we talk to a peer", "swarm", new long[] { 5*60*1000 });
        }
        
        public long getConnectionId() { return _connectionId; }
        public Destination getDestination() { return _remoteDestination; }
        
        public void run() {
            _started = _context.clock().now();
            _context.statManager().addRateData("swarm." + _connectionId + ".started", 1, 0);
            byte data[] = new byte[4*1024];
            _context.random().nextBytes(data);
            long value = 0;
            long lastSend = _context.clock().now();
            if (_socket == null) {
                try {
                    _socket = _manager.connect(_remoteDestination);
                } catch (Exception e) {
                    _log.error("Error connecting to " + _remoteDestination.calculateHash().toBase64().substring(0,4));
                    return;
                }
            }
            
            I2PThread floodListener = new I2PThread(new FloodListener(), "FloodListener" + _connectionId);
            floodListener.start();
            
            try {
                OutputStream out = _socket.getOutputStream();
                while (!_closed) {
                    if (shouldSend()) {
                        out.write(data);
                        // out.flush();
                        _totalSent += data.length;
                        _context.statManager().addRateData("swarm." + _connectionId + ".totalSent", _totalSent, 0);
                        //try { Thread.sleep(100); } catch (InterruptedException ie) {}
                        long now = _context.clock().now();
                        //_log.debug("Sending " + _connectionId + " after " + (now-lastSend));
                        lastSend = now;
                        //try { Thread.sleep(20); } catch (InterruptedException ie) {}
                    } else {
                        try { Thread.sleep(5000); } catch (InterruptedException ie) {}
                    }
                }
            } catch (Exception e) {
                _log.error("Error sending", e);
            }
        }
        
        private class FloodListener implements Runnable {
            public void run() {
                long lastRead = System.currentTimeMillis();
                long now = lastRead;
                try {
                    InputStream in = _socket.getInputStream();
                    byte buf[] = new byte[8*1024];
                    int read = 0;
                    while ( (read = in.read(buf)) != -1) {
                        now = System.currentTimeMillis();
                        _totalReceived += read;
                        _context.statManager().addRateData("swarm." + getConnectionId() + ".totalReceived", _totalReceived, 0);
                        //_log.debug("Receiving " + _connectionId + " with " + read + " after " + (now-lastRead));
                        lastRead = now;
                    }
                } catch (Exception e) {
                    _log.error("Error listening to the flood", e);
                }
            }
        }
    }
    
    private boolean shouldSend() {
        return Boolean.valueOf(_context.getProperty("shouldSend", "false")).booleanValue();
    }
}