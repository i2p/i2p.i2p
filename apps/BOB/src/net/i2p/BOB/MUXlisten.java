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
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
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
	private ServerSocket listener;
	private int backlog = 50; // should this be more? less?
	boolean go_out;
	boolean come_in;

	/**
	 * Constructor Will fail if INPORT is occupied.
	 *
	 * @param info DB entry for this tunnel
	 * @param database master database of tunnels
	 * @param _log
	 * @throws net.i2p.I2PException
	 * @throws java.io.IOException
	 */
	MUXlisten(NamedDB database, NamedDB info, Log _log) throws I2PException, IOException, RuntimeException {
		int port = 0;
		InetAddress host = null;
		this.tg = null;
		this.database = database;
		this.info = info;
		this._log = _log;

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

		socketManager = I2PSocketManagerFactory.createManager(prikey, Q);
		if (this.come_in) {
			this.listener = new ServerSocket(port, backlog, host);
		}

		// Everything is OK as far as we can tell.
		this.database.getWriteLock();
		this.info.getWriteLock();
		this.info.add("STARTING", new Boolean(true));
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
		I2PServerSocket SS = null;
		int ticks = 1200; // Allow 120 seconds, no more.
		try {
			wlock();
			try {
				info.add("RUNNING", new Boolean(true));
			} catch (Exception e) {
				wunlock();
				return;
			}
		} catch (Exception e) {
			return;
		}
		try {
			wunlock();
		} catch (Exception e) {
			return;
		}
//		socketManager.addDisconnectListener(new DisconnectListener());

quit:
		{
			try {
				tg = new ThreadGroup(N);
die:
				{
					// toss the connections to a new threads.
					// will wrap with TCP and UDP when UDP works

					if (go_out) {
						// I2P -> TCP
						SS = socketManager.getServerSocket();
						I2Plistener conn = new I2Plistener(SS, socketManager, info, database, _log);
						Thread t = new Thread(tg, conn, "BOBI2Plistener " + N);
						t.start();
					}

					if (come_in) {
						// TCP -> I2P
						TCPlistener conn = new TCPlistener(listener, socketManager, info, database, _log);
						Thread q = new Thread(tg, conn, "BOBTCPlistener" + N);
						q.start();
					}

					try {
						wlock();
						try {
							info.add("STARTING", new Boolean(false));
						} catch (Exception e) {
							wunlock();
							break die;
						}
					} catch (Exception e) {
						break die;
					}
					try {
						wunlock();
					} catch (Exception e) {
						break die;
					}
					boolean spin = true;
					while (spin) {
						try {
							Thread.sleep(1000); //sleep for 1 second
						} catch (InterruptedException e) {
							break die;
						}
						try {
							rlock();
							try {
								spin = info.get("STOPPING").equals(Boolean.FALSE);
							} catch (Exception e) {
								runlock();
								break die;
							}
						} catch (Exception e) {
							break die;
						}
						try {
							runlock();
						} catch (Exception e) {
							break die;
						}
					}

					try {
						wlock();
						try {
							info.add("RUNNING", new Boolean(false));
						} catch (Exception e) {
							wunlock();
							break die;
						}
					} catch (Exception e) {
						break die;
					}
					try {
						wunlock();
					} catch (Exception e) {
						break die;
					}
				} // die

				if (SS != null) {
					try {
						SS.close();
					} catch (I2PException ex) {
						//Logger.getLogger(MUXlisten.class.getName()).log(Level.SEVERE, null, ex);
					}
				}
				if (this.come_in) {
					try {
						listener.close();
					} catch (IOException e) {
					}
				}

				I2PSession session = socketManager.getSession();
				if (session != null) {
					// System.out.println("I2Plistener: destroySession");
					try {
						session.destroySession();
					} catch (I2PSessionException ex) {
					// nop
					}
				}
				try {
					socketManager.destroySocketManager();
				} catch (Exception e) {
					// nop
				}
				// Wait for child threads and thread groups to die
				// System.out.println("MUXlisten: waiting for children");
				if (tg.activeCount() + tg.activeGroupCount() != 0) {
					while ((tg.activeCount() + tg.activeGroupCount() != 0) && ticks != 0) {
						tg.interrupt(); // unwedge any blocking threads.
						ticks--;
						try {
							Thread.sleep(100); //sleep for 100 ms (One tenth second)
						} catch (InterruptedException ex) {
							break quit;
						}
					}
					if (tg.activeCount() + tg.activeGroupCount() != 0) {
						break quit; // Uh-oh.
					}
				}
				tg.destroy();
				// Zap reference to the ThreadGroup so the JVM can GC it.
				tg = null;
			} catch (Exception e) {
				// System.out.println("MUXlisten: Caught an exception" + e);
				break quit;
			}
		} // quit

		// This is here to catch when something fucks up REALLY bad.
		if (tg != null) {
			if (SS != null) {
				try {
					SS.close();
				} catch (I2PException ex) {
					//Logger.getLogger(MUXlisten.class.getName()).log(Level.SEVERE, null, ex);
				}
			}
			if (this.come_in) {
				try {
					listener.close();
				} catch (IOException e) {
				}
			}
			try {
				socketManager.destroySocketManager();
			} catch (Exception e) {
				// nop
			}
			ticks = 600; // 60 seconds
			if (tg.activeCount() + tg.activeGroupCount() != 0) {
				while ((tg.activeCount() + tg.activeGroupCount() != 0) && ticks != 0) {
					tg.interrupt(); // unwedge any blocking threads.
					ticks--;
					try {
						Thread.sleep(100); //sleep for 100 ms (One tenth second)
					} catch (InterruptedException ex) {
						// nop
					}
				}
			}
			if (tg.activeCount() + tg.activeGroupCount() == 0) {
				tg.destroy();
				// Zap reference to the ThreadGroup so the JVM can GC it.
				tg = null;
			} else {
				System.out.println("BOB: MUXlisten: Can't kill threads. Please send the following dump to sponge@mail.i2p");
				System.out.println("\n\nBOB: MUXlisten: ThreadGroup dump BEGIN");
				visit(tg, 0);
				System.out.println("BOB: MUXlisten: ThreadGroup dump END\n\n");
			}
		}

		// This is here to catch when something fucks up REALLY bad.
//		if (tg != null) {
//			System.out.println("BOB: MUXlisten: Something fucked up REALLY bad!");
//			System.out.println("BOB: MUXlisten: Please email the following dump to sponge@mail.i2p");
//			WrapperManager.requestThreadDump();
//			System.out.println("BOB: MUXlisten: Something fucked up REALLY bad!");
//			System.out.println("BOB: MUXlisten: Please email the above dump to sponge@mail.i2p");
//		}
		// zero out everything.
		try {
			wlock();
			try {
				info.add("STARTING", new Boolean(false));
				info.add("STOPPING", new Boolean(false));
				info.add("RUNNING", new Boolean(false));
			} catch (Exception e) {
				wunlock();
				return;
			}
			wunlock();
		} catch (Exception e) {
		}

	}


	// Debugging...

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
		visit(root, 0);
	}

	/**
	 * Recursively visits all thread groups under `group' and dumps them.
	 * @param group ThreadGroup to visit
	 * @param level Current level
	 */
	private static void visit(ThreadGroup group, int level) {
		// Get threads in `group'
		int numThreads = group.activeCount();
		Thread[] threads = new Thread[numThreads * 2];
		numThreads = group.enumerate(threads, false);
		String indent = "------------------------------------".substring(0, level) + "-> ";
		// Enumerate each thread in `group' and print it.
		for (int i = 0; i < numThreads; i++) {
			// Get thread
			Thread thread = threads[i];
			System.out.println("BOB: MUXlisten: " + indent + thread.toString());
		}

		// Get thread subgroups of `group'
		int numGroups = group.activeGroupCount();
		ThreadGroup[] groups = new ThreadGroup[numGroups * 2];
		numGroups = group.enumerate(groups, false);

		// Recursively visit each subgroup
		for (int i = 0; i < numGroups; i++) {
			visit(groups[i], level + 1);
		}
	}
}
