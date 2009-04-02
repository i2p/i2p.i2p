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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.NoRouteToHostException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.HashMap;
import java.util.StringTokenizer;

import net.i2p.I2PException;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;
import net.i2p.data.VerifiedDestination;
import net.i2p.util.I2PAppThread;

/**
 * Class able to handle a SAM version 3 client connection.
 *
 * @author mkvore
 */

public class SAMv3Handler extends SAMv1Handler
{
	private final static Log _log = new Log ( SAMv3Handler.class );

	protected SAMv3StreamSession streamSession = null ;
	protected SAMv3RawSession rawSession = null ;
	protected SAMv3DatagramSession datagramSession = null ;
	
	protected SAMDatagramSession getDatagramSession() {
		return datagramSession ;
	}
	
	protected SAMRawSession getRawSession() {
		return rawSession ;
	}
	
	protected Session session = null ;
	
	interface Session {
		String getNick();
		void close();
		boolean sendBytes(String dest, byte[] data) throws DataFormatException;
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
	public SAMv3Handler ( SocketChannel s, int verMajor, int verMinor ) throws SAMException, IOException
	{
		this ( s, verMajor, verMinor, new Properties() );
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

	public SAMv3Handler ( SocketChannel s, int verMajor, int verMinor, Properties i2cpProps ) throws SAMException, IOException
	{
		super ( s, verMajor, verMinor, i2cpProps );
		_log.debug("SAM version 3 handler instantiated");
	}

	public boolean verifVersion()
	{
		return (verMajor == 3 && verMinor == 0) ;
	}

	static public class DatagramServer  {
		
		private static DatagramServer _instance = null ;
		private static DatagramChannel server = null ;
		
		public static DatagramServer getInstance() throws IOException {
			return getInstance(new Properties());
		}
		
		public static DatagramServer getInstance(Properties props) throws IOException {
			if (_instance==null) {
				_instance = new DatagramServer(props);
			}
			return _instance ;
		}
		
		public DatagramServer(Properties props) throws IOException {
			if (server==null) {
				server = DatagramChannel.open();
			}
			
			String host = props.getProperty(SAMBridge.PROP_DATAGRAM_HOST, SAMBridge.DEFAULT_DATAGRAM_HOST);
			String portStr = props.getProperty(SAMBridge.PROP_DATAGRAM_PORT, SAMBridge.DEFAULT_DATAGRAM_PORT);
			int port ;
			try {
				port = Integer.parseInt(portStr);
			} catch (NumberFormatException e) {
				port = Integer.parseInt(SAMBridge.DEFAULT_DATAGRAM_PORT);
			}
			
			server.socket().bind(new InetSocketAddress(host, port));
			new I2PAppThread(new Listener(server), "DatagramListener").start();
		}
		
		public void send(SocketAddress addr, ByteBuffer msg) throws IOException {
			server.send(msg, addr);
		}
		
		class Listener implements Runnable {
			
			DatagramChannel server = null;
			
			public Listener(DatagramChannel server)
			{
				this.server = server ;
			}
			public void run()
			{
				ByteBuffer inBuf = ByteBuffer.allocateDirect(SAMRawSession.RAW_SIZE_MAX+1024);
				
				while (!Thread.interrupted())
				{
					inBuf.clear();
					try {
						server.receive(inBuf);
					} catch (IOException e) {
						break ;
					}
					inBuf.flip();
					ByteBuffer outBuf = ByteBuffer.wrap(new byte[inBuf.remaining()]);
					outBuf.put(inBuf);
					outBuf.flip();
					new I2PAppThread(new MessageDispatcher(outBuf.array()), "MessageDispatcher").start();
				}
			}
		}
	}

	public static class MessageDispatcher implements Runnable
	{
		ByteArrayInputStream is = null ;
		
		public MessageDispatcher(byte[] buf)
		{
			this.is = new java.io.ByteArrayInputStream(buf) ;
		}
		
		public void run() {
			String header = null ;
			String nick ;
			String dest ;

			try {
				header = DataHelper.readLine(is).trim();
				StringTokenizer tok = new StringTokenizer(header, " ");
				if (tok.countTokens() != 2) {
					// This is not a correct message, for sure
					_log.debug("Error in message format");
					return;
				}
				nick = tok.nextToken();
				dest = tok.nextToken();

				byte[] data = new byte[is.available()];
				is.read(data);
				SessionRecord rec = sSessionsHash.get(nick);
				if (rec!=null) {
					rec.getHandler().session.sendBytes(dest,data);
				}
			} catch (Exception e) {}
		}
	}
	
	public class SessionRecord
	{
		protected String m_dest ;
		protected Properties m_props ;
		protected ThreadGroup m_threadgroup ;
		protected SAMv3Handler m_handler ;

		public SessionRecord( String dest, Properties props, SAMv3Handler handler )
		{
			m_dest = new String(dest) ; 
			m_props = new Properties() ;
			m_props.putAll(props);
			m_threadgroup = null ;
			m_handler = handler ;
		}

		public SessionRecord( SessionRecord in )
		{
			m_dest = in.getDest();
			m_props = in.getProps();
			m_threadgroup = in.getThreadGroup();
			m_handler = in.getHandler();
		}

		synchronized public String getDest()
		{
			return new String(m_dest) ;
		}
		synchronized public Properties getProps()
		{
			Properties p = new Properties();
			p.putAll(m_props);
			return m_props;
		}
		synchronized public SAMv3Handler getHandler()
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

	public static class SessionsDB
	{
		static final long serialVersionUID = 0x1 ;

		HashMap<String, SessionRecord> map ;

		public SessionsDB() {
			map = new HashMap<String, SessionRecord>() ;
		}

		synchronized public boolean put( String nick, SessionRecord session ) 
		{
			if ( !map.containsKey(nick) ) {
				session.createThreadGroup("SAM session "+nick);
				map.put(nick, session) ;
				return true ;
			}
			else
				return false ;
		}
		synchronized public boolean del( String nick )
		{
			SessionRecord rec = map.get(nick);
			
			if ( rec!=null ) {
				map.remove(nick);
				return true ;
			}
			else
				return false ;
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

	public static SessionsDB sSessionsHash = new SessionsDB() ;

	public String getClientIP()
	{
		return this.socket.socket().getInetAddress().getHostAddress();
	}
	
	boolean stolenSocket = false ;
	
	public void stealSocket()
	{
		stolenSocket = true ;
		this.stopHandling();
	}
	
	public void handle() {
		String msg = null;
		String domain = null;
		String opcode = null;
		boolean canContinue = false;
		StringTokenizer tok;
		Properties props;

		this.thread.setName("SAMv3Handler " + _id);
		_log.debug("SAM handling started");

		try {
			InputStream in = getClientSocket().socket().getInputStream();

			while (true) {
				if (shouldStop()) {
					_log.debug("Stop request found");
					break;
				}

				msg = DataHelper.readLine(in).trim();
				if (msg == null) {
					_log.debug("Connection closed by client");
					break;
				}

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
			
			if (!this.stolenSocket)
			{
				try {
					closeClientSocket();
				} catch (IOException e) {
					_log.error("Error closing socket: " + e.getMessage());
				}
			}

			die();
		}
	}

	protected void die() {
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


				if (dest.equals("TRANSIENT")) {
					_log.debug("TRANSIENT destination requested");
					ByteArrayOutputStream priv = new ByteArrayOutputStream(640);
					SAMUtils.genRandomKey(priv, null);

					dest = Base64.encode(priv.toByteArray());
				} else {
					_log.debug("Custom destination specified [" + dest + "]");
				}

				boolean good_key = false ;
				try {
					good_key = (new VerifiedDestination(dest)).verifyCert(true);
				} catch (DataFormatException e) {
					good_key = false ;
				}
				if (!good_key)
				{
					_log.debug("Bad destination key");
					return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"bad destination key\"\n");
				}

				nick = props.getProperty("ID");
				if (nick == null) {
					_log.debug("SESSION ID parameter not specified");
					return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"ID not specified\"\n");
				}
				props.remove("ID");


				String style = props.getProperty("STYLE");
				if (style == null) {
					_log.debug("SESSION STYLE parameter not specified");
					return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"No SESSION STYLE specified\"\n");
				}
				props.remove("STYLE");



				// Record the session in the database sSessionsHash
				Properties allProps = new Properties();
				allProps.putAll(i2cpProps);
				allProps.putAll(props);
				
				if (! sSessionsHash.put( nick, new SessionRecord(dest, allProps, this) ) ) {
					_log.debug("SESSION ID parameter already in use");
					String n = nick ;
					return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"ID "+n+" already in use\"\n");
				}

				// Create the session

				if (style.equals("RAW")) {
					DatagramServer.getInstance(i2cpProps);
					rawSession = newSAMRawSession(nick);
					this.session = rawSession ;
				} else if (style.equals("DATAGRAM")) {
					DatagramServer.getInstance(i2cpProps);
					datagramSession = newSAMDatagramSession(nick);
					this.session = datagramSession ;
				} else if (style.equals("STREAM")) {
					streamSession = newSAMStreamSession(nick);
					this.session = streamSession ;
				} else {
					_log.debug("Unrecognized SESSION STYLE: \"" + style +"\"");
					return writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"Unrecognized SESSION STYLE\"\n");
				}
				ok = true ;
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
		} finally {
			// unregister the session if it has not been created
			if ( !ok && nick!=null ) {
				sSessionsHash.del(nick) ;
				session = null ;
			}
		}
	}

