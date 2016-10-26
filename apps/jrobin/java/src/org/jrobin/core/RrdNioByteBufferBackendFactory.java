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

/**
 * Factory class which creates actual {@link RrdNioBackend} objects.
 */
public class RrdNioByteBufferBackendFactory extends RrdFileBackendFactory {

	public static final String NAME = "MNIO";

	/**
	 * Creates RrdNioByteBufferBackend object for the given file path.
	 *
	 * @param path	 File path
	 * @param readOnly True, if the file should be accessed in read/only mode.
	 *                 False otherwise.
	 * @return RrdNioBackend object which handles all I/O operations for the given file path
	 * @throws IOException Thrown in case of I/O error.
	 */
	@Override
	protected RrdBackend open(String path, boolean readOnly) throws IOException {
		return new RrdNioByteBufferBackend(path, readOnly);
	}

	/**
	 * Returns the name of this factory.
	 *
	 * @return Factory name (equals to string "NIOBB")
	 */
	@Override
	public String getFactoryName() {
		return NAME;
	}
}
