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

class RrdDouble extends RrdPrimitive {
	private double cache;
	private boolean cached = false;

	RrdDouble(final RrdUpdater updater, final boolean isConstant) throws IOException {
		super(updater, RrdDouble.RRD_DOUBLE, isConstant);
	}

	RrdDouble(final RrdUpdater updater) throws IOException {
		super(updater, RrdDouble.RRD_DOUBLE, false);
	}

	void set(final double value) throws IOException {
		if (!isCachingAllowed()) {
			writeDouble(value);
		}
		// caching allowed
		else if (!cached || !Util.equal(cache, value)) {
			// update cache
			writeDouble(cache = value);
			cached = true;
		}
	}

	double get() throws IOException {
		return cached ? cache : readDouble();
	}
}
