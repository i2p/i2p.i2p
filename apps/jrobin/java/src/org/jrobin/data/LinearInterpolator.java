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
import org.jrobin.core.Util;

import java.util.Calendar;
import java.util.Date;

/**
 * Class used to interpolate datasource values from the collection of (timestamp, values)
 * points. This class is suitable for linear interpolation only.
 * <p>
 * Interpolation algorithm returns different values based on the value passed to
 * {@link #setInterpolationMethod(int) setInterpolationMethod()}. If not set, interpolation
 * method defaults to standard linear interpolation ({@link #INTERPOLATE_LINEAR}).
 * Interpolation method handles NaN datasource
 * values gracefully.
 */
public class LinearInterpolator extends Plottable {
	/**
	 * constant used to specify LEFT interpolation.
	 * See {@link #setInterpolationMethod(int) setInterpolationMethod()} for explanation.
	 */
	public static final int INTERPOLATE_LEFT = 0;
	/**
	 * constant used to specify RIGHT interpolation.
	 * See {@link #setInterpolationMethod(int) setInterpolationMethod()} for explanation.
	 */
	public static final int INTERPOLATE_RIGHT = 1;
	/**
	 * constant used to specify LINEAR interpolation (default interpolation method).
	 * See {@link #setInterpolationMethod(int) setInterpolationMethod()} for explanation.
	 */
	public static final int INTERPOLATE_LINEAR = 2;
	/**
	 * constant used to specify LINEAR REGRESSION as interpolation method.
	 * See {@link #setInterpolationMethod(int) setInterpolationMethod()} for explanation.
	 */
	public static final int INTERPOLATE_REGRESSION = 3;

	private int lastIndexUsed = 0;
	private int interpolationMethod = INTERPOLATE_LINEAR;

	private long[] timestamps;
	private double[] values;

	// used only if INTERPOLATE_BESTFIT is specified
	double b0 = Double.NaN, b1 = Double.NaN;

	/**
	 * Creates LinearInterpolator from arrays of timestamps and corresponding datasource values.
	 *
	 * @param timestamps timestamps in seconds
	 * @param values	 corresponding datasource values
	 * @throws RrdException Thrown if supplied arrays do not contain at least two values, or if
	 *                      timestamps are not ordered, or array lengths are not equal.
	 */
	public LinearInterpolator(long[] timestamps, double[] values) throws RrdException {
		this.timestamps = timestamps;
		this.values = values;
		validate();
	}

	/**
	 * Creates LinearInterpolator from arrays of timestamps and corresponding datasource values.
	 *
	 * @param dates  Array of Date objects
	 * @param values corresponding datasource values
	 * @throws RrdException Thrown if supplied arrays do not contain at least two values, or if
	 *                      timestamps are not ordered, or array lengths are not equal.
	 */
	public LinearInterpolator(Date[] dates, double[] values) throws RrdException {
		this.values = values;
		timestamps = new long[dates.length];
		for (int i = 0; i < dates.length; i++) {
			timestamps[i] = Util.getTimestamp(dates[i]);
		}
		validate();
	}

	/**
	 * Creates LinearInterpolator from arrays of timestamps and corresponding datasource values.
	 *
	 * @param dates  array of GregorianCalendar objects
	 * @param values corresponding datasource values
	 * @throws RrdException Thrown if supplied arrays do not contain at least two values, or if
	 *                      timestamps are not ordered, or array lengths are not equal.
	 */
	public LinearInterpolator(Calendar[] dates, double[] values) throws RrdException {
		this.values = values;
		timestamps = new long[dates.length];
		for (int i = 0; i < dates.length; i++) {
			timestamps[i] = Util.getTimestamp(dates[i]);
		}
		validate();
	}

	private void validate() throws RrdException {
		boolean ok = true;
		if (timestamps.length != values.length || timestamps.length < 2) {
			ok = false;
		}
		for (int i = 0; i < timestamps.length - 1 && ok; i++) {
			if (timestamps[i] >= timestamps[i + 1]) {
				ok = false;
			}
		}
		if (!ok) {
			throw new RrdException("Invalid plottable data supplied");
		}
	}

