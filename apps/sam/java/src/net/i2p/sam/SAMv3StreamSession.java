package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.Properties;

import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.ByteBuffer ;
import java.nio.channels.SocketChannel;

/**
 * SAMv3 STREAM session class.
 *
 * @author mkvore
 */

public class SAMv3StreamSession  extends SAMStreamSession implements SAMv3Handler.Session
{

		private final static Log _log = new Log ( SAMv3StreamSession.class );
		
		protected final int BUFFER_SIZE = 1024 ;
		
		protected Object socketServerLock = new Object();
		protected I2PServerSocket socketServer = null;
	
		protected String nick ;
		
		public String getNick() {
			return nick ;
		}
		
		   /**
	     * Create a new SAM STREAM session.
	     *
	     * @param dest Base64-encoded destination (private key)
	     * @param dir Session direction ("RECEIVE", "CREATE" or "BOTH")
	     * @param props Properties to setup the I2P session
	     * @param recv Object that will receive incoming data
	     * @throws IOException
	     * @throws DataFormatException
	     * @throws SAMException 
	     */
	    public SAMv3StreamSession(String login)
	    		throws IOException, DataFormatException, SAMException
	    {
	    	initSAMStreamSession(login);
	    }

	    public static SAMv3Handler.SessionsDB getDB()
	    {
	    	return SAMv3Handler.sSessionsHash ;
	    }

	    private void initSAMStreamSession(String login)
	    	throws IOException, DataFormatException, SAMException {

	        SAMv3Handler.SessionRecord rec = getDB().get(login);
	        String dest = rec.getDest() ;
	        ByteArrayInputStream ba_dest = new ByteArrayInputStream(Base64.decode(dest));

	        this.recv = rec.getHandler();

	    	_log.debug("SAM STREAM session instantiated");

	    	Properties allprops = new Properties();
	    	allprops.putAll(System.getProperties());
	    	allprops.putAll(rec.getProps());
	    	
	    	String i2cpHost = allprops.getProperty(I2PClient.PROP_TCP_HOST, "127.0.0.1");
	    	int i2cpPort ;
	    	String port = allprops.getProperty(I2PClient.PROP_TCP_PORT, "7654");
	    	try {
	    		i2cpPort = Integer.parseInt(port);
	    	} catch (NumberFormatException nfe) {
	    		throw new SAMException("Invalid I2CP port specified [" + port + "]");
	    	}

	    	_log.debug("Creating I2PSocketManager...");
	    	socketMgr = I2PSocketManagerFactory.createManager(ba_dest,
	    			i2cpHost,
	    			i2cpPort, 
	    			allprops);
	    	if (socketMgr == null) {
	    		throw new SAMException("Error creating I2PSocketManager towards "+i2cpHost+":"+i2cpPort);
	    	}

	    	socketMgr.addDisconnectListener(new DisconnectListener());
	    	this.nick = login ;
	    }

	    /**
	     * Connect the SAM STREAM session to the specified Destination
	     *
	     * @param id Unique id for the connection
	     * @param dest Base64-encoded Destination to connect to
	     * @param props Options to be used for connection
	     *
	     * @return true if successful
	     * @throws DataFormatException if the destination is not valid
	     * @throws ConnectException if the destination refuses connections
	     * @throws NoRouteToHostException if the destination can't be reached
	     * @throws InterruptedIOException if the connection timeouts
	     * @throws I2PException if there's another I2P-related error
	     * @throws IOException 
	     */
	    public void connect ( SAMv3Handler handler, String dest, Properties props ) throws I2PException, ConnectException, NoRouteToHostException, DataFormatException, InterruptedIOException, IOException {

	    	boolean verbose = (props.getProperty("SILENT", "false").equals("false"));
	        Destination d = SAMUtils.getDest(dest);

	        I2PSocketOptions opts = socketMgr.buildOptions(props);
	        if (props.getProperty(I2PSocketOptions.PROP_CONNECT_TIMEOUT) == null)
	            opts.setConnectTimeout(60 * 1000);

	        _log.debug("Connecting new I2PSocket...");

	        // blocking connection (SAMv3)

	        I2PSocket i2ps = socketMgr.connect(d, opts);

	        SAMv3Handler.SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
	        
	        if ( rec==null ) throw new InterruptedIOException() ;
	        
	        handler.notifyStreamResult(verbose, "OK", null) ;

	        handler.stealSocket() ;
	        
	        ReadableByteChannel fromClient = handler.getClientSocket();
	        ReadableByteChannel fromI2P    = Channels.newChannel(i2ps.getInputStream());
	        WritableByteChannel toClient   = handler.getClientSocket();
	        WritableByteChannel toI2P      = Channels.newChannel(i2ps.getOutputStream());
	        
	        (new Thread(rec.getThreadGroup(), new I2PAppThread(new Pipe(fromClient,toI2P), "SAMPipeClientToI2P"))).start();
	        (new Thread(rec.getThreadGroup(), new I2PAppThread(new Pipe(fromI2P,toClient), "SAMPipeClientToI2P"))).start();
	        
	    }

