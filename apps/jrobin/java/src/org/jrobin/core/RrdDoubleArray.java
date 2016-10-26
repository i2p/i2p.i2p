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

class RrdDoubleArray extends RrdPrimitive {
	private int length;

	RrdDoubleArray(final RrdUpdater updater, final int length) throws IOException {
		super(updater, RrdPrimitive.RRD_DOUBLE, length, false);
		this.length = length;
	}

	void set(final int index, final double value) throws IOException {
		set(index, value, 1);
	}

	void set(final int index, final double value, final int count) throws IOException {
		// rollovers not allowed!
		assert index + count <= length:	"Invalid robin index supplied: index=" + index +", count=" + count + ", length=" + length;
		writeDouble(index, value, count);
	}

	double get(final int index) throws IOException {
		assert index < length: "Invalid index supplied: " + index + ", length=" + length;
		return readDouble(index);
	}

	double[] get(final int index, final int count) throws IOException {
		assert index + count <= length: "Invalid index/count supplied: " + index + "/" + count + " (length=" + length + ")";
		return readDouble(index, count);
	}

}
