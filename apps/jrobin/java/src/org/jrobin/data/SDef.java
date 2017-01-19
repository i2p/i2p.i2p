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

import org.jrobin.core.RrdException;

class SDef extends Source {
	private String defName;
	private String consolFun;
	private double value;

	SDef(String name, String defName, String consolFun) {
		super(name);
		this.defName = defName;
		this.consolFun = consolFun;
	}

	String getDefName() {
		return defName;
	}

	String getConsolFun() {
		return consolFun;
	}

	void setValue(double value) {
		this.value = value;
		int count = getTimestamps().length;
		double[] values = new double[count];
		for (int i = 0; i < count; i++) {
			values[i] = value;
		}
		setValues(values);
	}

	Aggregates getAggregates(long tStart, long tEnd) throws RrdException {
		Aggregates agg = new Aggregates();
		agg.first = agg.last = agg.min = agg.max = agg.average = value;
		agg.total = value * (tEnd - tStart);
		return agg;
	}

	double getPercentile(long tStart, long tEnd, double percentile) throws RrdException {
		return value;
	}
}
