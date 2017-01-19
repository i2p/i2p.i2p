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

import org.jrobin.core.RrdJRobin14FileBackend.LockMode;

/**
 * Factory class which creates actual {@link RrdFileBackend} objects. This was the default
 * backend factory in JRobin before 1.4.0 release.
 */
public class RrdJRobin14FileBackendFactory extends RrdBackendFactory {
	/**
	 * factory name, "FILE"
	 */
	public static final String NAME = "14FILE";
	private LockMode m_lockMode = LockMode.NO_LOCKS;

	public RrdJRobin14FileBackendFactory() {
	    super();
	}

    public RrdJRobin14FileBackendFactory(final LockMode lockMode) {
        super();
        m_lockMode = lockMode;
    }

	/**
	 * Creates RrdFileBackend object for the given file path.
	 *
	 * @param path	 File path
	 * @param readOnly True, if the file should be accessed in read/only mode.
	 *                 False otherwise.
	 * @return RrdFileBackend object which handles all I/O operations for the given file path
	 * @throws IOException Thrown in case of I/O error.
	 */
	protected RrdBackend open(final String path, final boolean readOnly) throws IOException {
	    return new RrdJRobin14FileBackend(path, readOnly, m_lockMode);
	}

	/**
	 * Method to determine if a file with the given path already exists.
	 *
	 * @param path File path
	 * @return True, if such file exists, false otherwise.
	 */
	protected boolean exists(final String path) {
		return Util.fileExists(path);
	}

	/**
	 * Returns the name of this factory.
	 *
	 * @return Factory name (equals to string "FILE")
	 */
	public String getFactoryName() {
		return NAME;
	}

    public String toString() {
        return getClass().getSimpleName() + "@" + Integer.toHexString(hashCode()) + "[name=" + getFactoryName() + ",lockMode=" + m_lockMode + "]";
    }
}
