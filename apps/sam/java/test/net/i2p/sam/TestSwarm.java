package net.i2p.sam;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.sam.client.SAMEventHandler;
import net.i2p.sam.client.SAMReader;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Sit around on a SAM destination, receiving lots of data and sending lots of
 * data to whomever talks to us.
 *
 * Usage: TestSwarm samHost samPort myKeyFile [peerDestFile ]*
 *
 */
public class TestSwarm {
    private I2PAppContext _context;
    private Log _log;
    private String _samHost;
    private String _samPort;
    private String _destFile;
    private String _peerDestFiles[];
    private String _conOptions;
    private Socket _samSocket;
    private OutputStream _samOut;
    private InputStream _samIn;
    private SAMReader _reader;
    private boolean _dead;
    private SAMEventHandler _eventHandler;
    /** Connection id (Integer) to peer (Flooder) */
    private Map _remotePeers;
    
    public static void main(String args[]) {
        if (args.length < 3) {
            System.err.println("Usage: TestSwarm samHost samPort myDestFile [peerDestFile ]*");
            return;
        }
        I2PAppContext ctx = new I2PAppContext();
        String files[] = new String[args.length - 3];
        System.arraycopy(args, 3, files, 0, files.length);
        TestSwarm swarm = new TestSwarm(ctx, args[0], args[1], args[2], files);
        swarm.startup();
    }
    
    public TestSwarm(I2PAppContext ctx, String samHost, String samPort, String destFile, String peerDestFiles[]) {
        _context = ctx;
        _log = ctx.logManager().getLog(TestSwarm.class);
        _dead = false;
        _samHost = samHost;
        _samPort = samPort;
        _destFile = destFile;
        _peerDestFiles = peerDestFiles;
        _conOptions = "";
        _eventHandler = new SwarmEventHandler(_context);
        _remotePeers = new HashMap();
    }
    
    public void startup() {
        _log.debug("Starting up");
        boolean ok = connect();
        _log.debug("Connected: " + ok);
        if (ok) {
            _reader = new SAMReader(_context, _samIn, _eventHandler);
            _reader.startReading();
            _log.debug("Reader created");
            String ourDest = handshake();
            _log.debug("Handshake complete.  we are " + ourDest);
            if (ourDest != null) {
                boolean written = writeDest(ourDest);
                _log.debug("Dest written");
                if (written) {
                    connectWithPeers();
                    _log.debug("connected with peers");
                }
            }
        }
    }
    
    private class SwarmEventHandler extends SAMEventHandler {
        public SwarmEventHandler(I2PAppContext ctx) { super(ctx); }
        public void streamClosedReceived(String result, int id, String message) {
            Flooder flooder = null;
            synchronized (_remotePeers) {
                flooder = (Flooder)_remotePeers.remove(new Integer(id));
            }
            if (flooder != null) {
                flooder.closed();
                _log.debug("Connection " + flooder.getConnectionId() + " closed to " + flooder.getDestination());
            } else {
                _log.error("wtf, not connected to " + id + " but we were just closed?");
            }
        }
        public void streamDataReceived(int id, byte data[], int offset, int length) {
            Flooder flooder = null;
            synchronized (_remotePeers) {
                flooder = (Flooder)_remotePeers.get(new Integer(id));
            }
            long value = DataHelper.fromLong(data, 0, 4);
            if (flooder != null) {
                flooder.received(length, value);
            } else {
                _log.error("wtf, not connected to " + id + " but we received " + value + "?");
            }
        }
        public void streamConnectedReceived(String dest, int id) {  
            _log.debug("Connection " + id + " received from " + dest);

            Flooder flooder = new Flooder(id, dest);
            synchronized (_remotePeers) {
                _remotePeers.put(new Integer(id), flooder);
            }
            I2PThread t = new I2PThread(flooder, "Flood " + id);
            t.start();
        }

    }
    
    private boolean connect() {
        try {
            _samSocket = new Socket(_samHost, Integer.parseInt(_samPort));
            _samOut = _samSocket.getOutputStream();
            _samIn = _samSocket.getInputStream();
            return true;
        } catch (Exception e) {
            _log.error("Unable to connect to SAM at " + _samHost + ":" + _samPort, e);
            return false;
        }
    }
    
    private String handshake() {
        synchronized (_samOut) {
            try {
                _samOut.write("HELLO VERSION MIN=1.0 MAX=1.0\n".getBytes());
                _samOut.flush();
                _log.debug("Hello sent");
                boolean ok = _eventHandler.waitForHelloReply();
                _log.debug("Hello reply found: " + ok);
                if (!ok) 
                    throw new IOException("wtf, hello failed?");
                String req = "SESSION CREATE STYLE=STREAM DESTINATION=" + _destFile + " " + _conOptions + "\n";
                _samOut.write(req.getBytes());
                _samOut.flush();
                _log.debug("Session create sent");
                ok = _eventHandler.waitForSessionCreateReply();
                _log.debug("Session create reply found: " + ok);

                req = "NAMING LOOKUP NAME=ME\n";
                _samOut.write(req.getBytes());
                _samOut.flush();
                _log.debug("Naming lookup sent");
                String destination = _eventHandler.waitForNamingReply("ME");
                _log.debug("Naming lookup reply found: " + destination);
                if (destination == null) {
                    _log.error("No naming lookup reply found!");
                    return null;
                } else {
                    _log.info(_destFile + " is located at " + destination);
                }
                return destination;
            } catch (Exception e) {
                _log.error("Error handshaking", e);
                return null;
            }
        }
    }
    
