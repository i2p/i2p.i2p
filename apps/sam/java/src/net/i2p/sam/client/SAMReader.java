package net.i2p.sam.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Read from a socket, producing events for any SAM message read
 *
 */
public class SAMReader {
    private final Log _log;
    private final InputStream _inRaw;
    private final SAMClientEventListener _listener;
    private volatile boolean _live;
    private Thread _thread;
    private static final AtomicInteger _count = new AtomicInteger();
    
    public SAMReader(I2PAppContext context, InputStream samIn, SAMClientEventListener listener) {
        _log = context.logManager().getLog(SAMReader.class);
        _inRaw = samIn;
        _listener = listener;
    }
    
    public synchronized void startReading() {
        if (_live)
            throw new IllegalStateException();
        _live = true;
        I2PAppThread t = new I2PAppThread(new Runner(), "SAM reader " + _count.incrementAndGet());
        t.start();
        _thread = t;
    }

    public synchronized void stopReading() {
        _live = false;
        if (_thread != null) {
            _thread.interrupt();
            _thread = null;
            try { _inRaw.close(); } catch (IOException ioe) {}
        }
    }
    
    /**
     * Async event notification interface for SAM clients
     *
     */
    public interface SAMClientEventListener {
        public static final String SESSION_STATUS_OK = "OK";
        public static final String SESSION_STATUS_DUPLICATE_DEST = "DUPLICATE_DEST";
        public static final String SESSION_STATUS_I2P_ERROR = "I2P_ERROR";
        public static final String SESSION_STATUS_INVALID_KEY = "INVALID_KEY";
        
        public static final String STREAM_STATUS_OK = "OK";
        public static final String STREAM_STATUS_CANT_REACH_PEER = "CANT_REACH_PEER";
        public static final String STREAM_STATUS_I2P_ERROR = "I2P_ERROR";
        public static final String STREAM_STATUS_INVALID_KEY = "INVALID_KEY";
        public static final String STREAM_STATUS_TIMEOUT = "TIMEOUT";
        
        public static final String STREAM_CLOSED_OK = "OK";
        public static final String STREAM_CLOSED_CANT_REACH_PEER = "CANT_REACH_PEER";
        public static final String STREAM_CLOSED_I2P_ERROR = "I2P_ERROR";
        public static final String STREAM_CLOSED_PEER_NOT_FOUND = "PEER_NOT_FOUND";
        public static final String STREAM_CLOSED_TIMEOUT = "CLOSED";
        
        public static final String NAMING_REPLY_OK = "OK";
        public static final String NAMING_REPLY_INVALID_KEY = "INVALID_KEY";
        public static final String NAMING_REPLY_KEY_NOT_FOUND = "KEY_NOT_FOUND";
        
        public void helloReplyReceived(boolean ok, String version);
        public void sessionStatusReceived(String result, String destination, String message);
        public void streamStatusReceived(String result, String id, String message);
        public void streamConnectedReceived(String remoteDestination, String id);
        public void streamClosedReceived(String result, String id, String message);
        public void streamDataReceived(String id, byte data[], int offset, int length);
        public void namingReplyReceived(String name, String result, String value, String message);
        public void destReplyReceived(String publicKey, String privateKey);
        public void datagramReceived(String dest, byte[] data, int offset, int length, int fromPort, int toPort);
        public void rawReceived(byte[] data, int offset, int length, int fromPort, int toPort, int protocol);
        public void pingReceived(String data);
        public void pongReceived(String data);
        
        public void unknownMessageReceived(String major, String minor, Properties params);
    }
    
