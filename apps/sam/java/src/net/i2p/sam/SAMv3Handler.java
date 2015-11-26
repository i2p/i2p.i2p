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
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.NoRouteToHostException;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.HashMap;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;
import net.i2p.util.I2PAppThread;
import net.i2p.util.PasswordManager;

/**
 * Class able to handle a SAM version 3 client connection.
 *
 * @author mkvore
 */

class SAMv3Handler extends SAMv1Handler
{
	
	private Session session;
	public static final SessionsDB sSessionsHash = new SessionsDB();
	private volatile boolean stolenSocket;
	private volatile boolean streamForwardingSocket;
	private final boolean sendPorts;
	private long _lastPing;
	private static final int READ_TIMEOUT = 3*60*1000;
	
	interface Session {
		String getNick();
		void close();
		boolean sendBytes(String dest, byte[] data, int proto,
		                  int fromPort, int toPort) throws DataFormatException, I2PSessionException;
	}
	
	/**
	 * Create a new SAM version 3 handler.  This constructor expects
	 * that the SAM HELLO message has been still answered (and
	 * stripped) from the socket input stream.
	 *
	 * @param s Socket attached to a SAM client
	 * @param verMajor SAM major version to manage (should be 3)
	 * @param verMinor SAM minor version to manage
	 */
	public SAMv3Handler(SocketChannel s, int verMajor, int verMinor,
                            SAMBridge parent) throws SAMException, IOException
	{
		this(s, verMajor, verMinor, new Properties(), parent);
	}

	/**
	 * Create a new SAM version 3 handler.  This constructor expects
	 * that the SAM HELLO message has been still answered (and
	 * stripped) from the socket input stream.
	 *
	 * @param s Socket attached to a SAM client
	 * @param verMajor SAM major version to manage (should be 3)
	 * @param verMinor SAM minor version to manage
	 * @param i2cpProps properties to configure the I2CP connection (host, port, etc)
	 */

	public SAMv3Handler(SocketChannel s, int verMajor, int verMinor,
	                    Properties i2cpProps, SAMBridge parent) throws SAMException, IOException
	{
		super(s, verMajor, verMinor, i2cpProps, parent);
	        sendPorts = (verMajor == 3 && verMinor >= 2) || verMajor > 3;
		if (_log.shouldLog(Log.DEBUG))
			_log.debug("SAM version 3 handler instantiated");
	}

	@Override
	public boolean verifVersion()
	{
		return (verMajor == 3);
	}
	
	/**
	 *  The values in the SessionsDB
	 */
	public static class SessionRecord
	{
		private final String m_dest ;
		private final Properties m_props ;
		private ThreadGroup m_threadgroup ;
		private final SAMv3Handler m_handler ;

		public SessionRecord( String dest, Properties props, SAMv3Handler handler )
		{
			m_dest = dest; 
			m_props = new Properties() ;
			m_props.putAll(props);
			m_handler = handler ;
		}

		public SessionRecord( SessionRecord in )
		{
			m_dest = in.getDest();
			m_props = in.getProps();
			m_threadgroup = in.getThreadGroup();
			m_handler = in.getHandler();
		}

		public String getDest()
		{
			return m_dest;
		}

		synchronized public Properties getProps()
		{
			Properties p = new Properties();
			p.putAll(m_props);
			return m_props;
		}

		public SAMv3Handler getHandler()
		{
			return m_handler ;
		}

		synchronized public ThreadGroup getThreadGroup()
		{
			return m_threadgroup ;
		}

		synchronized public void createThreadGroup(String name)
		{
			if (m_threadgroup == null)
				m_threadgroup = new ThreadGroup(name);
		}
	}

	/**
	 *  basically a HashMap from String to SessionRecord
	 */
	public static class SessionsDB
	{
		private static final long serialVersionUID = 0x1;

		static class ExistingIdException extends Exception {
			private static final long serialVersionUID = 0x1;
		}

		static class ExistingDestException extends Exception {
			private static final long serialVersionUID = 0x1;
		}
		