	    /**
	     * Accept an incoming STREAM
	     *
	     * @param id Unique id for the connection
	     * @param dest Base64-encoded Destination to connect to
	     * @param props Options to be used for connection
	     *
	     * @return true if successful
	     * @throws DataFormatException if the destination is not valid
	     * @throws ConnectException if the destination refuses connections
	     * @throws NoRouteToHostException if the destination can't be reached
	     * @throws InterruptedIOException if the connection timeouts
	     * @throws I2PException if there's another I2P-related error
	     * @throws IOException 
	     */
	    public void accept(SAMv3Handler handler, boolean verbose) 
	    	throws I2PException, InterruptedIOException, IOException, SAMException {

	    	synchronized( this.socketServerLock )
	    	{
	    		if (this.socketServer!=null) {
	    			_log.debug("a socket server is already defined for this destination");
	    			throw new SAMException("a socket server is already defined for this destination");
	    		}
	    		this.socketServer = this.socketMgr.getServerSocket();
	    	}
	    	
			I2PSocket i2ps;
			i2ps = this.socketServer.accept();

	    	synchronized( this.socketServerLock )
	    	{
	    		this.socketServer = null ;
	    	}
	    	
	    	SAMv3Handler.SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
	        
	        if ( rec==null ) throw new InterruptedIOException() ;
	        
			if (verbose)
				handler.notifyStreamIncomingConnection(i2ps.getPeerDestination()) ;

	        handler.stealSocket() ;
	        ReadableByteChannel fromClient = handler.getClientSocket();
	        ReadableByteChannel fromI2P    = Channels.newChannel(i2ps.getInputStream());
	        WritableByteChannel toClient   = handler.getClientSocket();
	        WritableByteChannel toI2P      = Channels.newChannel(i2ps.getOutputStream());
	        
	        (new Thread(rec.getThreadGroup(), new I2PAppThread(new Pipe(fromClient,toI2P), "SAMPipeClientToI2P"))).start();
	        (new Thread(rec.getThreadGroup(), new I2PAppThread(new Pipe(fromI2P,toClient), "SAMPipeClientToI2P"))).start();	        
	    }

	    
	    public void startForwardingIncoming( Properties props ) throws SAMException, InterruptedIOException
	    {
	    	SAMv3Handler.SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
	    	boolean verbose = props.getProperty("SILENT", "false").equals("false");
	        
	        if ( rec==null ) throw new InterruptedIOException() ;
	        
	    	String portStr = props.getProperty("PORT") ;
	    	if ( portStr==null ) {
	    		_log.debug("receiver port not specified");
	    		throw new SAMException("receiver port not specified");
	    	}
	    	int port = Integer.parseInt(portStr);
	    	
	    	String host = props.getProperty("HOST");
	    	if ( host==null ) {
	    		_log.debug("no host specified. Take from the client socket");
	    		
	    		host = rec.getHandler().getClientIP();
	    	}

	    	
	    	synchronized( this.socketServerLock )
	    	{
	    		if (this.socketServer!=null) {
	    			_log.debug("a socket server is already defined for this destination");
	    			throw new SAMException("a socket server is already defined for this destination");
    			}
	    		this.socketServer = this.socketMgr.getServerSocket();
	    	}
	    	
	    	SocketForwarder forwarder = new SocketForwarder(host, port, this, verbose);
	    	(new Thread(rec.getThreadGroup(), new I2PAppThread(forwarder, "SAMStreamForwarder"))).start();
	        
	    }
	    