	/**
	 * Sets interpolation method to be used. Suppose that we have two timestamp/value pairs:<br>
	 * <code>(t, 100)</code> and <code>(t + 100, 300)</code>. Here are the results interpolator
	 * returns for t + 50 seconds, for various <code>interpolationMethods</code>:
	 * <p>
	 * <ul>
	 * <li><code>INTERPOLATE_LEFT:   100</code>
	 * <li><code>INTERPOLATE_RIGHT:  300</code>
	 * <li><code>INTERPOLATE_LINEAR: 200</code>
	 * </ul>
	 * If not set, interpolation method defaults to <code>INTERPOLATE_LINEAR</code>.
	 * <p>
	 * The fourth available interpolation method is INTERPOLATE_REGRESSION. This method uses
	 * simple linear regression to interpolate supplied data with a simple straight line which does not
	 * necessarily pass through all data points. The slope of the best-fit line will be chosen so that the
	 * total square distance of real data points from from the best-fit line is at minimum.
	 * <p>
	 * The full explanation of this inteprolation method can be found
	 * <a href="http://www.tufts.edu/~gdallal/slr.htm">here</a>.
	 *
	 * @param interpolationMethod Should be <code>INTERPOLATE_LEFT</code>,
	 *                            <code>INTERPOLATE_RIGHT</code>, <code>INTERPOLATE_LINEAR</code> or
	 *                            <code>INTERPOLATE_REGRESSION</code>. Any other value will be interpreted as
	 *                            INTERPOLATE_LINEAR (default).
	 */
	public void setInterpolationMethod(int interpolationMethod) {
		switch (interpolationMethod) {
			case INTERPOLATE_REGRESSION:
				calculateBestFitLine();
				this.interpolationMethod = interpolationMethod;
				break;
			case INTERPOLATE_LEFT:
			case INTERPOLATE_RIGHT:
			case INTERPOLATE_LINEAR:
				this.interpolationMethod = interpolationMethod;
				break;
			default:
				this.interpolationMethod = INTERPOLATE_LINEAR;
		}
	}

	private void calculateBestFitLine() {
		int count = timestamps.length, validCount = 0;
		double ts = 0.0, vs = 0.0;
		for (int i = 0; i < count; i++) {
			if (!Double.isNaN(values[i])) {
				ts += timestamps[i];
				vs += values[i];
				validCount++;
			}
		}
		if (validCount <= 1) {
			// just one not-NaN point
			b0 = b1 = Double.NaN;
			return;
		}
		ts /= validCount;
		vs /= validCount;
		double s1 = 0, s2 = 0;
		for (int i = 0; i < count; i++) {
			if (!Double.isNaN(values[i])) {
				double dt = timestamps[i] - ts;
				double dv = values[i] - vs;
				s1 += dt * dv;
				s2 += dt * dt;
			}
		}
		b1 = s1 / s2;
		b0 = vs - b1 * ts;
	}

	/**
	 * Method overriden from the base class. This method will be called by the framework. Call
	 * this method only if you need interpolated values in your code.
	 *
	 * @param timestamp timestamp in seconds
	 * @return inteprolated datasource value
	 */
	public double getValue(long timestamp) {
		if (interpolationMethod == INTERPOLATE_REGRESSION) {
			return b0 + b1 * timestamp;
		}
		int count = timestamps.length;
		// check if out of range
		if (timestamp < timestamps[0] || timestamp > timestamps[count - 1]) {
			return Double.NaN;
		}
		// find matching segment
		int startIndex = lastIndexUsed;
		if (timestamp < timestamps[lastIndexUsed]) {
			// backward reading, shift to the first timestamp
			startIndex = 0;
		}
		for (int i = startIndex; i < count; i++) {
			if (timestamps[i] == timestamp) {
				return values[i];
			}
			if (i < count - 1 && timestamps[i] < timestamp && timestamp < timestamps[i + 1]) {
				// matching segment found
				lastIndexUsed = i;
				switch (interpolationMethod) {
					case INTERPOLATE_LEFT:
						return values[i];
					case INTERPOLATE_RIGHT:
						return values[i + 1];
					case INTERPOLATE_LINEAR:
						double slope = (values[i + 1] - values[i]) /
								(timestamps[i + 1] - timestamps[i]);
						return values[i] + slope * (timestamp - timestamps[i]);
					default:
						return Double.NaN;
				}
			}
		}
		// should not be here ever, but let's satisfy the compiler
		return Double.NaN;
	}
}