		private final HashMap<String, SessionRecord> map;

		public SessionsDB() {
			map = new HashMap<String, SessionRecord>() ;
		}

		/** @return success */
		synchronized public boolean put( String nick, SessionRecord session )
			throws ExistingIdException, ExistingDestException
		{
			if ( map.containsKey(nick) ) {
				throw new ExistingIdException();
			}
			for ( SessionRecord r : map.values() ) {
				if (r.getDest().equals(session.getDest())) {
					throw new ExistingDestException();
				}
			}

			if ( !map.containsKey(nick) ) {
				session.createThreadGroup("SAM session "+nick);
				map.put(nick, session) ;
				return true ;
			}
			else
				return false ;
		}

		/** @return true if removed */
		synchronized public boolean del( String nick )
		{
			return map.remove(nick) != null;
		}

		synchronized public SessionRecord get(String nick)
		{
			return map.get(nick);
		}

		synchronized public boolean containsKey( String nick )
		{
			return map.containsKey(nick);
		}
	}

	public String getClientIP()
	{
		return this.socket.socket().getInetAddress().getHostAddress();
	}
	
	/**
	 *  For SAMv3StreamSession connect and accept
	 */
	public void stealSocket()
	{
		stolenSocket = true ;
		if (sendPorts) {
			try {
			       socket.socket().setSoTimeout(0);
			} catch (SocketException se) {}
		}
		this.stopHandling();
	}
	
	/**
	 *  For SAMv3StreamSession
	 *  @since 0.9.20
	 */
	SAMBridge getBridge() {
		return bridge;
	}
	
	/**
	 *  For SAMv3DatagramServer
	 *  @return may be null
	 *  @since 0.9.24
	 */
	Session getSession() {
		return session;
	}
	
