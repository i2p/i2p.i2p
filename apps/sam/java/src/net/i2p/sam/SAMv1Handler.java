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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PException;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Class able to handle a SAM version 1 client connections.
 *
 * @author human
 */
public class SAMv1Handler extends SAMHandler implements SAMRawReceiver, SAMDatagramReceiver, SAMStreamReceiver {
    
    private final static Log _log = new Log(SAMv1Handler.class);

    private final static int IN_BUFSIZE = 2048;

    private SAMRawSession rawSession = null;
    private SAMDatagramSession datagramSession = null;
    private SAMStreamSession streamSession = null;

    /**
     * Create a new SAM version 1 handler.  This constructor expects
     * that the SAM HELLO message has been still answered (and
     * stripped) from the socket input stream.
     *
     * @param s Socket attached to a SAM client
     * @param verMajor SAM major version to manage (should be 1)
     * @param verMinor SAM minor version to manage
     */
    public SAMv1Handler(Socket s, int verMajor, int verMinor) throws SAMException, IOException {
        this(s, verMajor, verMinor, new Properties());
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
     */
    public SAMv1Handler(Socket s, int verMajor, int verMinor, Properties i2cpProps) throws SAMException, IOException {
        super(s, verMajor, verMinor, i2cpProps);
        _log.debug("SAM version 1 handler instantiated");

        if ((this.verMajor != 1) || (this.verMinor != 0)) {
            throw new SAMException("BUG! Wrong protocol version!");
        }
    }

    public void handle() {
        String msg, domain, opcode;
        boolean canContinue = false;
        ByteArrayOutputStream buf = new ByteArrayOutputStream(IN_BUFSIZE);
        StringTokenizer tok;
        Properties props;

        this.thread.setName("SAMv1Handler");
        _log.debug("SAM handling started");

        try {
            InputStream in = getClientSocketInputStream();
            int b = -1;

            while (true) {
                if (shouldStop()) {
                    _log.debug("Stop request found");
                    break;
                }

                while ((b = in.read()) != -1) {
                    if (b == '\n') {
                        break;
                    }
                    buf.write(b);
                }
                if (b == -1) {
                    _log.debug("Connection closed by client");
                    break;
                }

                msg = buf.toString("ISO-8859-1").trim();
                if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug("New message received: " + msg);
                }
                buf.reset();

                tok = new StringTokenizer(msg, " ");
                if (tok.countTokens() < 2) {
                    // This is not a correct message, for sure
                    _log.debug("Error in message format");
                    break;
                }
                domain = tok.nextToken();
                opcode = tok.nextToken();
                if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug("Parsing (domain: \"" + domain
                               + "\"; opcode: \"" + opcode + "\")");
                }
                props = SAMUtils.parseParams(tok);

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
                    _log.debug("Unrecognized message domain: \""
                               + domain + "\"");
                    break;
                }

                if (!canContinue) {
                    break;
                }
            }
        } catch (UnsupportedEncodingException e) {
            _log.error("Caught UnsupportedEncodingException ("
                       + e.getMessage() + ")", e);
        } catch (IOException e) {
            _log.debug("Caught IOException ("
                       + e.getMessage() + ")", e);
        } catch (Exception e) {
            _log.error("Unexpected exception", e);
        } finally {
            _log.debug("Stopping handler");
            try {
                closeClientSocket();
            } catch (IOException e) {
                _log.error("Error closing socket: " + e.getMessage());
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
    private boolean execSessionMessage(String opcode, Properties props) {

        String dest = "BUG!";

        try{
            if (opcode.equals("CREATE")) {
                if ((rawSession != null) || (datagramSession != null)
                    || (streamSession != null)) {
                    _log.debug("Trying to create a session, but one still exists");
                    return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"Session already exists\"\n");
                }
                if (props == null) {
                    _log.debug("No parameters specified in SESSION CREATE message");
                    return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"No parameters for SESSION CREATE\"\n");
                }
                
                dest = props.getProperty("DESTINATION");
                if (dest == null) {
                    _log.debug("SESSION DESTINATION parameter not specified");
                    return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"DESTINATION not specified\"\n");
                }
                props.remove("DESTINATION");
                
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
                            _log.debug("Custom destination specified [" + dest + "] but it isnt know, creating a new one");
                        ByteArrayOutputStream baos = new ByteArrayOutputStream(640);
                        SAMUtils.genRandomKey(baos, null);
                        destKeystream = Base64.encode(baos.toByteArray());
                        bridge.addKeystream(dest, destKeystream);
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Custom destination specified [" + dest + "] and it is already known");
                    }
                }
                
                String style = props.getProperty("STYLE");
                if (style == null) {
                    _log.debug("SESSION STYLE parameter not specified");
                    return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"No SESSION STYLE specified\"\n");
                }
                props.remove("STYLE");
                
                if (style.equals("RAW")) {
                    rawSession = new SAMRawSession(destKeystream, props, this);
                } else if (style.equals("DATAGRAM")) {
                    datagramSession = new SAMDatagramSession(destKeystream, props,this);
                } else if (style.equals("STREAM")) {
                    String dir = props.getProperty("DIRECTION");
                    if (dir == null) {
                        _log.debug("No DIRECTION parameter in STREAM session, defaulting to BOTH");
                        dir = "BOTH";
                    }
                    if (!dir.equals("CREATE") && !dir.equals("RECEIVE")
                        && !dir.equals("BOTH")) {
                        _log.debug("Unknow DIRECTION parameter value: [" + dir + "]");
                        return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"Unknown DIRECTION parameter\"\n");
                    }
                    props.remove("DIRECTION");
                
                    streamSession = new SAMStreamSession(destKeystream, dir,props,this);
                } else {
                    _log.debug("Unrecognized SESSION STYLE: \"" + style +"\"");
                    return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"Unrecognized SESSION STYLE\"\n");
                }
                return writeString("SESSION STATUS RESULT=OK DESTINATION="
                                   + dest + "\n");
            } else {
                _log.debug("Unrecognized SESSION message opcode: \""
                           + opcode + "\"");
                return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"Unrecognized opcode\"\n");
            }
        } catch (DataFormatException e) {
            _log.debug("Invalid destination specified");
            return writeString("SESSION STATUS RESULT=INVALID_KEY DESTINATION=" + dest + " MESSAGE=\"" + e.getMessage() + "\"\n");
        } catch (I2PSessionException e) {
            _log.debug("I2P error when instantiating session", e);
            return writeString("SESSION STATUS RESULT=I2P_ERROR DESTINATION=" + dest + " MESSAGE=\"" + e.getMessage() + "\"\n");
        } catch (SAMException e) {
            _log.error("Unexpected SAM error", e);
            return writeString("SESSION STATUS RESULT=I2P_ERROR DESTINATION=" + dest + " MESSAGE=\"" + e.getMessage() + "\"\n");
        } catch (IOException e) {
            _log.error("Unexpected IOException", e);
            return writeString("SESSION STATUS RESULT=I2P_ERROR DESTINATION=" + dest + " MESSAGE=\"" + e.getMessage() + "\"\n");
        }
    }

    /* Parse and execute a DEST message*/
    private boolean execDestMessage(String opcode, Properties props) {

        if (opcode.equals("GENERATE")) {
            if (props.size() > 0) {
                _log.debug("Properties specified in DEST GENERATE message");
                return false;
            }

            ByteArrayOutputStream priv = new ByteArrayOutputStream();
            ByteArrayOutputStream pub = new ByteArrayOutputStream();
            
            SAMUtils.genRandomKey(priv, pub);
            return writeString("DEST REPLY"
                               + " PUB="
                               + Base64.encode(pub.toByteArray())
                               + " PRIV="
                               + Base64.encode(priv.toByteArray())
                               + "\n");
        } else {
            _log.debug("Unrecognized DEST message opcode: \"" + opcode + "\"");
            return false;
        }
    }

    /* Parse and execute a NAMING message */
    private boolean execNamingMessage(String opcode, Properties props) {
        if (opcode.equals("LOOKUP")) {
            if (props == null) {
                _log.debug("No parameters specified in NAMING LOOKUP message");
                return false;
            }
            
            String name = props.getProperty("NAME");
            if (name == null) {
                _log.debug("Name to resolve not specified in NAMING message");
                return false;
            }

            Destination dest;
            if (name.equals("ME")) {
                if (rawSession != null) {
                    dest = rawSession.getDestination();
                } else if (streamSession != null) {
                    dest = streamSession.getDestination();
                } else if (datagramSession != null) {
                    dest = datagramSession.getDestination();
                } else {
                    _log.debug("Lookup for SESSION destination, but session is null");
                    return false;
                }
            } else {
                dest = SAMUtils.lookupHost(name, null);
            }
            
            if (dest == null) {
                return writeString("NAMING REPLY RESULT=KEY_NOT_FOUND NAME=" + name + "\n");
            }
            
            return writeString("NAMING REPLY RESULT=OK NAME=" + name
                               + " VALUE="
                               + dest.toBase64()
                               + "\n");
        } else {
            _log.debug("Unrecognized NAMING message opcode: \""
                       + opcode + "\"");
            return false;
        }
    }


    /* Parse and execute a DATAGRAM message */
    private boolean execDatagramMessage(String opcode, Properties props) {
        if (datagramSession == null) {
            _log.error("DATAGRAM message received, but no DATAGRAM session exists");
            return false;
        }

        if (opcode.equals("SEND")) {
            if (props == null) {
                _log.debug("No parameters specified in DATAGRAM SEND message");
                return false;
            }
            
            String dest = props.getProperty("DESTINATION");
            if (dest == null) {
                _log.debug("Destination not specified in DATAGRAM SEND message");
                return false;
            }

            int size;
            {
                String strsize = props.getProperty("SIZE");
                if (strsize == null) {
                    _log.debug("Size not specified in DATAGRAM SEND message");
                    return false;
                }
                try {
                    size = Integer.parseInt(strsize);
                } catch (NumberFormatException e) {
                    _log.debug("Invalid DATAGRAM SEND size specified: " + strsize);
                    return false;
                }
                if (!checkDatagramSize(size)) {
                    _log.debug("Specified size (" + size
                               + ") is out of protocol limits");
                    return false;
                }
            }

            try {
                DataInputStream in = new DataInputStream(getClientSocketInputStream());
                byte[] data = new byte[size];

                in.readFully(data);

                if (!datagramSession.sendBytes(dest, data)) {
                    _log.error("DATAGRAM SEND failed");
                    return true;
                }

                return true;
            } catch (EOFException e) {
                _log.debug("Too few bytes with DATAGRAM SEND message (expected: "
                           + size);
                return false;
            } catch (IOException e) {
                _log.debug("Caught IOException while parsing DATAGRAM SEND message",
                           e);
                return false;
            } catch (DataFormatException e) {
                _log.debug("Invalid key specified with DATAGRAM SEND message",
                           e);
                return false;
            }
        } else {
            _log.debug("Unrecognized DATAGRAM message opcode: \""
                       + opcode + "\"");
            return false;
        }
    }

    /* Parse and execute a RAW message */
    private boolean execRawMessage(String opcode, Properties props) {
        if (rawSession == null) {
            _log.error("RAW message received, but no RAW session exists");
            return false;
        }

        if (opcode.equals("SEND")) {
            if (props == null) {
                _log.debug("No parameters specified in RAW SEND message");
                return false;
            }
            
            String dest = props.getProperty("DESTINATION");
            if (dest == null) {
                _log.debug("Destination not specified in RAW SEND message");
                return false;
            }

            int size;
            {
                String strsize = props.getProperty("SIZE");
                if (strsize == null) {
                    _log.debug("Size not specified in RAW SEND message");
                    return false;
                }
                try {
                    size = Integer.parseInt(strsize);
                } catch (NumberFormatException e) {
                    _log.debug("Invalid RAW SEND size specified: " + strsize);
                    return false;
                }
                if (!checkSize(size)) {
                    _log.debug("Specified size (" + size
                               + ") is out of protocol limits");
                    return false;
                }
            }

            try {
                DataInputStream in = new DataInputStream(getClientSocketInputStream());
                byte[] data = new byte[size];

                in.readFully(data);

                if (!rawSession.sendBytes(dest, data)) {
                    _log.error("RAW SEND failed");
                    return true;
                }

                return true;
            } catch (EOFException e) {
                _log.debug("Too few bytes with RAW SEND message (expected: "
                           + size);
                return false;
            } catch (IOException e) {
                _log.debug("Caught IOException while parsing RAW SEND message",
                           e);
                return false;
            } catch (DataFormatException e) {
                _log.debug("Invalid key specified with RAW SEND message",
                           e);
                return false;
            }
        } else {
            _log.debug("Unrecognized RAW message opcode: \""
                       + opcode + "\"");
            return false;
        }
    }

    /* Parse and execute a STREAM message */
    private boolean execStreamMessage(String opcode, Properties props) {
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
            _log.debug("Unrecognized RAW message opcode: \""
                       + opcode + "\"");
            return false;
        }
    }
            
    private boolean execStreamSend(Properties props) {
        if (props == null) {
            _log.debug("No parameters specified in STREAM SEND message");
            return false;
        }

        int id;
        {
            String strid = props.getProperty("ID");
            if (strid == null) {
                _log.debug("ID not specified in STREAM SEND message");
                return false;
            }
            try {
                id = Integer.parseInt(strid);
            } catch (NumberFormatException e) {
                _log.debug("Invalid STREAM SEND ID specified: " + strid);
                return false;
            }
        }

        int size;
        {
            String strsize = props.getProperty("SIZE");
            if (strsize == null) {
                _log.debug("Size not specified in STREAM SEND message");
                return false;
            }
            try {
                size = Integer.parseInt(strsize);
            } catch (NumberFormatException e) {
                _log.debug("Invalid STREAM SEND size specified: "+strsize);
                return false;
            }
            if (!checkSize(size)) {
                _log.debug("Specified size (" + size
                           + ") is out of protocol limits");
                return false;
            }
        }

        try {
            DataInputStream in = new DataInputStream(getClientSocketInputStream());
            byte[] data = new byte[size];

            in.readFully(data);

            if (!streamSession.sendBytes(id, data)) {
                _log.error("STREAM SEND failed");
                boolean rv = writeString("STREAM CLOSED RESULT=CANT_REACH_PEER ID=" + id + " MESSAGE=\"Send of " + size + " bytes failed\"\n");
                streamSession.closeConnection(id);
                return rv;
            }

            return true;
        } catch (EOFException e) {
            _log.debug("Too few bytes with RAW SEND message (expected: "
                       + size);
            return false;
        } catch (IOException e) {
            _log.debug("Caught IOException while parsing RAW SEND message",
                       e);
            return false;
        }
    }

    private boolean execStreamConnect(Properties props) {
        if (props == null) {
            _log.debug("No parameters specified in STREAM CONNECT message");
            return false;
        }

        int id;
        {
            String strid = props.getProperty("ID");
            if (strid == null) {
                _log.debug("ID not specified in STREAM SEND message");
                return false;
            }
            try {
                id = Integer.parseInt(strid);
            } catch (NumberFormatException e) {
                _log.debug("Invalid STREAM CONNECT ID specified: " +strid);
                return false;
            }
            if (id < 1) {
                _log.debug("Invalid STREAM CONNECT ID specified: " +strid);
                return false;
            }
            props.remove("ID");
        }

        String dest = props.getProperty("DESTINATION");
        if (dest == null) {
            _log.debug("Destination not specified in RAW SEND message");
            return false;
        }
        props.remove("DESTINATION");

        try {
            if (!streamSession.connect(id, dest, props)) {
                _log.debug("STREAM connection failed");
                return false;
            }
            return writeString("STREAM STATUS RESULT=OK ID=" + id + "\n");
        } catch (DataFormatException e) {
            _log.debug("Invalid destination in STREAM CONNECT message");
            return writeString("STREAM STATUS RESULT=INVALID_KEY ID="
                               + id + "\n");
        } catch (SAMInvalidDirectionException e) {
            _log.debug("STREAM CONNECT failed: " + e.getMessage());
            return writeString("STREAM STATUS RESULT=INVALID_DIRECTION ID="
                               + id + "\n");
        } catch (ConnectException e) {
            _log.debug("STREAM CONNECT failed: " + e.getMessage());
            return writeString("STREAM STATUS RESULT=CONNECTION_REFUSED ID="
                               + id + "\n");
        } catch (NoRouteToHostException e) {
            _log.debug("STREAM CONNECT failed: " + e.getMessage());
            return writeString("STREAM STATUS RESULT=CANT_REACH_PEER ID="
                               + id + "\n");
        } catch (InterruptedIOException e) {
            _log.debug("STREAM CONNECT failed: " + e.getMessage());
            return writeString("STREAM STATUS RESULT=TIMEOUT ID="
                               + id + "\n");
        } catch (I2PException e) {
            _log.debug("STREAM CONNECT failed: " + e.getMessage());
            return writeString("STREAM STATUS RESULT=I2P_ERROR ID="
                               + id + "\n");
        }
    }
    
    private boolean execStreamClose(Properties props) {
        if (props == null) {
            _log.debug("No parameters specified in STREAM CLOSE message");
            return false;
        }

        int id;
        {
            String strid = props.getProperty("ID");
            if (strid == null) {
                _log.debug("ID not specified in STREAM CLOSE message");
                return false;
            }
            try {
                id = Integer.parseInt(strid);
            } catch (NumberFormatException e) {
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
    public void receiveRawBytes(byte data[]) throws IOException {
        if (rawSession == null) {
            _log.error("BUG! Received raw bytes, but session is null!");
            throw new NullPointerException("BUG! RAW session is null!");
        }

        ByteArrayOutputStream msg = new ByteArrayOutputStream();

        msg.write(("RAW RECEIVED SIZE=" + data.length
                   + "\n").getBytes("ISO-8859-1"));
        msg.write(data);

        writeBytes(msg.toByteArray());
    }

    public void stopRawReceiving() {
        _log.debug("stopRawReceiving() invoked");

        if (rawSession == null) {
            _log.error("BUG! Got raw receiving stop, but session is null!");
            throw new NullPointerException("BUG! RAW session is null!");
        }

        try {
            closeClientSocket();
        } catch (IOException e) {
            _log.error("Error closing socket: " + e.getMessage());
        }
    }

    // SAMDatagramReceiver implementation
    public void receiveDatagramBytes(Destination sender, byte data[]) throws IOException {
        if (datagramSession == null) {
            _log.error("BUG! Received datagram bytes, but session is null!");
            throw new NullPointerException("BUG! DATAGRAM session is null!");
        }

        ByteArrayOutputStream msg = new ByteArrayOutputStream();

        msg.write(("DATAGRAM RECEIVED DESTINATION=" + sender.toBase64()
                   + " SIZE=" + data.length
                   + "\n").getBytes("ISO-8859-1"));
        msg.write(data);

        writeBytes(msg.toByteArray());
    }

    public void stopDatagramReceiving() {
        _log.debug("stopDatagramReceiving() invoked");

        if (datagramSession == null) {
            _log.error("BUG! Got datagram receiving stop, but session is null!");
            throw new NullPointerException("BUG! DATAGRAM session is null!");
        }

        try {
            closeClientSocket();
        } catch (IOException e) {
            _log.error("Error closing socket: " + e.getMessage());
        }
    }

    // SAMStreamReceiver implementation
    public void notifyStreamConnection(int id, Destination d) throws IOException {
        if (streamSession == null) {
            _log.error("BUG! Received stream connection, but session is null!");
            throw new NullPointerException("BUG! STREAM session is null!");
        }

        if (!writeString("STREAM CONNECTED DESTINATION="
                         + d.toBase64()
                         + " ID=" + id + "\n")) {
            throw new IOException("Error notifying connection to SAM client");
        }
    }

    public void receiveStreamBytes(int id, byte data[], int len) throws IOException {
        if (streamSession == null) {
            _log.error("Received stream bytes, but session is null!");
            throw new NullPointerException("BUG! STREAM session is null!");
        }

        ByteArrayOutputStream msg = new ByteArrayOutputStream();

        msg.write(("STREAM RECEIVED ID=" + id 
                   +" SIZE=" + len + "\n").getBytes("ISO-8859-1"));
        msg.write(data, 0, len);

        writeBytes(msg.toByteArray());
    }

    public void notifyStreamDisconnection(int id, String result, String msg) throws IOException {
        if (streamSession == null) {
            _log.error("BUG! Received stream disconnection, but session is null!");
            throw new NullPointerException("BUG! STREAM session is null!");
        }

        // FIXME: msg should be escaped!
        if (!writeString("STREAM CLOSED ID=" + id + " RESULT=" + result
                         + (msg == null ? "" : (" MESSAGE=" + msg))
                         + "\n")) {
            throw new IOException("Error notifying disconnection to SAM client");
        }
    }

    public void stopStreamReceiving() {
        _log.debug("stopStreamReceiving() invoked");

        if (streamSession == null) {
            _log.error("BUG! Got stream receiving stop, but session is null!");
            throw new NullPointerException("BUG! STREAM session is null!");
        }

        try {
            closeClientSocket();
        } catch (IOException e) {
            _log.error("Error closing socket: " + e.getMessage());
        }
    }
}
