/**
 * @author MKVore
 *
 */

package net.i2p.sam;

import java.io.IOException;
import java.util.Properties;

import net.i2p.client.I2PSessionException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

import java.net.InetSocketAddress;
import java.net.SocketAddress ;
import java.nio.ByteBuffer;

public class SAMv3DatagramSession extends SAMDatagramSession implements SAMv3Handler.Session, SAMDatagramReceiver {
	
	private final static Log _log = new Log ( SAMv3DatagramSession.class );

	final SAMv3Handler handler;
	final SAMv3Handler.DatagramServer server;
	final String nick;
	final SocketAddress clientAddress;
	
	public String getNick() { return nick; }

	/**
	 *   build a DatagramSession according to informations registered
	 *   with the given nickname
	 * @param nick nickname of the session
	 * @throws IOException
	 * @throws DataFormatException
	 * @throws I2PSessionException
	 */
	public SAMv3DatagramSession(String nick) 
	throws IOException, DataFormatException, I2PSessionException, SAMException {
		
		super(SAMv3Handler.sSessionsHash.get(nick).getDest(),
				SAMv3Handler.sSessionsHash.get(nick).getProps(),
				null  // to be replaced by this
				);
		this.nick = nick ;
		this.recv = this ;  // replacement
		this.server = SAMv3Handler.DatagramServer.getInstance() ;

		SAMv3Handler.SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
        if ( rec==null ) throw new SAMException("Record disappeared for nickname : \""+nick+"\"") ;

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
    			_log.debug("no host specified. Taken from the client socket : " + host+':'+port);
    		}

    	
    		this.clientAddress = new InetSocketAddress(host,port);
    	}
	}

	public void receiveDatagramBytes(Destination sender, byte[] data) throws IOException {
		if (this.clientAddress==null) {
			this.handler.receiveDatagramBytes(sender, data);
		} else {
			String msg = sender.toBase64()+"\n";
			ByteBuffer msgBuf = ByteBuffer.allocate(msg.length()+data.length);
			msgBuf.put(msg.getBytes("ISO-8859-1"));
			msgBuf.put(data);
			msgBuf.flip();
			this.server.send(this.clientAddress, msgBuf);
		}
	}

	public void stopDatagramReceiving() {
	}
}
