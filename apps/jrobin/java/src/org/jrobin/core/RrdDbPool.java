/*******************************************************************************
 * Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor.
 * Copyright (c) 2011 The OpenNMS Group, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/

package org.jrobin.core;

import java.io.IOException;
import java.util.HashMap;

import org.jrobin.core.RrdException;

/**
 * This class should be used to synchronize access to RRD files
 * in a multithreaded environment. This class should be also used to prevent openning of
 * too many RRD files at the same time (thus avoiding operating system limits)
 */

public class RrdDbPool {
	/**
	 * Initial capacity of the pool i.e. maximum number of simultaneously open RRD files. The pool will
	 * never open too many RRD files at the same time.
	 */
	public static final int INITIAL_CAPACITY = 200;
	private static RrdDbPool instance;

	private int capacity = INITIAL_CAPACITY;
	private HashMap<String, RrdEntry> rrdMap = new HashMap<String, RrdEntry>(INITIAL_CAPACITY);

	/**
	 * Creates a single instance of the class on the first call, or returns already existing one.
	 *
	 * @return Single instance of this class
	 * @throws RrdException Thrown if the default RRD backend is not derived from the {@link RrdFileBackendFactory}
	 */
	public synchronized static RrdDbPool getInstance() throws RrdException {
		if (instance == null) {
			instance = new RrdDbPool();
		}
		return instance;
	}

	private RrdDbPool() throws RrdException {
		RrdBackendFactory factory = RrdBackendFactory.getDefaultFactory();
		if (!(factory instanceof RrdFileBackendFactory)) {
			throw new RrdException("Cannot create instance of " + getClass().getName() + " with " +
					"a default backend factory not derived from RrdFileBackendFactory");
		}
	}

