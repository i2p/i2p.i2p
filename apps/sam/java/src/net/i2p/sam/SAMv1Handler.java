package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Class able to handle a SAM version 1 client connections.
 *
 * @author human
 */
class SAMv1Handler extends SAMHandler implements SAMRawReceiver, SAMDatagramReceiver, SAMStreamReceiver {
    
    protected SAMMessageSess rawSession;
    protected SAMMessageSess datagramSession;
    protected SAMStreamSession streamSession;

    protected final SAMMessageSess getRawSession() { return rawSession; }
    protected final SAMMessageSess getDatagramSession() { return datagramSession; }	
    protected final SAMStreamSession getStreamSession() { return streamSession; }

    protected final long _id;
    private static final AtomicLong __id = new AtomicLong();
    private static final int FIRST_READ_TIMEOUT = 60*1000;
    protected static final String SESSION_ERROR = "SESSION STATUS RESULT=I2P_ERROR";
    
    /**
     * Create a new SAM version 1 handler.  This constructor expects
     * that the SAM HELLO message has been still answered (and
     * stripped) from the socket input stream.
     *
     * @param s Socket attached to a SAM client
     * @param verMajor SAM major version to manage (should be 1)
     * @param verMinor SAM minor version to manage
     * @throws SAMException
     * @throws IOException 
     */
    public SAMv1Handler(SocketChannel s, int verMajor, int verMinor,
                        SAMBridge parent) throws SAMException, IOException {
        this(s, verMajor, verMinor, new Properties(), parent);
    }
    /**
     * Create a new SAM version 1 handler.  This constructor expects
     * that the SAM HELLO message has been still answered (and
     * stripped) from the socket input stream.
     *
     * @param s Socket attached to a SAM client
     * @param verMajor SAM major version to manage (should be 1)
     * @param verMinor SAM minor version to manage
     * @param i2cpProps properties to configure the I2CP connection (host, port, etc)
     * @throws SAMException
     * @throws IOException 
     */
    public SAMv1Handler(SocketChannel s, int verMajor, int verMinor,
                        Properties i2cpProps, SAMBridge parent) throws SAMException, IOException {
        super(s, verMajor, verMinor, i2cpProps, parent);
        _id = __id.incrementAndGet();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("SAM version 1 handler instantiated");

        if ( ! verifVersion() ) {
            throw new SAMException("BUG! Wrong protocol version!");
        }
    }

    public boolean verifVersion() {
        return (verMajor == 1);
    }

