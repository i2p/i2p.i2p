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
 * points using natural cubic spline interpolation.
 * <p>
 * <b>WARNING</b>: So far, this class cannot handle NaN datasource values
 * (an exception will be thrown by the constructor). Future releases might change this.
 */
public class CubicSplineInterpolator extends Plottable {
	private double[] x;
	private double[] y;

	// second derivates come here
	private double[] y2;

	// internal spline variables
	private int n, klo, khi;

	/**
	 * Creates cubic spline interpolator from arrays of timestamps and corresponding
	 * datasource values.
	 *
	 * @param timestamps timestamps in seconds
	 * @param values	 corresponding datasource values
	 * @throws RrdException Thrown if supplied arrays do not contain at least 3 values, or if
	 *                      timestamps are not ordered, or array lengths are not equal, or some datasource value is NaN.
	 */
	public CubicSplineInterpolator(long[] timestamps, double[] values) throws RrdException {
		this.x = new double[timestamps.length];
		for (int i = 0; i < timestamps.length; i++) {
			this.x[i] = timestamps[i];
		}
		this.y = values;
		validate();
		spline();
	}

	/**
	 * Creates cubic spline interpolator from arrays of Date objects and corresponding
	 * datasource values.
	 *
	 * @param dates  Array of Date objects
	 * @param values corresponding datasource values
	 * @throws RrdException Thrown if supplied arrays do not contain at least 3 values, or if
	 *                      timestamps are not ordered, or array lengths are not equal, or some datasource value is NaN.
	 */
	public CubicSplineInterpolator(Date[] dates, double[] values) throws RrdException {
		this.x = new double[dates.length];
		for (int i = 0; i < dates.length; i++) {
			this.x[i] = Util.getTimestamp(dates[i]);
		}
		this.y = values;
		validate();
		spline();
	}

	/**
	 * Creates cubic spline interpolator from arrays of GregorianCalendar objects and corresponding
	 * datasource values.
	 *
	 * @param dates  Array of GregorianCalendar objects
	 * @param values corresponding datasource values
	 * @throws RrdException Thrown if supplied arrays do not contain at least 3 values, or if
	 *                      timestamps are not ordered, or array lengths are not equal, or some datasource value is NaN.
	 */
	public CubicSplineInterpolator(Calendar[] dates, double[] values) throws RrdException {
		this.x = new double[dates.length];
		for (int i = 0; i < dates.length; i++) {
			this.x[i] = Util.getTimestamp(dates[i]);
		}
		this.y = values;
		validate();
		spline();
	}

	/**
	 * Creates cubic spline interpolator for an array of 2D-points.
	 *
	 * @param x x-axis point coordinates
	 * @param y y-axis point coordinates
	 * @throws RrdException Thrown if supplied arrays do not contain at least 3 values, or if
	 *                      timestamps are not ordered, or array lengths are not equal, or some datasource value is NaN.
	 */
	public CubicSplineInterpolator(double[] x, double[] y) throws RrdException {
		this.x = x;
		this.y = y;
		validate();
		spline();
	}

	private void validate() throws RrdException {
		boolean ok = true;
		if (x.length != y.length || x.length < 3) {
			ok = false;
		}
		for (int i = 0; i < x.length - 1 && ok; i++) {
			if (x[i] >= x[i + 1] || Double.isNaN(y[i])) {
				ok = false;
			}
		}
		if (!ok) {
			throw new RrdException("Invalid plottable data supplied");
		}
	}

	private void spline() {
		n = x.length;
		y2 = new double[n];
		double[] u = new double[n - 1];
		y2[0] = y2[n - 1] = 0.0;
		u[0] = 0.0; // natural spline
		for (int i = 1; i <= n - 2; i++) {
			double sig = (x[i] - x[i - 1]) / (x[i + 1] - x[i - 1]);
			double p = sig * y2[i - 1] + 2.0;
			y2[i] = (sig - 1.0) / p;
			u[i] = (y[i + 1] - y[i]) / (x[i + 1] - x[i]) - (y[i] - y[i - 1]) / (x[i] - x[i - 1]);
			u[i] = (6.0 * u[i] / (x[i + 1] - x[i - 1]) - sig * u[i - 1]) / p;
		}
		for (int k = n - 2; k >= 0; k--) {
			y2[k] = y2[k] * y2[k + 1] + u[k];
		}
		// prepare everything for getValue()
		klo = 0;
		khi = n - 1;
	}

	/**
	 * Calculates spline-interpolated y-value for the corresponding x-value. Call
	 * this if you need spline-interpolated values in your code.
	 *
	 * @param xval x-value
	 * @return inteprolated y-value
	 */
	public double getValue(double xval) {
		if (xval < x[0] || xval > x[n - 1]) {
			return Double.NaN;
		}
		if (xval < x[klo] || xval > x[khi]) {
			// out of bounds
			klo = 0;
			khi = n - 1;
		}
		while (khi - klo > 1) {
			// find bounding interval using bisection method
			int k = (khi + klo) >>> 1;
			if (x[k] > xval) {
				khi = k;
			}
			else {
				klo = k;
			}
		}
		double h = x[khi] - x[klo];
		double a = (x[khi] - xval) / h;
		double b = (xval - x[klo]) / h;
		return a * y[klo] + b * y[khi] +
				((a * a * a - a) * y2[klo] + (b * b * b - b) * y2[khi]) * (h * h) / 6.0;
	}

	/**
	 * Method overriden from the base class. This method will be called by the framework. Call
	 * this method only if you need spline-interpolated values in your code.
	 *
	 * @param timestamp timestamp in seconds
	 * @return inteprolated datasource value
	 */
	public double getValue(long timestamp) {
		return getValue((double) timestamp);
	}

}
