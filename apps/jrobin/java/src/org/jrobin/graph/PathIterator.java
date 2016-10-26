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
package org.jrobin.graph;

class PathIterator {
	private double[] y;
	private int pos = 0;

	PathIterator(double[] y) {
		this.y = y;
	}

	int[] getNextPath() {
		while (pos < y.length) {
			if (Double.isNaN(y[pos])) {
				pos++;
			}
			else {
				int endPos = pos + 1;
				while (endPos < y.length && !Double.isNaN(y[endPos])) {
					endPos++;
				}
				int[] result = {pos, endPos};
				pos = endPos;
				if (result[1] - result[0] >= 2) {
					return result;
				}
			}
		}
		return null;
	}
}