    public void handle() {
        String msg = null;
        String domain = null;
        String opcode = null;
        boolean canContinue = false;
        Properties props;
        final StringBuilder buf = new StringBuilder(128);

        this.thread.setName("SAMv1Handler " + _id);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("SAM handling started");

        try {
            boolean gotFirstLine = false;
            while (true) {
                if (shouldStop()) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Stop request found");
                    break;
                }

                SocketChannel clientSocketChannel = getClientSocket() ;
                if (clientSocketChannel == null) {
                	_log.info("Connection closed by client");
                	break;
                }
                if (clientSocketChannel.socket() == null) {
                	_log.info("Connection closed by client");
                	break;
                }
                buf.setLength(0);
                // first time, set a timeout
                try {
                    Socket sock = clientSocketChannel.socket();
                    ReadLine.readLine(sock, buf, gotFirstLine ? 0 : FIRST_READ_TIMEOUT);
                    sock.setSoTimeout(0);
                } catch (SocketTimeoutException ste) {
                    writeString(SESSION_ERROR, "command timeout, bye");
                    break;
                }
                msg = buf.toString();

                if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug("New message received: [" + msg + ']');
                }
                props = SAMUtils.parseParams(msg);
                domain = (String) props.remove(SAMUtils.COMMAND);
                if (domain == null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Ignoring newline");
                    continue;
                }
                opcode = (String) props.remove(SAMUtils.OPCODE);
                if (opcode == null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Error in message format");
                    break;
                }
                if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug("Parsing (domain: \"" + domain
                               + "\"; opcode: \"" + opcode + "\")");
                }
                gotFirstLine = true;
                if (domain.equals("STREAM")) {
                    canContinue = execStreamMessage(opcode, props);
                } else if (domain.equals("DATAGRAM")) {
                    canContinue = execDatagramMessage(opcode, props);
                } else if (domain.equals("RAW")) {
                    canContinue = execRawMessage(opcode, props);
                } else if (domain.equals("SESSION")) {
                    if (i2cpProps != null)
                        props.putAll(i2cpProps); // make sure we've got the i2cp settings
                    canContinue = execSessionMessage(opcode, props);
                } else if (domain.equals("DEST")) {
                    canContinue = execDestMessage(opcode, props);
                } else if (domain.equals("NAMING")) {
                    canContinue = execNamingMessage(opcode, props);
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Unrecognized message domain: \""
                               + domain + "\"");
                    break;
                }

                if (!canContinue) {
                    break;
                }
            }
        } catch (IOException e) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Caught IOException for message [" + msg + "]", e);
        } catch (SAMException e) {
            _log.error("Unexpected exception for message [" + msg + "]", e);
        } catch (RuntimeException e) {
            _log.error("Unexpected exception for message [" + msg + "]", e);
        } finally {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Stopping handler");
            try {
                closeClientSocket();
            } catch (IOException e) {
                if (_log.shouldWarn())
                    _log.warn("Error closing socket", e);
            }
            if (rawSession != null) {
            	rawSession.close();
            }
            if (datagramSession != null) {
            	datagramSession.close();
            }
            if (streamSession != null) {
            	streamSession.close();
            }
        }
    }

    /* Parse and execute a SESSION message */
    protected boolean execSessionMessage(String opcode, Properties props) {

        String dest = "BUG!";

        try{
            if (opcode.equals("CREATE")) {
                if ((rawSession != null) || (datagramSession != null)
                    || (streamSession != null)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Trying to create a session, but one still exists");
                    return writeString(SESSION_ERROR, "Session already exists");
                }
                if (props.isEmpty()) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("No parameters specified in SESSION CREATE message");
                    return writeString(SESSION_ERROR, "No parameters for SESSION CREATE");
                }
                
                dest = (String) props.remove("DESTINATION");
                if (dest == null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("SESSION DESTINATION parameter not specified");
                    return writeString(SESSION_ERROR, "DESTINATION not specified");
                }
                
                String destKeystream = null;
                
                if (dest.equals("TRANSIENT")) {
                    _log.debug("TRANSIENT destination requested");
                    ByteArrayOutputStream priv = new ByteArrayOutputStream(640);
                    SAMUtils.genRandomKey(priv, null);
                    
                    destKeystream = Base64.encode(priv.toByteArray());
                } else {
                    destKeystream = bridge.getKeystream(dest);
                    if (destKeystream == null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Custom destination specified [" + dest + "] but it isn't known, creating a new one");
                        ByteArrayOutputStream baos = new ByteArrayOutputStream(640);
                        SAMUtils.genRandomKey(baos, null);
                        destKeystream = Base64.encode(baos.toByteArray());
                        bridge.addKeystream(dest, destKeystream);
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Custom destination specified [" + dest + "] and it is already known");
                    }
                }
                
                String style = (String) props.remove("STYLE");
                if (style == null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("SESSION STYLE parameter not specified");
                    return writeString(SESSION_ERROR, "No SESSION STYLE specified");
                }
                
		// Unconditionally override what the client may have set
		// (iMule sets BestEffort) as None is more efficient
		// and the client has no way to access delivery notifications
		props.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_NONE);

                if (style.equals("RAW")) {
                    rawSession = new SAMRawSession(destKeystream, props, this);
                    rawSession.start();
                } else if (style.equals("DATAGRAM")) {
                    datagramSession = new SAMDatagramSession(destKeystream, props, this, 1);
                    datagramSession.start();
                } else if (style.equals("STREAM")) {
                    String dir = (String) props.remove("DIRECTION");
                    if (dir == null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("No DIRECTION parameter in STREAM session, defaulting to BOTH");
                        dir = "BOTH";
                    } else if (!dir.equals("CREATE") && !dir.equals("RECEIVE")
                               && !dir.equals("BOTH")) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Unknown DIRECTION parameter value: [" + dir + "]");
                        return writeString(SESSION_ERROR, "Unknown DIRECTION parameter");
                    }
                
                    streamSession = newSAMStreamSession(destKeystream, dir,props);
                    streamSession.start();
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Unrecognized SESSION STYLE: \"" + style +"\"");
                    return writeString(SESSION_ERROR, "Unrecognized SESSION STYLE");
                }
                return writeString("SESSION STATUS RESULT=OK DESTINATION="
                                   + dest + "\n");
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Unrecognized SESSION message opcode: \""
                           + opcode + "\"");
                return writeString(SESSION_ERROR, "Unrecognized opcode");
            }
        } catch (DataFormatException e) {
            _log.error("Invalid SAM destination specified", e);
            return writeString("SESSION STATUS RESULT=INVALID_KEY", e.getMessage());
        } catch (I2PSessionException e) {
            _log.error("Failed to start SAM session", e);
            return writeString(SESSION_ERROR, e.getMessage());
        } catch (SAMException e) {
            _log.error("Failed to start SAM session", e);
            return writeString(SESSION_ERROR, e.getMessage());
        } catch (IOException e) {
            _log.error("Failed to start SAM session", e);
            return writeString(SESSION_ERROR, e.getMessage());
        }
    }

		
  private SAMStreamSession newSAMStreamSession(String destKeystream, String direction, Properties props )
    throws IOException, DataFormatException, SAMException
  {
    return new SAMStreamSession(destKeystream, direction, props, this) ;
  }
		
    /* Parse and execute a DEST message*/
  protected boolean execDestMessage(String opcode, Properties props) {

        if (opcode.equals("GENERATE")) {
            String sigTypeStr = props.getProperty("SIGNATURE_TYPE");
            SigType sigType;
            if (sigTypeStr != null) {
                sigType = SigType.parseSigType(sigTypeStr);
                if (sigType == null) {
                    writeString("DEST REPLY RESULT=I2P_ERROR MESSAGE=\"SIGNATURE_TYPE " +
                                sigTypeStr + " unsupported\"\n");
                    return false;
                }
            } else {
                sigType = SigType.DSA_SHA1;
            }

            ByteArrayOutputStream priv = new ByteArrayOutputStream(663);
            ByteArrayOutputStream pub = new ByteArrayOutputStream(391);
            
            SAMUtils.genRandomKey(priv, pub, sigType);
            return writeString("DEST REPLY"
                               + " PUB="
                               + Base64.encode(pub.toByteArray())
                               + " PRIV="
                               + Base64.encode(priv.toByteArray())
                               + "\n");
        } else {
            writeString("DEST REPLY RESULT=I2P_ERROR MESSAGE=\"DEST GENERATE required\"");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Unrecognized DEST message opcode: \"" + opcode + "\"");
            return false;
        }
    }

    /* Parse and execute a NAMING message */
  protected boolean execNamingMessage(String opcode, Properties props) {
        if (opcode.equals("LOOKUP")) {
            String name = props.getProperty("NAME");
            if (name == null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Name to resolve not specified in NAMING message");
                return writeString("NAMING REPLY RESULT=KEY_NOT_FOUND NAME=\"\" MESSAGE=\"Must specify NAME\"\n");
            }

            Destination dest = null ;
            if (name.equals("ME")) {
                if (rawSession != null) {
                    dest = rawSession.getDestination();
                } else if (streamSession != null) {
                    dest = streamSession.getDestination();
                } else if (datagramSession != null) {
                    dest = datagramSession.getDestination();
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Lookup for SESSION destination, but session is null");
                    return false;
                }
            } else {
            	try {
            		dest = SAMUtils.getDest(name);
            	} catch (DataFormatException e) {
            	}
            }
            
            if (dest == null) {
                return writeString("NAMING REPLY RESULT=KEY_NOT_FOUND NAME=" + name + "\n");
            }
            
            return writeString("NAMING REPLY RESULT=OK NAME=" + name
                               + " VALUE="
                               + dest.toBase64()
                               + "\n");
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Unrecognized NAMING message opcode: \""
                       + opcode + "\"");
            return false;
        }
    }


    /* Parse and execute a DATAGRAM message */
    protected boolean execDatagramMessage(String opcode, Properties props) {
        if (datagramSession == null) {
            _log.error("DATAGRAM message received, but no DATAGRAM session exists");
            return false;
        }
        return execDgOrRawMessage(false, opcode, props);
    }

    /* Parse and execute a RAW message */
    protected boolean execRawMessage(String opcode, Properties props) {
        if (rawSession == null) {
            _log.error("RAW message received, but no RAW session exists");
            return false;
        }
        return execDgOrRawMessage(true, opcode, props);
    }


    /*
     * Parse and execute a RAW or DATAGRAM SEND message.
     * This is for v1/v2 compatible sending only.
     * For v3 sending, see SAMv3DatagramServer.
     *
     * Note that props are from the command line only.
     * Session defaults from CREATE are NOT honored here.
     * FIXME if we care, but nobody's probably using v3.2 options for v1/v2 sending.
     *
     * @since 0.9.25 consolidated from execDatagramMessage() and execRawMessage()
     */
    private boolean execDgOrRawMessage(boolean isRaw, String opcode, Properties props) {
        if (opcode.equals("SEND")) {
            if (props.isEmpty()) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("No parameters specified in SEND message");
                return false;
            }
            
            String dest = props.getProperty("DESTINATION");
            if (dest == null) {
                if (_log.shouldWarn())
                    _log.warn("Destination not specified in SEND message");
                return false;
            }

            int size;
            String strsize = props.getProperty("SIZE");
            if (strsize == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Size not specified in SEND message");
                return false;
            }
            try {
                size = Integer.parseInt(strsize);
            } catch (NumberFormatException e) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid SEND size specified: " + strsize);
                return false;
            }
            boolean ok = isRaw ? checkSize(size) : checkDatagramSize(size);
            if (!ok) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Specified size (" + size
                           + ") is out of protocol limits");
                return false;
            }
            int fromPort = I2PSession.PORT_UNSPECIFIED;
            int toPort = I2PSession.PORT_UNSPECIFIED;
            int proto;
            if (isRaw) {
                proto = I2PSession.PROTO_DATAGRAM_RAW;
                String s = props.getProperty("PROTOCOL");
                if (s != null) {
                    try {
                        proto = Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Invalid SEND protocol specified: " + s);
                    }
                }
            } else {
                proto = I2PSession.PROTO_DATAGRAM;
            }
            String s = props.getProperty("FROM_PORT");
            if (s != null) {
                try {
                    fromPort = Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Invalid SEND port specified: " + s);
                }
            }
            s = props.getProperty("TO_PORT");
            if (s != null) {
                try {
                    toPort = Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Invalid SEND port specified: " + s);
                }
            }

            try {
                DataInputStream in = new DataInputStream(getClientSocket().socket().getInputStream());
                byte[] data = new byte[size];

                in.readFully(data);

                SAMMessageSess sess = isRaw ? rawSession : datagramSession;
                if (!sess.sendBytes(dest, data, proto, fromPort, toPort)) {
                    if (_log.shouldWarn())
                        _log.warn((isRaw ? "SEND RAW to " : "SEND DATAGRAM to ") + dest + " size " + size +
                                  " failed");
                    // a message send failure is no reason to drop the SAM session
                    // for raw and repliable datagrams, just carry on our merry way
                }
                return true;
            } catch (EOFException e) {
                if (_log.shouldWarn())
                    _log.warn("Too few bytes with SEND message (expected: "
                           + size, e);
                return false;
            } catch (IOException e) {
                if (_log.shouldWarn())
                    _log.warn("Caught IOException while parsing SEND message",
                           e);
                return false;
            } catch (DataFormatException e) {
                if (_log.shouldWarn())
                    _log.warn("Invalid key specified with SEND message",
                           e);
                return false;
            } catch (I2PSessionException e) {
                _log.error("Session error with SEND message", e);
                return false;
            }
        } else {
            if (_log.shouldWarn())
                _log.warn("Unrecognized message opcode: \""
                       + opcode + "\"");
            return false;
        }
    }

    /* Parse and execute a STREAM message */
    protected boolean execStreamMessage(String opcode, Properties props) {
        if (streamSession == null) {
            _log.error("STREAM message received, but no STREAM session exists");
            return false;
        }

        if (opcode.equals("SEND")) {
            return execStreamSend(props);
        } else if (opcode.equals("CONNECT")) {
            return execStreamConnect(props);
        } else if (opcode.equals("CLOSE")) {
            return execStreamClose(props);
        } else {
            if (_log.shouldWarn())
                _log.warn("Unrecognized STREAM message opcode: \""
                       + opcode + "\"");
            return false;
        }
    }
            
  protected boolean execStreamSend(Properties props) {
        if (props.isEmpty()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("No parameters specified in STREAM SEND message");
            return false;
        }

        int id;
        {
            String strid = props.getProperty("ID");
            if (strid == null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("ID not specified in STREAM SEND message");
                return false;
            }
            try {
                id = Integer.parseInt(strid);
            } catch (NumberFormatException e) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Invalid STREAM SEND ID specified: " + strid);
                return false;
            }
        }

        int size;
        {
            String strsize = props.getProperty("SIZE");
            if (strsize == null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Size not specified in STREAM SEND message");
                return false;
            }
            try {
                size = Integer.parseInt(strsize);
            } catch (NumberFormatException e) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Invalid STREAM SEND size specified: "+strsize);
                return false;
            }
            if (!checkSize(size)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Specified size (" + size
                           + ") is out of protocol limits");
                return false;
            }
        }

        try {
            if (!streamSession.sendBytes(id, getClientSocket().socket().getInputStream(), size)) { // data)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("STREAM SEND [" + size + "] failed");
                // a message send failure is no reason to drop the SAM session
                // for style=stream, tell the client the stream failed, and kill the virtual connection..
                boolean rv = writeString("STREAM CLOSED RESULT=CANT_REACH_PEER ID=" + id + " MESSAGE=\"Send of " + size + " bytes failed\"\n");
                streamSession.closeConnection(id);
                return rv;
            }

            return true;
        } catch (EOFException e) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Too few bytes with STREAM SEND message (expected: "
                       + size);
            return false;
        } catch (IOException e) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Caught IOException while parsing STREAM SEND message",
                       e);
            return false;
        }
    }

  protected boolean execStreamConnect(Properties props) {
        if (props.isEmpty()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("No parameters specified in STREAM CONNECT message");
            return false;
        }

        int id;
        {
            String strid = (String) props.remove("ID");
            if (strid == null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("ID not specified in STREAM SEND message");
                return false;
            }
            try {
                id = Integer.parseInt(strid);
            } catch (NumberFormatException e) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Invalid STREAM CONNECT ID specified: " +strid);
                return false;
            }
            if (id < 1) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Invalid STREAM CONNECT ID specified: " +strid);
                return false;
            }
        }

        String dest = (String) props.remove("DESTINATION");
        if (dest == null) {
            _log.debug("Destination not specified in RAW SEND message");
            return false;
        }

        try {
            try {
                if (!streamSession.connect(id, dest, props)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("STREAM connection failed");
                    return false;
                }
            } catch (DataFormatException e) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Invalid destination in STREAM CONNECT message");
                notifyStreamOutgoingConnection ( id, "INVALID_KEY", null );
            } catch (SAMInvalidDirectionException e) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("STREAM CONNECT failed", e);
                notifyStreamOutgoingConnection ( id, "INVALID_DIRECTION", null );
            } catch (ConnectException e) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("STREAM CONNECT failed", e);
                notifyStreamOutgoingConnection ( id, "CONNECTION_REFUSED", null );
            } catch (NoRouteToHostException e) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("STREAM CONNECT failed", e);
                notifyStreamOutgoingConnection ( id, "CANT_REACH_PEER", null );
            } catch (InterruptedIOException e) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("STREAM CONNECT failed", e);
                notifyStreamOutgoingConnection ( id, "TIMEOUT", null );
            } catch (I2PException e) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("STREAM CONNECT failed", e);
                notifyStreamOutgoingConnection ( id, "I2P_ERROR", null );
            }
        } catch (IOException e) {
            return false ;
        }
    
        return true ;
    }
    
  protected boolean execStreamClose(Properties props) {
        if (props.isEmpty()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("No parameters specified in STREAM CLOSE message");
            return false;
        }

        int id;
        {
            String strid = props.getProperty("ID");
            if (strid == null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("ID not specified in STREAM CLOSE message");
                return false;
            }
            try {
                id = Integer.parseInt(strid);
            } catch (NumberFormatException e) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Invalid STREAM CLOSE ID specified: " +strid);
                return false;
            }
        }

        boolean closed = streamSession.closeConnection(id);
        if ( (!closed) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Stream unable to be closed, but this is non fatal");
        return true;
    }

    /* Check whether a size is inside the limits allowed by this protocol */
    private boolean checkSize(int size) {
        return ((size >= 1) && (size <= 32768));
    }

    /* Check whether a size is inside the limits allowed by this protocol */
    private boolean checkDatagramSize(int size) {
        return ((size >= 1) && (size <= 31744));
    }
    
    // SAMRawReceiver implementation
    public void receiveRawBytes(byte data[], int proto, int fromPort, int toPort) throws IOException {
        if (rawSession == null) {
            _log.error("BUG! Received raw bytes, but session is null!");
            return;
        }

        ByteArrayOutputStream msg = new ByteArrayOutputStream(64 + data.length);

        String msgText = "RAW RECEIVED SIZE=" + data.length;
        msg.write(DataHelper.getASCII(msgText));
        if ((verMajor == 3 && verMinor >= 2) || verMajor > 3) {
            msgText = " PROTOCOL=" + proto + " FROM_PORT=" + fromPort + " TO_PORT=" + toPort;
            msg.write(DataHelper.getASCII(msgText));
        }
        msg.write((byte) '\n');
        msg.write(data);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("sending to client: " + msgText);

        writeBytes(ByteBuffer.wrap(msg.toByteArray()));
    }

    public void stopRawReceiving() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("stopRawReceiving() invoked");

        if (rawSession == null) {
            _log.error("BUG! Got raw receiving stop, but session is null!");
            return;
        }

        try {
            closeClientSocket();
        } catch (IOException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error closing socket", e);
        }
    }

    // SAMDatagramReceiver implementation
    public void receiveDatagramBytes(Destination sender, byte data[], int proto,
                                     int fromPort, int toPort) throws IOException {
        if (datagramSession == null) {
            _log.error("BUG! Received datagram bytes, but session is null!");
            return;
        }

        ByteArrayOutputStream msg = new ByteArrayOutputStream(100 + data.length);

        String msgText = "DATAGRAM RECEIVED DESTINATION=" + sender.toBase64()
                         + " SIZE=" + data.length;
        msg.write(DataHelper.getASCII(msgText));
        if ((verMajor == 3 && verMinor >= 2) || verMajor > 3) {
            msgText = " FROM_PORT=" + fromPort + " TO_PORT=" + toPort;
            msg.write(DataHelper.getASCII(msgText));
        }
        msg.write((byte) '\n');
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("sending to client: " + msgText);
        msg.write(data);
        msg.flush();
        writeBytes(ByteBuffer.wrap(msg.toByteArray()));
    }

    public void receiveDatagramBytes(Hash sender, byte data[], int proto,
                                     int fromPort, int toPort) throws IOException {
        throw new UnsupportedOperationException();
    }

    public void stopDatagramReceiving() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("stopDatagramReceiving() invoked");

        if (datagramSession == null) {
            _log.error("BUG! Got datagram receiving stop, but session is null!");
            return;
        }

        try {
            closeClientSocket();
        } catch (IOException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error closing socket", e);
        }
    }

    // SAMStreamReceiver implementation

    public void streamSendAnswer( int id, String result, String bufferState ) throws IOException
    {
        if ( streamSession == null )
        {
            _log.error ( "BUG! Want to answer to stream SEND, but session is null!" );
            return;
        }
    
        if ( !writeString ( "STREAM SEND ID=" + id
                        + " RESULT=" + result
                        + " STATE=" + bufferState
                        + "\n" ) )
        {
            throw new IOException ( "Error notifying connection to SAM client" );
        }
    }


    public void notifyStreamSendBufferFree( int id ) throws IOException
    {
        if ( streamSession == null )
        {
            _log.error ( "BUG! Stream outgoing buffer is free, but session is null!" );
            return;
        }
    
        if ( !writeString ( "STREAM READY_TO_SEND ID=" + id + "\n" ) )
        {
            throw new IOException ( "Error notifying connection to SAM client" );
        }
    }


    public void notifyStreamIncomingConnection(int id, Destination d) throws IOException {
        if (streamSession == null) {
            _log.error("BUG! Received stream connection, but session is null!");
            return;
        }

        if (!writeString("STREAM CONNECTED DESTINATION="
                         + d.toBase64()
                         + " ID=" + id + "\n")) {
            throw new IOException("Error notifying connection to SAM client");
        }
    }

    /** @param msg may be null */
    public void notifyStreamOutgoingConnection ( int id, String result, String msg ) throws IOException
    {
        if ( streamSession == null )
        {
            _log.error ( "BUG! Received stream connection, but session is null!" );
            return;
        }

        String msgString = createMessageString(msg);
        if ( !writeString ( "STREAM STATUS RESULT="
                        + result
                        + " ID=" + id
                        + msgString
                        + "\n" ) )
        {
            throw new IOException ( "Error notifying connection to SAM client" );
        }
    }

    /**
     *  Create a string to be appended to a status.
     *
     *  @param msg may be null
     *  @return non-null, "" if msg is null, MESSAGE=msg or MESSAGE="msg a b c"
     *           with leading space if msg is non-null
     *  @since 0.9.20
     */
    protected static String createMessageString(String msg) {
        String rv;
        if ( msg != null ) {
            msg = msg.replace("\n", " ");
            msg = msg.replace("\r", " ");
            if (!msg.startsWith("\"")) {
                msg = msg.replace("\"", "");
                if (msg.contains(" ") || msg.contains("\t"))
                    msg = '"' + msg + '"';
            }
            rv = " MESSAGE=" + msg;
        } else {
            rv = "";
        }
        return rv;
    }

    /**
     * Write a string and message, escaping the message.
     * Writes s + createMessageString(msg) + \n
     *
     * @param s The string, non-null
     * @since 0.9.25
     */
    protected boolean writeString(String s, String msg) {
        return writeString(s + createMessageString(msg) + '\n');
    }
  
    public void receiveStreamBytes(int id, ByteBuffer data) throws IOException {
        if (streamSession == null) {
            _log.error("Received stream bytes, but session is null!");
            return;
        }

        String msgText = "STREAM RECEIVED ID=" + id +" SIZE=" + data.remaining() + "\n";
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("sending to client: " + msgText);
        
        ByteBuffer prefix = ByteBuffer.wrap(DataHelper.getASCII(msgText));
        
        Object writeLock = getWriteLock();
        synchronized (writeLock) {
        	while (prefix.hasRemaining()) socket.write(prefix);
            while (data.hasRemaining()) socket.write(data);
            socket.socket().getOutputStream().flush();
        }
    }

    /** @param msg may be null */
    public void notifyStreamDisconnection(int id, String result, String msg) throws IOException {
        if (streamSession == null) {
            _log.error("BUG! Received stream disconnection, but session is null!");
            return;
        }

        String msgString = createMessageString(msg);
        if (!writeString("STREAM CLOSED ID=" + id + " RESULT=" + result + msgString + '\n')) {
            throw new IOException("Error notifying disconnection to SAM client");
        }
    }

    public void stopStreamReceiving() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("stopStreamReceiving() invoked", new Exception("stopped"));

        if (streamSession == null) {
            _log.error("BUG! Got stream receiving stop, but session is null!");
            return;
        }

        try {
            closeClientSocket();
        } catch (IOException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error closing socket", e);
        }
    }
}
