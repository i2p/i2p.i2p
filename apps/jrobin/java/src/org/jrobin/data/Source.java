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

import org.jrobin.core.ConsolFuns;
import org.jrobin.core.RrdException;

abstract class Source implements ConsolFuns {
	final private String name;
	protected double[] values;
	protected long[] timestamps;

	Source(String name) {
		this.name = name;
	}

	String getName() {
		return name;
	}

	void setValues(double[] values) {
		this.values = values;
	}

	void setTimestamps(long[] timestamps) {
		this.timestamps = timestamps;
	}

	double[] getValues() {
		return values;
	}

	long[] getTimestamps() {
		return timestamps;
	}

	Aggregates getAggregates(long tStart, long tEnd) throws RrdException {
		Aggregator agg = new Aggregator(timestamps, values);
		return agg.getAggregates(tStart, tEnd);
	}

	double getPercentile(long tStart, long tEnd, double percentile) throws RrdException {
		Aggregator agg = new Aggregator(timestamps, values);
		return agg.getPercentile(tStart, tEnd, percentile, false);
	}

        double getPercentile(long tStart, long tEnd, double percentile, boolean includenan) throws RrdException {
		Aggregator agg = new Aggregator(timestamps, values);
		return agg.getPercentile(tStart, tEnd, percentile, includenan);
	}
}