	@Override
	public void handle() {
		String msg = null;
		String domain = null;
		String opcode = null;
		boolean canContinue = false;
		StringTokenizer tok;
		Properties props;

		this.thread.setName("SAMv3Handler " + _id);
		if (_log.shouldLog(Log.DEBUG))
			_log.debug("SAMv3 handling started");

		try {
			Socket socket = getClientSocket().socket();
			InputStream in = socket.getInputStream();

			StringBuilder buf = new StringBuilder(1024);
			while (true) {
				if (shouldStop()) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("Stop request found");
					break;
				}
				String line;
				if (sendPorts) {
					// client supports PING
					try {
						ReadLine.readLine(socket, buf, READ_TIMEOUT);
						line = buf.toString();
						buf.setLength(0);					
					} catch (SocketTimeoutException ste) {
						long now = System.currentTimeMillis();
						if (buf.length() <= 0) {
							if (_lastPing > 0) {
								if (now - _lastPing >= READ_TIMEOUT) {
									if (_log.shouldWarn())
										_log.warn("Failed to respond to PING");
									writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"PONG timeout\"\n");
									break;
								}
							} else {
								if (_log.shouldDebug())
									_log.debug("Sendng PING " + now);
								_lastPing = now;
								if (!writeString("PING " + now + '\n'))
									break;
							}
						} else {
							if (_lastPing > 0) {
								if (now - _lastPing >= 2*READ_TIMEOUT) {
									if (_log.shouldWarn())
										_log.warn("Failed to respond to PING");
									writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"PONG timeout\"\n");
									break;
								}
							} else if (_lastPing < 0) {
								if (_log.shouldWarn())
									_log.warn("2nd timeout");
								writeString("XXX STATUS RESULT=I2P_ERROR MESSAGE=\"command timeout, bye\"\n");
								break;
							} else {
								// don't clear buffer, don't send ping,
								// go around again
								_lastPing = -1;
								if (_log.shouldWarn())
									_log.warn("timeout after partial: " + buf);
							}
						}
						if (_log.shouldDebug())
							_log.debug("loop after timeout");
						continue;
					}
				} else {
					buf.setLength(0);					
					if (DataHelper.readLine(in, buf))
						line = buf.toString();
					else
						line = null;
				}
				if (line==null) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("Connection closed by client (line read : null)");
					break;
				}
				msg = line.trim();

				if (_log.shouldLog(Log.DEBUG)) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("New message received: [" + msg + "]");
				}

				if(msg.equals("")) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("Ignoring newline");
					continue;
				}

				tok = new StringTokenizer(msg, " ");
				int count = tok.countTokens();
				if (count <= 0) {
					// This is not a correct message, for sure
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("Ignoring whitespace");
					continue;
				}
				domain = tok.nextToken();
				// these may not have a second token
				if (domain.equals("PING")) {
					execPingMessage(tok);
					continue;
				} else if (domain.equals("PONG")) {
					execPongMessage(tok);
					continue;
				} else if (domain.equals("QUIT") || domain.equals("STOP") ||
				           domain.equals("EXIT")) {
					writeString(domain + " STATUS RESULT=OK MESSAGE=bye\n");
					break;
				}
				if (count <= 1) {
					// This is not a correct message, for sure
					if (writeString(domain + " STATUS RESULT=I2P_ERROR MESSAGE=\"command not specified\"\n"))
						continue;
					else
						break;
				}
				opcode = tok.nextToken();
				if (_log.shouldLog(Log.DEBUG)) {
					_log.debug("Parsing (domain: \"" + domain
							+ "\"; opcode: \"" + opcode + "\")");
				}
				props = SAMUtils.parseParams(tok);

				if (domain.equals("STREAM")) {
					canContinue = execStreamMessage(opcode, props);
				} else if (domain.equals("SESSION")) {
					if (i2cpProps != null)
						props.putAll(i2cpProps); // make sure we've got the i2cp settings
					canContinue = execSessionMessage(opcode, props);
				} else if (domain.equals("DEST")) {
					canContinue = execDestMessage(opcode, props);
				} else if (domain.equals("NAMING")) {
					canContinue = execNamingMessage(opcode, props);
				} else if (domain.equals("DATAGRAM")) {
					// TODO not yet overridden, ID is ignored, most recent DATAGRAM session is used
					canContinue = execDatagramMessage(opcode, props);
				} else if (domain.equals("RAW")) {
					// TODO not yet overridden, ID is ignored, most recent RAW session is used
					canContinue = execRawMessage(opcode, props);
				} else if (domain.equals("AUTH")) {
					canContinue = execAuthMessage(opcode, props);
				} else {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("Unrecognized message domain: \""
							+ domain + "\"");
					break;
				}

				if (!canContinue) {
					break;
				}
			} // while
		} catch (IOException e) {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("Caught IOException in handler", e);
		} catch (SAMException e) {
			_log.error("Unexpected exception for message [" + msg + ']', e);
		} catch (RuntimeException e) {
			_log.error("Unexpected exception for message [" + msg + ']', e);
		} finally {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("Stopping handler");
			
			if (!this.stolenSocket)
			{
				try {
					closeClientSocket();
				} catch (IOException e) {
					if (_log.shouldWarn())
						_log.warn("Error closing socket", e);
				}
			}
			if (streamForwardingSocket) 
			{
				if (this.getStreamSession()!=null) {
					try {
						((SAMv3StreamSession)streamSession).stopForwardingIncoming();
					} catch (SAMException e) {
						if (_log.shouldWarn())
							_log.warn("Error while stopping forwarding connections", e);
					} catch (InterruptedIOException e) {
						if (_log.shouldWarn())
							_log.warn("Interrupted while stopping forwarding connections", e);
					}
				}
			}
			die();
		}
	}

	/**
	 * Stop the SAM handler, close the socket,
	 * unregister with the bridge.
         *
         * Overridden to not close the client socket if stolen.
         *
         * @since 0.9.20
	 */
	@Override
	public void stopHandling() {
            if (_log.shouldInfo())
                _log.info("Stopping (stolen? " + stolenSocket + "): " + this, new Exception("I did it"));
	    synchronized (stopLock) {
	        stopHandler = true;
	    }
	    if (!stolenSocket) {
		try {
		    closeClientSocket();
		} catch (IOException e) {}
	    }
	    bridge.unregister(this);
	}

	private void die() {
		SessionRecord rec = null ;
		
		if (session!=null) {
			session.close();
			rec = sSessionsHash.get(session.getNick());
		}
		if (rec!=null) {
			rec.getThreadGroup().interrupt() ;
			while (rec.getThreadGroup().activeCount()>0)
				try {
					Thread.sleep(1000);
				} catch ( InterruptedException e) {}
			rec.getThreadGroup().destroy();
			sSessionsHash.del(session.getNick());
		}
	}
	
	/* Parse and execute a SESSION message */
	@Override
	protected boolean execSessionMessage(String opcode, Properties props) {

		String dest = "BUG!";
		String nick =  null ;
		boolean ok = false ;

		try{
			if (opcode.equals("CREATE")) {
				if ((this.getRawSession()!= null) || (this.getDatagramSession() != null)
						|| (this.getStreamSession() != null)) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("Trying to create a session, but one still exists");
					return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"Session already exists\"\n");
				}
				if (props.isEmpty()) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("No parameters specified in SESSION CREATE message");
					return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"No parameters for SESSION CREATE\"\n");
				}

				dest = props.getProperty("DESTINATION");
				if (dest == null) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("SESSION DESTINATION parameter not specified");
					return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"DESTINATION not specified\"\n");
				}
				props.remove("DESTINATION");

				if (dest.equals("TRANSIENT")) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("TRANSIENT destination requested");
					String sigTypeStr = props.getProperty("SIGNATURE_TYPE");
					SigType sigType;
					if (sigTypeStr != null) {
						sigType = SigType.parseSigType(sigTypeStr);
						if (sigType == null) {
							return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"SIGNATURE_TYPE "
							                   + sigTypeStr + " unsupported\"\n");
						}
						props.remove("SIGNATURE_TYPE");
					} else {
						sigType = SigType.DSA_SHA1;
					}
					ByteArrayOutputStream priv = new ByteArrayOutputStream(663);
					SAMUtils.genRandomKey(priv, null, sigType);

					dest = Base64.encode(priv.toByteArray());
				} else {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("Custom destination specified [" + dest + "]");
					if (!SAMUtils.checkPrivateDestination(dest))
						return writeString("SESSION STATUS RESULT=INVALID_KEY\n");
				}


				nick = props.getProperty("ID");
				if (nick == null) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("SESSION ID parameter not specified");
					return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"ID not specified\"\n");
				}
				props.remove("ID");


				String style = props.getProperty("STYLE");
				if (style == null) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("SESSION STYLE parameter not specified");
					return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"No SESSION STYLE specified\"\n");
				}
				props.remove("STYLE");

				// Unconditionally override what the client may have set
				// (iMule sets BestEffort) as None is more efficient
				// and the client has no way to access delivery notifications
				i2cpProps.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_NONE);

				// Record the session in the database sSessionsHash
				Properties allProps = new Properties();
				allProps.putAll(i2cpProps);
				allProps.putAll(props);
				

				try {
					sSessionsHash.put( nick, new SessionRecord(dest, allProps, this) ) ;
				} catch (SessionsDB.ExistingIdException e) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("SESSION ID parameter already in use");
					return writeString("SESSION STATUS RESULT=DUPLICATED_ID\n");
				} catch (SessionsDB.ExistingDestException e) {
					return writeString("SESSION STATUS RESULT=DUPLICATED_DEST\n");
				}

				
				// Create the session

				if (style.equals("RAW")) {
					SAMv3DatagramServer dgs = bridge.getV3DatagramServer(props);
					SAMv3RawSession v3 = new SAMv3RawSession(nick, dgs);
                                        rawSession = v3;
					this.session = v3;
				} else if (style.equals("DATAGRAM")) {
					SAMv3DatagramServer dgs = bridge.getV3DatagramServer(props);
					SAMv3DatagramSession v3 = new SAMv3DatagramSession(nick, dgs);
					datagramSession = v3;
					this.session = v3;
				} else if (style.equals("STREAM")) {
					SAMv3StreamSession v3 = newSAMStreamSession(nick);
					streamSession = v3;
					this.session = v3;
				} else {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("Unrecognized SESSION STYLE: \"" + style +"\"");
					return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"Unrecognized SESSION STYLE\"\n");
				}
				ok = true ;
				return writeString("SESSION STATUS RESULT=OK DESTINATION="
						+ dest + "\n");
			} else {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("Unrecognized SESSION message opcode: \""
						+ opcode + "\"");
				return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"Unrecognized opcode\"\n");
			}
		} catch (DataFormatException e) {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("Invalid destination specified");
			return writeString("SESSION STATUS RESULT=INVALID_KEY DESTINATION=" + dest + " MESSAGE=\"" + e.getMessage() + "\"\n");
		} catch (I2PSessionException e) {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("I2P error when instantiating session", e);
			return writeString("SESSION STATUS RESULT=I2P_ERROR DESTINATION=" + dest + " MESSAGE=\"" + e.getMessage() + "\"\n");
		} catch (SAMException e) {
			if (_log.shouldLog(Log.INFO))
				_log.info("Funny SAM error", e);
			return writeString("SESSION STATUS RESULT=I2P_ERROR DESTINATION=" + dest + " MESSAGE=\"" + e.getMessage() + "\"\n");
		} catch (IOException e) {
			_log.error("Unexpected IOException", e);
			return writeString("SESSION STATUS RESULT=I2P_ERROR DESTINATION=" + dest + " MESSAGE=\"" + e.getMessage() + "\"\n");
		} finally {
			// unregister the session if it has not been created
			if ( !ok && nick!=null ) {
				sSessionsHash.del(nick) ;
				session = null ;
			}
		}
	}

	/**
	 * @throws NPE if login nickname is not registered
	 */
	private static SAMv3StreamSession newSAMStreamSession(String login )
			throws IOException, DataFormatException, SAMException
	{
		return new SAMv3StreamSession( login ) ;
	}

	/* Parse and execute a STREAM message */
	@Override
	protected boolean execStreamMessage ( String opcode, Properties props )
	{
		String nick = null ;
		SessionRecord rec = null ;

		if ( session != null )
		{
			_log.error ( "STREAM message received, but this session is a master session" );
			
			try {
				notifyStreamResult(true, "I2P_ERROR", "master session cannot be used for streams");
			} catch (IOException e) {}
			return false;
		}

		nick = props.getProperty("ID");
		if (nick == null) {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("SESSION ID parameter not specified");
			try {
				notifyStreamResult(true, "I2P_ERROR", "ID not specified");
			} catch (IOException e) {}
			return false ;
		}
		props.remove("ID");

		rec = sSessionsHash.get(nick);

		if ( rec==null ) {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("STREAM SESSION ID does not exist");
			try {
				notifyStreamResult(true, "INVALID_ID", "STREAM SESSION ID does not exist");
			} catch (IOException e) {}
			return false ;
		}
		
		streamSession = rec.getHandler().streamSession ;
		
		if (streamSession==null) {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("specified ID is not a stream session");
			try {
				notifyStreamResult(true, "I2P_ERROR",  "specified ID is not a STREAM session");
			} catch (IOException e) {}
			return false ;
		}

		if ( opcode.equals ( "CONNECT" ) )
		{
			return execStreamConnect ( props );
		} 
		else if ( opcode.equals ( "ACCEPT" ) )
		{
			return execStreamAccept ( props );
		}
		else if ( opcode.equals ( "FORWARD") )
		{
			return execStreamForwardIncoming( props );
		}
		else
		{
			if (_log.shouldLog(Log.DEBUG))
				_log.debug ( "Unrecognized STREAM message opcode: \""
					+ opcode + "\"" );
			try {
				notifyStreamResult(true, "I2P_ERROR",  "Unrecognized STREAM message opcode: "+opcode );
			} catch (IOException e) {}
			return false;
		}
	}

	@Override
	protected boolean execStreamConnect( Properties props) {
		// Messages are NOT sent if SILENT=true,
		// The specs said that they were.
	    	boolean verbose = !Boolean.parseBoolean(props.getProperty("SILENT"));
		try {
			if (props.isEmpty()) {
				notifyStreamResult(verbose, "I2P_ERROR","No parameters specified in STREAM CONNECT message");
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("No parameters specified in STREAM CONNECT message");
				return false;
			}
		
			String dest = props.getProperty("DESTINATION");
			if (dest == null) {
				notifyStreamResult(verbose, "I2P_ERROR", "Destination not specified in STREAM CONNECT message");
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("Destination not specified in STREAM CONNECT message");
				return false;
			}
			props.remove("DESTINATION");

			try {
				((SAMv3StreamSession)streamSession).connect( this, dest, props );
				return true ;
			} catch (DataFormatException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("Invalid destination in STREAM CONNECT message");
				notifyStreamResult ( verbose, "INVALID_KEY", null );
			} catch (ConnectException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM CONNECT failed", e);
				notifyStreamResult ( verbose, "CONNECTION_REFUSED", null );
			} catch (NoRouteToHostException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM CONNECT failed", e);
				notifyStreamResult ( verbose, "CANT_REACH_PEER", null );
			} catch (InterruptedIOException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM CONNECT failed", e);
				notifyStreamResult ( verbose, "TIMEOUT", null );
			} catch (I2PException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM CONNECT failed", e);
				notifyStreamResult ( verbose, "I2P_ERROR", e.getMessage() );
			}
		} catch (IOException e) {
		}
		return false ;
	}

	private boolean execStreamForwardIncoming( Properties props ) {
		// Messages ARE sent if SILENT=true,
		// which is different from CONNECT and ACCEPT.
		// But this matched the specs.
		try {
			try {
				streamForwardingSocket = true ;
				((SAMv3StreamSession)streamSession).startForwardingIncoming(props, sendPorts);
				notifyStreamResult( true, "OK", null );
				return true ;
			} catch (SAMException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("Forwarding STREAM connections failed", e);
				notifyStreamResult ( true, "I2P_ERROR", "Forwarding failed : " + e.getMessage() );
			}
		} catch (IOException e) {
		}
		return false ;		
	}

	private boolean execStreamAccept( Properties props )
	{
		// Messages are NOT sent if SILENT=true,
		// The specs said that they were.
	    	boolean verbose = !Boolean.parseBoolean(props.getProperty("SILENT"));
		try {
			try {
				notifyStreamResult(verbose, "OK", null);
				((SAMv3StreamSession)streamSession).accept(this, verbose);
				return true ;
			} catch (InterruptedIOException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM ACCEPT failed", e);
				notifyStreamResult( verbose, "TIMEOUT", e.getMessage() );
			} catch (I2PException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM ACCEPT failed", e);
				notifyStreamResult ( verbose, "I2P_ERROR", e.getMessage() );
			} catch (SAMException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM ACCEPT failed", e);
				notifyStreamResult ( verbose, "ALREADY_ACCEPTING", null );
			}
		} catch (IOException e) {
		}
		return false ;
	}
	

	public void notifyStreamResult(boolean verbose, String result, String message) throws IOException {
		if (!verbose) return ;
		String msgString = createMessageString(message);
		String out = "STREAM STATUS RESULT=" + result + msgString + '\n';
        
		if (!writeString(out)) {
			throw new IOException ( "Error notifying connection to SAM client" );
		}
	}

	public void notifyStreamIncomingConnection(Destination d, int fromPort, int toPort) throws IOException {
	    if (getStreamSession() == null) {
	        _log.error("BUG! Received stream connection, but session is null!");
	        throw new NullPointerException("BUG! STREAM session is null!");
	    }
	    StringBuilder buf = new StringBuilder(600);
	    buf.append(d.toBase64());
	    if (sendPorts) {
		buf.append(" FROM_PORT=").append(fromPort).append(" TO_PORT=").append(toPort);
	    }
	    buf.append('\n');
	    if (!writeString(buf.toString())) {
	        throw new IOException("Error notifying connection to SAM client");
	    }
	}
	
	public static void notifyStreamIncomingConnection(SocketChannel client, Destination d) throws IOException {
	    if (!writeString(d.toBase64() + "\n", client)) {
	        throw new IOException("Error notifying connection to SAM client");
	    }
	}
	
	/** @since 0.9.24 */
	public static void notifyStreamIncomingConnection(SocketChannel client, Destination d,
	                                                  int fromPort, int toPort) throws IOException {
	    if (!writeString(d.toBase64() + " FROM_PORT=" + fromPort + " TO_PORT=" + toPort + '\n', client)) {
	        throw new IOException("Error notifying connection to SAM client");
	    }
	}

	/** @since 0.9.24 */
	private boolean execAuthMessage(String opcode, Properties props) {
		if (opcode.equals("ENABLE")) {
			i2cpProps.setProperty(SAMBridge.PROP_AUTH, "true");
		} else if (opcode.equals("DISABLE")) {
			i2cpProps.setProperty(SAMBridge.PROP_AUTH, "false");
		} else if (opcode.equals("ADD")) {
			String user = props.getProperty("USER");
			String pw = props.getProperty("PASSWORD");
			if (user == null || pw == null)
				return writeString("AUTH STATUS RESULT=I2P_ERROR MESSAGE=\"USER and PASSWORD required\"\n");
			String prop = SAMBridge.PROP_PW_PREFIX + user + SAMBridge.PROP_PW_SUFFIX;
			if (i2cpProps.containsKey(prop))
				return writeString("AUTH STATUS RESULT=I2P_ERROR MESSAGE=\"user " + user + " already exists\"\n");
			PasswordManager pm = new PasswordManager(I2PAppContext.getGlobalContext());
			String shash = pm.createHash(pw);
			i2cpProps.setProperty(prop, shash);
		} else if (opcode.equals("REMOVE")) {
			String user = props.getProperty("USER");
			if (user == null)
				return writeString("AUTH STATUS RESULT=I2P_ERROR MESSAGE=\"USER required\"\n");
			String prop = SAMBridge.PROP_PW_PREFIX + user + SAMBridge.PROP_PW_SUFFIX;
			if (!i2cpProps.containsKey(prop))
				return writeString("AUTH STATUS RESULT=I2P_ERROR MESSAGE=\"user " + user + " not found\"\n");
			i2cpProps.remove(prop);
		} else {
			return writeString("AUTH STATUS RESULT=I2P_ERROR MESSAGE=\"Unknown AUTH command\"\n");
		}
		try {
			bridge.saveConfig();
			return writeString("AUTH STATUS RESULT=OK\n");
		} catch (IOException ioe) {
			return writeString("AUTH STATUS RESULT=I2P_ERROR MESSAGE=\"Config save failed: " + ioe + "\"\n");
		}
	}

	/**
	 * Handle a PING.
	 * Send a PONG.
	 * @since 0.9.24
	 */
	private void execPingMessage(StringTokenizer tok) {
		StringBuilder buf = new StringBuilder();
		buf.append("PONG");
		while (tok.hasMoreTokens()) {
			buf.append(' ').append(tok.nextToken());
		}
		buf.append('\n');
		writeString(buf.toString());
	}

	/**
	 * Handle a PONG.
	 * @since 0.9.24
	 */
	private void execPongMessage(StringTokenizer tok) {
		String s;
		if (tok.hasMoreTokens()) {
			s = tok.nextToken();
		} else {
			s = "";
		}
		if (_lastPing > 0) {
			String expected = Long.toString(_lastPing);
			if (expected.equals(s)) {
				_lastPing = 0;
				if (_log.shouldInfo())
					_log.warn("Got expected pong: " + s);
			} else {
				if (_log.shouldInfo())
					_log.warn("Got unexpected pong: " + s);
			}
		} else {
			if (_log.shouldWarn())
				_log.warn("Pong received without a ping: " + s);
		}
	}
}

