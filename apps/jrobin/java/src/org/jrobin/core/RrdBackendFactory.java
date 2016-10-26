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

/**
 * Base (abstract) backend factory class which holds references to all concrete
 * backend factories and defines abstract methods which must be implemented in
 * all concrete factory implementations.
 * <p>
 * Factory classes are used to create concrete {@link RrdBackend} implementations.
 * Each factory creates unlimited number of specific backend objects.
 * <p>
 * JRobin supports four different backend types (backend factories) out of the box:<p>
 * <ul>
 * <li>{@link RrdFileBackend}: objects of this class are created from the
 * {@link RrdFileBackendFactory} class. This was the default backend used in all
 * JRobin releases before 1.4.0 release. It uses java.io.* package and RandomAccessFile class to store
 * RRD data in files on the disk.
 * <p>
 * <li>{@link RrdSafeFileBackend}: objects of this class are created from the
 * {@link RrdSafeFileBackendFactory} class. It uses java.io.* package and RandomAccessFile class to store
 * RRD data in files on the disk. This backend is SAFE:
 * it locks the underlying RRD file during update/fetch operations, and caches only static
 * parts of a RRD file in memory. Therefore, this backend is safe to be used when RRD files should
 * be shared <b>between several JVMs</b> at the same time. However, this backend is *slow* since it does
 * not use fast java.nio.* package (it's still based on the RandomAccessFile class).
 * <p>
 * <li>{@link RrdNioBackend}: objects of this class are created from the
 * {@link RrdNioBackendFactory} class. The backend uses java.io.* and java.nio.*
 * classes (mapped ByteBuffer) to store RRD data in files on the disk. This is the default backend
 * since 1.4.0 release.
 * <p>
 * <li>{@link RrdMemoryBackend}: objects of this class are created from the
 * {@link RrdMemoryBackendFactory} class. This backend stores all data in memory. Once
 * JVM exits, all data gets lost. The backend is extremely fast and memory hungry.
 * </ul>
 * <p>
 * Each backend factory is identifed by its {@link #getFactoryName() name}. Constructors
 * are provided in the {@link RrdDb} class to create RrdDb objects (RRD databases)
 * backed with a specific backend.
 * <p>
 * See javadoc for {@link RrdBackend} to find out how to create your custom backends.
 */
public abstract class RrdBackendFactory {
	private static final HashMap<String, RrdBackendFactory> factories = new HashMap<String, RrdBackendFactory>();
	private static RrdBackendFactory defaultFactory;

	static {
		try {
			RrdFileBackendFactory fileFactory = new RrdFileBackendFactory();
			registerFactory(fileFactory);
			RrdJRobin14FileBackendFactory jrobin14Factory = new RrdJRobin14FileBackendFactory();
			registerFactory(jrobin14Factory);
			RrdMemoryBackendFactory memoryFactory = new RrdMemoryBackendFactory();
			registerFactory(memoryFactory);
			RrdNioBackendFactory nioFactory = new RrdNioBackendFactory();
			registerFactory(nioFactory);
			RrdSafeFileBackendFactory safeFactory = new RrdSafeFileBackendFactory();
			registerFactory(safeFactory);
			RrdNioByteBufferBackendFactory nioByteBufferFactory = new RrdNioByteBufferBackendFactory();
			registerFactory(nioByteBufferFactory);
			selectDefaultFactory();
		}
		catch (RrdException e) {
			throw new RuntimeException("FATAL: Cannot register RRD backend factories: " + e);
		}
	}

	private static void selectDefaultFactory() throws RrdException {
		setDefaultFactory("FILE");
	}

