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

import static net.i2p.app.ClientAppState.*;

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
import java.net.URL;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import net.i2p.I2PAppContext;
import net.i2p.app.*;
import net.i2p.client.I2PClient;
import net.i2p.util.I2PAppThread;
import net.i2p.util.PortMapper;
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
public class BOB implements Runnable, ClientApp {

	public final static String PROP_CONFIG_LOCATION = "BOB.config";
	public final static String PROP_BOB_PORT = "BOB.port";
	public final static String PROP_BOB_HOST = "BOB.host";
	public final static String PROP_CFG_VER = "BOB.CFG.VER";

	/** unused when started via the ClientApp interface */
	private static BOB _bob;

	private final NamedDB database;
	private final Properties props = new Properties();
	private final AtomicBoolean spin = new AtomicBoolean(true);
	private static final String P_RUNNING = "RUNNING";
	private static final String P_STARTING = "STARTING";
	private static final String P_STOPPING = "STOPPING";
	private final AtomicBoolean lock = new AtomicBoolean(false);
	// no longer used.
	// private static int maxConnections = 0;

	private final I2PAppContext _context;
	private final Logger _log;
	private final ClientAppManager _mgr;
	private final String[] _args;
	private volatile ClientAppState _state = UNINITIALIZED;

	private volatile ServerSocket listener;
	private volatile Thread _runner;

	/**
	 * Stop BOB gracefully
	 * @deprecated unused
	 */
	@Deprecated
	public synchronized static void stop() {
		if (_bob != null)
			_bob.shutdown(null);
	}

	/**
	 *  For ClientApp interface.
	 *  Does NOT open the listener socket or start threads; caller must call startup()
	 *
	 *  @param mgr may be null
	 *  @param args non-null
	 *  @since 0.9.10
	 */
	public BOB(I2PAppContext context, ClientAppManager mgr, String[] args) {
		_context = context;
		// If we were run from command line, log to stdout
		boolean logToStdout = false;
		URL classResource = BOB.class.getResource("BOB.class");
		if (classResource != null) {
		    String classPath = classResource.toString();
		    if (classPath.startsWith("jar")) {
		        String manifestPath = classPath.substring(0, classPath.lastIndexOf('!') + 1) +
		                "/META-INF/MANIFEST.MF";
		        try {
		            Manifest manifest = new Manifest(new URL(manifestPath).openStream());
		            Attributes attrs = manifest.getMainAttributes();
		            String mainClass = attrs.getValue("Main-Class");
		            if ("net.i2p.BOB.Main".equals(mainClass))
		                logToStdout = true;
		        } catch (IOException ioe) {}
		    }
		}

		_log = new Logger(context.logManager().getLog(BOB.class), logToStdout);

		_mgr = mgr;
		_args = args;
		_state = INITIALIZED;
		database = new NamedDB();
		loadConfig();
	}

