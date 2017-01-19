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

package org.jrobin.data;

import org.jrobin.core.Util;

import java.util.Arrays;

class Normalizer {
	final private long[] timestamps;
	final int count;
	final long step;

	Normalizer(long[] timestamps) {
		this.timestamps = timestamps;
		this.step = timestamps[1] - timestamps[0];
		this.count = timestamps.length;
	}

	double[] normalize(long[] rawTimestamps, double[] rawValues) {
		int rawCount = rawTimestamps.length;
		long rawStep = rawTimestamps[1] - rawTimestamps[0];
		// check if we have a simple match
		if (rawCount == count && rawStep == step && rawTimestamps[0] == timestamps[0]) {
			return getCopyOf(rawValues);
		}
		// reset all normalized values to NaN
		double[] values = new double[count];
		Arrays.fill(values, Double.NaN);
		for (int rawSeg = 0, seg = 0; rawSeg < rawCount && seg < count; rawSeg++) {
			double rawValue = rawValues[rawSeg];
			if (!Double.isNaN(rawValue)) {
				long rawLeft = rawTimestamps[rawSeg] - rawStep;
				while (seg < count && rawLeft >= timestamps[seg]) {
					seg++;
				}
				boolean overlap = true;
				for (int fillSeg = seg; overlap && fillSeg < count; fillSeg++) {
					long left = timestamps[fillSeg] - step;
					long t1 = Math.max(rawLeft, left);
					long t2 = Math.min(rawTimestamps[rawSeg], timestamps[fillSeg]);
					if (t1 < t2) {
						values[fillSeg] = Util.sum(values[fillSeg], (t2 - t1) * rawValues[rawSeg]);
					}
					else {
						overlap = false;
					}
				}
			}
		}
		for (int seg = 0; seg < count; seg++) {
			values[seg] /= step;
		}
		return values;
	}

	private static double[] getCopyOf(double[] rawValues) {
		int n = rawValues.length;
		double[] values = new double[n];
		System.arraycopy(rawValues, 0, values, 0, n);
		return values;
	}
}