	/**
	 * Returns backend factory for the given backend factory name.
	 *
	 * @param name Backend factory name. Initially supported names are:<p>
	 *             <ul>
	 *             <li><b>FILE</b>: Default factory which creates backends based on the
	 *             java.io.* package. RRD data is stored in files on the disk
	 *             <li><b>SAFE</b>: Default factory which creates backends based on the
	 *             java.io.* package. RRD data is stored in files on the disk. This backend
	 *             is "safe". Being safe means that RRD files can be safely shared between
	 *             several JVM's.
	 *             <li><b>NIO</b>: Factory which creates backends based on the
	 *             java.nio.* package. RRD data is stored in files on the disk
	 *             <li><b>MEMORY</b>: Factory which creates memory-oriented backends.
	 *             RRD data is stored in memory, it gets lost as soon as JVM exits.
	 *             </ul>
	 * @return Backend factory for the given factory name
	 * @throws RrdException Thrown if no factory with the given name
	 *                      is available.
	 */
	public static synchronized RrdBackendFactory getFactory(final String name) throws RrdException {
	    final RrdBackendFactory factory = factories.get(name);
		if (factory != null) {
			return factory;
		}
		throw new RrdException("No backend factory found with the name specified [" + name + "]");
	}

	/**
	 * Registers new (custom) backend factory within the JRobin framework.
	 *
	 * @param factory Factory to be registered
	 * @throws RrdException Thrown if the name of the specified factory is already
	 *                      used.
	 */
	public static synchronized void registerFactory(final RrdBackendFactory factory) throws RrdException {
	    final String name = factory.getFactoryName();
		if (!factories.containsKey(name)) {
			factories.put(name, factory);
		}
		else {
			throw new RrdException("Backend factory of this name2 (" + name + ") already exists and cannot be registered");
		}
	}

	/**
	 * Registers new (custom) backend factory within the JRobin framework and sets this
	 * factory as the default.
	 *
	 * @param factory Factory to be registered and set as default
	 * @throws RrdException Thrown if the name of the specified factory is already
	 *                      used.
	 */
	public static synchronized void registerAndSetAsDefaultFactory(final RrdBackendFactory factory) throws RrdException {
		registerFactory(factory);
		setDefaultFactory(factory.getFactoryName());
	}

	/**
	 * Returns the defaul backend factory. This factory is used to construct
	 * {@link RrdDb} objects if no factory is specified in the RrdDb constructor.
	 *
	 * @return Default backend factory.
	 */
	public static RrdBackendFactory getDefaultFactory() {
		return defaultFactory;
	}

	/**
	 * Replaces the default backend factory with a new one. This method must be called before
	 * the first RRD gets created. <p>
	 *
	 * @param factoryName Name of the default factory. Out of the box, JRobin supports four
	 *                    different RRD backends: "FILE" (java.io.* based), "SAFE" (java.io.* based - use this
	 *                    backend if RRD files may be accessed from several JVMs at the same time),
	 *                    "NIO" (java.nio.* based) and "MEMORY" (byte[] based).
	 * @throws RrdException Thrown if invalid factory name is supplied or not called before
	 *                      the first RRD is created.
	 */
	public static void setDefaultFactory(final String factoryName) throws RrdException {
		// We will allow this only if no RRDs are created
		if (!RrdBackend.isInstanceCreated()) {
			defaultFactory = getFactory(factoryName);
		}
		else {
			throw new RrdException("Could not change the default backend factory. This method must be called before the first RRD gets created");
		}
	}

	/**
	 * Whether or not the RRD backend has created an instance yet.
	 *
	 * @return True if the backend instance is created, false if not.
	 */
	public static boolean isInstanceCreated() {
		return RrdBackend.isInstanceCreated();
	}

	/**
	 * Creates RrdBackend object for the given storage path.
	 *
	 * @param path	 Storage path
	 * @param readOnly True, if the storage should be accessed in read/only mode.
	 *                 False otherwise.
	 * @return Backend object which handles all I/O operations for the given storage path
	 * @throws IOException Thrown in case of I/O error.
	 */
	protected abstract RrdBackend open(String path, boolean readOnly) throws IOException;

	/**
	 * Method to determine if a storage with the given path already exists.
	 *
	 * @param path Storage path
	 * @return True, if such storage exists, false otherwise.
	 * @throws IOException Thrown in case of I/O error.
	 */
	protected abstract boolean exists(String path) throws IOException;

	/**
	 * Returns the name (primary ID) for the factory.
	 *
	 * @return Name of the factory.
	 */
	public abstract String getFactoryName();

	public String toString() {
	    return getClass().getSimpleName() + "@" + Integer.toHexString(hashCode()) + "[name=" + getFactoryName() + "]";
	}
}
