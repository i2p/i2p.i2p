package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.SocketChannel;
import java.security.GeneralSecurityException;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;

/**
 * SAMv3 STREAM session class.
 *
 * @author mkvore
 */

class SAMv3StreamSession  extends SAMStreamSession implements Session
{

		private static final int BUFFER_SIZE = 1024;
		private static final int MAX_ACCEPT_QUEUE = 64;
		
		private final Object socketServerLock = new Object();
		/** this is ONLY set for FORWARD, not for ACCEPT */
		private I2PServerSocket socketServer;
		/** this is the count of active ACCEPT sockets */
		private final AtomicInteger _acceptors = new AtomicInteger();
		/** for subsession only, null otherwise */
		private final LinkedBlockingQueue<I2PSocket> _acceptQueue;

		private static I2PSSLSocketFactory _sslSocketFactory;
	
		private final String nick ;
		
		public String getNick() {
			return nick ;
		}
		
	   /**
	     * Create a new SAM STREAM session, according to information
	     * registered with the given nickname
	     *
	     * Caller MUST call start().
	     *
	     * @param login The nickname
	     * @throws IOException
	     * @throws DataFormatException
	     * @throws SAMException 
	     * @throws NullPointerException if login nickname is not registered
	     */
	    public SAMv3StreamSession(String login)
	    		throws IOException, DataFormatException, SAMException
	    {
                super(getDB().get(login).getDest(), "__v3__",
                      getDB().get(login).getProps(),
                      getDB().get(login).getHandler());
	    	this.nick = login ;
		_acceptQueue = null;
	    }

	    /**
	     *   Build a Stream Session on an existing I2P session
	     *   registered with the given nickname
	     *   
	     * Caller MUST call start().
	     *
	     * @param login nickname of the session
	     * @throws IOException
	     * @throws DataFormatException
	     * @since 0.9.25
	     */
	    public SAMv3StreamSession(String login, Properties props, SAMv3Handler handler, I2PSocketManager mgr,
	                                int listenPort) throws IOException, DataFormatException, SAMException {
		super(mgr, props, handler, listenPort);
		this.nick = login ;
		_acceptQueue = new LinkedBlockingQueue<I2PSocket>(MAX_ACCEPT_QUEUE);
	    }

	    /**
	     * Put a socket on the accept queue.
	     * Only for subsession, throws IllegalStateException otherwise.
	     *   
	     * @return success, false if full
	     * @since 0.9.25
	     */
	    public boolean queueSocket(I2PSocket sock) {
		if (_acceptQueue == null)
		    throw new IllegalStateException();
		return _acceptQueue.offer(sock);
	    }

	    /**
	     * Take a socket from the accept queue.
	     * Only for subsession, throws IllegalStateException otherwise.
	     *   
	     * @since 0.9.25
	     */
	    private I2PSocket acceptSocket() throws ConnectException {
		if (_acceptQueue == null)
		    throw new IllegalStateException();
		try {
			// TODO there's no CoDel or expiration in this queue
			return _acceptQueue.take();
		} catch (InterruptedException ie) {
			ConnectException ce = new ConnectException("interrupted");
			ce.initCause(ie);
			throw ce;
		}
	    }

	    public static SessionsDB getDB()
	    {
	    	return SAMv3Handler.sSessionsHash ;
	    }

