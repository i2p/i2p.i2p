package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.util.Enumeration;
import java.util.Properties;
import java.util.StringTokenizer;

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
public class SAMv1Handler extends SAMHandler implements SAMRawReceiver {
    
    private final static Log _log = new Log(SAMv1Handler.class);

    private final static int IN_BUFSIZE = 2048;

    private SAMRawSession rawSession = null;
    private SAMRawSession datagramSession = null;
    private SAMRawSession streamSession = null;

    /**
     * Create a new SAM version 1 handler.  This constructor expects
     * that the SAM HELLO message has been still answered (and
     * stripped) from the socket input stream.
     *
     * @param s Socket attached to a SAM client
     */
    public SAMv1Handler(Socket s, int verMajor, int verMinor) throws SAMException{
	_log.debug("SAM version 1 handler instantiated");

	this.verMajor = verMajor;
	this.verMinor = verMinor;

	if ((this.verMajor != 1) || (this.verMinor != 0)) {
	    throw new SAMException("BUG! Wrong protocol version!");
	}

	this.socket = s;
	this.verMajor = verMajor;
	this.verMinor = verMinor;
    }

    public void handle() {
	String msg, domain, opcode;
	boolean canContinue = false;
	ByteArrayOutputStream buf = new ByteArrayOutputStream(IN_BUFSIZE);
	StringTokenizer tok;

	this.thread.setName("SAMv1Handler");
	_log.debug("SAM handling started");

	try {
	    InputStream in = socket.getInputStream();
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

		msg = buf.toString("ISO-8859-1");
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

		_log.debug("Parsing (domain: \"" + domain + "\"; opcode: \""
			   + opcode + "\")");
		if (domain.equals("RAW")) {
		    canContinue = execRawMessage(opcode, tok);
		} else if (domain.equals("SESSION")) {
		    canContinue = execSessionMessage(opcode, tok);
		} else if (domain.equals("DEST")) {
		    canContinue = execDestMessage(opcode, tok);
		} else if (domain.equals("NAMING")) {
		    canContinue = execNamingMessage(opcode, tok);
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
		       + e.getMessage() + ")");
	} catch (IOException e) {
	    _log.debug("Caught IOException ("
		       + e.getMessage() + ")");
	} catch (Exception e) {
	    _log.error("Unexpected exception", e);
	} finally {
	    _log.debug("Stopping handler");
	    try {
		this.socket.close();
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
    private boolean execSessionMessage(String opcode, StringTokenizer tok) {
	Properties props = null;

	if (opcode.equals("CREATE")) {

	    if ((rawSession != null) || (datagramSession != null)
		|| (streamSession != null)) {
		_log.debug("Trying to create a session, but one still exists");
		return false;
	    }
	    props = SAMUtils.parseParams(tok);
	    if (props == null) {
		return false;
	    }
	    
	    String dest = props.getProperty("DESTINATION");
	    if (dest == null) {
		_log.debug("SESSION DESTINATION parameter not specified");
		return false;
	    }
	    props.remove("DESTINATION");

	    String style = props.getProperty("STYLE");
	    if (style == null) {
		_log.debug("SESSION STYLE parameter not specified");
		return false;
	    }
	    props.remove("STYLE");

	    try {
		if (style.equals("RAW")) {
		    try {
			if (dest.equals("TRANSIENT")) {
			    _log.debug("TRANSIENT destination requested");
			    ByteArrayOutputStream priv = new ByteArrayOutputStream();			
			    SAMUtils.genRandomKey(priv, null);
			    
			    dest = Base64.encode(priv.toByteArray());
			}
			rawSession = new SAMRawSession (dest, props, this);
			writeBytes(("SESSION STATUS RESULT=OK DESTINATION=" + dest + "\n").getBytes("ISO-8859-1"));
		    } catch (DataFormatException e) {
			_log.debug("Invalid destination specified");
			writeBytes(("SESSION STATUS RESULT=INVALID_KEY DESTINATION=" + dest + "\n").getBytes("ISO-8859-1"));
			return true;
		    } catch (I2PSessionException e) {
			_log.debug("I2P error when instantiating RAW session", e);
			writeBytes(("SESSION STATUS RESULT=I2P_ERROR DESTINATION=" + dest + "\n").getBytes("ISO-8859-1"));
			return true;
		    }
		} else {
		    _log.debug("Unrecognized SESSION STYLE: \"" + style + "\"");
		    return false;
		}
	    } catch (UnsupportedEncodingException e) {
		_log.error("Caught UnsupportedEncodingException ("
			   + e.getMessage() + ")");
		return false;
	    } catch (IOException e) {
		_log.error("Caught IOException while parsing SESSION message ("
			   + e.getMessage() + ")");
		return false;
	    }
	    
	    return true;
	} else {
	    _log.debug("Unrecognized SESSION message opcode: \""
		       + opcode + "\"");
	    return false;
	}
    }

    /* Parse and execute a DEST message*/
    private boolean execDestMessage(String opcode, StringTokenizer tok) {

	if (opcode.equals("GENERATE")) {
	    if (tok.countTokens() > 0) {
		_log.debug("Bad format in DEST GENERATE message");
		return false;
	    }

	    try {
		ByteArrayOutputStream priv = new ByteArrayOutputStream();
		ByteArrayOutputStream pub = new ByteArrayOutputStream();
		
		SAMUtils.genRandomKey(priv, pub);
		writeBytes(("DEST REPLY"
			    + " PUB="
			    + Base64.encode(pub.toByteArray())
			    + " PRIV="
			    + Base64.encode(priv.toByteArray())
			    + "\n").getBytes("ISO-8859-1"));
	    } catch (UnsupportedEncodingException e) {
		_log.error("Caught UnsupportedEncodingException ("
			   + e.getMessage() + ")");
		return false;
	    } catch (IOException e) {
		_log.debug("IOException while executing DEST message", e);
		return false;
	    }
	} else {
	    _log.debug("Unrecognized DEST message opcode: \"" + opcode + "\"");
	    return false;
	}

	return true;
    }

    /* Parse and execute a NAMING message */
    private boolean execNamingMessage(String opcode, StringTokenizer tok) {
	Properties props = null;

	if (opcode.equals("LOOKUP")) {
	    props = SAMUtils.parseParams(tok);
	    if (props == null) {
		return false;
	    }
	    
	    String name = props.getProperty("NAME");
	    if (name == null) {
		_log.debug("Name to resolve not specified");
		return false;
	    }

	    try {
		ByteArrayOutputStream pubKey = new ByteArrayOutputStream();
		Destination dest = SAMUtils.lookupHost(name, pubKey);

		if (dest == null) {
		    writeBytes("NAMING REPLY RESULT=KEY_NOT_FOUND\n".getBytes("ISP-8859-1"));
		    return true;
		}
		
		writeBytes(("NAMING REPLY RESULT=OK NAME=" + name
			    + " VALUE=" + Base64.encode(pubKey.toByteArray())
			    + "\n").getBytes("ISO-8859-1"));
		return true;
	    } catch (UnsupportedEncodingException e) {
		_log.error("Caught UnsupportedEncodingException ("
			   + e.getMessage() + ")");
		return false;
	    } catch (IOException e) {
		_log.debug("Caught IOException while parsing NAMING message",
			   e);
		return false;
	    }
	} else {
	    _log.debug("Unrecognized NAMING message opcode: \""
		       + opcode + "\"");
	    return false;
	}
    }

    public String toString() {
	return "SAM v1 handler (client: "
	    + this.socket.getInetAddress().toString() + ":"
	    + this.socket.getPort() + ")";
    }

    /* Parse and execute a RAW message */
    private boolean execRawMessage(String opcode, StringTokenizer tok) {
	Properties props = null;

	if (rawSession == null) {
	    _log.debug("RAW message received, but no RAW session exists");
	    return false;
	}

	if (opcode.equals("SEND")) {
	    props = SAMUtils.parseParams(tok);
	    if (props == null) {
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
		DataInputStream in = new DataInputStream(socket.getInputStream());
		byte[] data = new byte[size];

		in.readFully(data);

		if (!rawSession.sendBytes(dest, data)) {
		    _log.error("RAW SEND failed");
		    return false;
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

    /* Check whether a size is inside the limits allowed by this protocol */
    private boolean checkSize(int size) {
	return ((size >= 1) && (size <= 32768));
    }
    
    // SAMRawReceiver implementation
    public void receiveRawBytes(byte data[]) throws IOException {
	if (rawSession == null) {
	    _log.error("BUG! Trying to write raw bytes, but session is null!");
	    throw new NullPointerException("BUG! RAW session is null!");
	}

	ByteArrayOutputStream msg = new ByteArrayOutputStream();

	msg.write(("RAW RECEIVED SIZE=" + data.length + "\n").getBytes());
	msg.write(data);

	writeBytes(msg.toByteArray());
    }

    public void stopReceiving() {
	_log.debug("stopReceiving() invoked");
	try {
	    this.socket.close();
	} catch (IOException e) {
	    _log.error("Error closing socket: " + e.getMessage());
	}
    }
}
