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
import org.jrobin.core.Util;

/**
 * Simple class which holds aggregated values (MIN, MAX, FIRST, LAST, AVERAGE and TOTAL). You
 * don't need to create objects of this class directly. Objects of this class are returned from
 * <code>getAggregates()</code> method in
 * {@link org.jrobin.core.FetchData#getAggregates(String) FetchData} and
 * {@link DataProcessor#getAggregates(String)} DataProcessor} classes.
 */
public class Aggregates implements ConsolFuns {
	double min = Double.NaN, max = Double.NaN;
	double first = Double.NaN, last = Double.NaN;
	double average = Double.NaN, total = Double.NaN;
        double stdev = Double.NaN, lslslope = Double.NaN;
        double lslint = Double.NaN, lslcorrel = Double.NaN;

	Aggregates() {
		// NOP;
	}

	/**
	 * Returns the minimal value
	 *
	 * @return Minimal value
	 */
	public double getMin() {
		return min;
	}

	/**
	 * Returns the maximum value
	 *
	 * @return Maximum value
	 */
	public double getMax() {
		return max;
	}

	/**
	 * Returns the first falue
	 *
	 * @return First value
	 */
	public double getFirst() {
		return first;
	}

	/**
	 * Returns the last value
	 *
	 * @return Last value
	 */
	public double getLast() {
		return last;
	}

	/**
	 * Returns average
	 *
	 * @return Average value
	 */
	public double getAverage() {
		return average;
	}

	/**
	 * Returns total value
	 *
	 * @return Total value
	 */
	public double getTotal() {
		return total;
	}

	/**
	 * Returns stdev value
	 *
	 * @return Stdev value
	 */
	public double getStdev() {
		return stdev;
	}

	/**
	 * Returns Least Squares Line Slope value
	 *
	 * @return lslslope value
	 */
	public double getLSLSlope() {
		return stdev;
	}

	/**
	 * Returns Least Squares Line y-intercept value
	 *
	 * @return lslint value
	 */
	public double getLSLInt() {
		return lslint;
	}

	/**
	 * Returns Least Squares Line Correlation Coefficient
	 *
	 * @return lslcorrel value
	 */
	public double getLSLCorrel() {
		return lslcorrel;
	}

	/**
	 * Returns single aggregated value for the give consolidation function
	 *
	 * @param consolFun Consolidation function: MIN, MAX, FIRST, LAST, AVERAGE, TOTAL. These constants
	 *                  are conveniently defined in the {@link org.jrobin.core.ConsolFuns ConsolFuns} interface.
	 * @return Aggregated value
	 * @throws RrdException Thrown if unsupported consolidation function is supplied
	 */
	public double getAggregate(String consolFun) throws RrdException {
		if (consolFun.equals(CF_AVERAGE)) {
			return average;
		}
		else if (consolFun.equals(CF_FIRST)) {
			return first;
		}
		else if (consolFun.equals(CF_LAST)) {
			return last;
		}
		else if (consolFun.equals(CF_MAX)) {
			return max;
		}
		else if (consolFun.equals(CF_MIN)) {
			return min;
		}
		else if (consolFun.equals(CF_TOTAL)) {
			return total;
		}
		else if (consolFun.equals("STDEV")) {
			return stdev;
		}
		else if (consolFun.equals("LSLSLOPE")) {
			return lslslope;
		}
		else if (consolFun.equals("LSLINT")) {
			return lslint;
		}
		else if (consolFun.equals("LSLCORREL")) {
			return lslcorrel;
		}
		else {
			throw new RrdException("Unknown consolidation function: " + consolFun);
		}
	}

	/**
	 * Returns String representing all aggregated values. Just for debugging purposes.
	 *
	 * @return String containing all aggregated values
	 */
	public String dump() {
		return "MIN=" + Util.formatDouble(min) + ", MAX=" + Util.formatDouble(max) + "\n" +
				"FIRST=" + Util.formatDouble(first) + ", LAST=" + Util.formatDouble(last) + "\n" +
				"AVERAGE=" + Util.formatDouble(average) + ", TOTAL=" + Util.formatDouble(total);
	}
}
