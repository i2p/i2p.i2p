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

import gnu.getopt.Getopt;

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
    private SAMReader _reader, _reader2;
    private boolean _isV3;
    private String _v3ID;
    //private boolean _dead;
    /** Connection id (Integer) to peer (Flooder) */
    private final Map<String, Sink> _remotePeers;
    
    private static final int STREAM=0, DG=1, V1DG=2, RAW=3, V1RAW=4;
    private static final String USAGE = "Usage: SAMStreamSink [-s] [-m mode] [-v version] [-b samHost] [-p samPort] myDestFile sinkDir\n" +
                                        "       modes: stream: 0; datagram: 1; v1datagram: 2; raw: 3; v1raw: 4\n" +
                                        "       -s: use SSL";

    public static void main(String args[]) {
        Getopt g = new Getopt("SAM", args, "sb:m:p:v:");
        boolean isSSL = false;
        int mode = STREAM;
        String version = "1.0";
        String host = "127.0.0.1";
        String port = "7656";
        int c;
        while ((c = g.getopt()) != -1) {
          switch (c) {
            case 's':
                isSSL = true;
                break;

            case 'm':
                mode = Integer.parseInt(g.getOptarg());
                if (mode < 0 || mode > V1RAW) {
                    System.err.println(USAGE);
                    return;
                }
                break;

            case 'v':
                version = g.getOptarg();
                break;

            case 'b':
                host = g.getOptarg();
                break;

            case 'p':
                port = g.getOptarg();
                break;

            case 'h':
            case '?':
            case ':':
            default:
                System.err.println(USAGE);
                return;
          }  // switch
        } // while

        int startArgs = g.getOptind();
        if (args.length - startArgs != 2) {
            System.err.println(USAGE);
            return;
        }
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SAMStreamSink sink = new SAMStreamSink(ctx, host, port,
                                                    args[startArgs], args[startArgs + 1]);
        sink.startup(version, isSSL, mode);
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
        _remotePeers = new HashMap<String, Sink>();
    }
    
    public void startup(String version, boolean isSSL, int mode) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Starting up");
        try {
            Socket sock = connect(isSSL);
            OutputStream out = sock.getOutputStream();
            SAMEventHandler eventHandler = new SinkEventHandler(_context, out);
            _reader = new SAMReader(_context, sock.getInputStream(), eventHandler);
            _reader.startReading();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Reader created");
            String ourDest = handshake(out, version, true, eventHandler, mode);
            if (ourDest == null)
                throw new IOException("handshake failed");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Handshake complete.  we are " + ourDest);
            if (_isV3 && mode != V1DG && mode != V1RAW) {
                Socket sock2 = connect(isSSL);
                out = sock2.getOutputStream();
                eventHandler = new SinkEventHandler2(_context, sock2.getInputStream(), out);
                _reader2 = new SAMReader(_context, sock2.getInputStream(), eventHandler);
                _reader2.startReading();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Reader2 created");
                String ok = handshake(out, version, false, eventHandler, mode);
                if (ok == null)
                    throw new IOException("2nd handshake failed");
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Handshake2 complete.");
            }
            writeDest(ourDest);
        } catch (IOException e) {
            _log.error("Unable to connect to SAM at " + _samHost + ":" + _samPort, e);
        }
    }
    
    private class SinkEventHandler extends SAMEventHandler {

        protected final OutputStream _out;

        public SinkEventHandler(I2PAppContext ctx, OutputStream out) {
            super(ctx);
            _out = out;
        }

        @Override
        public void streamClosedReceived(String result, String id, String message) {
            Sink sink;
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
            Sink sink;
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

        @Override
        public void pingReceived(String data) {
            if (_log.shouldInfo())
                _log.info("Got PING " + data + ", sending PONG " + data);
            synchronized (_out) {
                try {
                    _out.write(("PONG " + data + '\n').getBytes());
                    _out.flush();
                } catch (IOException ioe) {
                    _log.error("PONG fail", ioe);
                }
            }
        }

        @Override
        public void datagramReceived(String dest, byte[] data, int offset, int length, int fromPort, int toPort) {
            // just get the first
            Sink sink;
            synchronized (_remotePeers) {
                if (_remotePeers.isEmpty()) {
                    _log.error("not connected but we received datagram " + length + "?");
                    return;
                }
                sink = _remotePeers.values().iterator().next();
            }
            sink.received(data, offset, length);
        }

        @Override
        public void rawReceived(byte[] data, int offset, int length, int fromPort, int toPort, int protocol) {
            // just get the first
            Sink sink;
            synchronized (_remotePeers) {
                if (_remotePeers.isEmpty()) {
                    _log.error("not connected but we received raw " + length + "?");
                    return;
                }
                sink = _remotePeers.values().iterator().next();
            }
            sink.received(data, offset, length);
        }
    }

    private class SinkEventHandler2 extends SinkEventHandler {

        private final InputStream _in;

        public SinkEventHandler2(I2PAppContext ctx, InputStream in, OutputStream out) {
            super(ctx, out);
            _in = in;
        }

        @Override
        public void streamStatusReceived(String result, String id, String message) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("got STREAM STATUS, result=" + result);
            super.streamStatusReceived(result, id, message);
            Sink sink = null;
            try {
                String dest = "TODO_if_not_silent";
                sink = new Sink(_v3ID, dest);
                synchronized (_remotePeers) {
                    _remotePeers.put(_v3ID, sink);
                }
            } catch (IOException ioe) {
                _log.error("Error creating a new sink", ioe);
                try { _in.close(); } catch (IOException ioe2) {}
                if (sink != null)
                    sink.closed();
                return;
            }
            // inline so the reader doesn't grab the data
            try {
                boolean gotDest = false;
                byte[] dest = new byte[1024];
                int dlen = 0;
                byte buf[] = new byte[4096];
                int len;
                while((len = _in.read(buf)) >= 0) {
                    if (!gotDest) {
                        // eat the dest line
                        for (int i = 0; i < len; i++) {
                            byte b = buf[i];
                            if (b == (byte) '\n') {
                                gotDest = true;
                                if (_log.shouldInfo()) {
                                    try {
                                        _log.info("Got incoming accept from: \"" + new String(dest, 0, dlen, "ISO-8859-1") + '"');
                                    } catch (IOException uee) {}
                                }
                                // feed any remaining to the sink
                                i++;
                                if (i < len)
                                    sink.received(buf, i, len - i);
                                break;
                            } else {
                                if (dlen < dest.length) {
                                    dest[dlen++] = b;
                                } else if (dlen == dest.length) {
                                    dlen++;
                                    _log.error("first line overflow on accept");
                                }
                            }
                        }
                    } else {
                        sink.received(buf, 0, len);
                    }
                }
                sink.closed();
            } catch (IOException ioe) {
                _log.error("Error reading", ioe);
            } finally {
                try { _in.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    private Socket connect(boolean isSSL) throws IOException {
        return new Socket(_samHost, Integer.parseInt(_samPort));
    }
    
    /** @return our b64 dest or null */
    private String handshake(OutputStream samOut, String version, boolean isMaster, SAMEventHandler eventHandler, int mode) {
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
                if (!isMaster) {
                    // only for v3
                    //String req = "STREAM ACCEPT SILENT=true ID=" + _v3ID + "\n";
                    // TO_PORT not supported until 3.2 but 3.0-3.1 will ignore
                    String req = "STREAM ACCEPT SILENT=false TO_PORT=5678 ID=" + _v3ID + "\n";
                    samOut.write(req.getBytes());
                    samOut.flush();
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("STREAM ACCEPT sent");
                    // docs were wrong, we do not get a STREAM STATUS if SILENT=true
                    //boolean ok = eventHandler.waitForStreamStatusReply();
                    //if (!ok) 
                    //    throw new IOException("Stream status failed");
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("got STREAM STATUS, awaiting connection");
                    return "OK";
                }
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
                        _v3ID = Base32.encode(id);
                        _conOptions = "ID=" + _v3ID;
                    }
                } else {
                    // we use the filename as the name in sam.keys
                    // and give it to the SAM server
                    dest = _destFile;
                }
                String style;
                if (mode == STREAM)
                    style = "STREAM";
                else if (mode == DG || mode == V1DG)
                    style = "DATAGRAM";
                else
                    style = "RAW";
                String req = "SESSION CREATE STYLE=" + style + " DESTINATION=" + dest + " " + _conOptions + "\n";
                samOut.write(req.getBytes());
                samOut.flush();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Session create sent");
                if (mode == STREAM) {
                    boolean ok = eventHandler.waitForSessionCreateReply();
                    if (!ok) 
                        throw new IOException("Session create failed");
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Session create reply found: " + ok);
                }
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
                }
                if (_log.shouldInfo())
                    _log.info(_destFile + " is located at " + destination);
                if (mode != STREAM) {
                    // fake it so the sink starts
                    eventHandler.streamConnectedReceived(destination, "FAKE");
                }
                return destination;
            } catch (IOException e) {
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
        } catch (IOException e) {
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