	/**
	 * Requests a RrdDb reference for the given RRD file path.<p>
	 * <ul>
	 * <li>If the file is already open, previously returned RrdDb reference will be returned. Its usage count
	 * will be incremented by one.
	 * <li>If the file is not already open and the number of already open RRD files is less than
	 * {@link #INITIAL_CAPACITY}, the file will be open and a new RrdDb reference will be returned.
	 * If the file is not already open and the number of already open RRD files is equal to
	 * {@link #INITIAL_CAPACITY}, the method blocks until some RRD file is closed.
	 * </ul>
	 *
	 * @param path Path to existing RRD file
	 * @return reference for the give RRD file
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public synchronized RrdDb requestRrdDb(String path) throws IOException, RrdException {
		String canonicalPath = Util.getCanonicalPath(path);
		while (!rrdMap.containsKey(canonicalPath) && rrdMap.size() >= capacity) {
			try {
				wait();
			}
			catch (InterruptedException e) {
				throw new RrdException(e);
			}
		}
		if (rrdMap.containsKey(canonicalPath)) {
			// already open, just increase usage count
			RrdEntry entry = rrdMap.get(canonicalPath);
			entry.count++;
			return entry.rrdDb;
		}
		else {
			// not open, open it now and add to the map
			RrdDb rrdDb = new RrdDb(canonicalPath);
			rrdMap.put(canonicalPath, new RrdEntry(rrdDb));
			return rrdDb;
		}
	}

	/**
	 * Requests a RrdDb reference for the given RRD file definition object.<p>
	 * <ul>
	 * <li>If the file with the path specified in the RrdDef object is already open,
	 * the method blocks until the file is closed.
	 * <li>If the file is not already open and the number of already open RRD files is less than
	 * {@link #INITIAL_CAPACITY}, a new RRD file will be created and a its RrdDb reference will be returned.
	 * If the file is not already open and the number of already open RRD files is equal to
	 * {@link #INITIAL_CAPACITY}, the method blocks until some RRD file is closed.
	 * </ul>
	 *
	 * @param rrdDef Definition of the RRD file to be created
	 * @return Reference to the newly created RRD file
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public synchronized RrdDb requestRrdDb(RrdDef rrdDef) throws IOException, RrdException {
		String canonicalPath = Util.getCanonicalPath(rrdDef.getPath());
		while (rrdMap.containsKey(canonicalPath) || rrdMap.size() >= capacity) {
			try {
				wait();
			}
			catch (InterruptedException e) {
				throw new RrdException(e);
			}
		}
		RrdDb rrdDb = new RrdDb(rrdDef);
		rrdMap.put(canonicalPath, new RrdEntry(rrdDb));
		return rrdDb;
	}

	/**
	 * Requests a RrdDb reference for the given path. The file will be created from
	 * external data (from XML dump, RRD file or RRDTool's binary RRD file).<p>
	 * <ul>
	 * <li>If the file with the path specified is already open,
	 * the method blocks until the file is closed.
	 * <li>If the file is not already open and the number of already open RRD files is less than
	 * {@link #INITIAL_CAPACITY}, a new RRD file will be created and a its RrdDb reference will be returned.
	 * If the file is not already open and the number of already open RRD files is equal to
	 * {@link #INITIAL_CAPACITY}, the method blocks until some RRD file is closed.
	 * </ul>
	 *
	 * @param path	   Path to RRD file which should be created
	 * @param sourcePath Path to external data which is to be converted to JRobin's native RRD file format
	 * @return Reference to the newly created RRD file
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public synchronized RrdDb requestRrdDb(String path, String sourcePath)
			throws IOException, RrdException,RrdException {
		String canonicalPath = Util.getCanonicalPath(path);
		while (rrdMap.containsKey(canonicalPath) || rrdMap.size() >= capacity) {
			try {
				wait();
			}
			catch (InterruptedException e) {
				throw new RrdException(e);
			}
		}
		RrdDb rrdDb = new RrdDb(canonicalPath, sourcePath);
		rrdMap.put(canonicalPath, new RrdEntry(rrdDb));
		return rrdDb;
	}

	/**
	 * Releases RrdDb reference previously obtained from the pool. When a reference is released, its usage
	 * count is decremented by one. If usage count drops to zero, the underlying RRD file will be closed.
	 *
	 * @param rrdDb RrdDb reference to be returned to the pool
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public synchronized void release(RrdDb rrdDb) throws IOException, RrdException {
		// null pointer should not kill the thread, just ignore it
		if (rrdDb == null) {
			return;
		}
		String canonicalPath = Util.getCanonicalPath(rrdDb.getPath());
		if (!rrdMap.containsKey(canonicalPath)) {
			throw new RrdException("Could not release [" + canonicalPath + "], the file was never requested");
		}
		RrdEntry entry = rrdMap.get(canonicalPath);
		if (--entry.count <= 0) {
			// no longer used
			rrdMap.remove(canonicalPath);
			notifyAll();
			entry.rrdDb.close();
		}
	}

	/**
	 * Returns the maximum number of simultaneously open RRD files.
	 *
	 * @return maximum number of simultaneously open RRD files
	 */
	public synchronized int getCapacity() {
		return capacity;
	}

	/**
	 * Sets the maximum number of simultaneously open RRD files.
	 *
	 * @param capacity Maximum number of simultaneously open RRD files.
	 */
	public synchronized void setCapacity(int capacity) {
		this.capacity = capacity;
	}

	/**
	 * Returns an array of open file names.
	 *
	 * @return Array with canonical paths to open RRD files held in the pool.
	 */
	public synchronized String[] getOpenFiles() {
		return rrdMap.keySet().toArray(new String[0]);
	}

	/**
	 * Returns the number of open RRD files.
	 *
	 * @return Number of currently open RRD files held in the pool.
	 */
	public synchronized int getOpenFileCount() {
		return rrdMap.size();
	}

	private final static class RrdEntry {
		RrdDb rrdDb;
		int count;

		RrdEntry(final RrdDb rrdDb) {
			this.rrdDb = rrdDb;
			this.count = 1;
		}
	}
}

// OLDER VERSION IS HERE

