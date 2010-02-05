/**
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *                    Version 2, December 2004
 *
 * Copyright (C) sponge
 *   Planet Earth
 * Everyone is permitted to copy and distribute verbatim or modified
 * copies of this license document, and changing it is allowed as long
 * as the name is changed.
 *
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION
 *
 *  0. You just DO WHAT THE FUCK YOU WANT TO.
 *
 * See...
 *
 *	http://sam.zoy.org/wtfpl/
 *	and
 *	http://en.wikipedia.org/wiki/WTFPL
 *
 * ...for any additional details and liscense questions.
 */
package net.i2p.BOB;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.client.streaming.I2PSocket;

/**
 * Process I2P->TCP
 *
 * @author sponge
 */
public class I2PtoTCP implements Runnable {

	private I2PSocket I2P;
	private NamedDB info,  database;
	private Socket sock;
	private AtomicBoolean lives;

	/**
	 * Constructor
	 *
	 * @param I2Psock
	 * @param info
	 * @param database
	 */
	I2PtoTCP(I2PSocket I2Psock, NamedDB info, NamedDB database, AtomicBoolean lives) {
		this.I2P = I2Psock;
		this.info = info;
		this.database = database;
		this.lives = lives;
	}

	private void rlock() {
		database.getReadLock();
		info.getReadLock();
	}

	private void runlock() {
		database.releaseReadLock();
		info.releaseReadLock();
	}

	/**
	 * I2P stream to TCP stream thread starter
	 *
	 */
	public void run() {
		String host;
		int port;
		boolean tell;
		InputStream in = null;
		OutputStream out = null;
		InputStream Iin = null;
		OutputStream Iout = null;
		Thread t = null;
		Thread q = null;
		try {
			die:
			{
				try {
					try {
						rlock();
					} catch (Exception e) {
						break die;
					}
					try {
						host = info.get("OUTHOST").toString();
						port = Integer.parseInt(info.get("OUTPORT").toString());
						tell = info.get("QUIET").equals(Boolean.FALSE);
					} catch (Exception e) {
						runlock();
						break die;
					}
					try {
						runlock();
					} catch (Exception e) {
						break die;
					}
					sock = new Socket(host, port);
					sock.setKeepAlive(true);
					// make readers/writers
					in = sock.getInputStream();
					out = sock.getOutputStream();
					Iin = I2P.getInputStream();
					Iout = I2P.getOutputStream();
					I2P.setReadTimeout(0); // temp bugfix, this *SHOULD* be the default

					if (tell) {
						// tell who is connecting
						out.write(I2P.getPeerDestination().toBase64().getBytes());
						out.write(10); // nl
						out.flush(); // not really needed, but...
					}
					// setup to cross the streams
					TCPio conn_c = new TCPio(in, Iout, lives); // app -> I2P
					TCPio conn_a = new TCPio(Iin, out, lives); // I2P -> app
					t = new Thread(conn_c, Thread.currentThread().getName() + " TCPioA");
					q = new Thread(conn_a, Thread.currentThread().getName() + " TCPioB");
					// Fire!
					t.start();
					q.start();
					while (t.isAlive() && q.isAlive() && lives.get()) { // AND is used here to kill off the other thread
						try {
							Thread.sleep(10); //sleep for 10 ms
						} catch (InterruptedException e) {
							break die;
						}
					}
				// System.out.println("I2PtoTCP: Going away...");
				} catch (Exception e) {
					// System.out.println("I2PtoTCP: Owch! damn!");
					break die;
				}
			} // die
		} finally {
			try {
				in.close();
			} catch (Exception ex) {
			}
			try {
				out.close();
			} catch (Exception ex) {
			}
			try {
				Iin.close();
			} catch (Exception ex) {
			}
			try {
				Iout.close();
			} catch (Exception ex) {
			}
			try {
				t.interrupt();
			} catch (Exception e) {
			}
			try {
				q.interrupt();
			} catch (Exception e) {
			}
			try {
				// System.out.println("I2PtoTCP: Close I2P");
				I2P.close();
			} catch (Exception e) {
				tell = false;
			}
			//System.out.println("I2PtoTCP: Closed I2P");
			try {
				// System.out.println("I2PtoTCP: Close sock");
				sock.close();
			} catch (Exception e) {
				tell = false;
			}
		// System.out.println("I2PtoTCP: Done");

		}
	}
}