	SAMv3StreamSession newSAMStreamSession(String login )
	throws IOException, DataFormatException, SAMException
	{
		return new SAMv3StreamSession( login ) ;
	}

	SAMv3RawSession newSAMRawSession(String login )
	throws IOException, DataFormatException, SAMException, I2PSessionException
	{
		return new SAMv3RawSession( login ) ;
	}

	SAMv3DatagramSession newSAMDatagramSession(String login )
	throws IOException, DataFormatException, SAMException, I2PSessionException
	{
		return new SAMv3DatagramSession( login ) ;
	}

	/* Parse and execute a STREAM message */
	protected boolean execStreamMessage ( String opcode, Properties props )
	{
		String nick = null ;
		SessionRecord rec = null ;

		if ( session != null )
		{
			_log.error ( "STREAM message received, but this session is a master session" );
			writeString("STREAM STATUS RESULT=I2P_ERROR MESSAGE=\"master session cannot be used for streams");
			return false;
		}

		nick = props.getProperty("ID");
		if (nick == null) {
			_log.debug("SESSION ID parameter not specified");
			writeString("STREAM STATUS RESULT=I2P_ERROR MESSAGE=\"ID not specified\"\n");
			return false ;
		}
		props.remove("ID");

		rec = sSessionsHash.get(nick);

		if ( rec==null ) {
			_log.debug("STREAM SESSION ID does not exist");
			writeString("STREAM STATUS RESULT=I2P_ERROR MESSAGE=\"STREAM SESSION ID does not exist\"\n");
			return false ;
		}
		
		streamSession = rec.getHandler().streamSession ;
		
		if (streamSession==null) {
			_log.debug("specified ID is not a stream session");
			writeString("STREAM STATUS RESULT=I2P_ERROR MESSAGE=\"specified ID is not a STREAM session\"\n");
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
			_log.debug ( "Unrecognized RAW message opcode: \""
					+ opcode + "\"" );
			writeString("STREAM STATUS RESULT=I2P_ERROR MESSAGE=\"Unrecognized RAW message opcode: \""
					+ opcode + "\"" );
			return false;
		}
	}

