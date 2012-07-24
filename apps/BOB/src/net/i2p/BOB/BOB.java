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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer2;

/**
 * <span style="font-size:8px;font-family:courier;color:#EEEEEE;background-color:#000000">
 * ################################################################################<br>
 * ############################.#..........#..#..........##########################<br>
 * #######################......................................###################<br>
 * ####################...........................#.......#........################<br>
 * #################..................##...................#.........##############<br>
 * ###############................###...####.....#..###.....#.........#############<br>
 * #############...........###..#..###...#####...###.##........#.......############<br>
 * ###########................#......##...#####...##..##.......#..#........########<br>
 * ##########.........................#....##.##..#...##.....................######<br>
 * #########...................................#....#.........................#####<br>
 * ########.........................................#...............#..........####<br>
 * ########.........................................#..........#######..........###<br>
 * #######.................................................############..........##<br>
 * #######..........................................####################.........##<br>
 * #######............####################......########################.........##<br>
 * ######.............###############################################.##.........##<br>
 * ######............################################################..##........##<br>
 * ######............################################################..##........##<br>
 * ######.............##############################################..##.........##<br>
 * ######............##############################################...##..........#<br>
 * ######............#..###########################################...##..........#<br>
 * ######.............#############################################....#..........#<br>
 * #######...........###############################################..##.........##<br>
 * #######...........#####.#.#.#.########################.....#.####...##........##<br>
 * ######............#..............##################.................##.........#<br>
 * ######................####.........###############........#####......##........#<br>
 * ######..............####..#.........############.......##.#.######...##.......##<br>
 * ######.................#.####.........########...........##....###...##.......##<br>
 * #######....#....###...................#######...............#...###..##.......##<br>
 * #######.........###..###.....###.......######.##.#####.........####..##.......##<br>
 * #######.....#...##############.........############......###########.###......##<br>
 * #######....##...##########.......##...##############......#.############.....###<br>
 * ########....#..########......######...##################################....####<br>
 * ########....##.####################...##################################....####<br>
 * ########..#.##..###################..##################################..#..####<br>
 * ##########..###..#################...##################################...#.####<br>
 * #########....##...##############....########..#####.################.##..#.#####<br>
 * ############.##....##########.......#########.###.......###########..#.#########<br>
 * ###############.....#######...#.......########.....##.....######.....###########<br>
 * ###############......###....##..........##.......######....#.........#.#########<br>
 * ##############............##..................##########..............##########<br>
 * ##############..............................##########..#.............##########<br>
 * ###############.......##..................#####..............####....###########<br>
 * ###############.......#####.......#.............####.....#######.....###########<br>
 * ################...#...####......##################.....########....############<br>
 * ################...##..#####.........####.##.....#....##########....############<br>
 * ##################..##..####...........#####.#....############.....#############<br>
 * ##################......#####.................################....##############<br>
 * ###################.....####..........##########..###########....###############<br>
 * ####################..#..#..........................########.....###############<br>
 * #####################.##.......###.................########....#################<br>
 * ######################.........#.......#.##.###############....#################<br>
 * #############.#######...............#####################....###################<br>
 * ###..#.....##...####..........#.....####################....####################<br>
 * ####......##........................##################....######################<br>
 * #.##...###..............###.........###############......#######################<br>
 * #...###..##............######...........................########################<br>
 * ##.......###..........##########....#...#...........############################<br>
 * ##.........##.......############################################################<br>
 * ###........##.....##############################################################<br>
 * ####.............###############################################################<br>
 * ######.........#################################################################<br>
 * #########....###################################################################<br>
 * ################################################################################<br>
 * </span>
 * BOB, main command socket listener, launches the command parser engine.
 *
 * @author sponge
 */
public class BOB {

	private final static Log _log = new Log(BOB.class);
	public final static String PROP_CONFIG_LOCATION = "BOB.config";
	public final static String PROP_BOB_PORT = "BOB.port";
	public final static String PROP_BOB_HOST = "BOB.host";
	public final static String PROP_CFG_VER = "BOB.CFG.VER";
	private static NamedDB database;
	private static Properties props = new Properties();
	private static AtomicBoolean spin = new AtomicBoolean(true);
	private static final String P_RUNNING = "RUNNING";
	private static final String P_STARTING = "STARTING";
	private static final String P_STOPPING = "STOPPING";
	private static AtomicBoolean lock = new AtomicBoolean(false);
	// no longer used.
	// private static int maxConnections = 0;

