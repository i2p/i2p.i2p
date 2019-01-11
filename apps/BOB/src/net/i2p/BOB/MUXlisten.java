/**
 *                    WTFPL
 *                    Version 2, December 2004
 *
 * Copyright (C) sponge
 *   Planet Earth
 *
 * See...
 *
 *	http://sam.zoy.org/wtfpl/
 *	and
 *	http://en.wikipedia.org/wiki/WTFPL
 *
 * ...for any additional details and license questions.
 */
package net.i2p.BOB;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 *
 * Multiplex listeners for TCP and I2P
 *
 * @author sponge
 */
public class MUXlisten implements Runnable {

	private final NamedDB database, info;
	private final Logger _log;
	private final I2PSocketManager socketManager;
	private final ByteArrayInputStream prikey;
	private ThreadGroup tg;
	private final String N;
	private ServerSocket listener;
	private static final int backlog = 50; // should this be more? less?
	private final boolean go_out;
	private final boolean come_in;
	private final AtomicBoolean lock;
	private final AtomicBoolean lives;

	/**
	 * Constructor Will fail if INPORT is occupied.
	 *
	 * @param info DB entry for this tunnel
	 * @param database master database of tunnels
	 * @param _log
	 * @throws net.i2p.I2PException
	 * @throws java.io.IOException
	 */
	MUXlisten(AtomicBoolean lock, NamedDB database, NamedDB info, Logger _log) throws I2PException, IOException, RuntimeException {
		int port = 0;
		InetAddress host = null;
		this.lock = lock;
		this.tg = null;
		this.database = database;
		this.info = info;
		this._log = _log;
		lives = new AtomicBoolean(false);
		try {
			wlock();
			try {
				this.info.add("STARTING", Boolean.TRUE);
			} finally {
				wunlock();
			}
			Properties Q = new Properties();
			rlock();
			try {
				N = this.info.get("NICKNAME").toString();
				prikey = new ByteArrayInputStream((byte[]) info.get("KEYS"));
				// Make a new copy so that anything else won't muck with our database.
				Properties R = (Properties) info.get("PROPERTIES");
				Lifted.copyProperties(R, Q);

				this.go_out = info.exists("OUTPORT");
				this.come_in = info.exists("INPORT");
				if (this.come_in) {
					port = Integer.parseInt(info.get("INPORT").toString());
					host = InetAddress.getByName(info.get("INHOST").toString());
				}
			} finally {
				runlock();
			}

			String i2cpHost = Q.getProperty(I2PClient.PROP_TCP_HOST, "127.0.0.1");
			int i2cpPort = I2PClient.DEFAULT_LISTEN_PORT;
			String i2cpPortStr = Q.getProperty(I2PClient.PROP_TCP_PORT);
			if (i2cpPortStr != null) {
				try {
					i2cpPort = Integer.parseInt(i2cpPortStr);
				} catch (NumberFormatException nfe) {
					throw new IllegalArgumentException("Invalid I2CP port specified [" + i2cpPortStr + "]");
				}
			}

			if (this.come_in) {
				this.listener = new ServerSocket(port, backlog, host);
			}
			socketManager = I2PSocketManagerFactory.createManager(
					prikey, i2cpHost, i2cpPort, Q);
		} catch (IOException e) {
			// Something went bad.
			wlock();
			try {
				this.info.add("STARTING", Boolean.FALSE);
			} finally {
				wunlock();
			}
			throw e;
		} catch (RuntimeException e) {
			// Something went bad.
			wlock();
			try {
				this.info.add("STARTING", Boolean.FALSE);
			} finally {
				wunlock();
			}
			throw e;
		} catch (Exception e) {
			// Something else went bad.
			wlock();
			try {
				this.info.add("STARTING", Boolean.FALSE);
			} finally {
				wunlock();
			}
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private void rlock() {
		database.getReadLock();
		info.getReadLock();
	}

	private void runlock() {
		info.releaseReadLock();
		database.releaseReadLock();
	}

	private void wlock() {
		database.getWriteLock();
		info.getWriteLock();
	}

	private void wunlock() {
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
			wlock();
			try {
				try {
					info.add("RUNNING", Boolean.TRUE);
				} catch (Exception e) {
					lock.set(false);
					return;
				}
			} catch (Exception e) {
				lock.set(false);
				return;
			} finally {
				wunlock();
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
							t = new I2PAppThread(tg, conn, "BOBI2Plistener " + N);
							t.start();
						}

						if (come_in) {
							// TCP -> I2P
							TCPlistener conn = new TCPlistener(listener, socketManager, info, database, _log, lives);
							q = new I2PAppThread(tg, conn, "BOBTCPlistener " + N);
							q.start();
						}

						wlock();
						try {
							try {
								info.add("STARTING", Boolean.FALSE);
							} catch (Exception e) {
								break quit;
							}
						} catch (Exception e) {
							break quit;
						} finally {
							wunlock();
						}
						boolean spin = true;
						while (spin && lives.get()) {
							try {
								Thread.sleep(1000); //sleep for 1 second
							} catch (InterruptedException e) {
								break quit;
							}
							rlock();
							try {
								try {
									spin = info.get("STOPPING").equals(Boolean.FALSE);
								} catch (Exception e) {
									break quit;
								}
							} catch (Exception e) {
								break quit;
							} finally {
								runlock();
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
					info.add("STARTING", Boolean.FALSE);
					info.add("STOPPING", Boolean.TRUE);
					info.add("RUNNING", Boolean.FALSE);
				} catch (Exception e) {
					lock.set(false);
					return;
				}
			} catch (Exception e) {
			} finally {
				wunlock();
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
				String groupName = tg.getName();
				try {
					_log.warn("destroySocketManager " + groupName);
					socketManager.destroySocketManager();
					_log.warn("destroySocketManager Successful" + groupName);
				} catch (Exception e) {
					// nop
					_log.warn("destroySocketManager Failed" + groupName);
					_log.warn(e.toString());
				}
			}
			// zero out everything.
			try {
				wlock();
				try {
					info.add("STARTING", Boolean.FALSE);
					info.add("STOPPING", Boolean.FALSE);
					info.add("RUNNING", Boolean.FALSE);
				} catch (Exception e) {
					lock.set(false);
					return;
				} finally {
					wunlock();
				}	
			} catch (Exception e) {
			}

			lock.set(false); // Should we force waiting for all threads??

			// Wait around till all threads are collected.
			if (tg != null) {
				String groupName = tg.getName();
				// System.out.println("BOB: MUXlisten: Starting thread collection for: " + groupName);
				_log.warn("BOB: MUXlisten: Starting thread collection for: " + groupName);
				if (tg.activeCount() + tg.activeGroupCount() != 0) {
					// visit(tg, 0, groupName);
					int foo = tg.activeCount() + tg.activeGroupCount();
					// hopefully no longer needed!
					// int bar = lives;
					// System.out.println("BOB: MUXlisten: Waiting on threads for " + groupName);
					// System.out.println("\nBOB: MUXlisten: ThreadGroup dump BEGIN " + groupName);
					// visit(tg, 0, groupName);
					// System.out.println("BOB: MUXlisten: ThreadGroup dump END " + groupName + "\n");
					// Happily spin forever :-(
					while (foo != 0) {
						foo = tg.activeCount() + tg.activeGroupCount();
						// if (lives != bar && lives != 0) {
						// 	System.out.println("\nBOB: MUXlisten: ThreadGroup dump BEGIN " + groupName);
						// 	visit(tg, 0, groupName);
						// 	System.out.println("BOB: MUXlisten: ThreadGroup dump END " + groupName + "\n");
						// }
						// bar = lives;
						try {
							Thread.sleep(100); //sleep for 100 ms (One tenth second)
						} catch (InterruptedException ex) {
							// nop
						}
					}
				}
				// System.out.println("BOB: MUXlisten: Threads went away. Success: " + groupName);
				_log.warn("BOB: MUXlisten: Threads went away. Success: " + groupName);
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
