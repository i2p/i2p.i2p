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
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
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
	private ServerSocket listener = null;
	private int backlog = 50; // should this be more? less?
	boolean go_out;
	boolean come_in;
	private AtomicBoolean lock;
	private AtomicBoolean lives;

	/**
	 * Constructor Will fail if INPORT is occupied.
	 *
	 * @param info DB entry for this tunnel
	 * @param database master database of tunnels
	 * @param _log
	 * @throws net.i2p.I2PException
	 * @throws java.io.IOException
	 */
	MUXlisten(AtomicBoolean lock, NamedDB database, NamedDB info, Log _log) throws I2PException, IOException, RuntimeException {
		try {
			int port = 0;
			InetAddress host = null;
			this.lock = lock;
			this.tg = null;
			this.database = database;
			this.info = info;
			this._log = _log;
			lives = new AtomicBoolean(false);

			this.database.getWriteLock();
			this.info.getWriteLock();
			this.info.add("STARTING", new Boolean(true));
			this.info.releaseWriteLock();
			this.database.releaseWriteLock();
			this.database.getReadLock();
			this.info.getReadLock();

			N = this.info.get("NICKNAME").toString();
			prikey = new ByteArrayInputStream((byte[]) info.get("KEYS"));
			// Make a new copy so that anything else won't muck with our database.
			Properties R = (Properties) info.get("PROPERTIES");
			Properties Q = new Properties();
			Lifted.copyProperties(R, Q);
			this.database.releaseReadLock();
			this.info.releaseReadLock();

			this.database.getReadLock();
			this.info.getReadLock();
			this.go_out = info.exists("OUTPORT");
			this.come_in = info.exists("INPORT");
			if (this.come_in) {
				port = Integer.parseInt(info.get("INPORT").toString());
				host = InetAddress.getByName(info.get("INHOST").toString());
			}
			this.database.releaseReadLock();
			this.info.releaseReadLock();

			if (this.come_in) {
				this.listener = new ServerSocket(port, backlog, host);
			}
			socketManager = I2PSocketManagerFactory.createManager(prikey, Q);
		} catch (IOException e) {
			// Something went bad.
			this.database.getWriteLock();
			this.info.getWriteLock();
			this.info.add("STARTING", new Boolean(false));
			this.info.releaseWriteLock();
			this.database.releaseWriteLock();
			throw new IOException(e.toString());
		} catch (RuntimeException e) {
			// Something went bad.
			this.database.getWriteLock();
			this.info.getWriteLock();
			this.info.add("STARTING", new Boolean(false));
			this.info.releaseWriteLock();
			this.database.releaseWriteLock();
			throw new RuntimeException(e);
		} catch (Exception e) {
			// Something else went bad.
			this.database.getWriteLock();
			this.info.getWriteLock();
			this.info.add("STARTING", new Boolean(false));
			this.info.releaseWriteLock();
			this.database.releaseWriteLock();
			e.printStackTrace();
			throw new RuntimeException(e);
		}
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
		I2PServerSocket SS = null;
		Thread t = null;
		Thread q = null;
		try {
			try {
				wlock();
				try {
					info.add("RUNNING", new Boolean(true));
				} catch (Exception e) {
					lock.set(false);
					wunlock();
					return;
				}
			} catch (Exception e) {
				lock.set(false);
				return;
			}
			try {
				wunlock();
			} catch (Exception e) {
				lock.set(false);
				return;
			}
			lives.set(true);
			lock.set(false);
			quit:
			{
				try {
					tg = new ThreadGroup(N);
					{
						// toss the connections to a new threads.
						// will wrap with TCP and UDP when UDP works

						if (go_out) {
							// I2P -> TCP
							SS = socketManager.getServerSocket();
							I2Plistener conn = new I2Plistener(SS, socketManager, info, database, _log, lives);
							t = new Thread(tg, conn, "BOBI2Plistener " + N);
							t.start();
						}

						if (come_in) {
							// TCP -> I2P
							TCPlistener conn = new TCPlistener(listener, socketManager, info, database, _log, lives);
							q = new Thread(tg, conn, "BOBTCPlistener " + N);
							q.start();
						}

						try {
							wlock();
							try {
								info.add("STARTING", new Boolean(false));
							} catch (Exception e) {
								wunlock();
								break quit;
							}
						} catch (Exception e) {
							break quit;
						}
						try {
							wunlock();
						} catch (Exception e) {
							break quit;
						}
						boolean spin = true;
						while (spin && lives.get()) {
							try {
								Thread.sleep(1000); //sleep for 1 second
							} catch (InterruptedException e) {
								break quit;
							}
							try {
								rlock();
								try {
									spin = info.get("STOPPING").equals(Boolean.FALSE);
								} catch (Exception e) {
									runlock();
									break quit;
								}
							} catch (Exception e) {
								break quit;
							}
							try {
								runlock();
							} catch (Exception e) {
								break quit;
							}
						}
					} // die

				} catch (Exception e) {
					// System.out.println("MUXlisten: Caught an exception" + e);
					break quit;
				}
			} // quit
		} finally {
			lives.set(false);
			// Some grace time.
			try {
				Thread.sleep(100);
			} catch (InterruptedException ex) {
			}
			try {
				wlock();
				try {
					info.add("STARTING", new Boolean(false));
					info.add("STOPPING", new Boolean(true));
					info.add("RUNNING", new Boolean(false));
				} catch (Exception e) {
					lock.set(false);
					wunlock();
					return;
				}
				wunlock();
			} catch (Exception e) {
			}
			// Start cleanup.
			while (!lock.compareAndSet(false, true)) {
				// wait
			}
			if (SS != null) {
				try {
					SS.close();
				} catch (I2PException ex) {
				}
			}
			if (listener != null) {
				try {
					listener.close();
				} catch (IOException e) {
				}
			}

			// Some grace time.
			try {
				Thread.sleep(100);
			} catch (InterruptedException ex) {
			}

			// Hopefully nuke stuff here...
			{
				String boner = tg.getName();
				try {
					_log.warn("destroySocketManager " + boner);
					socketManager.destroySocketManager();
					_log.warn("destroySocketManager Successful" + boner);
				} catch (Exception e) {
					// nop
					_log.warn("destroySocketManager Failed" + boner);
					_log.warn(e.toString());
				}
			}
			// zero out everything.
			try {
				wlock();
				try {
					info.add("STARTING", new Boolean(false));
					info.add("STOPPING", new Boolean(false));
					info.add("RUNNING", new Boolean(false));
				} catch (Exception e) {
					lock.set(false);
					wunlock();
					return;
				}
				wunlock();
			} catch (Exception e) {
			}

			lock.set(false); // Should we force waiting for all threads??

			// Wait around till all threads are collected.
			if (tg != null) {
				String boner = tg.getName();
				// System.out.println("BOB: MUXlisten: Starting thread collection for: " + boner);
				_log.warn("BOB: MUXlisten: Starting thread collection for: " + boner);
				if (tg.activeCount() + tg.activeGroupCount() != 0) {
					// visit(tg, 0, boner);
					int foo = tg.activeCount() + tg.activeGroupCount();
					// hopefully no longer needed!
					// int bar = lives;
					// System.out.println("BOB: MUXlisten: Waiting on threads for " + boner);
					// System.out.println("\nBOB: MUXlisten: ThreadGroup dump BEGIN " + boner);
					// visit(tg, 0, boner);
					// System.out.println("BOB: MUXlisten: ThreadGroup dump END " + boner + "\n");
					// Happily spin forever :-(
					while (foo != 0) {
						foo = tg.activeCount() + tg.activeGroupCount();
						// if (lives != bar && lives != 0) {
						// 	System.out.println("\nBOB: MUXlisten: ThreadGroup dump BEGIN " + boner);
						// 	visit(tg, 0, boner);
						// 	System.out.println("BOB: MUXlisten: ThreadGroup dump END " + boner + "\n");
						// }
						// bar = lives;
						try {
							Thread.sleep(100); //sleep for 100 ms (One tenth second)
						} catch (InterruptedException ex) {
							// nop
						}
					}
				}
				// System.out.println("BOB: MUXlisten: Threads went away. Success: " + boner);
				_log.warn("BOB: MUXlisten: Threads went away. Success: " + boner);
				tg.destroy();
				// Zap reference to the ThreadGroup so the JVM can GC it.
				tg = null;
			}
			try {
				socketManager.destroySocketManager();
			} catch (Exception e) {
				// nop
			}

		}
	}


	// Debugging... None of this is normally used.
	/**
	 *	Find the root thread group and print them all.
	 *
	 */
	private void visitAllThreads() {
		ThreadGroup root = Thread.currentThread().getThreadGroup().getParent();
		while (root.getParent() != null) {
			root = root.getParent();
		}

		// Visit each thread group
		visit(root, 0, root.getName());
	}

	/**
	 * Recursively visits all thread groups under `group' and dumps them.
	 * @param group ThreadGroup to visit
	 * @param level Current level
	 */
	private static void visit(ThreadGroup group, int level, String tn) {
		// Get threads in `group'
		int numThreads = group.activeCount();
		Thread[] threads = new Thread[numThreads * 2];
		numThreads = group.enumerate(threads, false);
		String indent = "------------------------------------".substring(0, level) + "-> ";
		// Enumerate each thread in `group' and print it.
		for (int i = 0; i < numThreads; i++) {
			// Get thread
			Thread thread = threads[i];
			System.out.println("BOB: MUXlisten: " + tn + ": " + indent + thread.toString());
		}

		// Get thread subgroups of `group'
		int numGroups = group.activeGroupCount();
		ThreadGroup[] groups = new ThreadGroup[numGroups * 2];
		numGroups = group.enumerate(groups, false);

		// Recursively visit each subgroup
		for (int i = 0; i < numGroups; i++) {
			visit(groups[i], level + 1, groups[i].getName());
		}
	}
}
