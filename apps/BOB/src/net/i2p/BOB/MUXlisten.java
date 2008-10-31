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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Properties;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.util.Log;

/**
 *
 * Multiplex listeners for TCP and I2P
 *
 * @author sponge
 */
public class MUXlisten implements Runnable {

	private NamedDB database,  info;
	private Log _log;
	private I2PSocketManager socketManager;
	private ByteArrayInputStream prikey;
	private ThreadGroup tg;
	private String N;
	private ServerSocket listener;
	private int backlog = 50; // should this be more? less?
	boolean go_out;
	boolean come_in;

	/**
	 * Constructor Will fail if INPORT is occupied.
	 *
	 * @param info
	 * @param database
	 * @param _log
	 * @throws net.i2p.I2PException
	 * @throws java.io.IOException
	 */
	MUXlisten(NamedDB database, NamedDB info, Log _log) throws I2PException, IOException, RuntimeException {
		int port = 0;
		InetAddress host = null;
		this.database = database;
		this.info = info;
		this._log = _log;

		this.database.getReadLock();
		this.info.getReadLock();
		N = this.info.get("NICKNAME").toString();
		prikey = new ByteArrayInputStream((byte[])info.get("KEYS"));
		Properties Q = (Properties)info.get("PROPERTIES");
		this.database.releaseReadLock();
		this.info.releaseReadLock();

		this.database.getReadLock();
		this.info.getReadLock();
		this.go_out = info.exists("OUTPORT");
		this.come_in = info.exists("INPORT");
		if(this.come_in) {
			port = Integer.parseInt(info.get("INPORT").toString());
			host = InetAddress.getByName(info.get("INHOST").toString());
		}
		this.database.releaseReadLock();
		this.info.releaseReadLock();

		socketManager = I2PSocketManagerFactory.createManager(prikey, Q);
		if(this.come_in) {
			this.listener = new ServerSocket(port, backlog, host);
		}

		// Everything is OK as far as we can tell.
		this.database.getWriteLock();
		this.info.getWriteLock();
		this.info.add("STARTING", Boolean.TRUE);
		this.info.releaseWriteLock();
		this.database.releaseWriteLock();
	}

	private void rlock() throws Exception {
		database.getReadLock();
		info.getReadLock();
	}

	private void runlock() throws Exception {
		database.releaseReadLock();
		info.releaseReadLock();
	}

	private void wlock() throws Exception {
		database.getWriteLock();
		info.getWriteLock();
	}

	private void wunlock() throws Exception {
		info.releaseWriteLock();
		database.releaseWriteLock();
	}

	/**
	 * MUX sockets, fire off a thread to connect, get destination info, and do I/O
	 *
	 */
	public void run() {

		try {
			wlock();
			try {
				info.add("RUNNING", Boolean.TRUE);
				info.add("STARTING", Boolean.FALSE);
			} catch(Exception e) {
				wunlock();
				return;
			}
		} catch(Exception e) {
			return;
		}
		try {
			wunlock();
		} catch(Exception e) {
			return;
		}

quit:           {
			try {
				tg = new ThreadGroup(N);
die:                            {
					// toss the connections to a new threads.
					// will wrap with TCP and UDP when UDP works

					if(go_out) {
						// I2P -> TCP
						I2Plistener conn = new I2Plistener(socketManager, info, database, _log);
						Thread t = new Thread(tg, conn, "BOBI2Plistener " + N);
						t.start();
					}

					if(come_in) {
						// TCP -> I2P
						TCPlistener conn = new TCPlistener(listener, socketManager, info, database, _log);
						Thread q = new Thread(tg, conn, "BOBTCPlistener" + N);
						q.start();
					}

					boolean spin = true;
					while(spin) {
						try {
							Thread.sleep(1000); //sleep for 1000 ms (One second)
						} catch(InterruptedException e) {
							// nop
						}
						try {
							rlock();
							try {
								spin = info.get("STOPPING").equals(Boolean.FALSE);
							} catch(Exception e) {
								runlock();
								break die;
							}
						} catch(Exception e) {
							break die;
						}
						try {
							runlock();
						} catch(Exception e) {
							break die;
						}
					}

					try {
						wlock();
						try {
							info.add("RUNNING", Boolean.FALSE);
						} catch(Exception e) {
							wunlock();
							break die;
						}
					} catch(Exception e) {
						break die;
					}
					try {
						wunlock();
					} catch(Exception e) {
						break die;
					}
				} // die
				// wait for child threads and thread groups to die
				while(tg.activeCount() + tg.activeGroupCount() != 0) {
					try {
						Thread.sleep(1000); //sleep for 1000 ms (One second)
					} catch(InterruptedException ex) {
						// nop
						}
				}
				tg.destroy();
				// Zap reference to the ThreadGroup so the JVM can GC it.
				tg = null;
			} catch(Exception e) {
				break quit;
			}
		} // quit
		socketManager.destroySocketManager();
		// zero out everything, just incase.
		try {
			wlock();
			try {
				info.add("STARTING", Boolean.FALSE);
				info.add("STOPPING", Boolean.FALSE);
				info.add("RUNNING", Boolean.FALSE);
			} catch(Exception e) {
				wunlock();
				return;
			}
			wunlock();
		} catch(Exception e) {
			return;
		}
	}
}
