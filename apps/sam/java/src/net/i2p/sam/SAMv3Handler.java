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

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.RouterRestartException;
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
        // TODO remove singleton, hang off SAMBridge like dgserver
	public static final SessionsDB sSessionsHash = new SessionsDB();
	private volatile boolean stolenSocket;
	private volatile boolean streamForwardingSocket;
	private final boolean sendPorts;
	private final Object socketRLock = new Object();
	private long _lastPing;
	private static final int FIRST_READ_TIMEOUT = 60*1000;
	private static final int READ_TIMEOUT = 3*60*1000;
	private static final String AUTH_ERROR = "AUTH STATUS RESULT=I2P_ERROR";
	
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

	/**
	 *  For subsessions created by PrimarySession
	 *  @since 0.9.25
	 */
	void setSession(SAMv3RawSession sess) {
		rawSession = sess; session = sess;
	}

	/**
	 *  For subsessions created by PrimarySession
	 *  @since 0.9.25
	 */
	void setSession(SAMv3DatagramSession sess) {
		datagramSession = sess; session = sess;
	}	

	/**
	 *  For subsessions created by PrimarySession
	 *  @since 0.9.25
	 */
	void setSession(SAMv3StreamSession sess) {
		streamSession = sess; session = sess;
	}	

	@Override
	public void handle() {
		String msg = null;
		String domain = null;
		String opcode = null;
		boolean canContinue = false;
		Properties props;

		this.thread.setName("SAMv3Handler " + _id);
		if (_log.shouldLog(Log.DEBUG))
			_log.debug("SAMv3 handling started");

		try {
			Socket socket = getClientSocket().socket();
			StringBuilder buf = new StringBuilder(1024);
			boolean gotFirstLine = false;
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
									writeString(SESSION_ERROR, "PONG timeout");
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
									writeString(SESSION_ERROR, "PONG timeout");
									break;
								}
							} else if (_lastPing < 0) {
								if (_log.shouldWarn())
									_log.warn("2nd timeout");
								writeString(SESSION_ERROR, "command timeout, bye");
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
					// first time, set a timeout
					try {
						synchronized(socketRLock) {
							ReadLine.readLine(socket, buf, gotFirstLine ? 0 : FIRST_READ_TIMEOUT);
							socket.setSoTimeout(0);
						}
					} catch (SocketTimeoutException ste) {
						writeString(SESSION_ERROR, "command timeout, bye");
						break;
					}
					line = buf.toString();
				}

				if (_log.shouldLog(Log.DEBUG))
					_log.debug("New message received: [" + line + ']');
				props = SAMUtils.parseParams(line);
				domain = (String) props.remove(SAMUtils.COMMAND);
				if (domain == null) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("Ignoring newline");
					continue;
				}
				gotFirstLine = true;
				opcode = (String) props.remove(SAMUtils.OPCODE);
				if (_log.shouldLog(Log.DEBUG)) {
					_log.debug("Parsing (domain: \"" + domain
							+ "\"; opcode: \"" + opcode + "\")");
				}

				// these may not have a second token
				if (domain.equals("PING")) {
					execPingMessage(opcode);
					continue;
				} else if (domain.equals("PONG")) {
					execPongMessage(opcode);
					continue;
				} else if (domain.equals("HELP")) {
					writeString("HELP STATUS RESULT=OK MESSAGE=https://geti2p.net/en/docs/api/samv3\n");
					continue;
				} else if (domain.equals("QUIT") || domain.equals("STOP") ||
				           domain.equals("EXIT")) {
					writeString(domain + " STATUS RESULT=OK MESSAGE=bye\n");
					break;
				}

				if (opcode == null) {
					// This is not a correct message, for sure
					if (writeString(domain + " STATUS RESULT=I2P_ERROR", "missing subcommand, enter HELP for help"))
						continue;
					else
						break;
				}

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
					canContinue = writeString(domain + " STATUS RESULT=I2P_ERROR", "unsupported command, enter HELP for help");
				}

				if (!canContinue) {
					break;
				}
			} // while
		} catch (IOException e) {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("Caught IOException in handler", e);
			writeString(SESSION_ERROR, e.getMessage());
		} catch (SAMException e) {
			_log.error("Unexpected exception for message [" + msg + ']', e);
			writeString(SESSION_ERROR, e.getMessage());
		} catch (RuntimeException e) {
			_log.error("Unexpected exception for message [" + msg + ']', e);
			writeString(SESSION_ERROR, e.getMessage());
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
		boolean ok = false ;

		String nick = (String) props.remove("ID");
		if (nick == null)
			return writeString(SESSION_ERROR, "ID not specified");

		String style = (String) props.remove("STYLE");
		if (style == null && !opcode.equals("REMOVE"))
			return writeString(SESSION_ERROR, "No SESSION STYLE specified");

		SocketCloseDetector detector = null;
		try {
			if (opcode.equals("CREATE")) {
				if ((this.getRawSession()!= null) || (this.getDatagramSession() != null)
						|| (this.getStreamSession() != null)) {
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

				if (dest.equals("TRANSIENT")) {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("TRANSIENT destination requested");
					String sigTypeStr = (String) props.remove("SIGNATURE_TYPE");
					SigType sigType;
					if (sigTypeStr != null) {
						sigType = SigType.parseSigType(sigTypeStr);
						if (sigType == null) {
							return writeString(SESSION_ERROR, "SIGNATURE_TYPE "
							                   + sigTypeStr + " unsupported");
						}
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

				// Unconditionally override what the client may have set
				// (iMule sets BestEffort) as None is more efficient
				// and the client has no way to access delivery notifications
				i2cpProps.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_NONE);
				i2cpProps.setProperty("i2cp.fastReceive", "true");

				// Record the session in the database sSessionsHash
				Properties allProps = new Properties();
				allProps.putAll(i2cpProps);
				allProps.putAll(props);

				if (style.equals("PRIMARY") || style.equals("MASTER")) {
					// We must put these here, as SessionRecord.getProps() makes a copy,
					// and the socket manager is instantiated in the
					// SAMStreamSession constructor.
					allProps.setProperty("i2p.streaming.enforceProtocol", "true");
					allProps.setProperty("i2cp.dontPublishLeaseSet", "false");
				}

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
				// We block in the session constructors while tunnels are built.
				// If the client times out and closes the socket, we won't know it
				// without a separate socket monitor.
				detector = new SocketCloseDetector();

				if (style.equals("RAW")) {
					detector.start();
					SAMv3DatagramServer dgs = bridge.getV3DatagramServer(props);
					SAMv3RawSession v3 = new SAMv3RawSession(nick, dgs);
                                        rawSession = v3;
					this.session = v3;
					v3.start();
				} else if (style.equals("DATAGRAM")) {
					detector.start();
					SAMv3DatagramServer dgs = bridge.getV3DatagramServer(props);
					SAMv3DatagramSession v3 = new SAMv3DatagramSession(nick, dgs);
					datagramSession = v3;
					this.session = v3;
					v3.start();
				} else if (style.equals("STREAM")) {
					detector.start();
					SAMv3StreamSession v3 = newSAMStreamSession(nick);
					streamSession = v3;
					this.session = v3;
					v3.start();
				} else if (style.equals("PRIMARY") || style.equals("MASTER")) {
					detector.start();
					SAMv3DatagramServer dgs = bridge.getV3DatagramServer(props);
					PrimarySession v3 = new PrimarySession(nick, dgs, this, allProps);
					streamSession = v3;
					datagramSession = v3;
                                        rawSession = v3;
					this.session = v3;
					v3.start();
				} else {
					if (_log.shouldLog(Log.DEBUG))
						_log.debug("Unsupported SESSION STYLE: \"" + style +"\"");
					return writeString(SESSION_ERROR, "Unrecognized SESSION STYLE");
				}
				// kill the detector
				detector.done = true;
				synchronized(socketRLock) {
					detector.interrupt();
				}
				String ignoredCommand = detector.ignoredCommand;
				detector = null;
				ok = true;
				boolean rv = writeString("SESSION STATUS RESULT=OK DESTINATION=" + dest + '\n');
				if (rv && ignoredCommand != null)
					rv = writeString(ignoredCommand + " STATUS RESULT=I2P_ERROR", "invalid state");
				return rv;
			} else if (opcode.equals("ADD") || opcode.equals("REMOVE")) {
                                // prevent trouble in finally block
				ok = true;
				if (streamSession == null || datagramSession == null || rawSession == null)
					return writeString(SESSION_ERROR, "Not a PRIMARY session");
				PrimarySession msess = (PrimarySession) session;
				String msg;
				if (opcode.equals("ADD")) {
					msg = msess.add(nick, style, props);
				} else {
					msg = msess.remove(nick, props);
				}
				if (msg == null)
					return writeString("SESSION STATUS RESULT=OK ID=\"" + nick + '"', opcode + ' ' + nick);
				else
					return writeString(SESSION_ERROR + " ID=\"" + nick + '"', msg);
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
		} finally {
			if (detector != null) {
				// kill the detector
				detector.done = true;
				synchronized(socketRLock) {
					detector.interrupt();
				}
				String ignoredCommand = detector.ignoredCommand;
				if (ignoredCommand != null)
					writeString(ignoredCommand + " STATUS RESULT=I2P_ERROR", "invalid state");
			}
			// unregister the session if it has not been created
			if ( !ok && nick!=null ) {
				sSessionsHash.del(nick) ;
				session = null ;
			}
		}
	}

	/**
	 *  Check for socket close while tunnels are being built,
	 *  by doing what is hopefully a dummy read for the next command.
	 *  Interrupt the handler if it happens.
	 *  After tunnel build success or failure, the handler will interrupt us.
	 *
	 *  If the command is QUIT or equivalent, do that.
	 *  If it's anything else, set ignoredCommand, and execSessionMessage() will deal with it.
	 *
	 *  @since 0.9.58
	 */
	private class SocketCloseDetector extends I2PAppThread {
		private final Thread _handler = Thread.currentThread();
		public volatile String ignoredCommand;
		public volatile boolean done;

		public SocketCloseDetector() {
			super("SAM control socket close detector");
		}

		@Override
		public void run() {
			StringBuilder buf = new StringBuilder();
			try {
				Socket s = socket.socket();
				InputStream in = s.getInputStream();
				do {
					try {
						//_log.debug("sleeping...");
						Thread.sleep(1000);
					} catch (InterruptedException ie) {
						break;
					}
					// Only read under lock
					// And execSessionMessage() must lock before interrupting us
					// Because interrupting a SocketChannel read will close the socket
					synchronized(socketRLock) {
						s.setSoTimeout(20);
						try {
							// we could use ReadLine here, but let's keep it simple
							while (true) {
								int c = in.read();
								if (c < 0)
									throw new IOException("Socket closed");
								if (c == '\n') {
									String line = buf.toString();
									buf.setLength(0);					
									try {
										Properties props = SAMUtils.parseParams(line);
										String domain = props.getProperty(SAMUtils.COMMAND);
										if (domain == null)
											continue;  // empty line
										if (domain.equals("QUIT") || domain.equals("STOP") || domain.equals("EXIT")) {
											_log.error("SAM socket closed while waiting for tunnels to build");
											writeString(SESSION_ERROR, "Tunnel build interrupted");
											writeString(domain + " STATUS RESULT=OK", "bye");
											try { closeClientSocket(); } catch (IOException ioe) {}
											_handler.interrupt();
											return;
										}
										ignoredCommand = domain;
									} catch (SAMException e) {
										ignoredCommand = "SESSION";
									}
									if (_log.shouldWarn())
										_log.warn("Ignoring SAM command during tunnel build: " + line);
								}
								buf.append((char) c);
							}
						} catch (SocketTimeoutException ste) {}
						s.setSoTimeout(0);
					}
				} while (!done);
				if (_log.shouldWarn())
					_log.warn("Detector exit after tunnel build");
			} catch (IOException ioe) {
				_log.error("SAM socket closed while waiting for tunnels to build", ioe);
				_handler.interrupt();
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
			_log.error("v3 control socket cannot be used for STREAM");
			try {
				notifyStreamResult(true, "I2P_ERROR", "v3 control socket cannot be used for STREAM");
			} catch (IOException e) {}
			return false;
		}

		nick = (String) props.remove("ID");
		if (nick == null) {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("SESSION ID parameter not specified");
			try {
				notifyStreamResult(true, "I2P_ERROR", "ID not specified");
			} catch (IOException e) {}
			return false ;
		}

		rec = sSessionsHash.get(nick);
		if ( rec==null ) {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("STREAM SESSION ID does not exist");
			try {
				notifyStreamResult(true, "INVALID_ID", "STREAM SESSION ID " + nick + " does not exist");
			} catch (IOException e) {}
			return false ;
		}
		
		SAMv3Handler ctl = rec.getHandler();
		streamSession = ctl.streamSession;
		if (streamSession==null) {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("specified ID is not a stream session");
			try {
				notifyStreamResult(true, "I2P_ERROR",  "specified ID " + nick + " is not a STREAM session");
			} catch (IOException e) {}
			return false ;
		}
		if (streamSession.isDestroyed()) {
			if (_log.shouldDebug())
				_log.debug("Session manager is destroyed");
			try {
				notifyStreamResult(true, "I2P_ERROR",  "Session is closed");
			} catch (IOException e) {}
			ctl.writeString(SESSION_ERROR, "Session is closed");
			ctl.stopHandling();
			return false;
		}

		if ( opcode.equals ( "CONNECT" ) )
		{
			return execStreamConnect ( props );
		} 
		else if ( opcode.equals ( "ACCEPT" ) )
		{
			try {
				return execStreamAccept(props);
			} catch (I2PSessionException ise) {
				ctl.writeString(SESSION_ERROR, ise.getMessage());
				ctl.stopHandling();
				return false;
			} catch (RouterRestartException rre) {
				ctl.writeString(SESSION_ERROR, "Router restart");
				ctl.stopHandling();
				return false;
			}
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
		
			String dest = (String) props.remove("DESTINATION");
			if (dest == null) {
				notifyStreamResult(verbose, "I2P_ERROR", "Destination not specified in STREAM CONNECT message");
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("Destination not specified in STREAM CONNECT message");
				return false;
			}

			try {
				((SAMv3StreamSession)streamSession).connect( this, dest, props );
				return true ;
			} catch (DataFormatException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("Invalid destination in STREAM CONNECT message");
				notifyStreamResult ( verbose, "INVALID_KEY", e.getMessage());
			} catch (ConnectException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM CONNECT failed", e);
				notifyStreamResult ( verbose, "CONNECTION_REFUSED", e.getMessage());
			} catch (NoRouteToHostException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM CONNECT failed", e);
				notifyStreamResult ( verbose, "CANT_REACH_PEER", e.getMessage());
			} catch (InterruptedIOException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM CONNECT failed", e);
				notifyStreamResult ( verbose, "TIMEOUT", e.getMessage());
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

	/**
	 * @return success
	 * @throws I2PSessionException if socket manager is destroyed while waiting
	 */
	private boolean execStreamAccept(Properties props) throws I2PSessionException, RouterRestartException
	{
		// Messages are NOT sent if SILENT=true,
		// The specs said that they were.
	    	boolean verbose = !Boolean.parseBoolean(props.getProperty("SILENT"));
		try {
			try {
				// execStreamSession() above checks if session is destroyed first
				notifyStreamResult(verbose, "OK", null);
				((SAMv3StreamSession)streamSession).accept(this, verbose);
				return true ;
			} catch (InterruptedIOException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM ACCEPT failed", e);
				notifyStreamResult( verbose, "TIMEOUT", e.getMessage() );
			} catch (I2PSessionException e) {
				// As of 0.9.60, this is thrown for a destroyed session.
				// Kill the SAM session.
				if (_log.shouldDebug())
					_log.debug("STREAM ACCEPT failed", e);
				notifyStreamResult (verbose, "I2P_ERROR", e.getMessage());
				// throw so caller can close control socket
				throw e;
			} catch (RouterRestartException e) {
				// Kill the SAM session.
				if (_log.shouldDebug())
					_log.debug("STREAM ACCEPT failed", e);
				notifyStreamResult (verbose, "I2P_ERROR", "Router restart");
				// throw so caller can close control socket
				throw e;
			} catch (I2PException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM ACCEPT failed", e);
				notifyStreamResult ( verbose, "I2P_ERROR", e.getMessage() );
			} catch (SAMException e) {
				if (_log.shouldLog(Log.DEBUG))
					_log.debug("STREAM ACCEPT failed", e);
				notifyStreamResult ( verbose, "ALREADY_ACCEPTING", e.getMessage());
			}
		} catch (IOException e) {
		}
		return false ;
	}
	

	/**
	 * @param verbose if false, does nothing
	 * @param result non-null
	 * @param message may be null
	 */
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
				return writeString(AUTH_ERROR, "USER and PASSWORD required");
			String prop = SAMBridge.PROP_PW_PREFIX + user + SAMBridge.PROP_PW_SUFFIX;
			if (i2cpProps.containsKey(prop))
				return writeString(AUTH_ERROR, "user " + user + " already exists");
			PasswordManager pm = new PasswordManager(I2PAppContext.getGlobalContext());
			String shash = pm.createHash(pw);
			i2cpProps.setProperty(prop, shash);
		} else if (opcode.equals("REMOVE")) {
			String user = props.getProperty("USER");
			if (user == null)
				return writeString(AUTH_ERROR, "USER required");
			String prop = SAMBridge.PROP_PW_PREFIX + user + SAMBridge.PROP_PW_SUFFIX;
			if (!i2cpProps.containsKey(prop))
				return writeString(AUTH_ERROR, "user " + user + " not found");
			i2cpProps.remove(prop);
		} else {
			return writeString(AUTH_ERROR, "Unknown AUTH command");
		}
		try {
			bridge.saveConfig();
			return writeString("AUTH STATUS RESULT=OK\n");
		} catch (IOException ioe) {
			return writeString(AUTH_ERROR, "Config save failed: " + ioe);
		}
	}

	/**
	 * Handle a PING.
	 * Send a PONG.
	 *
	 * @param msg to append, may be null
	 * @since 0.9.24
	 */
	private void execPingMessage(String msg) {
		StringBuilder buf = new StringBuilder();
		buf.append("PONG");
		if (msg != null) {
			buf.append(' ').append(msg);
		}
		buf.append('\n');
		writeString(buf.toString());
	}

	/**
	 * Handle a PONG.
	 *
	 * @param s received, may be null
	 * @since 0.9.24
	 */
	private void execPongMessage(String s) {
		if (s == null) {
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