	    public class SocketForwarder extends Thread
	    {
	    	String host = null ;
	    	int port = 0 ;
	    	SAMv3StreamSession session;
	    	boolean verbose;
	    	
	    	SocketForwarder(String host, int port, SAMv3StreamSession session, boolean verbose) {
	    		this.host = host ;
	    		this.port = port ;
	    		this.session = session ;
	    		this.verbose = verbose ;
	    	}
	    	
	    	public void run()
	    	{
	    		while (session.socketServer!=null) {
	    			
	    			I2PSocket i2ps = null ;
	    			try {
	    				session.socketServer.waitIncoming(0);
	    			} catch (ConnectException e) {
	    				_log.debug("ConnectException");
	    				break ;
	    			} catch (I2PException e) {
	    				_log.debug("I2PServerSocket has been closed");
	    				break ;
	    			} catch (InterruptedException e) {
	    				_log.debug("InterruptedException");
	    				break ;
	    			}
	    			
	    			java.net.InetSocketAddress addr = new java.net.InetSocketAddress(host,port);
	    			
	    			SocketChannel clientServerSock = null ;
	    			try {
	    				clientServerSock = SocketChannel.open(addr) ;
	    			}
	    			catch ( IOException e ) {
	    				continue ;
	    			}
	    			
	    			try {
	    				i2ps = session.socketServer.accept(1);
	    			} catch (Exception e) {}
	    			
	    			if (i2ps==null) {
	    				try {
	    					clientServerSock.close();
	    				} catch (IOException ee) {}
	    				continue ;
	    			}
	    			try {
	    				if (this.verbose)
	    					SAMv3Handler.notifyStreamIncomingConnection(
	    							clientServerSock, i2ps.getPeerDestination());
	    				ReadableByteChannel fromClient = clientServerSock ;
	    				ReadableByteChannel fromI2P    = Channels.newChannel(i2ps.getInputStream());
	    				WritableByteChannel toClient   = clientServerSock ;
	    				WritableByteChannel toI2P      = Channels.newChannel(i2ps.getOutputStream());
	    				new I2PAppThread(new Pipe(fromClient,toI2P), "SAMPipeClientToI2P").start();
	    				new I2PAppThread(new Pipe(fromI2P,toClient), "SAMPipeClientToI2P").start();	        

	    			} catch (IOException e) {
	    				try {
	    					clientServerSock.close();
	    				} catch (IOException ee) {}
	    				try {
	    					i2ps.close();
	    				} catch (IOException ee) {}
	    				continue ;
	    			}
	    		}
	    	}
	    }
	    public class Pipe extends Thread
	    {
	    	ReadableByteChannel in  ;
	    	WritableByteChannel out ;
	    	ByteBuffer buf ;
	    	
	    	public Pipe(ReadableByteChannel in, WritableByteChannel out)
	    	{
	    		this.in  = in ;
	    		this.out = out ;
	    		this.buf = ByteBuffer.allocate(BUFFER_SIZE) ;
	    	}
	    	
	    	public void run()
	    	{
    			try {
    				while (!Thread.interrupted() && (in.read(buf)>=0 || buf.position() != 0)) {
	    				 buf.flip();
	    				 out.write(buf);
	    				 buf.compact();
	    			}
	    		}
				catch (IOException e)
				{
					this.interrupt();
				}
    			try {
    				in.close();
    			}
    			catch (IOException e) {}
    			try {
    				buf.flip();
    				while (buf.hasRemaining())
    					out.write(buf);
    			}
    			catch (IOException e) {}
    			try {
    				out.close();
    			}
    			catch (IOException e) {}
	    	}
	    }
	    


	    /**
	     * Close the stream session
	     */
	    @Override
	    public void close() {
	        socketMgr.destroySocketManager();
	    }

	    public boolean sendBytes(String s, byte[] b) throws DataFormatException
	    {
	    	throw new DataFormatException(null);
	    }

}