	    /**
	     * Connect the SAM STREAM session to the specified Destination
	     * for a single connection, using the socket stolen from the handler.
	     *
	     * @param handler The handler that communicates with the requesting client
	     * @param dest Base64-encoded Destination to connect to
	     * @param props Options to be used for connection
	     *
	     * @throws DataFormatException if the destination is not valid
	     * @throws ConnectException if the destination refuses connections
	     * @throws NoRouteToHostException if the destination can't be reached
	     * @throws InterruptedIOException if the connection timeouts
	     * @throws I2PException if there's another I2P-related error
	     * @throws IOException 
	     */
	    public void connect ( SAMv3Handler handler, String dest, Properties props ) 
	        throws I2PException, ConnectException, NoRouteToHostException, 
	    		DataFormatException, InterruptedIOException, IOException {

	    	boolean verbose = !Boolean.parseBoolean(props.getProperty("SILENT"));
	        Destination d = SAMUtils.getDest(dest);

	        I2PSocketOptions opts = socketMgr.buildOptions(props);
	        if (props.getProperty(I2PSocketOptions.PROP_CONNECT_TIMEOUT) == null)
	            opts.setConnectTimeout(60 * 1000);
	        String fromPort = props.getProperty("FROM_PORT");
	        if (fromPort != null) {
	            try {
	                opts.setLocalPort(Integer.parseInt(fromPort));
	            } catch (NumberFormatException nfe) {
	                throw new I2PException("Bad port " + fromPort);
	            }
	        }
	        String toPort = props.getProperty("TO_PORT");
	        if (toPort != null) {
	            try {
	                opts.setPort(Integer.parseInt(toPort));
	            } catch (NumberFormatException nfe) {
	                throw new I2PException("Bad port " + toPort);
	            }
	        }

	        if (_log.shouldLog(Log.DEBUG))
	            _log.debug("Connecting new I2PSocket...");

	        // blocking connection (SAMv3)

	        I2PSocket i2ps = socketMgr.connect(d, opts);

	        SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
	        
	        if ( rec==null ) throw new InterruptedIOException() ;
	        
	        handler.notifyStreamResult(verbose, "OK", null) ;

	        handler.stealSocket() ;
	        
	        ReadableByteChannel fromClient = handler.getClientSocket();
	        ReadableByteChannel fromI2P    = Channels.newChannel(i2ps.getInputStream());
	        WritableByteChannel toClient   = handler.getClientSocket();
	        WritableByteChannel toI2P      = Channels.newChannel(i2ps.getOutputStream());
	        
		SAMBridge bridge = handler.getBridge();
		(new I2PAppThread(rec.getThreadGroup(),
		            new Pipe(fromClient, toI2P, bridge),
		            "ConnectV3 SAMPipeClientToI2P")).start();
		(new I2PAppThread(rec.getThreadGroup(),
		            new Pipe(fromI2P, toClient, bridge),
		            "ConnectV3 SAMPipeI2PToClient")).start();
	    }

	    /**
	     * Accept a single incoming STREAM on the socket stolen from the handler.
	     * As of version 3.2 (0.9.24), multiple simultaneous accepts are allowed.
	     * Accepts and forwarding may not be done at the same time.
	     *
	     * @param handler The handler that communicates with the requesting client
	     * @param verbose If true, SAM will send the Base64-encoded peer Destination of an
	     *                incoming socket as the first line of data sent to its client
	     *                on the handler socket
	     *
	     * @throws DataFormatException if the destination is not valid
	     * @throws ConnectException if the destination refuses connections
	     * @throws NoRouteToHostException if the destination can't be reached
	     * @throws InterruptedIOException if the connection timeouts
	     * @throws I2PException if there's another I2P-related error
	     * @throws IOException 
	     */
	    public void accept(SAMv3Handler handler, boolean verbose) 
	    	throws I2PException, InterruptedIOException, IOException, SAMException {

		synchronized(this.socketServerLock) {
			if (this.socketServer != null) {
				if (_log.shouldWarn())
					_log.warn("a forwarding server is already defined for this destination");
				throw new SAMException("a forwarding server is already defined for this destination");
			}
		}

		I2PSocket i2ps = null;
		_acceptors.incrementAndGet();
		try {
			if (_acceptQueue != null)
				i2ps = acceptSocket();
			else
				i2ps = socketMgr.getServerSocket().accept();
		} finally {
			_acceptors.decrementAndGet();
		}

	    	SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);

		if ( rec==null || i2ps==null ) throw new InterruptedIOException() ;

		if (verbose) {
			handler.notifyStreamIncomingConnection(i2ps.getPeerDestination(),
			                                       i2ps.getPort(), i2ps.getLocalPort());
		}
	        handler.stealSocket() ;
	        ReadableByteChannel fromClient = handler.getClientSocket();
	        ReadableByteChannel fromI2P    = Channels.newChannel(i2ps.getInputStream());
	        WritableByteChannel toClient   = handler.getClientSocket();
	        WritableByteChannel toI2P      = Channels.newChannel(i2ps.getOutputStream());
	        