///* ============================================================
// * JRobin : Pure java implementation of RRDTool's functionality
// * ============================================================
// *
// * Project Info:  http://www.jrobin.org
// * Project Lead:  Sasa Markovic (saxon@jrobin.org);
// *
// * (C) Copyright 2003-2005, by Sasa Markovic.
// *
// * Developers:    Sasa Markovic (saxon@jrobin.org)
// *
// *
// * This library is free software; you can redistribute it and/or modify it under the terms
// * of the GNU Lesser General Public License as published by the Free Software Foundation;
// * either version 2.1 of the License, or (at your option) any later version.
// *
// * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
// * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// * See the GNU Lesser General Public License for more details.
// *
// * You should have received a copy of the GNU Lesser General Public License along with this
// * library; if not, write to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
// * Boston, MA 02111-1307, USA.
// */
//package org.jrobin.core;
//
//import java.io.IOException;
//import java.util.HashMap;
//import java.util.Iterator;
//import java.util.LinkedHashMap;
//import java.util.Set;
//
///**
// * Class to represent the pool of open RRD files.<p>
// *
// * To open already existing RRD file with JRobin, you have to create a
// * {@link org.jrobin.core.RrdDb RrdDb} object by specifying RRD file path
// * as constructor argument. This operation can be time consuming
// * especially with large RRD files with many datasources and
// * several long archives.<p>
// *
// * In a multithreaded environment you might probably need a reference to the
// * same RRD file from two different threads (RRD file updates are performed in
// * one thread but data fetching and graphing is performed in another one). To make
// * the RrdDb construction process more efficient it might be convenient to open all
// * RRD files in a centralized place. That's the purpose of RrdDbPool class.<p>
// *
// * How does it work? The typical usage scenario goes like this:<p>
// *
// * <pre>
// * // obtain instance to RrdDbPool object
// * RrdDbPool pool = RrdDbPool.getInstance();
// *
// * // request a reference to RrdDb object
// * String path = "some_relative_or_absolute_path_to_any_RRD_file";
// * RrdDb rrdDb = RrdDbPool.requestRrdDb(path);
// *
// * // reference obtained, do whatever you want with it...
// * ...
// * ...
// *
// * // once you don't need the reference, release it.
// * // DO NOT CALL rrdDb.close() - files no longer in use are eventually closed by the pool
// * pool.release(rrdDb);
// * </pre>
// *
// * It's that simple. When the reference is requested for the first time, RrdDbPool will open the RRD file
// * for you and make some internal note that the RRD file is used only once. When the reference
// * to the same file (same RRD file path) is requested for the second time, the same RrdDb
// * reference will be returned, and its usage count will be increased by one. When the
// * reference is released its usage count will be decremented by one.<p>
// *
// * When the reference count drops to zero, RrdDbPool will not close the underlying
// * RRD file immediatelly. Instead of it, it will be marked as 'eligible for closing'.
// * If someone request the same RRD file again (before it gets closed), the same
// * reference will be returned again.<p>
// *
// * RrdDbPool has a 'garbage collector' which runs in a separate, low-priority
// * thread and gets activated only when the number of RRD files kept in the
// * pool is too big (greater than number returned from {@link #getCapacity getCapacity()}).
// * Only RRD files with a reference count equal to zero
// * will be eligible for closing. Unreleased RrdDb references are never invalidated.
// * RrdDbPool object keeps track of the time when each RRD file
// * becomes eligible for closing so that the oldest RRD file gets closed first.<p>
// *
// * Initial RrdDbPool capacity is set to {@link #INITIAL_CAPACITY}. Use {@link #setCapacity(int)}
// * method to change it at any time.<p>
// *
// * <b>WARNING:</b>Never use close() method on the reference returned from the pool.
// * When the reference is no longer needed, return it to the pool with the
// * {@link #release(RrdDb) release()} method.<p>
// *
// * However, you are not forced to use RrdDbPool methods to obtain RrdDb references
// * to RRD files, 'ordinary' RrdDb constructors are still available. But RrdDbPool class
// * offers serious performance improvement especially in complex applications with many
// * threads and many simultaneously open RRD files.<p>
// *
// * The pool is thread-safe. Not that the {@link RrdDb} objects returned from the pool are
// * also thread-safe<p>
// *
// * You should know that each operating system has its own internal limit on the number
// * of simultaneously open files. The capacity of your RrdDbPool should be
// * reasonably smaller than the limit imposed by your operating system.<p>
// *
// * <b>WARNING:</b> The pool cannot be used to manipulate RrdDb objects
// * with {@link RrdBackend backends} different from default.<p>
// */
//public class RrdDbPool implements Runnable {
//	static final String GC_THREAD_NAME = "RrdDbPool GC thread";
//	static final String CLOSING_THREAD_NAME = "RrdDbPool closing thread";
//	private static final boolean DEBUG = false;
//
//	// singleton pattern
//	private static RrdDbPool ourInstance;
//	private boolean closingOnExit = true;
//
//	private Thread shutdownHook = new Thread(CLOSING_THREAD_NAME) {
//		public void run() {
//			try {
//				close();
//			}
//			catch (IOException e) {
//				e.printStackTrace();
//			}
//		}
//	};
//
//	/**
//	 * Constant to represent the maximum number of internally open RRD files
//	 * which still does not force garbage collector (the process which closes RRD files) to run.
//	 */
//	public static final int INITIAL_CAPACITY = 500;
//	private int capacity = INITIAL_CAPACITY, maxUsedCapacity;
//	private boolean active = true;
//
//	/**
//	 * Constant to represent the internal behaviour of the pool.
//	 * Defaults to <code>true</code> but can be changed at runtime. See
//	 * {@link #setLimitedCapacity(boolean)} for more information.
//	 */
//	public static final boolean LIMITED_CAPACITY = false;
//	private boolean limitedCapacity = LIMITED_CAPACITY;
//
//	/**
//	 * Constant to represent the priority of the background thread which closes excessive RRD files
//	 * which are no longer in use.
//	 */
//	public static final int GC_THREAD_PRIORITY = /** Thread.NORM_PRIORITY - */ 1;
//
//	private HashMap<String, RrdEntry> rrdMap = new HashMap<String, RrdEntry>(INITIAL_CAPACITY);
//	private LinkedHashMap<String, RrdEntry> rrdIdleMap = new LinkedHashMap<String, RrdEntry>(INITIAL_CAPACITY);
//	private RrdBackendFactory factory;
//	private int poolHitsCount = 0, poolRequestsCount = 0;
//
//	/**
//	 * Returns an instance to RrdDbPool object. Only one such object may exist in each JVM.
//	 *
//	 * @return Instance to RrdDbPool object.
//	 */
//	public synchronized static RrdDbPool getInstance() {
//		if (ourInstance == null) {
//			ourInstance = new RrdDbPool();
//			ourInstance.startGarbageCollector();
//		}
//		return ourInstance;
//	}
//
//	private RrdDbPool() {
//		setClosingOnExit(closingOnExit);
//	}
//
//	/**
//	 * Checks the exiting behaviour of RrdDbPool.
//	 * @return <code>True</code>, if all RRD files are to be closed
//	 * when application invokes <code>System.exit()</code>.
//	 * <code>False</code> otherwise. The default behaviour is <code>true</code>
//	 * (all RRD files will be closed on exit).
//	 */
//	public synchronized boolean isClosingOnExit() {
//		return closingOnExit;
//	}
//
//	/**
//	 * Sets the exiting behaviour of RrdDbPool.
//	 * @param closingOnExit <code>True</code>, if all RRD files are to be closed
//	 * when application invokes <code>System.exit()</code>.
//	 * <code>False</code> otherwise. The default behaviour is <code>true</code>
//	 * (all RRD files will be closed on exit).
//	 */
//	public synchronized void setClosingOnExit(boolean closingOnExit) {
//		Runtime runtime = Runtime.getRuntime();
//		runtime.removeShutdownHook(shutdownHook);
//		if(closingOnExit) {
//			runtime.addShutdownHook(shutdownHook);
//		}
//		this.closingOnExit = closingOnExit;
//	}
//
//	private void startGarbageCollector() {
//		Thread gcThread = new Thread(this, GC_THREAD_NAME);
//		gcThread.setPriority(GC_THREAD_PRIORITY);
//		gcThread.setDaemon(true);
//		gcThread.start();
//	}
//
//	/**
//	 * Returns a reference to an existing RRD file with the specified path.
//	 * If the file is already open in the pool, existing reference to it will be returned.
//	 * Otherwise, the file is open and a newly created reference to it is returned.
//	 *
//	 * @param path Relative or absolute path to a RRD file.
//	 * @return Reference to a RrdDb object (RRD file).
//	 * @throws IOException  Thrown in case of I/O error.
//	 * @throws RrdException Thrown in case of JRobin specific error.
//	 */
//	public synchronized RrdDb requestRrdDb(String path) throws IOException, RrdException {
//		proveActive();
//		poolRequestsCount++;
//		String canonicalPath = getCanonicalPath(path);
//		for(;;) {
//			RrdEntry rrdEntry = rrdMap.get(canonicalPath);
//			if (rrdEntry != null) {
//				// already open, use it!
//				reportUsage(canonicalPath, rrdEntry);
//				poolHitsCount++;
////				debug("CACHED: " + rrdEntry.dump());
//				return rrdEntry.getRrdDb();
//			}
//			else if(!limitedCapacity || rrdMap.size() < capacity) {
//				// not found, open it
//				RrdDb rrdDb = createRrdDb(path, null);
//				rrdEntry = new RrdEntry(rrdDb);
//				addRrdEntry(canonicalPath, rrdEntry);
////				debug("ADDED: " + rrdEntry.dump());
//				return rrdDb;
//			}
//			else {
//				// we have to wait
//				try {
//					wait();
//				}
//				catch (InterruptedException e) {
//					throw new RrdException("Request for file '" + path + "' was interrupted");
//				}
//			}
//		}
//	}
//
//	/**
//	 * Returns a reference to a new RRD file. The new file will have the specified
//	 * relative or absolute path, and its contents will be provided from the specified
//	 * XML file (RRDTool comaptible).
//	 *
//	 * @param path    Relative or absolute path to a new RRD file.
//	 * @param xmlPath Relative or absolute path to an existing XML dump file (RRDTool comaptible)
//	 * @return Reference to a RrdDb object (RRD file).
//	 * @throws IOException  Thrown in case of I/O error.
//	 * @throws RrdException Thrown in case of JRobin specific error.
//	 */
//	public synchronized RrdDb requestRrdDb(String path, String xmlPath)
//			throws IOException, RrdException {
//		return requestNewRrdDb(path, xmlPath);
//	}
//
//	/**
//	 * Returns a reference to a new RRD file. The new file will be created based on the
//	 * definition contained in a RrdDef object.
//	 *
//	 * @param rrdDef RRD definition object
//	 * @return Reference to a RrdDb object (RRD file).
//	 * @throws IOException  Thrown in case of I/O error.
//	 * @throws RrdException Thrown in case of JRobin specific error.
//	 */
//	public synchronized RrdDb requestRrdDb(RrdDef rrdDef) throws IOException, RrdException {
//		return requestNewRrdDb(rrdDef.getPath(), rrdDef);
//	}
//
//	private RrdDb requestNewRrdDb(String path, Object creationDef) throws IOException, RrdException {
//		proveActive();
//		poolRequestsCount++;
//		String canonicalPath = getCanonicalPath(path);
//		for(;;) {
//			RrdEntry rrdEntry = rrdMap.get(canonicalPath);
//			if(rrdEntry != null) {
//				// already open
//				removeIfIdle(canonicalPath, rrdEntry);
//			}
//			else if(!limitedCapacity || rrdMap.size() < capacity) {
//				RrdDb rrdDb = createRrdDb(path, creationDef);
//				RrdEntry newRrdEntry = new RrdEntry(rrdDb);
//				addRrdEntry(canonicalPath, newRrdEntry);
////				debug("ADDED: " + newRrdEntry.dump());
//				return rrdDb;
//			}
//			else {
//				 // we have to wait
//				try {
//					wait();
//				}
//				catch (InterruptedException e) {
//					throw new RrdException("Request for file '" + path + "' was interrupted");
//				}
//			}
//		}
//	}
//
//	private RrdDb createRrdDb(String path, Object creationDef) throws RrdException, IOException {
//		if(creationDef == null) {
//			// existing RRD
//			return new RrdDb(path, getFactory());
//		}
//		else if(creationDef instanceof String) {
//			// XML input
//			return new RrdDb(path, (String) creationDef, getFactory());
//		}
//		else if(creationDef instanceof RrdDef) {
//			// RrdDef
//			return new RrdDb((RrdDef) creationDef, getFactory());
//		}
//		else {
//			throw new RrdException("Unexpected input object type: " +
//				creationDef.getClass().getName());
//		}
//	}
//
//	private void reportUsage(String canonicalPath, RrdEntry rrdEntry) {
//		if (rrdEntry.reportUsage() == 1) {
//			// must not be garbage collected
//			rrdIdleMap.remove(canonicalPath);
//		}
//	}
//
//	private void reportRelease(String canonicalPath, RrdEntry rrdEntry) {
//		if (rrdEntry.reportRelease() == 0) {
//			// ready to be garbage collected
//			rrdIdleMap.put(canonicalPath, rrdEntry);
//		}
//	}
//
//	private void addRrdEntry(String canonicalPath, RrdEntry newRrdEntry) {
//		rrdMap.put(canonicalPath, newRrdEntry);
//		maxUsedCapacity = Math.max(rrdMap.size(), maxUsedCapacity);
//		// notify waiting threads
//		notifyAll();
//	}
//
//	private void removeIfIdle(String canonicalPath, RrdEntry rrdEntry)
//			throws RrdException, IOException {
//		// already open, check if active (not released)
//		if (rrdEntry.isInUse()) {
//			// not released, not allowed here
//			throw new RrdException("Cannot create new RrdDb file: " +
//					"File '" + canonicalPath + "' already in use");
//		} else {
//			// open but released... safe to close it
////			debug("WILL BE RECREATED: " + rrdEntry.dump());
//			removeRrdEntry(canonicalPath, rrdEntry);
//		}
//	}
//
//	private void removeRrdEntry(String canonicalPath, RrdEntry rrdEntry) throws IOException {
//		rrdEntry.closeRrdDb();
//		rrdMap.remove(canonicalPath);
//		rrdIdleMap.remove(canonicalPath);
////		debug("REMOVED: " + rrdEntry.dump());
//	}
//
//	/**
//	 * Method used to report that the reference to a RRD file is no longer needed. File that
//	 * is no longer needed (all references to it are released) is marked 'eligible for
//	 * closing'. It will be eventually closed by the pool when the number of open RRD files
//	 * becomes too big. Most recently released files will be closed last.
//	 *
//	 * @param rrdDb Reference to RRD file that is no longer needed.
//	 * @throws IOException  Thrown in case of I/O error.
//	 * @throws RrdException Thrown in case of JRobin specific error.
//	 */
//	public synchronized void release(RrdDb rrdDb) throws IOException, RrdException {
//		proveActive();
//		if (rrdDb == null) {
//			// we don't want NullPointerException
//			return;
//		}
//		if (rrdDb.isClosed()) {
//			throw new RrdException("File " + rrdDb.getPath() + " already closed");
//		}
//		String canonicalPath = getCanonicalPath(rrdDb.getPath());
//		if (rrdMap.containsKey(canonicalPath)) {
//			RrdEntry rrdEntry = rrdMap.get(canonicalPath);
//			reportRelease(canonicalPath, rrdEntry);
////			debug("RELEASED: " + rrdEntry.dump());
//		} else {
//			throw new RrdException("RRD file " + rrdDb.getPath() + " not in the pool");
//		}
//		// notify waiting threads
//		notifyAll();
//	}
//
//	/**
//	 * This method runs garbage collector in a separate thread. If the number of
//	 * open RRD files kept in the pool is too big (greater than number
//	 * returned from {@link #getCapacity getCapacity()}), garbage collector will try
//	 * to close and remove RRD files with a reference count equal to zero.
//	 * Never call this method directly.
//	 */
//	public void run() {
////		debug("GC: started");
//		while (active) {
//			synchronized (this) {
//				if (rrdMap.size() >= capacity && rrdIdleMap.size() > 0) {
//					try {
//						String canonicalPath = rrdIdleMap.keySet().iterator().next();
//						RrdEntry rrdEntry = rrdIdleMap.get(canonicalPath);
////						debug("GC: closing " + rrdEntry.dump());
//						removeRrdEntry(canonicalPath, rrdEntry);
//					} catch (IOException e) {
//						e.printStackTrace();
//					}
//					notifyAll();
//				}
//				else {
//					try {
////						debug("GC: waiting");
//						wait();
////						debug("GC: running");
//					} catch (InterruptedException e) {
//						e.printStackTrace();
//					}
//				}
//			}
//		}
//	}
//
//	protected void finalize() throws IOException {
//		close();
//	}
//
//	/**
//	 * Clears the internal state of the pool entirely. All open RRD files are closed.
//	 *
//	 * @throws IOException Thrown in case of I/O related error.
//	 */
//	public synchronized void reset() throws IOException {
//		Iterator<RrdEntry> it = rrdMap.values().iterator();
//		while (it.hasNext()) {
//			RrdEntry rrdEntry = it.next();
//			rrdEntry.closeRrdDb();
//		}
//		rrdMap.clear();
//		rrdIdleMap.clear();
////		debug("Pool cleared");
//	}
//
//	/**
//	 * Closes the pool and all RRD files currently held in the pool.
//	 * No further operations on the pool are allowed.
//	 * @throws IOException Thrown in case of I/O error.
//	 */
//	public synchronized void close() throws IOException {
//		if(active) {
//			active = false;
//			reset();
////			debug("The pool is closed.");
//		}
//		else {
////			debug("The pool is already closed!");
//		}
//	}
//
//	private static String getCanonicalPath(String path) throws IOException {
//		return Util.getCanonicalPath(path);
//	}
//
//	static void debug(String msg) {
//		if (DEBUG) {
//			System.out.println("POOL: " + msg);
//		}
//	}
//
//	/**
//	 * Returns the internal state of the pool. Useful for debugging purposes.
//	 *
//	 * @param dumpFiles <code>true</code>, if dumped information should contain paths to open files
//	 * currently held in the pool, <code>false</code> otherwise
//	 * @return Internal pool state (with an optional list of open RRD files and
//	 * the current number of usages for each one).
//	 * @throws IOException Thrown in case of I/O error.
//	 */
//	public synchronized String dump(boolean dumpFiles) throws IOException {
//		StringBuffer buff = new StringBuffer();
//		buff.append("==== POOL DUMP ===========================\n");
//		buff.append("open=" + rrdMap.size() + ", idle=" + rrdIdleMap.size() + "\n");
//		buff.append("capacity=" + capacity + ", " + "maxUsedCapacity=" + maxUsedCapacity + "\n");
//		buff.append("hits=" + poolHitsCount + ", " + "requests=" + poolRequestsCount + "\n");
//		buff.append("efficiency=" + getPoolEfficiency() + "\n");
//		if(dumpFiles) {
//			buff.append("---- CACHED FILES ------------------------\n");
//			Iterator<RrdEntry> it = rrdMap.values().iterator();
//			while (it.hasNext()) {
//				RrdEntry rrdEntry = it.next();
//				buff.append(rrdEntry.dump() + "\n");
//			}
//		}
//		return buff.toString();
//	}
//
//	/**
//	 * Returns the complete internal state of the pool. Useful for debugging purposes.
//	 *
//	 * @return Internal pool state (with a list of open RRD files and the current number of
//	 * usages for each one).
//	 * @throws IOException Thrown in case of I/O error.
//	 */
//	public synchronized String dump() throws IOException {
//		return dump(true);
//	}
//
//	/**
//	 * Returns paths to all open files currently held in the pool.
//	 * @return An array containing open file paths.
//	 */
//	public synchronized String[] getCachedFilePaths() {
//		Set<String> keySet = rrdMap.keySet();
//		int n = keySet.size(), i = 0;
//		String[] files = new String[n];
//		Iterator<String> it = keySet.iterator();
//		while(it.hasNext()) {
//			files[i++] = it.next();
//		}
//		return files;
//	}
//
//	/**
//	 * Returns maximum number of internally open RRD files
//	 * which still does not force garbage collector to run.
//	 *
//	 * @return Desired nuber of open files held in the pool.
//	 */
//	public synchronized int getCapacity() {
//		return capacity;
//	}
//
//	/**
//	 * Sets maximum number of internally open RRD files
//	 * which still does not force garbage collector to run.
//	 *
//	 * @param capacity Desired number of open files to hold in the pool
//	 */
//	public synchronized void setCapacity(int capacity) {
//		this.capacity = capacity;
////		debug("Capacity set to: " + capacity);
//	}
//
//	private RrdBackendFactory getFactory() throws RrdException {
//		if (factory == null) {
//			factory = RrdBackendFactory.getDefaultFactory();
//			if (!(factory instanceof RrdFileBackendFactory)) {
//				factory = null;
//				throw new RrdException(
//					"RrdDbPool cannot work with factories not derived from RrdFileBackendFactory");
//			}
//		}
//		return factory;
//	}
//
//	private class RrdEntry {
//		private RrdDb rrdDb;
//		private int usageCount = 1;
//
//		public RrdEntry(RrdDb rrdDb) {
//			this.rrdDb = rrdDb;
//		}
//
//		RrdDb getRrdDb() {
//			return rrdDb;
//		}
//
//		int reportUsage() {
//			assert usageCount >= 0: "Unexpected reportUsage count: " + usageCount;
//			return ++usageCount;
//		}
//
//		int reportRelease() {
//			assert usageCount > 0: "Unexpected reportRelease count: " + usageCount;
//			return --usageCount;
//		}
//
//		boolean isInUse() {
//			return usageCount > 0;
//		}
//
//		void closeRrdDb() throws IOException {
//			rrdDb.close();
//		}
//
//		String dump() throws IOException {
//			String canonicalPath = getCanonicalPath(rrdDb.getPath());
//			return canonicalPath + " [" + usageCount + "]";
//		}
//	}
//
//	/**
//	 * Calculates pool's efficency ratio. The ratio is obtained by dividing the number of
//	 * RrdDb requests served from the internal pool of open RRD files
//	 * with the number of total RrdDb requests.
//	 *
//	 * @return Pool's efficiency ratio as a double between 1 (best) and 0 (worst).
//	 * If no RrdDb reference was ever requested, 1 would be returned.
//	 */
//	public synchronized double getPoolEfficiency() {
//		if (poolRequestsCount == 0) {
//			return 1.0;
//		}
//		double ratio = (double) poolHitsCount / (double) poolRequestsCount;
//		// round to 3 decimal digits
//		return Math.round(ratio * 1000.0) / 1000.0;
//	}
//
//	/**
//	 * Returns the number of RRD requests served from the internal pool of open RRD files
//	 *
//	 * @return The number of pool "hits".
//	 */
//	public synchronized int getPoolHitsCount() {
//		return poolHitsCount;
//	}
//
//	/**
//	 * Returns the total number of RRD requests successfully served by this pool.
//	 *
//	 * @return Total number of RRD requests
//	 */
//	public synchronized int getPoolRequestsCount() {
//		return poolRequestsCount;
//	}
//
//	/**
//	 * Returns the maximum number of open RRD files over the lifetime
//	 * of the pool.
//	 * @return maximum number of open RRD files.
//	 */
//	public synchronized int getMaxUsedCapacity() {
//		return maxUsedCapacity;
//	}
//
//	/**
//	 * Checks the internal behaviour of the pool. See {@link #setLimitedCapacity(boolean)} for
//	 * more information.
//	 *
//	 * @return <code>true</code> if the pool is 'flexible' (by not imposing the strict
//	 * limit on the number of simultaneously open files), <code>false</code> otherwise.
//	 */
//	public synchronized boolean isLimitedCapacity() {
//		return limitedCapacity;
//	}
//
//	/**
//	 * Sets the behaviour of the pool. If <code>true</code> is passed as argument, the pool will never
//	 * open more than {@link #getCapacity()} files at any time. If set to <code>false</code>,
//	 * the pool might keep more open files, but only for a short period of time. This method might be
//	 * useful if you want avoid OS limits when it comes to the number of simultaneously open files.<p>
//	 *
//	 * By default, the pool behaviour is 'flexible' (<code>limitedCapacity</code> property defaults
//	 * to false<p>
//	 *
//	 * @param limitedCapacity <code>true</code> if the pool should be 'flexible' (not imposing the strict
//	 * limit on the number of simultaneously open files), <code>false</code> otherwise.
//	 */
//	public synchronized void setLimitedCapacity(boolean limitedCapacity) {
//		this.limitedCapacity = limitedCapacity;
//	}
//
//	private void proveActive() throws IOException {
//		if(!active) {
//			throw new IOException("RrdDbPool is already closed");
//		}
//	}
//
//	/**
//	 * Checks if the pool is active. You can request RrdDb references only from the active pool. The
//	 * pool is deactived when the {@link #close()} method is called.
//	 * @return <code>true</code> if active, <code>false</code> otherwise.
//	 */
//	public synchronized boolean isActive() {
//		return active;
//	}
//}
//
