package net.i2p.sam.client;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Send a file to a peer
 *
 * Usage: SAMStreamSend samHost samPort peerDestFile dataFile
 *
 */
public class SAMStreamSend {
    private I2PAppContext _context;
    private Log _log;
    private String _samHost;
    private String _samPort;
    private String _destFile;
    private String _dataFile;
    private String _conOptions;
    private Socket _samSocket;
    private OutputStream _samOut;
    private InputStream _samIn;
    private SAMReader _reader;
    //private boolean _dead;
    private SAMEventHandler _eventHandler;
    /** Connection id (Integer) to peer (Flooder) */
    private Map<Integer, Sender> _remotePeers;
    
    public static void main(String args[]) {
        if (args.length < 4) {
            System.err.println("Usage: SAMStreamSend samHost samPort peerDestFile dataFile");
            return;
        }
        I2PAppContext ctx = new I2PAppContext();
        //String files[] = new String[args.length - 3];
        SAMStreamSend sender = new SAMStreamSend(ctx, args[0], args[1], args[2], args[3]);
        sender.startup();
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
        _eventHandler = new SendEventHandler(_context);
        _remotePeers = new HashMap<Integer,Sender>();
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
                send();
            }
        }
    }
    
    private class SendEventHandler extends SAMEventHandler {
        public SendEventHandler(I2PAppContext ctx) { super(ctx); }
        public void streamClosedReceived(String result, int id, String message) {
            Sender sender = null;
            synchronized (_remotePeers) {
                sender = (Sender)_remotePeers.remove(new Integer(id));
            }
            if (sender != null) {
                sender.closed();
                _log.debug("Connection " + sender.getConnectionId() + " closed to " + sender.getDestination());
            } else {
                _log.error("wtf, not connected to " + id + " but we were just closed?");
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
                String req = "SESSION CREATE STYLE=STREAM DESTINATION=TRANSIENT " + _conOptions + "\n";
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
                    _log.info("We are " + destination);
                }
                return destination;
            } catch (Exception e) {
                _log.error("Error handshaking", e);
                return null;
            }
        }
    }
    
    private void send() {
        Sender sender = new Sender();
        boolean ok = sender.openConnection();
        if (ok) {
            I2PAppThread t = new I2PAppThread(sender, "Sender");
            t.start();
        }
    }
    
    private class Sender implements Runnable {
        private int _connectionId; 
        private String _remoteDestination;
        private InputStream _in;
        private boolean _closed;
        private long _started;
        private long _totalSent;
        
        public Sender() {
            _closed = false;
        }
        
        public boolean openConnection() {
            try {
                FileInputStream fin = new FileInputStream(_destFile);
                byte dest[] = new byte[1024];
                int read = DataHelper.read(fin, dest);

                _remoteDestination = new String(dest, 0, read);
                synchronized (_remotePeers) {
                    _connectionId = _remotePeers.size() + 1;
                    _remotePeers.put(new Integer(_connectionId), Sender.this);
                }

                _context.statManager().createRateStat("send." + _connectionId + ".totalSent", "Data size sent", "swarm", new long[] { 30*1000, 60*1000, 5*60*1000 });
                _context.statManager().createRateStat("send." + _connectionId + ".started", "When we start", "swarm", new long[] { 5*60*1000 });
                _context.statManager().createRateStat("send." + _connectionId + ".lifetime", "How long we talk to a peer", "swarm", new long[] { 5*60*1000 });
                
                byte msg[] = ("STREAM CONNECT ID=" + _connectionId + " DESTINATION=" + _remoteDestination + "\n").getBytes();
                synchronized (_samOut) {
                    _samOut.write(msg);
                    _samOut.flush();
                }

                _in = new FileInputStream(_dataFile);
                return true;
            } catch (IOException ioe) {
                _log.error("Unable to connect", ioe);
                return false;
            }
        }
        
        public int getConnectionId() { return _connectionId; }
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
            byte data[] = new byte[1024];
            long lastSend = _context.clock().now();
            while (!_closed) {
                try {
                    int read = _in.read(data);
                    long now = _context.clock().now();
                    if (read == -1) {
                        _log.debug("EOF from the data for " + _connectionId + " after " + (now-lastSend));
                        break;
                    } else if (read > 0) {
                        _log.debug("Sending " + read + " on " + _connectionId + " after " + (now-lastSend));
                        lastSend = now;
                        
                        byte msg[] = ("STREAM SEND ID=" + _connectionId + " SIZE=" + read + "\n").getBytes();
                        synchronized (_samOut) {
                            _samOut.write(msg);
                            _samOut.write(data, 0, read);
                            _samOut.flush();
                        }
                        
                        _totalSent += read;
                        _context.statManager().addRateData("send." + _connectionId + ".totalSent", _totalSent, 0);
                    }
                } catch (IOException ioe) {
                    _log.error("Error sending", ioe);
                }
            }
            
            byte msg[] = ("STREAM CLOSE ID=" + _connectionId + "\n").getBytes();
            try {
                synchronized (_samOut) {
                    _samOut.write(msg);
                    _samOut.flush();
                }
            } catch (IOException ioe) {
                _log.error("Error closing", ioe);
            }
            
            closed();
        }
    }
}