/**
 * 
 */
package net.i2p.sam;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Properties;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * @author MKVore
 *
 */
class SAMv3RawSession extends SAMRawSession implements Session, SAMRawReceiver {
	
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
	 * Caller MUST call start().
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
		      null  // to be replaced by this
		);
		this.nick = nick ;
		this.recv = this ;  // replacement
		this.server = dgServer;
		SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
		if (rec == null)
			throw new InterruptedIOException() ;
		this.handler = rec.getHandler();
		Properties props = rec.getProps();
		clientAddress = getSocketAddress(props, handler);
		_sendHeader = ((handler.verMajor == 3 && handler.verMinor >= 2) || handler.verMajor > 3) &&
		              Boolean.parseBoolean(props.getProperty("HEADER"));
	}

	/**
	 *   Build a Raw Session on an existing i2p session
	 *   registered with the given nickname
	 *   
	 * Caller MUST call start().
	 *
	 * @param nick nickname of the session
	 * @throws IOException
	 * @throws DataFormatException
	 * @throws I2PSessionException
	 * @since 0.9.25
	 */
	public SAMv3RawSession(String nick, Properties props, SAMv3Handler handler, I2PSession isess,
	                       int listenProtocol, int listenPort, SAMv3DatagramServer dgServer) 
			throws IOException, DataFormatException, I2PSessionException {
		super(isess, props, listenProtocol, listenPort, null);  // to be replaced by this
		this.nick = nick ;
		this.recv = this ;  // replacement
		this.server = dgServer;
		this.handler = handler;
		clientAddress = getSocketAddress(props, handler);
		_sendHeader = ((handler.verMajor == 3 && handler.verMinor >= 2) || handler.verMajor > 3) &&
		              Boolean.parseBoolean(props.getProperty("HEADER"));
	}
	
	/**
	 *  @return null if PORT not set
	 *  @since 0.9.25 moved from constructor
	 */
	static SocketAddress getSocketAddress(Properties props, SAMv3Handler handler) {
		String portStr = props.getProperty("PORT") ;
		if (portStr == null) {
			return null;
		} else {
			int port = Integer.parseInt(portStr);
			String host = props.getProperty("HOST");
			if ( host==null ) {
				host = handler.getClientIP();
			}
			return new InetSocketAddress(host, port);
		}
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
			((Buffer)msgBuf).flip();
			this.server.send(this.clientAddress, msgBuf);
		}
	}

	public void stopRawReceiving() {}
}
