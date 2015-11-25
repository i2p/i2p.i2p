package net.i2p.sam.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

/**
 * Send a file to a peer
 *
 * Usage: SAMStreamSend samHost samPort peerDestFile dataFile
 *
 */
public class SAMStreamSend {
    private final I2PAppContext _context;
    private final Log _log;
    private final String _samHost;
    private final String _samPort;
    private final String _destFile;
    private final String _dataFile;
    private String _conOptions;
    private SAMReader _reader, _reader2;
    private boolean _isV3;
    private String _v3ID;
    //private boolean _dead;
    /** Connection id (Integer) to peer (Flooder) */
    private final Map<String, Sender> _remotePeers;
    
    public static void main(String args[]) {
        if (args.length < 4) {
            System.err.println("Usage: SAMStreamSend samHost samPort peerDestFile dataFile [version]");
            return;
        }
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        //String files[] = new String[args.length - 3];
        SAMStreamSend sender = new SAMStreamSend(ctx, args[0], args[1], args[2], args[3]);
        String version = (args.length >= 5) ? args[4] : "1.0";
        sender.startup(version);
    }
    
    public SAMStreamSend(I2PAppContext ctx, String samHost, String samPort, String destFile, String dataFile) {
        _context = ctx;
        _log = ctx.logManager().getLog(SAMStreamSend.class);
        //_dead = false;
        _samHost = samHost;
        _samPort = samPort;
        _destFile = destFile;
        _dataFile = dataFile;
        _conOptions = "";
        _remotePeers = new HashMap<String, Sender>();
    }
    
