package org.rrd4j.data;

import org.rrd4j.core.Util;

import java.util.Calendar;
import java.util.Date;

/**
 * Class used to interpolate datasource values from the collection of (timestamp, values)
 * points using natural cubic spline interpolation.
 * <p>
 *
 * <b>WARNING</b>: So far, this class cannot handle NaN datasource values
 * (an exception will be thrown by the constructor). Future releases might change this.
 */
@SuppressWarnings("deprecation")
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
     * @param values     corresponding datasource values
     * @throws java.lang.IllegalArgumentException Thrown if supplied arrays do not contain at least 3 values, or if
     *                                  timestamps are not ordered, or array lengths are not equal, or some datasource value is NaN.
     */
    public CubicSplineInterpolator(long[] timestamps, double[] values) {
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
     * @throws java.lang.IllegalArgumentException Thrown if supplied arrays do not contain at least 3 values, or if
     *                                  timestamps are not ordered, or array lengths are not equal, or some datasource value is NaN.
     */
    public CubicSplineInterpolator(Date[] dates, double[] values) {
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
     * @throws java.lang.IllegalArgumentException Thrown if supplied arrays do not contain at least 3 values, or if
     *                                  timestamps are not ordered, or array lengths are not equal, or some datasource value is NaN.
     */
    public CubicSplineInterpolator(Calendar[] dates, double[] values) {
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
     * @throws java.lang.IllegalArgumentException Thrown if supplied arrays do not contain at least 3 values, or if
     *                                  timestamps are not ordered, or array lengths are not equal, or some datasource value is NaN.
     */
    public CubicSplineInterpolator(double[] x, double[] y) {
        this.x = x;
        this.y = y;
        validate();
        spline();
    }

    private void validate() {
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
            throw new IllegalArgumentException("Invalid plottable data supplied");
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
            int k = (khi + klo) / 2;
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
	 * {@inheritDoc}
	 *
	 * Method overridden from the base class. This method will be called by the framework. Call
	 * this method only if you need spline-interpolated values in your code.
	 */
	public double getValue(long timestamp) {
		return getValue((double)timestamp);
	}

}