    private class Runner implements Runnable {
        public void run() {
            Properties params = new Properties();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(80);
            while (_live) {
                
                try {
                    int c = -1;
                    while ((c = _inRaw.read()) != -1) {
                        if (c == '\n') {
                            break;
                        }
                        baos.write(c);
                    }
                    if (c == -1) {
                        _log.info("EOF reading from the SAM bridge");
                        break;
                    }
                } catch (IOException ioe) {
                    _log.error("Error reading from SAM", ioe);
                    break;
                }
                
                String line = DataHelper.getUTF8(baos.toByteArray());
                baos.reset();
                
                if (_log.shouldDebug())
                    _log.debug("Line read from the bridge: " + line);
                
                StringTokenizer tok = new StringTokenizer(line);
                
                if (tok.countTokens() <= 0) {
                    _log.error("Invalid SAM line: [" + line + "]");
                    break;
                }
                
                String major = tok.nextToken();
                String minor = tok.hasMoreTokens() ? tok.nextToken() : "";
                
                params.clear();
                while (tok.hasMoreTokens()) {
                    String pair = tok.nextToken();
                    int eq = pair.indexOf('=');
                    if ( (eq > 0) && (eq < pair.length() - 1) ) {
                        String name = pair.substring(0, eq);
                        String val = pair.substring(eq+1);
                        if (val.length() <= 0) {
                            _log.error("Empty value for " + name);
                            continue;
                        }
                        while ( (val.charAt(0) == '\"') && (val.length() > 0) )
                            val = val.substring(1);
                        while ( (val.length() > 0) && (val.charAt(val.length()-1) == '\"') )
                            val = val.substring(0, val.length()-1);
                        params.setProperty(name, val);
                    }
                }
                
                processEvent(major, minor, params);
            }
            _live = false;
            if (_log.shouldWarn())
                _log.warn("SAMReader exiting");
        }
    }
    