    public void startup(String version) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Starting up");
        try {
            Socket sock = connect();
            SAMEventHandler eventHandler = new SendEventHandler(_context);
            _reader = new SAMReader(_context, sock.getInputStream(), eventHandler);
            _reader.startReading();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Reader created");
            OutputStream out = sock.getOutputStream();
            String ourDest = handshake(out, version, true, eventHandler);
            if (ourDest == null)
                throw new IOException("handshake failed");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Handshake complete.  we are " + ourDest);
            if (_isV3) {
                Socket sock2 = connect();
                eventHandler = new SendEventHandler(_context);
                _reader2 = new SAMReader(_context, sock2.getInputStream(), eventHandler);
                _reader2.startReading();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Reader2 created");
                out = sock2.getOutputStream();
                String ok = handshake(out, version, false, eventHandler);
                if (ok == null)
                    throw new IOException("2nd handshake failed");
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Handshake2 complete.");
            }
            if (ourDest != null) {
                send(out, eventHandler);
            }
        } catch (IOException e) {
            _log.error("Unable to connect to SAM at " + _samHost + ":" + _samPort, e);
            if (_reader != null)
                _reader.stopReading();
            if (_reader2 != null)
                _reader2.stopReading();
        }
    }
    
    private class SendEventHandler extends SAMEventHandler {
        public SendEventHandler(I2PAppContext ctx) { super(ctx); }

        @Override
        public void streamClosedReceived(String result, String id, String message) {
            Sender sender = null;
            synchronized (_remotePeers) {
                sender = _remotePeers.remove(id);
            }
            if (sender != null) {
                sender.closed();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Connection " + sender.getConnectionId() + " closed to " + sender.getDestination());
            } else {
                _log.error("not connected to " + id + " but we were just closed?");
            }
        }
    }
    
    private Socket connect() throws IOException {
        return new Socket(_samHost, Integer.parseInt(_samPort));
    }
    
    /** @return our b64 dest or null */
    private String handshake(OutputStream samOut, String version, boolean isMaster, SAMEventHandler eventHandler) {
        synchronized (samOut) {
            try {
                samOut.write(("HELLO VERSION MIN=1.0 MAX=" + version + '\n').getBytes());
                samOut.flush();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Hello sent");
                String hisVersion = eventHandler.waitForHelloReply();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Hello reply found: " + hisVersion);
                if (hisVersion == null) 
                    throw new IOException("Hello failed");
                if (!isMaster)
                    return "OK";
                _isV3 = VersionComparator.comp(hisVersion, "3") >= 0;
                if (_isV3) {
                    byte[] id = new byte[5];
                    _context.random().nextBytes(id);
                    _v3ID = Base32.encode(id);
                    _conOptions = "ID=" + _v3ID;
                }
                String req = "SESSION CREATE STYLE=STREAM DESTINATION=TRANSIENT " + _conOptions + "\n";
                samOut.write(req.getBytes());
                samOut.flush();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Session create sent");
                boolean ok = eventHandler.waitForSessionCreateReply();
                if (!ok) 
                    throw new IOException("Session create failed");
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Session create reply found: " + ok);

                req = "NAMING LOOKUP NAME=ME\n";
                samOut.write(req.getBytes());
                samOut.flush();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Naming lookup sent");
                String destination = eventHandler.waitForNamingReply("ME");
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Naming lookup reply found: " + destination);
                if (destination == null) {
                    _log.error("No naming lookup reply found!");
                    return null;
                } else {
                    _log.info("We are " + destination);
                }
                return destination;
            } catch (Exception e) {
                _log.error("Error handshaking", e);
                return null;
            }
        }
    }
    
    private void send(OutputStream samOut, SAMEventHandler eventHandler) {
        Sender sender = new Sender(samOut, eventHandler);
        boolean ok = sender.openConnection();
        if (ok) {
            I2PAppThread t = new I2PAppThread(sender, "Sender");
            t.start();
        }
    }
    
    private class Sender implements Runnable {
        private final String _connectionId; 
        private String _remoteDestination;
        private InputStream _in;
        private volatile boolean _closed;
        private long _started;
        private long _totalSent;
        private final OutputStream _samOut;
        private final SAMEventHandler _eventHandler;
        
        public Sender(OutputStream samOut, SAMEventHandler eventHandler) {
            _samOut = samOut;
            _eventHandler = eventHandler;
            synchronized (_remotePeers) {
                if (_v3ID != null)
                    _connectionId = _v3ID;
                else
                    _connectionId = Integer.toString(_remotePeers.size() + 1);
                _remotePeers.put(_connectionId, Sender.this);
            }
        }
        
        public boolean openConnection() {
            FileInputStream fin = null;
            try {
                fin = new FileInputStream(_destFile);
                byte dest[] = new byte[1024];
                int read = DataHelper.read(fin, dest);

                _remoteDestination = new String(dest, 0, read);

                _context.statManager().createRateStat("send." + _connectionId + ".totalSent", "Data size sent", "swarm", new long[] { 30*1000, 60*1000, 5*60*1000 });
                _context.statManager().createRateStat("send." + _connectionId + ".started", "When we start", "swarm", new long[] { 5*60*1000 });
                _context.statManager().createRateStat("send." + _connectionId + ".lifetime", "How long we talk to a peer", "swarm", new long[] { 5*60*1000 });
                
                byte msg[] = ("STREAM CONNECT ID=" + _connectionId + " DESTINATION=" + _remoteDestination + "\n").getBytes();
                synchronized (_samOut) {
                    _samOut.write(msg);
                    _samOut.flush();
                }
                _log.debug("STREAM CONNECT sent, waiting for STREAM STATUS...");
                boolean ok = _eventHandler.waitForStreamStatusReply();
                if (!ok)
                    throw new IOException("STREAM CONNECT failed");

                _in = new FileInputStream(_dataFile);
                return true;
            } catch (IOException ioe) {
                _log.error("Unable to connect", ioe);
                return false;
            } finally {
                if(fin != null) {
                    try {
                        fin.close();
                    } catch(IOException ioe) {}
                }
            }
        }
        
        public String getConnectionId() { return _connectionId; }
        public String getDestination() { return _remoteDestination; }
        
        public void closed() {
            if (_closed) return;
            _closed = true;
            long lifetime = _context.clock().now() - _started;
            _context.statManager().addRateData("send." + _connectionId + ".lifetime", lifetime, lifetime);
            try { _in.close(); } catch (IOException ioe) {}
        }
        
        public void run() {
            _started = _context.clock().now();
            _context.statManager().addRateData("send." + _connectionId + ".started", 1, 0);
            final long toSend = (new File(_dataFile)).length();
            byte data[] = new byte[1024];
            long lastSend = _context.clock().now();
            while (!_closed) {
                try {
                    int read = _in.read(data);
                    long now = _context.clock().now();
                    if (read == -1) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("EOF from the data for " + _connectionId + " after " + (now-lastSend));
                        break;
                    } else if (read > 0) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Sending " + read + " on " + _connectionId + " after " + (now-lastSend));
                        lastSend = now;
                        
                        synchronized (_samOut) {
                            if (!_isV3) {
                                byte msg[] = ("STREAM SEND ID=" + _connectionId + " SIZE=" + read + "\n").getBytes();
                                _samOut.write(msg);
                            }
                            _samOut.write(data, 0, read);
                            _samOut.flush();
                        }
                        
                        _totalSent += read;
                        _context.statManager().addRateData("send." + _connectionId + ".totalSent", _totalSent, 0);
                    }
                } catch (IOException ioe) {
                    _log.error("Error sending", ioe);
                    break;
                }
            }
            
            if (_isV3) {
                try {
                    _samOut.close();
                } catch (IOException ioe) {
                    _log.info("Error closing", ioe);
                }
            } else {
                byte msg[] = ("STREAM CLOSE ID=" + _connectionId + "\n").getBytes();
                try {
                    synchronized (_samOut) {
                        _samOut.write(msg);
                        _samOut.flush();
                        _samOut.close();
                    }
                } catch (IOException ioe) {
                    _log.info("Error closing", ioe);
                }
            }
            
            closed();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Runner exiting");
            if (toSend != _totalSent)
                _log.error("Only sent " + _totalSent + " of " + toSend + " bytes");
            if (_reader2 != null)
                _reader2.stopReading();
            // stop the reader, since we're only doing this once for testing
            // you wouldn't do this in a real application
            _reader.stopReading();
        }
    }
}