	/**
	 * Listen for incoming connections and handle them
	 *
	 * @param args
	 */
	public synchronized static void main(String[] args) {
		try {
			_bob = new BOB(I2PAppContext.getGlobalContext(), null, args);
			_bob.startup();
		} catch (RuntimeException e) {
			e.printStackTrace();
			throw e;
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	/**
	 * @since 0.9.10
	 */
	private void loadConfig() {
		int i = 0;
		boolean save = false;
		// Set up all defaults to be passed forward to other threads.
		// Re-reading the config file in each thread is pretty damn stupid.
		String configLocation = System.getProperty(PROP_CONFIG_LOCATION, "bob.config");
		// This is here just to ensure there is no interference with our threadgroups.
		SimpleTimer2 Y2 = SimpleTimer2.getInstance();
		i = Y2.hashCode();
		{
			File cfg = new File(configLocation);
			if (!cfg.isAbsolute()) {
				cfg = new File(_context.getConfigDir(), configLocation);
			}
			FileInputStream fi = null;
			try {
				fi = new FileInputStream(cfg);
				props.load(fi);
			} catch (FileNotFoundException fnfe) {
				_log.warn("Unable to load up the BOB config file " + cfg.getAbsolutePath() + ", Using defaults.", fnfe);
				save = true;
			} catch (IOException ioe) {
				_log.warn("IOException on BOB config file " + cfg.getAbsolutePath() + ", using defaults.", ioe);
			} finally {
				if (fi != null) try { fi.close(); } catch (IOException ioe) {}
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
			props.setProperty("inbound.length", "3");
			save = true;
		}
		if (!props.containsKey("outbound.length")) {
			props.setProperty("outbound.length", "3");
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
				cfg = new File(_context.getConfigDir(), configLocation);
			}
			FileOutputStream fo = null;
			try {
				_log.warn("Writing new defaults file " + cfg.getAbsolutePath());
				fo = new FileOutputStream(cfg);
				props.store(fo, cfg.getAbsolutePath());
			} catch (IOException ioe) {
				_log.error("IOException on BOB config file " + cfg.getAbsolutePath(), ioe);
			} finally {
				if (fo != null) try { fo.close(); } catch (IOException ioe) {}
			}
		}
	}

	/**
	 * @since 0.9.10
	 */
	private void startListener() throws IOException {
		listener = new ServerSocket(Integer.parseInt(props.getProperty(PROP_BOB_PORT)), 10, InetAddress.getByName(props.getProperty(PROP_BOB_HOST)));
		listener.setSoTimeout(500); // .5 sec
	}

	/**
	 * @since 0.9.10
	 */
	private void startThread() {
		I2PAppThread t = new I2PAppThread(this, "BOBListener");
		t.start();
		_runner = t;
	}

	/**
	 * @since 0.9.10
	 */
	public void run() {
		if (listener == null) return;
		changeState(RUNNING);
		_log.info("BOB is now running.");
		if (_mgr != null)
			_mgr.register(this);
		_context.portMapper().register(PortMapper.SVC_BOB, props.getProperty(PROP_BOB_HOST),
		                               Integer.parseInt(props.getProperty(PROP_BOB_PORT)));

		int i = 0;
		boolean g = false;
		spin.set(true);
		try {
			Socket server = null;

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
					Thread t = new I2PAppThread(conn_c);
					t.setName("BOB.DoCMDS " + i);
					t.start();
					i++;
				}
			}
			changeState(STOPPING);
		} catch (Exception e) {
			if (spin.get())
				_log.error("Unexpected error while listening for connections", e);
			else
				e = null;
			changeState(STOPPING, e);
		} finally {
			_log.info("BOB is now shutting down...");
			_context.portMapper().unregister(PortMapper.SVC_BOB);
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
			NamedDB nickinfo;
			try {
				for (Object ndb : database.values()) {
					nickinfo = (NamedDB) ndb;
					nickinfo.getReadLock();
					boolean released = false;
					try {
						if (nickinfo.get(P_RUNNING).equals(Boolean.TRUE) && nickinfo.get(P_STOPPING).equals(Boolean.FALSE) && nickinfo.get(P_STARTING).equals(Boolean.FALSE)) {
							nickinfo.releaseReadLock();
							released = true;
							nickinfo.getWriteLock();
							try {
								nickinfo.add(P_STOPPING, Boolean.TRUE);
							} finally {
								nickinfo.releaseWriteLock();
							}
						}
					} finally {
						if (!released)
							nickinfo.releaseReadLock();
					}
				}
			} finally {
				database.releaseReadLock();
			}
			changeState(STOPPED);
			_log.info("BOB is now stopped.");
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

	////// begin ClientApp interface

	/**
	 * @since 0.9.10
	 */
	@Override
	public void startup() throws IOException {
		if (_state != INITIALIZED)
			return;
		changeState(STARTING);
		try {
			startListener();
		} catch (IOException e) {
			_log.error("Error starting BOB on"
					+ props.getProperty(PROP_BOB_HOST)
					+ ":" + props.getProperty(PROP_BOB_PORT), e);
			changeState(START_FAILED, e);
			throw e;
		}
		startThread();
	}

	/**
	 * @since 0.9.10
	 */
	@Override
	public void shutdown(String[] args) {
		if (_state != RUNNING)
			return;
		changeState(STOPPING);
		spin.set(false);
		if (_runner != null)
			_runner.interrupt();
		else
			changeState(STOPPED);
	}

	/**
	 * @since 0.9.10
	 */
	@Override
	public ClientAppState getState() {
		return _state;
	}

	/**
	 * @since 0.9.10
	 */
	@Override
	public String getName() {
		return "BOB";
	}

	/**
	 * @since 0.9.10
	 */
	@Override
	public String getDisplayName() {
		return "BOB " + Arrays.toString(_args);
	}

	////// end ClientApp interface
	////// begin ClientApp helpers

	/**
	 *  @since 0.9.10
	 */
	private void changeState(ClientAppState state) {
		changeState(state, null);
	}

	/**
	 *  @since 0.9.10
	 */
	private synchronized void changeState(ClientAppState state, Exception e) {
		_state = state;
		if (_mgr != null)
			_mgr.notify(this, state, null, e);
	}

	////// end ClientApp helpers
}
