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

import java.util.HashMap;

/**
 * Factory class which creates actual {@link RrdMemoryBackend} objects. JRobin's support
 * for in-memory RRDs is still experimental. You should know that all active RrdMemoryBackend
 * objects are held in memory, each backend object stores RRD data in one big byte array. This
 * implementation is therefore quite basic and memory hungry but runs very fast.
 * <p>
 * Calling {@link RrdDb#close() close()} on RrdDb objects does not release any memory at all
 * (RRD data must be available for the next <code>new RrdDb(path)</code> call. To release allocated
 * memory, you'll have to call {@link #delete(String) delete(path)} method of this class.
 */
public class RrdMemoryBackendFactory extends RrdBackendFactory {
	/**
	 * factory name, "MEMORY"
	 */
	public static final String NAME = "MEMORY";
	private HashMap<String, RrdMemoryBackend> backends = new HashMap<String, RrdMemoryBackend>();

	/**
	 * Creates RrdMemoryBackend object.
	 *
	 * @param id	   Since this backend holds all data in memory, this argument is interpreted
	 *                 as an ID for this memory-based storage.
	 * @param readOnly This parameter is ignored
	 * @return RrdMemoryBackend object which handles all I/O operations
	 */
	protected synchronized RrdBackend open(String id, boolean readOnly) {
		RrdMemoryBackend backend;
		if (backends.containsKey(id)) {
			backend = backends.get(id);
		}
		else {
			backend = new RrdMemoryBackend(id);
			backends.put(id, backend);
		}
		return backend;
	}

	/**
	 * Method to determine if a memory storage with the given ID already exists.
	 *
	 * @param id Memory storage ID.
	 * @return True, if such storage exists, false otherwise.
	 */
	protected synchronized boolean exists(String id) {
		return backends.containsKey(id);
	}

	/**
	 * Removes the storage with the given ID from the memory.
	 *
	 * @param id Storage ID
	 * @return True, if the storage with the given ID is deleted, false otherwise.
	 */
	public boolean delete(String id) {
		if (backends.containsKey(id)) {
			backends.remove(id);
			return true;
		}
		return false;
	}

	/**
	 * Returns the name of this factory.
	 *
	 * @return Factory name (equals to "MEMORY").
	 */
	public String getFactoryName() {
		return NAME;
	}
}
