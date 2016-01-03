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
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * @author MKVore
 *
 */
class SAMv3RawSession extends SAMRawSession  implements Session, SAMRawReceiver {
	
	private final String nick;
	private final SAMv3Handler handler;
	private final SAMv3DatagramServer server;
	private final SocketAddress clientAddress;
	private final boolean _sendHeader;

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
	public SAMv3RawSession(String nick, SAMv3DatagramServer dgServer) 
			throws IOException, DataFormatException, I2PSessionException {
		super(SAMv3Handler.sSessionsHash.get(nick).getDest(),
		      SAMv3Handler.sSessionsHash.get(nick).getProps(),
		      SAMv3Handler.sSessionsHash.get(nick).getHandler()  // to be replaced by this
		);
		this.nick = nick ;
		this.recv = this ;  // replacement
		this.server = dgServer;

		SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
		if (rec == null)
			throw new InterruptedIOException() ;
		this.handler = rec.getHandler();
		Properties props = rec.getProps();
		String portStr = props.getProperty("PORT") ;
		if (portStr == null) {
			if (_log.shouldLog(Log.DEBUG))
				_log.debug("receiver port not specified. Current socket will be used.");
			this.clientAddress = null;
		} else {
			int port = Integer.parseInt(portStr);
			String host = props.getProperty("HOST");
			if ( host==null ) {
				host = rec.getHandler().getClientIP();
				if (_log.shouldLog(Log.DEBUG))
		    			_log.debug("no host specified. Taken from the client socket : " + host +':'+port);
			}
			this.clientAddress = new InetSocketAddress(host, port);
		}
		_sendHeader = ((handler.verMajor == 3 && handler.verMinor >= 2) || handler.verMajor > 3) &&
                              Boolean.parseBoolean(props.getProperty("HEADER"));
	}
	
	public void receiveRawBytes(byte[] data, int proto, int fromPort, int toPort) throws IOException {
		if (this.clientAddress==null) {
			this.handler.receiveRawBytes(data, proto, fromPort, toPort);
		} else {
			ByteBuffer msgBuf;
			if (_sendHeader) {
				StringBuilder buf = new StringBuilder(64);
				buf.append("PROTOCOL=").append(proto)
				   .append(" FROM_PORT=").append(fromPort)
				   .append(" TO_PORT=").append(toPort)
				   .append('\n');
				String msg = buf.toString();
				msgBuf = ByteBuffer.allocate(msg.length()+data.length);
				msgBuf.put(DataHelper.getASCII(msg));
			} else {
				msgBuf = ByteBuffer.allocate(data.length);
			}
			msgBuf.put(data);
			msgBuf.flip();
			this.server.send(this.clientAddress, msgBuf);
		}
	}

	public void stopRawReceiving() {}
}