	/**
	 * Log a warning
	 *
	 * @param arg
	 */
	public static void info(String arg) {
		System.out.println("INFO:" + arg);
		_log.info(arg);
	}

	/**
	 * Log a warning
	 *
	 * @param arg
	 */
	public static void warn(String arg) {
		System.out.println("WARNING:" + arg);
		_log.warn(arg);
	}

	/**
	 * Log an error
	 *
	 * @param arg
	 */
	public static void error(String arg) {
		System.out.println("ERROR: " + arg);
		_log.error(arg);
	}

	/**
	 * Stop BOB gracefully
	 */
	public static void stop() {
		spin.set(false);
	}

	/**
	 * Listen for incoming connections and handle them
	 *
	 * @param args
	 */
	public static void main(String[] args) {
		database = new NamedDB();
		ServerSocket listener = null;
		int i = 0;
		boolean save = false;
		// Set up all defaults to be passed forward to other threads.
		// Re-reading the config file in each thread is pretty damn stupid.
		String configLocation = System.getProperty(PROP_CONFIG_LOCATION, "bob.config");
		// This is here just to ensure there is no interference with our threadgroups.
		SimpleScheduler Y1 = SimpleScheduler.getInstance();
		SimpleTimer2 Y2 = SimpleTimer2.getInstance();
		i = Y1.hashCode();
		i = Y2.hashCode();
		try {
			{
				File cfg = new File(configLocation);
				if (!cfg.isAbsolute()) {
					cfg = new File(I2PAppContext.getGlobalContext().getConfigDir(), configLocation);
				}
				try {
					FileInputStream fi = new FileInputStream(cfg);
					props.load(fi);
					fi.close();
				} catch (FileNotFoundException fnfe) {
					warn("Unable to load up the BOB config file " + cfg.getAbsolutePath() + ", Using defaults.");
					warn(fnfe.toString());
					save = true;
				} catch (IOException ioe) {
					warn("IOException on BOB config file " + cfg.getAbsolutePath() + ", using defaults.");
					warn(ioe.toString());
				}
			}
			// Global router and client API configurations that are missing are set to defaults here.
			if (!props.containsKey(I2PClient.PROP_TCP_HOST)) {
				props.setProperty(I2PClient.PROP_TCP_HOST, "localhost");
				save = true;
			}
			if (!props.containsKey(I2PClient.PROP_TCP_PORT)) {
				props.setProperty(I2PClient.PROP_TCP_PORT, "7654");
				save = true;
			}
			if (!props.containsKey(PROP_BOB_PORT)) {
				props.setProperty(PROP_BOB_PORT, "2827"); // 0xB0B
				save = true;
			}
			if (!props.containsKey("inbound.length")) {
				props.setProperty("inbound.length", "1");
				save = true;
			}
			if (!props.containsKey("outbound.length")) {
				props.setProperty("outbound.length", "1");
				save = true;
			}
			if (!props.containsKey("inbound.lengthVariance")) {
				props.setProperty("inbound.lengthVariance", "0");
				save = true;
			}
			if (!props.containsKey("outbound.lengthVariance")) {
				props.setProperty("outbound.lengthVariance", "0");
				save = true;
			}
			if (!props.containsKey(PROP_BOB_HOST)) {
				props.setProperty(PROP_BOB_HOST, "localhost");
				save = true;
			}
			// PROP_RELIABILITY_NONE, PROP_RELIABILITY_BEST_EFFORT, PROP_RELIABILITY_GUARANTEED
			if (!props.containsKey(PROP_CFG_VER)) {
				props.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_NONE);
				props.setProperty(PROP_CFG_VER,"1");
				save = true;
			}
			if (save) {
				File cfg = new File(configLocation);
				if (!cfg.isAbsolute()) {
					cfg = new File(I2PAppContext.getGlobalContext().getConfigDir(), configLocation);
				}
				try {
					warn("Writing new defaults file " + cfg.getAbsolutePath());
					FileOutputStream fo = new FileOutputStream(cfg);
					props.store(fo, cfg.getAbsolutePath());
					fo.close();
				} catch (IOException ioe) {
					error("IOException on BOB config file " + cfg.getAbsolutePath() + ", " + ioe);
				}
			}

			i = 0;
			boolean g = false;
			spin.set(true);
			try {
				info("BOB is now running.");
				listener = new ServerSocket(Integer.parseInt(props.getProperty(PROP_BOB_PORT)), 10, InetAddress.getByName(props.getProperty(PROP_BOB_HOST)));
				Socket server = null;
				listener.setSoTimeout(500); // .5 sec
				
				while (spin.get()) {
					//DoCMDS connection;

					try {
						server = listener.accept();
						server.setKeepAlive(true);
						g = true;
					} catch (ConnectException ce) {
						g = false;
					} catch (SocketTimeoutException ste) {
						g = false;
					}

					if (g) {
						DoCMDS conn_c = new DoCMDS(spin, lock, server, props, database, _log);
						Thread t = new Thread(conn_c);
						t.setName("BOB.DoCMDS " + i);
						t.start();
						i++;
					}
				}
			} catch (IOException ioe) {
				error("IOException on socket listen: " + ioe);
				ioe.printStackTrace();
			}
		} finally {
			info("BOB is now shutting down...");
			// Clean up everything.
			try {
				listener.close();
			} catch (Exception ex) {
				// nop
			}
			// Find all our "BOB.DoCMDS" threads, wait for them to be finished.
			// We could order them to stop, but that could cause nasty issues in the locks.
			visitAllThreads();
			database.getReadLock();
			int all = database.getcount();
			database.releaseReadLock();
			NamedDB nickinfo;
			for (i = 0; i < all; i++) {
				database.getReadLock();
				nickinfo = (NamedDB) database.getnext(i);
				nickinfo.getReadLock();
				if (nickinfo.get(P_RUNNING).equals(Boolean.TRUE) && nickinfo.get(P_STOPPING).equals(Boolean.FALSE) && nickinfo.get(P_STARTING).equals(Boolean.FALSE)) {
					nickinfo.releaseReadLock();
					database.releaseReadLock();
					database.getWriteLock();
					nickinfo.getWriteLock();
					nickinfo.add(P_STOPPING, new Boolean(true));
					nickinfo.releaseWriteLock();
					database.releaseWriteLock();
				} else {
					nickinfo.releaseReadLock();
					database.releaseReadLock();
				}
			}
			info("BOB is now stopped.");

		}
	}

	/**
	 *	Find the root thread group,
	 *	then find all theads with certain names and wait for them all to be dead.
	 *
	 */
	private static void visitAllThreads() {
		ThreadGroup root = Thread.currentThread().getThreadGroup().getParent();
		while (root.getParent() != null) {
			root = root.getParent();
		}

		// Visit each thread group
		waitjoin(root, 0, root.getName());
	}

	private static void waitjoin(ThreadGroup group, int level, String tn) {
		// Get threads in `group'
		int numThreads = group.activeCount();
		Thread[] threads = new Thread[numThreads * 2];
		numThreads = group.enumerate(threads, false);
		// Enumerate each thread in `group' and wait for it to stop if it is one of ours.
		for (int i = 0; i < numThreads; i++) {
			// Get thread
			Thread thread = threads[i];
			if (thread.getName().startsWith("BOB.DoCMDS ")) {
				try {
					if (thread.isAlive()) {
						try {
							thread.join();
						} catch (InterruptedException ex) {
						}
					}
				} catch (SecurityException se) {
					//nop
				}
			}
		}

		// Get thread subgroups of `group'
		int numGroups = group.activeGroupCount();
		ThreadGroup[] groups = new ThreadGroup[numGroups * 2];
		numGroups = group.enumerate(groups, false);

		// Recursively visit each subgroup
		for (int i = 0; i < numGroups; i++) {
			waitjoin(groups[i], level + 1, groups[i].getName());
		}
	}
}
