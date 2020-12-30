/**
 * @author MKVore
 *
 */

package net.i2p.sam;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress ;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Properties;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;


class SAMv3DatagramSession extends SAMDatagramSession implements Session, SAMDatagramReceiver {
	
	private final SAMv3Handler handler;
	private final SAMv3DatagramServer server;
	private final String nick;
	private final SocketAddress clientAddress;
	
	public String getNick() { return nick; }

	/**
	 *   build a DatagramSession according to informations registered
	 *   with the given nickname
	 *
	 * Caller MUST call start().
	 *
	 * @param nick nickname of the session
	 * @throws IOException
	 * @throws DataFormatException
	 * @throws I2PSessionException
	 */
	public SAMv3DatagramSession(String nick, SAMv3DatagramServer dgServer) 
			throws IOException, DataFormatException, I2PSessionException, SAMException {
		super(SAMv3Handler.sSessionsHash.get(nick).getDest(),
				SAMv3Handler.sSessionsHash.get(nick).getProps(),
				null  // to be replaced by this
				);
		this.nick = nick;
		this.recv = this;  // replacement
		this.server = dgServer;

		SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
		if (rec == null)
			throw new SAMException("Record disappeared for nickname : \""+nick+"\"");

		this.handler = rec.getHandler();
		
		Properties props = rec.getProps();
		clientAddress = SAMv3RawSession.getSocketAddress(props, handler);
	}

	/**
	 *   Build a Datagram Session on an existing i2p session
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
	public SAMv3DatagramSession(String nick, Properties props, SAMv3Handler handler, I2PSession isess,
	                            int listenPort, SAMv3DatagramServer dgServer) 
			throws IOException, DataFormatException, I2PSessionException {
		super(isess, props, listenPort, null);  // to be replaced by this
		this.nick = nick ;
		this.recv = this ;  // replacement
		this.server = dgServer;
		this.handler = handler;
		clientAddress = SAMv3RawSession.getSocketAddress(props, handler);
	}

	public void receiveDatagramBytes(Destination sender, byte[] data, int proto,
	                                 int fromPort, int toPort) throws IOException {
		if (this.clientAddress==null) {
			this.handler.receiveDatagramBytes(sender, data, proto, fromPort, toPort);
		} else {
			StringBuilder buf = new StringBuilder(600);
			buf.append(sender.toBase64());
			if ((handler.verMajor == 3 && handler.verMinor >= 2) || handler.verMajor > 3) {
				buf.append(" FROM_PORT=").append(fromPort).append(" TO_PORT=").append(toPort);
			}
			buf.append('\n');
			String msg = buf.toString();
			ByteBuffer msgBuf = ByteBuffer.allocate(msg.length()+data.length);
			msgBuf.put(DataHelper.getASCII(msg));
			msgBuf.put(data);
			// not ByteBuffer to avoid Java 8/9 issues with flip()
			((Buffer)msgBuf).flip();
			this.server.send(this.clientAddress, msgBuf);
		}
	}

	public void stopDatagramReceiving() {
	}
}
