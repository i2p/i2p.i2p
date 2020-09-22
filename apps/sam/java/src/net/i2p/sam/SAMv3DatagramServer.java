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
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;

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

	private class Listener implements Runnable {
		
		private final DatagramChannel server;
		
		public Listener(DatagramChannel server)
		{
			this.server = server ;
		}

		public void run() {
			I2PAppContext.getGlobalContext().portMapper().register(PortMapper.SVC_SAM_UDP, _host, _port);
			try {
				run2();
			} finally {
				I2PAppContext.getGlobalContext().portMapper().unregister(PortMapper.SVC_SAM_UDP);
			}
		}

		private void run2() {
			ByteBuffer inBuf = ByteBuffer.allocateDirect(SAMRawSession.RAW_SIZE_MAX+1024);
			
			while (!Thread.interrupted())
			{
				// not ByteBuffer to avoid Java 8/9 issues
				((Buffer)inBuf).clear();
				try {
					server.receive(inBuf);
				} catch (IOException e) {
					break ;
				}
				((Buffer)inBuf).flip();
				ByteBuffer outBuf = ByteBuffer.wrap(new byte[inBuf.remaining()]);
				outBuf.put(inBuf);
				((Buffer)outBuf).flip();
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
		private static final int MAX_LINE_LENGTH = 2*1024;
	
		public MessageDispatcher(byte[] buf) {
			this.is = new ByteArrayInputStream(buf) ;
		}
	
		public void run() {
			try {
				// not UTF-8
				//String header = DataHelper.readLine(is).trim();
				// we cannot use SAMUtils.parseParams() here
				final UTF8Reader reader = new UTF8Reader(is);
				final StringBuilder buf = new StringBuilder(MAX_LINE_LENGTH);
				int c;
				int i = 0;
				while ((c = reader.read()) != -1) {
					if (++i > MAX_LINE_LENGTH)
						throw new IOException("Line too long - max " + MAX_LINE_LENGTH);
					if (c == '\n')
						break;
					buf.append((char)c);
				}
				String header = buf.toString();
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

				SessionRecord rec = SAMv3Handler.sSessionsHash.get(nick);
				if (rec!=null) {
					Properties sprops = rec.getProps();
					// 3.2 props
					String pr = sprops.getProperty("PROTOCOL");
					String fp = sprops.getProperty("FROM_PORT");
					String tp = sprops.getProperty("TO_PORT");
					// 3.3 props
					// If this is a straight DATAGRAM or RAW session, we
					// don't need to send these, the router already got them in
					// the options, but if a subsession, we must, so just
					// do it all the time.
					String st = sprops.getProperty("crypto.tagsToSend");
					String tt = sprops.getProperty("crypto.lowTagThreshold");
					String sl = sprops.getProperty("shouldBundleReplyInfo");
					String exms = sprops.getProperty("clientMessageTimeout");  // ms
					String exs = null;                                         // seconds
					while (tok.hasMoreTokens()) {
						String t = tok.nextToken();
						// 3.2 props
						if (t.startsWith("PROTOCOL="))
							pr = t.substring("PROTOCOL=".length());
						else if (t.startsWith("FROM_PORT="))
							fp = t.substring("FROM_PORT=".length());
						else if (t.startsWith("TO_PORT="))
							tp = t.substring("TO_PORT=".length());
						// 3.3 props
						else if (t.startsWith("SEND_TAGS="))
							st = t.substring("SEND_TAGS=".length());
						else if (t.startsWith("TAG_THRESHOLD="))
							tt = t.substring("TAG_THRESHOLD=".length());
						else if (t.startsWith("EXPIRES="))
							exs = t.substring("EXPIRES=".length());
						else if (t.startsWith("SEND_LEASESET="))
							sl = t.substring("SEND_LEASESET=".length());
					}

					// 3.2 props
					int proto = I2PSession.PROTO_UNSPECIFIED;
					int fromPort = I2PSession.PORT_UNSPECIFIED;
					int toPort = I2PSession.PORT_UNSPECIFIED;
					// 3.3 props
					int sendTags = 0;
					int tagThreshold = 0;
					int expires = 0; // seconds
					boolean sendLeaseSet = true;
					try {
						// 3.2 props
						if (pr != null)
							proto = Integer.parseInt(pr);
						if (fp != null)
							fromPort = Integer.parseInt(fp);
						if (tp != null)
							toPort = Integer.parseInt(tp);
						// 3.3 props
						if (st != null)
							sendTags = Integer.parseInt(st);
						if (tt != null)
							tagThreshold = Integer.parseInt(tt);
						if (exs != null)
							expires = Integer.parseInt(exs);
						else if (exms != null)
							expires = Integer.parseInt(exms) / 1000;
						if (sl != null)
							sendLeaseSet = Boolean.parseBoolean(sl);
					} catch (NumberFormatException nfe) {
						warn("Bad datagram header received");
						return;
					}
					// TODO too many allocations and copies. One here and one in Listener above.
					byte[] data = new byte[is.available()];
					is.read(data);
					Session sess = rec.getHandler().getSession();
					if (sess != null) {
						if (sendTags > 0 || tagThreshold > 0 || expires > 0 || !sendLeaseSet) {
							sess.sendBytes(dest, data, proto, fromPort, toPort,
							               sendLeaseSet, sendTags, tagThreshold, expires);
						} else {
							sess.sendBytes(dest, data, proto, fromPort, toPort);
						}
					} else {
						warn("Dropping datagram, no session for " + nick);
					}
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
