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
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PException;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Class able to handle a SAM version 1 client connections.
 *
 * @author human
 */
public class SAMv1Handler extends SAMHandler implements SAMRawReceiver, SAMDatagramReceiver, SAMStreamReceiver {
  protected int verMajorId = 1;
  protected int verMinorId = 0;
    
    private final static Log _log = new Log(SAMv1Handler.class);

    protected SAMRawSession rawSession = null;
    protected SAMDatagramSession datagramSession = null;
    protected SAMStreamSession streamSession = null;
    protected SAMRawSession getRawSession() {return rawSession ;}
    protected SAMDatagramSession getDatagramSession() {return datagramSession ;}	
    protected SAMStreamSession getStreamSession() {return streamSession ;}

    protected long _id;
    protected static volatile long __id = 0;
    
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
    public SAMv1Handler(SocketChannel s, int verMajor, int verMinor) throws SAMException, IOException {
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
     * @throws SAMException
     * @throws IOException 
     */
    public SAMv1Handler(SocketChannel s, int verMajor, int verMinor, Properties i2cpProps) throws SAMException, IOException {
        super(s, verMajor, verMinor, i2cpProps);
        _id = ++__id;
        _log.debug("SAM version 1 handler instantiated");

    if ( ! verifVersion() ) {
            throw new SAMException("BUG! Wrong protocol version!");
        }
    }

  public boolean verifVersion() {
    return ( verMajor == 1 && verMinor == 0 ) ;
  }

    public void handle() {
        String msg = null;
        String domain = null;
        String opcode = null;
        boolean canContinue = false;
        StringTokenizer tok;
        Properties props;

        this.thread.setName("SAMv1Handler " + _id);
        _log.debug("SAM handling started");

        try {
            while (true) {
                if (shouldStop()) {
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
                java.io.InputStream is = clientSocketChannel.socket().getInputStream();
                if (is == null) {
                	_log.info("Connection closed by client");
                	break;
                }
                msg = DataHelper.readLine(is);
                if (msg == null) {
                    _log.info("Connection closed by client (line read : null)");
                    break;
                }
                msg = msg.trim();

                if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug("New message received: [" + msg + "]");
                }

                if(msg.equals("")) {
                    _log.debug("Ignoring newline");
                    continue;
                }

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
        } catch (IOException e) {
            _log.debug("Caught IOException ("
                       + e.getMessage() + ") for message [" + msg + "]", e);
        } catch (Exception e) {
            _log.error("Unexpected exception for message [" + msg + "]", e);
        } finally {
            _log.debug("Stopping handler");
            try {
                closeClientSocket();
            } catch (IOException e) {
                _log.error("Error closing socket: " + e.getMessage());
            }
            if (getRawSession() != null) {
            	getRawSession().close();
            }
            if (getDatagramSession() != null) {
            	getDatagramSession().close();
            }
            if (getStreamSession() != null) {
            	getStreamSession().close();
            }
        }
    }

    /* Parse and execute a SESSION message */
    protected boolean execSessionMessage(String opcode, Properties props) {

        String dest = "BUG!";

        try{
            if (opcode.equals("CREATE")) {
                if ((getRawSession() != null) || (getDatagramSession() != null)
                    || (getStreamSession() != null)) {
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
                
                    streamSession = newSAMStreamSession(destKeystream, dir,props);
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

		
  SAMStreamSession newSAMStreamSession(String destKeystream, String direction, Properties props )
    throws IOException, DataFormatException, SAMException
  {
    return new SAMStreamSession(destKeystream, direction, props, this) ;
  }
		
    /* Parse and execute a DEST message*/
  protected boolean execDestMessage(String opcode, Properties props) {

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
  protected boolean execNamingMessage(String opcode, Properties props) {
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

            Destination dest = null ;
            if (name.equals("ME")) {
                if (getRawSession() != null) {
                    dest = getRawSession().getDestination();
                } else if (getStreamSession() != null) {
                    dest = getStreamSession().getDestination();
                } else if (getDatagramSession() != null) {
                    dest = getDatagramSession().getDestination();
                } else {
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
            _log.debug("Unrecognized NAMING message opcode: \""
                       + opcode + "\"");
            return false;
        }
    }


    /* Parse and execute a DATAGRAM message */
    protected boolean execDatagramMessage(String opcode, Properties props) {
        if (getDatagramSession() == null) {
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
                DataInputStream in = new DataInputStream(getClientSocket().socket().getInputStream());
                byte[] data = new byte[size];

                in.readFully(data);

                if (!getDatagramSession().sendBytes(dest, data)) {
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
    protected boolean execRawMessage(String opcode, Properties props) {
        if (getRawSession() == null) {
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
                DataInputStream in = new DataInputStream(getClientSocket().socket().getInputStream());
                byte[] data = new byte[size];

                in.readFully(data);

                if (!getRawSession().sendBytes(dest, data)) {
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
    protected boolean execStreamMessage(String opcode, Properties props) {
        if (getStreamSession() == null) {
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
            
  protected boolean execStreamSend(Properties props) {
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
            if (!getStreamSession().sendBytes(id, getClientSocket().socket().getInputStream(), size)) { // data)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("STREAM SEND [" + size + "] failed");
                boolean rv = writeString("STREAM CLOSED RESULT=CANT_REACH_PEER ID=" + id + " MESSAGE=\"Send of " + size + " bytes failed\"\n");
                getStreamSession().closeConnection(id);
                return rv;
            }

            return true;
        } catch (EOFException e) {
            _log.debug("Too few bytes with STREAM SEND message (expected: "
                       + size);
            return false;
        } catch (IOException e) {
            _log.debug("Caught IOException while parsing STREAM SEND message",
                       e);
            return false;
        }
    }

  protected boolean execStreamConnect(Properties props) {
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
            try {
                if (!getStreamSession().connect(id, dest, props)) {
                    _log.debug("STREAM connection failed");
                    return false;
                }
            } catch (DataFormatException e) {
                _log.debug("Invalid destination in STREAM CONNECT message");
                notifyStreamOutgoingConnection ( id, "INVALID_KEY", null );
            } catch (SAMInvalidDirectionException e) {
                _log.debug("STREAM CONNECT failed: " + e.getMessage());
                notifyStreamOutgoingConnection ( id, "INVALID_DIRECTION", null );
            } catch (ConnectException e) {
                _log.debug("STREAM CONNECT failed: " + e.getMessage());
                notifyStreamOutgoingConnection ( id, "CONNECTION_REFUSED", null );
            } catch (NoRouteToHostException e) {
                _log.debug("STREAM CONNECT failed: " + e.getMessage());
                notifyStreamOutgoingConnection ( id, "CANT_REACH_PEER", null );
            } catch (InterruptedIOException e) {
                _log.debug("STREAM CONNECT failed: " + e.getMessage());
                notifyStreamOutgoingConnection ( id, "TIMEOUT", null );
            } catch (I2PException e) {
                _log.debug("STREAM CONNECT failed: " + e.getMessage());
                notifyStreamOutgoingConnection ( id, "I2P_ERROR", null );
            }
        } catch (IOException e) {
            return false ;
        }
    
        return true ;
    }
    
  protected boolean execStreamClose(Properties props) {
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

        boolean closed = getStreamSession().closeConnection(id);
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
        if (getRawSession() == null) {
            _log.error("BUG! Received raw bytes, but session is null!");
            throw new NullPointerException("BUG! RAW session is null!");
        }

        ByteArrayOutputStream msg = new ByteArrayOutputStream();

        String msgText = "RAW RECEIVED SIZE=" + data.length + "\n";
        msg.write(msgText.getBytes("ISO-8859-1"));
        msg.write(data);
        msg.flush();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("sending to client: " + msgText);

        writeBytes(ByteBuffer.wrap(msg.toByteArray()));
    }

    public void stopRawReceiving() {
        _log.debug("stopRawReceiving() invoked");

        if (getRawSession() == null) {
            _log.error("BUG! Got raw receiving stop, but session is null!");
            throw new NullPointerException("BUG! RAW session is null!");
        }

        try {
            closeClientSocket();
        } catch (IOException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error closing socket", e);
        }
    }

    // SAMDatagramReceiver implementation
    public void receiveDatagramBytes(Destination sender, byte data[]) throws IOException {
        if (getDatagramSession() == null) {
            _log.error("BUG! Received datagram bytes, but session is null!");
            throw new NullPointerException("BUG! DATAGRAM session is null!");
        }

        ByteArrayOutputStream msg = new ByteArrayOutputStream();

        String msgText = "DATAGRAM RECEIVED DESTINATION=" + sender.toBase64()
                         + " SIZE=" + data.length + "\n";
        msg.write(msgText.getBytes("ISO-8859-1"));
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("sending to client: " + msgText);
        msg.write(data);
        msg.flush();
        writeBytes(ByteBuffer.wrap(msg.toByteArray()));
    }

    public void stopDatagramReceiving() {
        _log.debug("stopDatagramReceiving() invoked");

        if (getDatagramSession() == null) {
            _log.error("BUG! Got datagram receiving stop, but session is null!");
            throw new NullPointerException("BUG! DATAGRAM session is null!");
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
        if ( getStreamSession() == null )
        {
            _log.error ( "BUG! Want to answer to stream SEND, but session is null!" );
            throw new NullPointerException ( "BUG! STREAM session is null!" );
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
        if ( getStreamSession() == null )
        {
            _log.error ( "BUG! Stream outgoing buffer is free, but session is null!" );
            throw new NullPointerException ( "BUG! STREAM session is null!" );
        }
    
        if ( !writeString ( "STREAM READY_TO_SEND ID=" + id + "\n" ) )
        {
            throw new IOException ( "Error notifying connection to SAM client" );
        }
    }


    public void notifyStreamIncomingConnection(int id, Destination d) throws IOException {
        if (getStreamSession() == null) {
            _log.error("BUG! Received stream connection, but session is null!");
            throw new NullPointerException("BUG! STREAM session is null!");
        }

        if (!writeString("STREAM CONNECTED DESTINATION="
                         + d.toBase64()
                         + " ID=" + id + "\n")) {
            throw new IOException("Error notifying connection to SAM client");
        }
    }

    public void notifyStreamOutgoingConnection ( int id, String result, String msg ) throws IOException
    {
        if ( getStreamSession() == null )
        {
            _log.error ( "BUG! Received stream connection, but session is null!" );
            throw new NullPointerException ( "BUG! STREAM session is null!" );
        }

        String msgString = "" ;

        if ( msg != null ) msgString = " MESSAGE=\"" + msg + "\"";

        if ( !writeString ( "STREAM STATUS RESULT="
                        + result
                        + " ID=" + id
                        + msgString
                        + "\n" ) )
        {
            throw new IOException ( "Error notifying connection to SAM client" );
        }
    }
  
    public void receiveStreamBytes(int id, ByteBuffer data) throws IOException {
        if (getStreamSession() == null) {
            _log.error("Received stream bytes, but session is null!");
            throw new NullPointerException("BUG! STREAM session is null!");
        }

        String msgText = "STREAM RECEIVED ID=" + id +" SIZE=" + data.remaining() + "\n";
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("sending to client: " + msgText);
        
        ByteBuffer prefix = ByteBuffer.wrap(msgText.getBytes("ISO-8859-1"));
        
        Object writeLock = getWriteLock();
        synchronized (writeLock) {
        	while (prefix.hasRemaining()) socket.write(prefix);
            while (data.hasRemaining()) socket.write(data);
            socket.socket().getOutputStream().flush();
        }
    }

    public void notifyStreamDisconnection(int id, String result, String msg) throws IOException {
        if (getStreamSession() == null) {
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
        _log.debug("stopStreamReceiving() invoked", new Exception("stopped"));

        if (getStreamSession() == null) {
            _log.error("BUG! Got stream receiving stop, but session is null!");
            throw new NullPointerException("BUG! STREAM session is null!");
        }

        try {
            closeClientSocket();
        } catch (IOException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error closing socket", e);
        }
    }
}