	protected boolean execStreamConnect( Properties props) {
		if (props == null) {
			_log.debug("No parameters specified in STREAM CONNECT message");
			return false;
		}
		boolean verbose = props.getProperty("SILENT","false").equals("false");
		
		String dest = props.getProperty("DESTINATION");
		if (dest == null) {
			_log.debug("Destination not specified in RAW SEND message");
			return false;
		}
		props.remove("DESTINATION");

		try {
			try {
				streamSession.connect( this, dest, props );
				return true ;
			} catch (DataFormatException e) {
				_log.debug("Invalid destination in STREAM CONNECT message");
				if (verbose) notifyStreamAccept ( "INVALID_KEY" );
			} catch (ConnectException e) {
				_log.debug("STREAM CONNECT failed: " + e.getMessage());
				if (verbose) notifyStreamAccept ( "CONNECTION_REFUSED" );
			} catch (NoRouteToHostException e) {
				_log.debug("STREAM CONNECT failed: " + e.getMessage());
				if (verbose) notifyStreamAccept ( "CANT_REACH_PEER" );
			} catch (InterruptedIOException e) {
				_log.debug("STREAM CONNECT failed: " + e.getMessage());
				if (verbose) notifyStreamAccept ( "TIMEOUT" );
			} catch (I2PException e) {
				_log.debug("STREAM CONNECT failed: " + e.getMessage());
				if (verbose) notifyStreamAccept ( "I2P_ERROR" );
			}
		} catch (IOException e) {
		}
		return false ;
	}