    /**
     * Big ugly method parsing everything.  If I cared, I'd factor this out into
     * a dozen tiny methods.
     *
     */
    private void processEvent(String major, String minor, Properties params) {
        if ("HELLO".equals(major)) {
            if ("REPLY".equals(minor)) {
                String result = params.getProperty("RESULT");
                String version= params.getProperty("VERSION");
                if ("OK".equals(result) && version != null)
                    _listener.helloReplyReceived(true, version);
                else
                    _listener.helloReplyReceived(false, version);
            } else {
                _listener.unknownMessageReceived(major, minor, params);
            }
        } else if ("SESSION".equals(major)) {
            if ("STATUS".equals(minor)) {
                String result = params.getProperty("RESULT");
                String dest = params.getProperty("DESTINATION");
                String msg = params.getProperty("MESSAGE");
                _listener.sessionStatusReceived(result, dest, msg);
            } else {
                _listener.unknownMessageReceived(major, minor, params);
            }
        } else if ("STREAM".equals(major)) {
            if ("STATUS".equals(minor)) {
                String result = params.getProperty("RESULT");
                String id = params.getProperty("ID");
                String msg = params.getProperty("MESSAGE");
                // id is null in v3, so pass it through regardless
                //if (id != null) {
                    _listener.streamStatusReceived(result, id, msg);
                //} else {
                //    _listener.unknownMessageReceived(major, minor, params);
                //}
            } else if ("CONNECTED".equals(minor)) {
                String dest = params.getProperty("DESTINATION");
                String id = params.getProperty("ID");
                if (id != null) {
                    _listener.streamConnectedReceived(dest, id);
                } else {
                    _listener.unknownMessageReceived(major, minor, params);
                }
            } else if ("CLOSED".equals(minor)) {
                String result = params.getProperty("RESULT");
                String id = params.getProperty("ID");
                String msg = params.getProperty("MESSAGE");
                if (id != null) {
                    _listener.streamClosedReceived(result, id, msg);
                } else {
                    _listener.unknownMessageReceived(major, minor, params);
                }
            } else if ("RECEIVED".equals(minor)) {
                String id = params.getProperty("ID");
                String size = params.getProperty("SIZE");
                if (id != null) {
                    try {
                        int sizeVal = Integer.parseInt(size);
                        
                        byte data[] = new byte[sizeVal];
                        int read = DataHelper.read(_inRaw, data);
                        if (read != sizeVal) {
                            _listener.unknownMessageReceived(major, minor, params);
                        } else {
                            _listener.streamDataReceived(id, data, 0, sizeVal);
                        }
                    } catch (NumberFormatException nfe) {
                        _listener.unknownMessageReceived(major, minor, params);
                    } catch (IOException ioe) {
                        _live = false;
                        _listener.unknownMessageReceived(major, minor, params);
                    }
                } else {
                    _listener.unknownMessageReceived(major, minor, params);
                }
            } else {
                _listener.unknownMessageReceived(major, minor, params);
            }
        } else if ("DATAGRAM".equals(major)) {
            if ("RECEIVED".equals(minor)) {
                String dest = params.getProperty("DESTINATION");
                String size = params.getProperty("SIZE");
                String fp = params.getProperty("FROM_PORT");
                String tp = params.getProperty("TO_PORT");
                int fromPort = 0;
                int toPort = 0;
                if (dest != null) {
                    try {
                      if (fp != null)
                          fromPort = Integer.parseInt(fp);
                      if (tp != null)
                          toPort = Integer.parseInt(tp);
                        int sizeVal = Integer.parseInt(size);
                        byte data[] = new byte[sizeVal];
                        int read = DataHelper.read(_inRaw, data);
                        if (read != sizeVal) {
                            _listener.unknownMessageReceived(major, minor, params);
                        } else {
                            _listener.datagramReceived(dest, data, 0, sizeVal, fromPort, toPort);
                        }
                    } catch (NumberFormatException nfe) {
                        _listener.unknownMessageReceived(major, minor, params);
                    } catch (IOException ioe) {
                        _live = false;
                        _listener.unknownMessageReceived(major, minor, params);
                    }
                } else {
                    _listener.unknownMessageReceived(major, minor, params);
                }
            } else {
                _listener.unknownMessageReceived(major, minor, params);
            }
        } else if ("RAW".equals(major)) {
            if ("RECEIVED".equals(minor)) {
                String size = params.getProperty("SIZE");
                String fp = params.getProperty("FROM_PORT");
                String tp = params.getProperty("TO_PORT");
                String pr = params.getProperty("PROTOCOL");
                int fromPort = 0;
                int toPort = 0;
                int protocol = I2PSession.PROTO_DATAGRAM_RAW;
                try {
                    if (fp != null)
                        fromPort = Integer.parseInt(fp);
                    if (tp != null)
                        toPort = Integer.parseInt(tp);
                    if (pr != null)
                        protocol = Integer.parseInt(pr);
                    int sizeVal = Integer.parseInt(size);
                    byte data[] = new byte[sizeVal];
                    int read = DataHelper.read(_inRaw, data);
                    if (read != sizeVal) {
                        _listener.unknownMessageReceived(major, minor, params);
                    } else {
                        _listener.rawReceived(data, 0, sizeVal, fromPort, toPort, protocol);
                    }
                } catch (NumberFormatException nfe) {
                    _listener.unknownMessageReceived(major, minor, params);
                } catch (IOException ioe) {
                    _live = false;
                    _listener.unknownMessageReceived(major, minor, params);
                }
            } else {
                _listener.unknownMessageReceived(major, minor, params);
            }
        } else if ("NAMING".equals(major)) {
            if ("REPLY".equals(minor)) {
                String name = params.getProperty("NAME");
                String result = params.getProperty("RESULT");
                String value = params.getProperty("VALUE");
                String msg = params.getProperty("MESSAGE");
                _listener.namingReplyReceived(name, result, value, msg);
            } else {
                _listener.unknownMessageReceived(major, minor, params);
            }
        } else if ("DEST".equals(major)) {
            if ("REPLY".equals(minor)) {
                String pub = params.getProperty("PUB");
                String priv = params.getProperty("PRIV");
                _listener.destReplyReceived(pub, priv);
            } else {
                _listener.unknownMessageReceived(major, minor, params);
            }
        } else if ("PING".equals(major)) {
            // this omits anything after a space
            _listener.pingReceived(minor);
        } else if ("PONG".equals(major)) {
            // this omits anything after a space
            _listener.pongReceived(minor);
        } else {
            _listener.unknownMessageReceived(major, minor, params);
        }
    }
}
