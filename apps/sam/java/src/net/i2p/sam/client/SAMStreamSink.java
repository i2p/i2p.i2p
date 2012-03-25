package net.i2p.sam.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Sit around on a SAM destination, receiving lots of data and 
 * writing it to disk
 *
 * Usage: SAMStreamSink samHost samPort myKeyFile sinkDir
 *
 */
public class SAMStreamSink {
    private I2PAppContext _context;
    private Log _log;
    private String _samHost;
    private String _samPort;
    private String _destFile;
    private String _sinkDir;
    private String _conOptions;
    private Socket _samSocket;
    private OutputStream _samOut;
    private InputStream _samIn;
    private SAMReader _reader;
    //private boolean _dead;
    private SAMEventHandler _eventHandler;
    /** Connection id (Integer) to peer (Flooder) */
    private Map<Integer, Sink> _remotePeers;
    
    public static void main(String args[]) {
        if (args.length < 4) {
            System.err.println("Usage: SAMStreamSink samHost samPort myDestFile sinkDir");
            return;
        }
        I2PAppContext ctx = new I2PAppContext();
        SAMStreamSink sink = new SAMStreamSink(ctx, args[0], args[1], args[2], args[3]);
        sink.startup();
    }
    
    public SAMStreamSink(I2PAppContext ctx, String samHost, String samPort, String destFile, String sinkDir) {
        _context = ctx;
        _log = ctx.logManager().getLog(SAMStreamSink.class);
        //_dead = false;
        _samHost = samHost;
        _samPort = samPort;
        _destFile = destFile;
        _sinkDir = sinkDir;
        _conOptions = "";
        _eventHandler = new SinkEventHandler(_context);
        _remotePeers = new HashMap<Integer,Sink>();
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
                //boolean written = 
                	writeDest(ourDest);
                _log.debug("Dest written");
            }
        }
    }
    
    private class SinkEventHandler extends SAMEventHandler {
        public SinkEventHandler(I2PAppContext ctx) { super(ctx); }
		@Override
        public void streamClosedReceived(String result, int id, String message) {
            Sink sink = null;
            synchronized (_remotePeers) {
                sink = _remotePeers.remove(new Integer(id));
            }
            if (sink != null) {
                sink.closed();
                _log.debug("Connection " + sink.getConnectionId() + " closed to " + sink.getDestination());
            } else {
                _log.error("wtf, not connected to " + id + " but we were just closed?");
            }
        }
		@Override
        public void streamDataReceived(int id, byte data[], int offset, int length) {
            Sink sink = null;
            synchronized (_remotePeers) {
                sink = _remotePeers.get(new Integer(id));
            }
            if (sink != null) {
                sink.received(data, offset, length);
            } else {
                _log.error("wtf, not connected to " + id + " but we received " + length + "?");
            }
        }
		@Override
        public void streamConnectedReceived(String dest, int id) {  
            _log.debug("Connection " + id + " received from " + dest);

            try {
                Sink sink = new Sink(id, dest);
                synchronized (_remotePeers) {
                    _remotePeers.put(new Integer(id), sink);
                }
            } catch (IOException ioe) {
                _log.error("Error creating a new sink", ioe);
            }
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
    
    private class Sink {
        private int _connectionId; 
        private String _remoteDestination;
        private boolean _closed;
        private long _started;
        private long _totalReceived;
        private long _lastReceivedOn;
        private OutputStream _out;
        
        public Sink(int conId, String remDest) throws IOException {
            _connectionId = conId;
            _remoteDestination = remDest;
            _closed = false;
            _lastReceivedOn = _context.clock().now();
            _context.statManager().createRateStat("sink." + conId + ".totalReceived", "Data size received", "swarm", new long[] { 30*1000, 60*1000, 5*60*1000 });
            _context.statManager().createRateStat("sink." + conId + ".started", "When we start", "swarm", new long[] { 5*60*1000 });
            _context.statManager().createRateStat("sink." + conId + ".lifetime", "How long we talk to a peer", "swarm", new long[] { 5*60*1000 });
            
            File sinkDir = new File(_sinkDir);
            if (!sinkDir.exists())
                sinkDir.mkdirs();
            
            File out = File.createTempFile("sink", ".dat", sinkDir);
            _out = new FileOutputStream(out);
        }
        
        public int getConnectionId() { return _connectionId; }
        public String getDestination() { return _remoteDestination; }
        
        public void closed() {
            if (_closed) return;
            _closed = true;
            long lifetime = _context.clock().now() - _started;
            _context.statManager().addRateData("sink." + _connectionId + ".lifetime", lifetime, lifetime);
            try { 
                _out.close();
            } catch (IOException ioe) {
                _log.error("Error closing", ioe);
            }
        }
        public void received(byte data[], int offset, int len) {
            if (_closed) return;
            _totalReceived += len;
            try {
                _out.write(data, offset, len);
            } catch (IOException ioe) {
                _log.error("Error writing received data");
                closed();
                return;
            }
            _log.debug("Received " + len + " on " + _connectionId + " after " + (_context.clock().now()-_lastReceivedOn)
                       + "ms with " + _remoteDestination.substring(0,6));
            
            _lastReceivedOn = _context.clock().now();
        }
    }
}