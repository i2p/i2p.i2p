package net.i2p.sam.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

/**
 * Sit around on a SAM destination, receiving lots of data and 
 * writing it to disk
 *
 * Usage: SAMStreamSink samHost samPort myKeyFile sinkDir
 *
 */
public class SAMStreamSink {
    private final I2PAppContext _context;
    private final Log _log;
    private final String _samHost;
    private final String _samPort;
    private final String _destFile;
    private final String _sinkDir;
    private String _conOptions;
    private SAMReader _reader;
    private boolean _isV3;
    //private boolean _dead;
    private final SAMEventHandler _eventHandler;
    /** Connection id (Integer) to peer (Flooder) */
    private final Map<String, Sink> _remotePeers;
    
    public static void main(String args[]) {
        if (args.length < 4) {
            System.err.println("Usage: SAMStreamSink samHost samPort myDestFile sinkDir [version]");
            return;
        }
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SAMStreamSink sink = new SAMStreamSink(ctx, args[0], args[1], args[2], args[3]);
        String version = (args.length >= 5) ? args[4] : "1.0";
        sink.startup(version);
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
        _remotePeers = new HashMap<String, Sink>();
    }
    
    public void startup(String version) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Starting up");
        try {
            Socket sock = connect();
            _reader = new SAMReader(_context, sock.getInputStream(), _eventHandler);
            _reader.startReading();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Reader created");
            OutputStream out = sock.getOutputStream();
            String ourDest = handshake(out, version, true);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Handshake complete.  we are " + ourDest);
            if (ourDest != null) {
                //boolean written = 
               	writeDest(ourDest);
            } else {
                _reader.stopReading();
            }
        } catch (IOException e) {
            _log.error("Unable to connect to SAM at " + _samHost + ":" + _samPort, e);
        }
    }
    
    private class SinkEventHandler extends SAMEventHandler {

        public SinkEventHandler(I2PAppContext ctx) { super(ctx); }

        @Override
        public void streamClosedReceived(String result, String id, String message) {
            Sink sink = null;
            synchronized (_remotePeers) {
                sink = _remotePeers.remove(id);
            }
            if (sink != null) {
                sink.closed();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Connection " + sink.getConnectionId() + " closed to " + sink.getDestination());
            } else {
                _log.error("not connected to " + id + " but we were just closed?");
            }
        }

        @Override
        public void streamDataReceived(String id, byte data[], int offset, int length) {
            Sink sink = null;
            synchronized (_remotePeers) {
                sink = _remotePeers.get(id);
            }
            if (sink != null) {
                sink.received(data, offset, length);
            } else {
                _log.error("not connected to " + id + " but we received " + length + "?");
            }
        }

        @Override
        public void streamConnectedReceived(String dest, String id) {  
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Connection " + id + " received from " + dest);

            try {
                Sink sink = new Sink(id, dest);
                synchronized (_remotePeers) {
                    _remotePeers.put(id, sink);
                }
            } catch (IOException ioe) {
                _log.error("Error creating a new sink", ioe);
            }
        }
    }
    
    private Socket connect() throws IOException {
        return new Socket(_samHost, Integer.parseInt(_samPort));
    }
    
    /** @return our b64 dest or null */
    private String handshake(OutputStream samOut, String version, boolean isMaster) {
        synchronized (samOut) {
            try {
                samOut.write(("HELLO VERSION MIN=1.0 MAX=" + version + '\n').getBytes());
                samOut.flush();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Hello sent");
                String hisVersion = _eventHandler.waitForHelloReply();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Hello reply found: " + hisVersion);
                if (hisVersion == null) 
                    throw new IOException("Hello failed");
                _isV3 = VersionComparator.comp(hisVersion, "3") >= 0;
                String dest;
                if (_isV3) {
                    // we use the filename as the name in sam.keys
                    // and read it in ourselves
                    File keys = new File("sam.keys");
                    if (keys.exists()) {
                        Properties opts = new Properties();
                        DataHelper.loadProps(opts, keys);
                        String s = opts.getProperty(_destFile);
                        if (s != null) {
                            dest = s;
                        } else {
                            dest = "TRANSIENT";
                            (new File(_destFile)).delete();
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Requesting new transient destination");
                        }
                    } else {
                        dest = "TRANSIENT";
                        (new File(_destFile)).delete();
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Requesting new transient destination");
                    }
                    if (isMaster) {
                        byte[] id = new byte[5];
                        _context.random().nextBytes(id);
                        _conOptions = "ID=" + Base32.encode(id);
                    }
                } else {
                    // we use the filename as the name in sam.keys
                    // and give it to the SAM server
                    dest = _destFile;
                }
                String req = "SESSION CREATE STYLE=STREAM DESTINATION=" + dest + " " + _conOptions + "\n";
                samOut.write(req.getBytes());
                samOut.flush();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Session create sent");
                boolean ok = _eventHandler.waitForSessionCreateReply();
                if (!ok) 
                    throw new IOException("Session create failed");
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Session create reply found: " + ok);

                req = "NAMING LOOKUP NAME=ME\n";
                samOut.write(req.getBytes());
                samOut.flush();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Naming lookup sent");
                String destination = _eventHandler.waitForNamingReply("ME");
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Naming lookup reply found: " + destination);
                if (destination == null) {
                    _log.error("No naming lookup reply found!");
                    return null;
                } else {
                    if (_log.shouldInfo())
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
        File f = new File(_destFile);
/*
        if (f.exists()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Destination file exists, not overwriting: " + _destFile);
            return false;
        }
*/
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(dest.getBytes());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("My destination written to " + _destFile);
        } catch (Exception e) {
            _log.error("Error writing to " + _destFile, e);
            return false;
        } finally {
            if(fos != null) try { fos.close(); } catch(IOException ioe) {}
        }
        return true;
    }
    
    private class Sink {
        private final String _connectionId; 
        private final String _remoteDestination;
        private volatile boolean _closed;
        private final long _started;
        private long _lastReceivedOn;
        private final OutputStream _out;
        
        public Sink(String conId, String remDest) throws IOException {
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
            _started = _context.clock().now();
        }
        
        public String getConnectionId() { return _connectionId; }
        public String getDestination() { return _remoteDestination; }
        
        public void closed() {
            if (_closed) return;
            _closed = true;
            long lifetime = _context.clock().now() - _started;
            _context.statManager().addRateData("sink." + _connectionId + ".lifetime", lifetime, lifetime);
            try { 
                _out.close();
            } catch (IOException ioe) {
                _log.info("Error closing", ioe);
            }
        }
        public void received(byte data[], int offset, int len) {
            if (_closed) return;
            try {
                _out.write(data, offset, len);
            } catch (IOException ioe) {
                _log.error("Error writing received data");
                closed();
                return;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received " + len + " on " + _connectionId + " after " + (_context.clock().now()-_lastReceivedOn)
                       + "ms with " + _remoteDestination.substring(0,6));
            
            _lastReceivedOn = _context.clock().now();
        }
    }
}