    private boolean writeDest(String dest) {
        try {
            FileOutputStream fos = new FileOutputStream(_destFile);
            fos.write(dest.getBytes());
            fos.close();
            return true;
        } catch (Exception e) {
            _log.error("Error writing to " + _destFile, e);
            return false;
        }
    }
    
    private void connectWithPeers() {
        if (_peerDestFiles != null) {
            for (int i = 0; i < _peerDestFiles.length; i++) {
                try {
                    FileInputStream fin = new FileInputStream(_peerDestFiles[i]);
                    byte dest[] = new byte[1024];
                    int read = DataHelper.read(fin, dest);
                    
                    String remDest = new String(dest, 0, read);
                    int con = 0;
                    Flooder flooder = null;
                    synchronized (_remotePeers) {
                        con = _remotePeers.size() + 1;
                        flooder = new Flooder(con, remDest);
                        _remotePeers.put(new Integer(con), flooder);
                    }
                    
                    byte msg[] = ("STREAM CONNECT ID=" + con + " DESTINATION=" + remDest + "\n").getBytes();
                    synchronized (_samOut) {
                        _samOut.write(msg);
                        _samOut.flush();
                    }
                    I2PThread flood = new I2PThread(flooder, "Flood " + con);
                    flood.start();
                    _log.debug("Starting flooder with peer from " + _peerDestFiles[i] + ": " + con);
                } catch (IOException ioe) {
                    _log.error("Unable to read the peer from " + _peerDestFiles[i]);
                }
            }
        }
    }
    
    private class Flooder implements Runnable {
        private int _connectionId; 
        private String _remoteDestination;
        private boolean _closed;
        private long _started;
        private long _totalSent;
        private long _totalReceived;
        private long _lastReceived;
        private long _lastReceivedOn;
        private boolean _outOfSync;
        
        public Flooder(int conId, String remDest) {
            _connectionId = conId;
            _remoteDestination = remDest;
            _closed = false;
            _outOfSync = false;
            _lastReceived = -1;
            _lastReceivedOn = _context.clock().now();
            _context.statManager().createRateStat("swarm." + conId + ".totalReceived", "Data size received", "swarm", new long[] { 30*1000, 60*1000, 5*60*1000 });
            _context.statManager().createRateStat("swarm." + conId + ".totalSent", "Data size sent", "swarm", new long[] { 30*1000, 60*1000, 5*60*1000 });
            _context.statManager().createRateStat("swarm." + conId + ".started", "When we start", "swarm", new long[] { 5*60*1000 });
            _context.statManager().createRateStat("swarm." + conId + ".lifetime", "How long we talk to a peer", "swarm", new long[] { 5*60*1000 });
        }
        
        public int getConnectionId() { return _connectionId; }
        public String getDestination() { return _remoteDestination; }
        
        public void closed() {
            _closed = true;
            long lifetime = _context.clock().now() - _started;
            _context.statManager().addRateData("swarm." + _connectionId + ".lifetime", lifetime, lifetime);
        }
        public void run() {
            _started = _context.clock().now();
            _context.statManager().addRateData("swarm." + _connectionId + ".started", 1, 0);
            byte data[] = new byte[32*1024];
            long value = 0;
            long lastSend = _context.clock().now();
            while (!_closed) {
                byte msg[] = ("STREAM SEND ID=" + _connectionId + " SIZE=" + data.length + "\n").getBytes();
                DataHelper.toLong(data, 0, 4, value);
                try {
                    synchronized (_samOut) {
                        _samOut.write(msg);
                        _samOut.write(data);
                        _samOut.flush();
                    }
                } catch (IOException ioe) {
                    _log.error("Error talking to SAM", ioe);
                    return;
                }
                _totalSent += data.length;
                _context.statManager().addRateData("swarm." + _connectionId + ".totalSent", _totalSent, 0);
                value++;
                try { Thread.sleep(20); } catch (InterruptedException ie) {}
                long now = _context.clock().now();
                _log.debug("Sending " + value + " on " + _connectionId + " after " + (now-lastSend));
                lastSend = now;
            }
        }
        public void received(int len, long value) {
            _totalReceived += len;
            if ( (!_outOfSync) && (len % 32*1024 != 0) ) {
                _outOfSync = true;
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Out of sync (len=" + len + " after " + (_totalReceived-len) + ")");
            }
            _context.statManager().addRateData("swarm." + getConnectionId() + ".totalReceived", _totalReceived, 0);
            if (value != _lastReceived + 1) {
                if (!_outOfSync)
                    _log.error("Received " + value + " when expecting " + (_lastReceived+1) + " on " 
                               + _connectionId + " with " + _remoteDestination.substring(0,6));
                else
                    _log.debug("(out of sync) Received " + value + " when expecting " + (_lastReceived+1) + " on " 
                               + _connectionId + " with " + _remoteDestination.substring(0,6));
            } else {
                _log.debug("Received " + value + " on " + _connectionId + " after " + (_context.clock().now()-_lastReceivedOn)
                           + "ms with " + _remoteDestination.substring(0,6));
            }
            _lastReceived = value;
            _lastReceivedOn = _context.clock().now();
        }
    }
}