	protected boolean execStreamForwardIncoming( Properties props ) {
		try {
			try {
				streamSession.startForwardingIncoming(props);
				notifyStreamAccept("OK");
				return true ;
			} catch (SAMException e) {
				_log.debug("Forwarding STREAM connections failed: " + e.getMessage());
				notifyStreamAccept ( "FORWARDER_FAILED" );
			}
		} catch (IOException e) {
		}
		return false ;		
	}

	protected boolean execStreamAccept( Properties props )
	{
		boolean verbose = props.getProperty( "SILENT", "false").equals("false");
		try {
			try {
				streamSession.accept(this, verbose);
				return true ;
			} catch (InterruptedIOException e) {
				_log.debug("STREAM ACCEPT failed: " + e.getMessage());
				if (verbose) notifyStreamAccept( "TIMEOUT" );
			} catch (I2PException e) {
				_log.debug("STREAM ACCEPT failed: " + e.getMessage());
				if (verbose) notifyStreamAccept ( "I2P_ERROR" );
			} catch (SAMException e) {
				_log.debug("STREAM ACCEPT failed: " + e.getMessage());
				if (verbose) notifyStreamAccept ( "ALREADY_ACCEPTING" );
			}
		} catch (IOException e) {
		}
		return false ;
	}
	

	public void notifyStreamAccept(String status) throws IOException
    {
        if ( streamSession == null )
        {
            _log.error ( "BUG! Received stream connection, but session is null!" );
            throw new NullPointerException ( "BUG! STREAM session is null!" );
        }

        if ( !writeString ( "STREAM STATUS RESULT="
                        + status
                        + "\n" ) )
        {
            throw new IOException ( "Error notifying connection to SAM client" );
        }
    }

	public void notifyStreamOutgoingConnection(String result) throws IOException
    {
        if ( streamSession == null )
        {
            _log.error ( "BUG! Received stream connection, but session is null!" );
            throw new NullPointerException ( "BUG! STREAM session is null!" );
        }

        if ( !writeString ( "STREAM STATUS RESULT="
                        + result
                        + "\n" ) )
        {
            throw new IOException ( "Error notifying connection to SAM client" );
        }
    }

	public void notifyStreamIncomingConnection(Destination d) throws IOException {
	    if (streamSession == null) {
	        _log.error("BUG! Received stream connection, but session is null!");
	        throw new NullPointerException("BUG! STREAM session is null!");
	    }

	    if (!writeString(d.toBase64() + "\n")) {
	        throw new IOException("Error notifying connection to SAM client");
	    }
	}
	
	public static void notifyStreamIncomingConnection(SocketChannel client, Destination d) throws IOException {
	    if (!writeString(d.toBase64() + "\n", client)) {
	        throw new IOException("Error notifying connection to SAM client");
	    }
	}

}

