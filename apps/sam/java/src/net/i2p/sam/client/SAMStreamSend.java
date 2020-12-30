package net.i2p.sam.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLSocket;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

/**
 * Swiss army knife tester.
 * Sends a file (datafile) to a peer (b64 dest in peerDestFile).
 *
 * Usage: SAMStreamSend [options] peerDestFile dataFile
 *
 * See apps/sam/doc/README-test.txt for info on test setup.
 * Sends data in one of 5 modes.
 * Optionally uses SSL.
 * Configurable SAM client version.
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
    private boolean _isV32;
    private String _v3ID;
    //private boolean _dead;
    /** Connection id (Integer) to peer (Flooder) */
    private final Map<String, Sender> _remotePeers;
    private static I2PSSLSocketFactory _sslSocketFactory;
    
    private static final int STREAM=0, DG=1, V1DG=2, RAW=3, V1RAW=4;
    private static final int PRIMARY=8;
    private static final String USAGE = "Usage: SAMStreamSend [-s] [-x] [-m mode] [-v version] [-b samHost] [-p samPort]\n" +
                                        "                     [-o opt=val] [-u user] [-w password] peerDestFile dataDir\n" +
                                        "       modes: stream: 0; datagram: 1; v1datagram: 2; raw: 3; v1raw: 4\n" +
                                        "              default is stream\n" +
                                        "       -s: use SSL\n" +
                                        "       -x: use primary session (forces -v 3.3)\n" +
                                        "       multiple -o session options are allowed";

    public static void main(String args[]) {
        Getopt g = new Getopt("SAM", args, "sxhb:m:o:p:u:v:w:");
        boolean isSSL = false;
        boolean isPrimary = false;
        int mode = STREAM;
        String version = "3.3";
        String host = "127.0.0.1";
        String port = "7656";
        String user = null;
        String password = null;
        String opts = "inbound.length=0 outbound.length=0";
        int c;
        while ((c = g.getopt()) != -1) {
          switch (c) {
            case 's':
                isSSL = true;
                break;

            case 'x':
                isPrimary = true;
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

            case 'o':
                opts = opts + ' ' + g.getOptarg();
                break;

            case 'p':
                port = g.getOptarg();
                break;

            case 'u':
                user = g.getOptarg();
                break;

            case 'w':
                password = g.getOptarg();
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
        if (isPrimary) {
            mode += PRIMARY;
            version = "3.3";
        }
        if ((user == null && password != null) ||
            (user != null && password == null)) {
            System.err.println("both user and password or neither");
            return;
        }
        if (user != null && password != null && VersionComparator.comp(version, "3.2") < 0) {
            System.err.println("user/password require 3.2");
            return;
        }
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SAMStreamSend sender = new SAMStreamSend(ctx, host, port,
                                                      args[startArgs], args[startArgs + 1]);
        sender.startup(version, isSSL, mode, user, password, opts);
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
    
    public void startup(String version, boolean isSSL, int mode, String user, String password, String sessionOpts) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Starting up");
        try {
            Socket sock = connect(isSSL);
            SAMEventHandler eventHandler = new SendEventHandler(_context);
            _reader = new SAMReader(_context, sock.getInputStream(), eventHandler);
            _reader.startReading();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Reader created");
            OutputStream out = sock.getOutputStream();
            String ourDest = handshake(out, version, true, eventHandler, mode, user, password, sessionOpts);
            if (mode >= PRIMARY)
                mode -= PRIMARY;
            if (ourDest == null)
                throw new IOException("handshake failed");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Handshake complete.  we are " + ourDest);
            if (_isV3 && mode == STREAM) {
                Socket sock2 = connect(isSSL);
                eventHandler = new SendEventHandler(_context);
                _reader2 = new SAMReader(_context, sock2.getInputStream(), eventHandler);
                _reader2.startReading();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Reader2 created");
                out = sock2.getOutputStream();
                String ok = handshake(out, version, false, eventHandler, mode, user, password, "");
                if (ok == null)
                    throw new IOException("2nd handshake failed");
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Handshake2 complete.");
            }
            if (mode == DG || mode == RAW)
                out = null;
            send(out, eventHandler, mode);
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
    
    private Socket connect(boolean isSSL) throws IOException {
        int port = Integer.parseInt(_samPort);
        if (!isSSL)
            return new Socket(_samHost, port);
        synchronized(SAMStreamSink.class) {
            if (_sslSocketFactory == null) {
                try {
                    _sslSocketFactory = new I2PSSLSocketFactory(
                        _context, true, "certificates/sam");
                } catch (GeneralSecurityException gse) {
                    throw new IOException("SSL error", gse);
                }
            }
        }
        SSLSocket sock = (SSLSocket) _sslSocketFactory.createSocket(_samHost, port);
        I2PSSLSocketFactory.verifyHostname(_context, sock, _samHost);
        return sock;
    }
    
    /**
     * @param isPrimary is this the control socket
     * @return our b64 dest or null
     */
    private String handshake(OutputStream samOut, String version, boolean isPrimary,
                             SAMEventHandler eventHandler, int mode, String user, String password,
                             String opts) {
        synchronized (samOut) {
            try {
                if (user != null && password != null)
                    samOut.write(("HELLO VERSION MIN=1.0 MAX=" + version + " USER=\"" + user.replace("\"", "\\\"") +
                                  "\" PASSWORD=\"" + password.replace("\"", "\\\"") + "\"\n").getBytes("UTF-8"));
                else
                    samOut.write(("HELLO VERSION MIN=1.0 MAX=" + version + '\n').getBytes("UTF-8"));
                samOut.flush();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Hello sent");
                String hisVersion = eventHandler.waitForHelloReply();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Hello reply found: " + hisVersion);
                if (hisVersion == null) 
                    throw new IOException("Hello failed");
                if (!isPrimary)
                    return "OK";
                _isV3 = VersionComparator.comp(hisVersion, "3") >= 0;
                if (_isV3) {
                    _isV32 = VersionComparator.comp(hisVersion, "3.2") >= 0;
                    byte[] id = new byte[5];
                    _context.random().nextBytes(id);
                    _v3ID = Base32.encode(id);
                    if (_isV32)
                        _v3ID = "xx€€xx" + _v3ID;
                    _conOptions = "ID=" + _v3ID;
                }
                boolean primaryMode;  // are we using v3.3 primary session
                String command;
                if (mode >= PRIMARY) {
                    primaryMode = true;
                    command = "ADD";
                    mode -= PRIMARY;
                } else {
                    primaryMode = false;
                    command = "CREATE DESTINATION=TRANSIENT";
                }
                String style;
                if (mode == STREAM)
                    style = "STREAM";
                else if (mode == DG || mode == V1DG)
                    style = "DATAGRAM";
                else   // RAW or V1RAW
                    style = "RAW";

                if (primaryMode) {
                    if (mode == V1DG || mode == V1RAW)
                        throw new IllegalArgumentException("v1 dg/raw incompatible with primary session");
                    String req = "SESSION CREATE DESTINATION=TRANSIENT STYLE=PRIMARY ID=primarySend " + opts + '\n';
                    samOut.write(req.getBytes("UTF-8"));
                    samOut.flush();
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("SESSION CREATE STYLE=PRIMARY sent");
                    boolean ok = eventHandler.waitForSessionCreateReply();
                    if (!ok) 
                        throw new IOException("SESSION CREATE STYLE=PRIMARY failed");
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("SESSION CREATE STYLE=PRIMARY reply found: " + ok);
                    // PORT required even if we aren't listening for this test
                    if (mode != STREAM)
                        opts += " PORT=9999";
                }
                String req = "SESSION " + command + " STYLE=" + style + ' ' + _conOptions + ' ' + opts + '\n';
                samOut.write(req.getBytes("UTF-8"));
                samOut.flush();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("SESSION " + command + " sent");
                boolean ok;
                if (primaryMode)
                    ok = eventHandler.waitForSessionAddReply();
                else
                    ok = eventHandler.waitForSessionCreateReply();
                if (!ok) 
                    throw new IOException("SESSION " + command + " failed");
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("SESSION " + command + " reply found: " + ok);

                if (primaryMode) {
                    // do a bunch more
                    req = "SESSION ADD STYLE=STREAM FROM_PORT=99 ID=stream99\n";
                    samOut.write(req.getBytes("UTF-8"));
                    req = "SESSION ADD STYLE=STREAM FROM_PORT=98 ID=stream98\n";
                    samOut.write(req.getBytes("UTF-8"));
                    req = "SESSION REMOVE ID=stream99\n";
                    samOut.write(req.getBytes("UTF-8"));
                    samOut.flush();
                }
                req = "NAMING LOOKUP NAME=ME\n";
                samOut.write(req.getBytes("UTF-8"));
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
            } catch (IOException e) {
                _log.error("Error handshaking", e);
                return null;
            }
        }
    }
    
    private void send(OutputStream samOut, SAMEventHandler eventHandler, int mode) throws IOException {
        Sender sender = new Sender(samOut, eventHandler, mode);
        boolean ok = sender.openConnection();
        if (ok) {
            I2PAppThread t = new I2PAppThread(sender, "Sender");
            t.start();
        } else {
            throw new IOException("Sender failed to connect");
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
        private final int _mode;
        private final DatagramSocket _dgSock;
        private final InetSocketAddress _dgSAM;
        
        public Sender(OutputStream samOut, SAMEventHandler eventHandler, int mode) throws IOException {
            _samOut = samOut;
            _eventHandler = eventHandler;
            _mode = mode;
            if (mode == DG || mode == RAW) {
                // samOut will be null
                _dgSock = new DatagramSocket();
                _dgSAM = new InetSocketAddress(_samHost, 7655);
            } else {
                _dgSock = null;
                _dgSAM = null;
            }
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

                _remoteDestination = DataHelper.getUTF8(dest, 0, read);

                _context.statManager().createRateStat("send." + _connectionId + ".totalSent", "Data size sent", "swarm", new long[] { 30*1000, 60*1000, 5*60*1000 });
                _context.statManager().createRateStat("send." + _connectionId + ".started", "When we start", "swarm", new long[] { 5*60*1000 });
                _context.statManager().createRateStat("send." + _connectionId + ".lifetime", "How long we talk to a peer", "swarm", new long[] { 5*60*1000 });
                
                if (_mode == STREAM) {
                    StringBuilder buf = new StringBuilder(1024);
                    buf.append("STREAM CONNECT ID=").append(_connectionId).append(" DESTINATION=").append(_remoteDestination);
                    // not supported until 3.2 but 3.0-3.1 will ignore
                    if (_isV3)
                        buf.append(" FROM_PORT=1234 TO_PORT=5678");
                    buf.append('\n');
                    byte[] msg = DataHelper.getUTF8(buf.toString());
                    synchronized (_samOut) {
                        _samOut.write(msg);
                        _samOut.flush();
                    }
                    _log.debug("STREAM CONNECT sent, waiting for STREAM STATUS...");
                    boolean ok = _eventHandler.waitForStreamStatusReply();
                    if (!ok)
                        throw new IOException("STREAM CONNECT failed");
                }

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
            byte data[] = new byte[8192];
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
                        
                        if (_samOut != null) {
                            synchronized (_samOut) {
                                if (!_isV3 || _mode == V1DG || _mode == V1RAW) {
                                    String m;
                                    if (_mode == STREAM) {
                                        m = "STREAM SEND ID=" + _connectionId + " SIZE=" + read + "\n";
                                    } else if (_mode == V1DG) {
                                        m = "DATAGRAM SEND DESTINATION=" + _remoteDestination + " SIZE=" + read + "\n";
                                    } else if (_mode == V1RAW) {
                                        m = "RAW SEND DESTINATION=" + _remoteDestination + " SIZE=" + read + "\n";
                                    } else {
                                        throw new IOException("unsupported mode " + _mode);
                                    }
                                    byte msg[] = DataHelper.getUTF8(m);
                                    _samOut.write(msg);
                                }
                                _samOut.write(data, 0, read);
                                _samOut.flush();
                            }
                        } else {
                            // real datagrams
                            ByteArrayOutputStream baos = new ByteArrayOutputStream(read + 1024);
                            baos.write(DataHelper.getUTF8("3.0 "));
                            baos.write(DataHelper.getUTF8(_v3ID));
                            baos.write((byte) ' ');
                            baos.write(DataHelper.getUTF8(_remoteDestination));
                            if (_isV32) {
                                // only set TO_PORT to test session setting of FROM_PORT
                                if (_mode == RAW)
                                    baos.write(DataHelper.getUTF8(" PROTOCOL=123 TO_PORT=5678"));
                                else
                                    baos.write(DataHelper.getUTF8(" TO_PORT=5678"));
                                baos.write(DataHelper.getUTF8(" SEND_TAGS=19 TAG_THRESHOLD=13 EXPIRES=33 SEND_LEASESET=true"));
                            }
                            baos.write((byte) '\n');
                            baos.write(data, 0, read);
                            byte[] pkt = baos.toByteArray();
                            DatagramPacket p = new DatagramPacket(pkt, pkt.length, _dgSAM);
                            _dgSock.send(p);
                            try { Thread.sleep(25); } catch (InterruptedException ie) {}
                        }
                        
                        _totalSent += read;
                        _context.statManager().addRateData("send." + _connectionId + ".totalSent", _totalSent, 0);
                    }
                } catch (IOException ioe) {
                    _log.error("Error sending", ioe);
                    break;
                }
            }
            
            if (_samOut != null) {
                if (_isV3) {
                    try {
                        _samOut.close();
                    } catch (IOException ioe) {
                        _log.info("Error closing", ioe);
                    }
                } else {
                    try {
                        byte msg[] = ("STREAM CLOSE ID=" + _connectionId + "\n").getBytes("UTF-8");
                        synchronized (_samOut) {
                            _samOut.write(msg);
                            _samOut.flush();
                            // we can't close this yet, we will lose data
                            //_samOut.close();
                        }
                    } catch (IOException ioe) {
                        _log.info("Error closing", ioe);
                    }
                }
            } else if (_dgSock != null) { 
                _dgSock.close();
            }
            
            closed();
            // stop the reader, since we're only doing this once for testing
            // you wouldn't do this in a real application
            // closing the primary socket too fast will kill the data socket flushing through
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ie) {}
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Runner exiting");
            if (toSend != _totalSent)
                _log.error("Only sent " + _totalSent + " of " + toSend + " bytes");
            if (_reader2 != null)
                _reader2.stopReading();
            _reader.stopReading();
        }
    }
}