		SAMBridge bridge = handler.getBridge();
		(new I2PAppThread(rec.getThreadGroup(),
		            new Pipe(fromClient, toI2P, bridge),
		            "AcceptV3 SAMPipeClientToI2P")).start();
		(new I2PAppThread(rec.getThreadGroup(),
		            new Pipe(fromI2P, toClient, bridge),
		            "AcceptV3 SAMPipeI2PToClient")).start();
	    }

	    
	    /**
	     *  Forward sockets from I2P to the host/port provided.
	     *  Accepts and forwarding may not be done at the same time.
	     */
	    public void startForwardingIncoming(Properties props, boolean sendPorts) throws SAMException, InterruptedIOException
	    {
	    	SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
	    	boolean verbose = !Boolean.parseBoolean(props.getProperty("SILENT"));
	        
	        if ( rec==null ) throw new InterruptedIOException() ;
	        
	    	String portStr = props.getProperty("PORT") ;
	    	if ( portStr==null ) {
	                if (_log.shouldLog(Log.DEBUG))
	    			_log.debug("receiver port not specified");
	    		throw new SAMException("receiver port not specified");
	    	}
	    	int port = Integer.parseInt(portStr);
	    	
	    	String host = props.getProperty("HOST");
	    	if ( host==null ) {
	    		host = rec.getHandler().getClientIP();
	                if (_log.shouldLog(Log.DEBUG))
		    		_log.debug("no host specified. Taken from the client socket : " + host +':'+port);
	    	}
		boolean isSSL = Boolean.parseBoolean(props.getProperty("SSL"));
		if (_acceptors.get() > 0) {
			if (_log.shouldWarn())
				_log.warn("an accepting server is already defined for this destination");
			throw new SAMException("an accepting server is already defined for this destination");
		}
		synchronized(this.socketServerLock) {
			if (this.socketServer!=null) {
				if (_log.shouldWarn())
					_log.warn("a forwarding server is already defined for this destination");
				throw new SAMException("a forwarding server is already defined for this destination");
			}
	    		this.socketServer = this.socketMgr.getServerSocket();
	    	}
	    	
	    	SocketForwarder forwarder = new SocketForwarder(host, port, isSSL, verbose, sendPorts);
	    	(new I2PAppThread(rec.getThreadGroup(), forwarder, "SAMV3StreamForwarder")).start();
	    }
	    
	    /**
	     *  Forward sockets from I2P to the host/port provided
	     */
	    private class SocketForwarder implements Runnable
	    {
	    	private final String host;
	    	private final int port;
	    	private final boolean isSSL, verbose, sendPorts;
	    	
	    	SocketForwarder(String host, int port, boolean isSSL,
		                boolean verbose, boolean sendPorts) {
	    		this.host = host ;
	    		this.port = port ;
	    		this.verbose = verbose ;
	    		this.sendPorts = sendPorts;
			this.isSSL = isSSL;
	    	}
	    	
	    	public void run()
	    	{
	    		while (getSocketServer() != null) {
	    			
	    			// wait and accept a connection from I2P side
	    			I2PSocket i2ps;
	    			try {
					if (_acceptQueue != null)
						i2ps = acceptSocket();
					else
		    				i2ps = getSocketServer().accept();
	    				if (i2ps == null)
		    				continue;
				} catch (SocketTimeoutException ste) {
					continue;
				} catch (ConnectException ce) {
					Log log = I2PAppContext.getGlobalContext().logManager().getLog(SAMv3StreamSession.class);
					if (log.shouldLog(Log.WARN))
						log.warn("Error accepting", ce);
					try { Thread.sleep(50); } catch (InterruptedException ie) {}
					continue;
				} catch (I2PException ipe) {
					Log log = I2PAppContext.getGlobalContext().logManager().getLog(SAMv3StreamSession.class);
					if (log.shouldLog(Log.WARN))
						log.warn("Error accepting", ipe);
					break;
				}

	    			// open a socket towards client
	    			
	    			SocketChannel clientServerSock;
	    			try {
					if (isSSL) {
						I2PAppContext ctx =  I2PAppContext.getGlobalContext();
						synchronized(SAMv3StreamSession.class) {
							if (_sslSocketFactory == null) {
								try {
									_sslSocketFactory = new I2PSSLSocketFactory(
									    ctx, true, "certificates/sam");
								} catch (GeneralSecurityException gse) {
									Log log = ctx.logManager().getLog(SAMv3StreamSession.class);
									log.error("SSL error", gse);
									try {
										i2ps.reset();
									} catch (IOException ee) {}
									throw new RuntimeException("SSL error", gse);
								}
							}
						}
						SSLSocket sock = (SSLSocket) _sslSocketFactory.createSocket(host, port);
						I2PSSLSocketFactory.verifyHostname(ctx, sock, host);
		    				clientServerSock = new SSLSocketChannel(sock);
		    			} else {
		    				InetSocketAddress addr = new InetSocketAddress(host, port);
		    				clientServerSock = SocketChannel.open(addr) ;
		    			}
	    			} catch (IOException ioe) {
					Log log = I2PAppContext.getGlobalContext().logManager().getLog(SAMv3StreamSession.class);
					if (log.shouldLog(Log.WARN))
						log.warn("Error forwarding", ioe);
					try {
						i2ps.reset();
					} catch (IOException ee) {}
					continue;
	    			}

	    			// build pipes between both sockets
	    			try {
					clientServerSock.socket().setKeepAlive(true);
	    				if (this.verbose) {
						if (sendPorts) {
	    					       SAMv3Handler.notifyStreamIncomingConnection(
	    							clientServerSock, i2ps.getPeerDestination(),
								i2ps.getPort(), i2ps.getLocalPort());
						} else {
	    					       SAMv3Handler.notifyStreamIncomingConnection(
	    							clientServerSock, i2ps.getPeerDestination());
						}
					}
	    				ReadableByteChannel fromClient = clientServerSock ;
	    				ReadableByteChannel fromI2P    = Channels.newChannel(i2ps.getInputStream());
	    				WritableByteChannel toClient   = clientServerSock ;
	    				WritableByteChannel toI2P      = Channels.newChannel(i2ps.getOutputStream());
	    				(new I2PAppThread(new Pipe(fromClient, toI2P, null),
					                  "ForwardV3 SAMPipeClientToI2P")).start();
	    				(new I2PAppThread(new Pipe(fromI2P,toClient, null),
					                  "ForwardV3 SAMPipeI2PToClient")).start();

	    			} catch (IOException e) {
	    				try {
	    					clientServerSock.close();
	    				} catch (IOException ee) {}
	    				try {
	    					i2ps.reset();
	    				} catch (IOException ee) {}
	    				continue ;
	    			}
	    		}
	    	}
	    }

	    private static class Pipe implements Runnable, Handler
	    {
	    	private final ReadableByteChannel in  ;
	    	private final WritableByteChannel out ;
	    	private final ByteBuffer buf ;
		private final SAMBridge bridge;
	    	
		/**
		 *  @param bridge may be null
		 */
	    	public Pipe(ReadableByteChannel in, WritableByteChannel out, SAMBridge bridge)
	    	{
	    		this.in  = in ;
	    		this.out = out ;
	    		this.buf = ByteBuffer.allocate(BUFFER_SIZE) ;
			this.bridge = bridge;
	    	}
	    	
		public void run() {
			if (bridge != null)
				bridge.register(this);
			try {
				while (!Thread.interrupted() && (in.read(buf)>=0 || buf.position() != 0)) {
					// not ByteBuffer to avoid Java 8/9 issues with flip()
					((Buffer)buf).flip();
					 out.write(buf);
					 buf.compact();
				}
			} catch (IOException ioe) {
				// ignore
			} finally {
				try {
					in.close();
				} catch (IOException e) {}
				try {
					((Buffer)buf).flip();
					while (buf.hasRemaining()) {
						out.write(buf);
					}
				} catch (IOException e) {}
				try {
					out.close();
				} catch (IOException e) {}
				if (bridge != null)
					bridge.unregister(this);
			}
		}

		/**
		 *  Handler interface
		 *  @since 0.9.20
		 */
		public void stopHandling() {
			try {
				in.close();
			} catch (IOException e) {}
		}
	    }
	    
	    protected I2PServerSocket getSocketServer()
	    {
	    	synchronized ( this.socketServerLock ) {
	    		return this.socketServer ;
	    	}
	    }
	    /**
	     *  stop Forwarding Incoming connection coming from I2P
	     * @throws SAMException
	     * @throws InterruptedIOException
	     */
	    public void stopForwardingIncoming() throws SAMException, InterruptedIOException
	    {
	    	SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
	        
	        if ( rec==null ) throw new InterruptedIOException() ;
	        
	    	I2PServerSocket server = null ;
	    	synchronized( this.socketServerLock )
	    	{
	    		if (this.socketServer==null) {
		                if (_log.shouldLog(Log.DEBUG))
		    			_log.debug("no socket server is defined for this destination");
	    			throw new SAMException("no socket server is defined for this destination");
    			}
	    		server = this.socketServer ;
	    		this.socketServer = null ;
	                if (_log.shouldLog(Log.DEBUG))
		    		_log.debug("nulling socketServer in stopForwardingIncoming. Object " + this );
	    	}
	    	try {
	    		server.close();
	    	} catch ( I2PException e) {}
	    }

	    /**
	     * Close the stream session
	     * TODO Why do we override?
	     */
	    @Override
	    public void close() {
		if (_isOwnSession)
			socketMgr.destroySocketManager();
	    }
}
