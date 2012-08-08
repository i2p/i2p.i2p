/**
 * 
 */
package net.i2p.sam;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Properties;

import net.i2p.client.I2PSessionException;
import net.i2p.data.DataFormatException;
import net.i2p.util.Log;

/**
 * @author MKVore
 *
 */
public class SAMv3RawSession extends SAMRawSession  implements SAMv3Handler.Session, SAMRawReceiver {
	
	final String nick;
	final SAMv3Handler handler;
	final SAMv3Handler.DatagramServer server;
	private final static Log _log = new Log ( SAMv3DatagramSession.class );
	final SocketAddress clientAddress;

	public String getNick() { return nick; }

	/**
	 *   Build a Raw Datagram Session according to information
	 *   registered with the given nickname
	 *   
	 * @param nick nickname of the session
	 * @throws IOException
	 * @throws DataFormatException
	 * @throws I2PSessionException
	 */
	public SAMv3RawSession(String nick) 
	throws IOException, DataFormatException, I2PSessionException {
		
		super(SAMv3Handler.sSessionsHash.get(nick).getDest(),
				SAMv3Handler.sSessionsHash.get(nick).getProps(),
				SAMv3Handler.sSessionsHash.get(nick).getHandler()  // to be replaced by this
				);
		this.nick = nick ;
		this.recv = this ;  // replacement
		this.server = SAMv3Handler.DatagramServer.getInstance() ;

		SAMv3Handler.SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
        if ( rec==null ) throw new InterruptedIOException() ;

        this.handler = rec.getHandler();
		
        Properties props = rec.getProps();
        
        
    	String portStr = props.getProperty("PORT") ;
    	if ( portStr==null ) {
    		_log.debug("receiver port not specified. Current socket will be used.");
    		this.clientAddress = null;
    	}
    	else {
    		int port = Integer.parseInt(portStr);
    	
    		String host = props.getProperty("HOST");
    		if ( host==null ) {
    			host = rec.getHandler().getClientIP();

    			_log.debug("no host specified. Taken from the client socket : " + host +':'+port);
    		}

    	
    		this.clientAddress = new InetSocketAddress(host,port);
    	}
	}
	
	public void receiveRawBytes(byte[] data) throws IOException {
		if (this.clientAddress==null) {
			this.handler.receiveRawBytes(data);
		} else {
			ByteBuffer msgBuf = ByteBuffer.allocate(data.length);
			msgBuf.put(data);
			msgBuf.flip();
			this.server.send(this.clientAddress, msgBuf);
		}
	}

	public void stopRawReceiving() {}
}
