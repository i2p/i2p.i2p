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
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 *  This is the thread listening on 127.0.0.1:7655 or as specified by
 *  sam.udp.host and sam.udp.port properties.
 *  This is used for both repliable and raw datagrams.
 *
 *  @since 0.9.24 moved from SAMv3Handler
 */
class SAMv3DatagramServer implements Handler {
	
	private final DatagramChannel _server;
	private final Thread _listener;
	private final SAMBridge _parent;
	private final String _host;
	private final int _port;
	
	/**
	 *  Does not start listener.
	 *  Caller must call start().
	 *
	 *  @param parent may be null
	 *  @param props ignored for now
	 */
	public SAMv3DatagramServer(SAMBridge parent, String host, int port, Properties props) throws IOException {
		_parent = parent;
		_server = DatagramChannel.open();
		
		_server.socket().bind(new InetSocketAddress(host, port));
		_listener = new I2PAppThread(new Listener(_server), "SAM DatagramListener " + port);
		_host = host;
		_port = port;
	}
	
	/**
	 *  Only call once.
	 *  @since 0.9.22
	 */
	public synchronized void start() {
		_listener.start();
		if (_parent != null)
			_parent.register(this);
	}
	
	/**
	 *  Cannot be restarted.
	 *  @since 0.9.22
	 */
	public synchronized void stopHandling() {
		try {
			_server.close();
		} catch (IOException ioe) {}
		_listener.interrupt();
		if (_parent != null)
			_parent.unregister(this);
	}
	
	public void send(SocketAddress addr, ByteBuffer msg) throws IOException {
		_server.send(msg, addr);
	}

	/** @since 0.9.24 */
	public String getHost() { return _host; }

	/** @since 0.9.24 */
	public int getPort() { return _port; }

	private static class Listener implements Runnable {
		
		private final DatagramChannel server;
		
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
				// A new thread for every message is wildly inefficient...
				//new I2PAppThread(new MessageDispatcher(outBuf.array()), "MessageDispatcher").start();
				// inline
				// Even though we could be sending messages through multiple sessions,
				// that isn't a common use case, and blocking should be rare.
				// Inside router context, I2CP drops on overflow.
				(new MessageDispatcher(outBuf.array())).run();
			}
		}
	}

	private static class MessageDispatcher implements Runnable {
		private final ByteArrayInputStream is;
	
		public MessageDispatcher(byte[] buf) {
			this.is = new ByteArrayInputStream(buf) ;
		}
	
		public void run() {
			try {
				String header = DataHelper.readLine(is).trim();
				StringTokenizer tok = new StringTokenizer(header, " ");
				if (tok.countTokens() < 3) {
					// This is not a correct message, for sure
					warn("Bad datagram header received");
					return;
				}
				String version = tok.nextToken();
				if (!version.startsWith("3.")) {
					warn("Bad datagram header received");
					return;
				}
				String nick = tok.nextToken();
				String dest = tok.nextToken();

				SAMv3Handler.SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
				if (rec!=null) {
					Properties sprops = rec.getProps();
					String pr = sprops.getProperty("PROTOCOL");
					String fp = sprops.getProperty("FROM_PORT");
					String tp = sprops.getProperty("TO_PORT");
					while (tok.hasMoreTokens()) {
						String t = tok.nextToken();
						if (t.startsWith("PROTOCOL="))
							pr = t.substring("PROTOTCOL=".length());
						else if (t.startsWith("FROM_PORT="))
							fp = t.substring("FROM_PORT=".length());
						else if (t.startsWith("TO_PORT="))
							tp = t.substring("TO_PORT=".length());
					}

					int proto = I2PSession.PROTO_UNSPECIFIED;
					int fromPort = I2PSession.PORT_UNSPECIFIED;
					int toPort = I2PSession.PORT_UNSPECIFIED;
					if (pr != null) {
						try {
							proto = Integer.parseInt(pr);
						} catch (NumberFormatException nfe) {
							warn("Bad datagram header received");
							return;
						}
					}
					if (fp != null) {
						try {
							fromPort = Integer.parseInt(fp);
						} catch (NumberFormatException nfe) {
							warn("Bad datagram header received");
							return;
						}
					}
					if (tp != null) {
						try {
							toPort = Integer.parseInt(tp);
						} catch (NumberFormatException nfe) {
							warn("Bad datagram header received");
							return;
						}
					}
					// TODO too many allocations and copies. One here and one in Listener above.
					byte[] data = new byte[is.available()];
					is.read(data);
					SAMv3Handler.Session sess = rec.getHandler().getSession();
					if (sess != null)
						sess.sendBytes(dest, data, proto, fromPort, toPort);
					else
						warn("Dropping datagram, no session for " + nick);
				} else {
					warn("Dropping datagram, no session for " + nick);
				}
			} catch (Exception e) {
				warn("Error handling datagram", e);
			}
		}

		/** @since 0.9.22 */
		private static void warn(String s) {
			warn(s, null);
		}

		/** @since 0.9.22 */
		private static void warn(String s, Throwable t) {
			Log log = I2PAppContext.getGlobalContext().logManager().getLog(SAMv3DatagramServer.class);
			if (log.shouldLog(Log.WARN))
				log.warn(s, t);
		}
	}
}
