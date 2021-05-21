package org.rrd4j.data;

import java.io.Serializable;
import java.util.Comparator;
import java.util.SortedSet;
import java.util.TreeSet;

import org.rrd4j.core.Util;

/**
 *  An abstract class to help extract single value from a set of value (VDEF in rrdtool)
 *  
 *  It can be used to add new fancy statistical calculation with rrd values 
 *
 */
public abstract class Variable {

    /**
     * This class store both the value and the time stamp
     * It will be used by graph rendering legend
     */
    public static final class Value {
        public final double value;
        public final long timestamp;
        Value(long timestamp, double value) {
            this.value = value;
            this.timestamp = timestamp;
        }
        @Override
        public String toString() {
            return "Value [value=" + value + ", timestamp=" + timestamp + "]";
        }
    };

    public static final Value INVALIDVALUE = new Value(0, Double.NaN);

    private Value val = null;

    /**
     * Used to calculate the needed value from a source, this method call the abstract method {@link #fill(long[], double[], long, long)}.
     * @param s
     * @param start
     * @param end
     */
    void calculate(Source s, long start, long end) {
        long step = s.timestamps[1] - s.timestamps[0];
        int first = -1;
        int last = -1;
        // Iterate over array, stop then end cursor reach start or when both start and end has been found
        // It also stop if cursor cross other side boundary
        for (int i = 0, j = s.timestamps.length - 1 ; i < s.timestamps.length && j >= 0 ; i++, j--) {
            if (first == -1) {
                long leftdown = Math.max(s.timestamps[i] - step, start);
                long rightdown = Math.min(s.timestamps[i], end);
                if(rightdown > leftdown) {
                    first = i;
                }
            }

            if (last == -1) {
                long leftup = Math.max(s.timestamps[j] - step, start);
                long rightup = Math.min(s.timestamps[j], end);
                if (rightup > leftup ) {
                    last = j;
                }
            }
            if ((( last != -1 || j <= first ) && ( first != -1 || ( last != -1 && i >= last )))) {
                break;
            }
        }
        if (first == -1 || last == -1) {
            val = INVALIDVALUE;
        } else if (s instanceof VDef) {
            // Already a variable, just check if it fits
            Value v = ((VDef) s).getValue();
            // No time stamp, or not time stamped value, keep it
            if (v.timestamp == 0) {
                val = v;
            }
            else {
                if (v.timestamp < end && v.timestamp > start) {
                    val = v;
                }
                else {
                    val = new Value(0, Double.NaN);
                }
            }
        } else {
            long[] timestamps = new long[ last - first + 1];
            System.arraycopy(s.timestamps, first, timestamps, 0, timestamps.length);
            double[] values = new double[ last - first + 1];
            System.arraycopy(s.getValues(), first, values, 0, values.length);
            val = fill(timestamps, values, start, end);
        }
    }

    public Value getValue() {
        assert val != null : "Used before calculation";
        return val;
    }

    /**
     * This method is call with the needed values, extracted from the datasource to do the calculation.
     * 
     * Value is to be filled with both the double value and a possible timestamp, when it's used to find
     * a specific point
     * 
     * @param timestamps the timestamps for the value
     * @param values the actual values
     * @param start the start of the period
     * @param end the end of the period
     * @return a filled Value object
     */
    protected abstract Value fill(long timestamps[], double[] values, long start, long end);

    /**
     * Find the first valid data point and it's timestamp
     *
     */
    public static class FIRST extends Variable {
        @Override
        protected Value fill(long[] timestamps, double[] values, long start, long end) {
            for (int i = 0; i < values.length; i++) {
                if (timestamps[i] > start && timestamps[i] < end && ! Double.isNaN(values[i])) {
                    return new Value(timestamps[i], values[i]);
                }
            }
            return new Value(0, Double.NaN);
        }
    }

    /**
     * Find the first last valid point and it's timestamp
     *
     */
    public static class LAST extends Variable {
        @Override
        protected Value fill(long[] timestamps, double[] values, long start, long end) {
            for (int i = values.length - 1 ; i >=0 ; i--) {
                if (! Double.isNaN(values[i]) ) {
                    return new Value(timestamps[i], values[i]);
                }
            }
            return new Value(0, Double.NaN);
        }
    }

    /**
     * The smallest of the data points and it's time stamp (the first one) is stored.
     *
     */
    public static class MIN extends Variable {
        @Override
        protected Value fill(long[] timestamps, double[] values, long start, long end) {
            long timestamp = 0;
            double value = Double.NaN;
            for (int i = values.length -1 ; i >=0 ; i--) {
                if (! Double.isNaN(values[i]) && Double.isNaN(value)) {
                    timestamp = timestamps[i];
                    value = values[i];
                } else if ( ! Double.isNaN(values[i]) && value > values[i]) {
                    timestamp = timestamps[i];
                    value = values[i];
                }
            }
            return new Value(timestamp, value);
        }
    }

    /**
     * The biggest of the data points and it's time stamp (the first one) is stored.
     *
     */
    public static class MAX extends Variable {
        @Override
        protected Value fill(long[] timestamps, double[] values, long start, long end) {
            long timestamp = 0;
            double value = Double.NaN;
            for (int i = values.length -1 ; i >=0 ; i--) {
                if (! Double.isNaN(values[i]) && Double.isNaN(value)) {
                    timestamp = timestamps[i];
                    value = values[i];
                } else if (!Double.isNaN(values[i]) && value < values[i]) {
                    timestamp = timestamps[i];
                    value = values[i];
                }
            }
            return new Value(timestamp, value);
        }
    }

    /**
     * Calculate the sum of the data points.
     *
     */
    public static class TOTAL extends Variable {
        @Override
        protected Value fill(long[] timestamps, double[] values, long start, long end) {
            double value = Double.NaN;

            for (double tempVal: values) {
                value = Util.sum(value, tempVal);
            }
            return new Value(0, value * (timestamps[1] - timestamps[0]) );
        }
    }

    /**
     * Calculate the average of the data points.
     *
     */
    public static class AVERAGE extends Variable {
        @Override
        protected Value fill(long[] timestamps, double[] values, long start, long end) {
            double value = 0;
            int count = 0;
            for (int i = values.length - 1 ; i >= 0 ; i--) {
                if ( !Double.isNaN(values[i]) ) {
                    count++;
                    value = Double.isNaN(value) ?  values[i] : values[i] + value;
                }
            }
            if (! Double.isNaN(value) && count > 0) {
                value = value / count;
            } else {
                value = Double.NaN;
            }
            return new Value(0, value);
        }
    }

    /**
     * Calculate the standard deviation for the data point.
     *
     */
    public static class STDDEV extends Variable {
        @Override
        protected Value fill(long[] timestamps, double[] values, long start, long end) {
            double value = Double.NaN;
            int count = 0;
            double M = 0.0;
            double S = 0.0;
            // See Knuth TAOCP vol 2, 3rd edition, page 232 and http://www.johndcook.com/standard_deviation.html
            for (double cursVal: values) {
                if (Double.isNaN(cursVal))
                    continue;
                count++;
                if (count == 1) {
                    M = cursVal;
                    S = 0;
                } else {
                    double dM = cursVal - M;
                    M += dM/count;
                    S += dM * (cursVal - M);
                }
            }
            if (count > 1) {
                value = Math.sqrt( S/(count - 1) );
            }
            return new Value(0, value);
        }
    }

    /**
     * Store all the informations about a datasource point, for predictive and consistent sorting
     *
     */
    static final class PercentElem {
        long timestamp;
        double value;

        PercentElem(int pos, long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            PercentElem other = (PercentElem) obj;
            if (timestamp != other.timestamp)
                return false;
            return true;
        }

        @Override
        public int hashCode() {
            return Long.valueOf(timestamp).hashCode();
        }

        @Override
        public String toString() {
            return String.format("[%d, %f]", timestamp, value);
        }
    }

    /**
     * The sort used by rrdtool for percent, where NaN &lt; -INF &lt; finite values &lt; INF
     *
     */
    static final class ComparPercentElemen implements Comparator<PercentElem>, Serializable {
        @Override
        public final int compare(PercentElem arg0, PercentElem arg1) {
            if (Double.isNaN(arg0.value) && Double.isNaN(arg1.value))
                return Long.signum(arg0.timestamp - arg1.timestamp);
            else if (Double.isNaN(arg0.value))
                return -1;
            else if (Double.isNaN(arg1.value))
                return +1;
            else  {
                int compared = Double.compare(arg0.value, arg1.value);
                if (compared == 0) {
                    compared = Long.signum(arg0.timestamp - arg1.timestamp);
                }
                return compared;
            }
        }
    }

    /**
     * Find the point at the n-th percentile.
     *
     */
    public static class PERCENTILE extends Variable {
        private final float percentile;
        private final boolean withNaN;

        protected PERCENTILE(float percentile, boolean withNaN) {
            this.percentile = percentile;
            this.withNaN = withNaN;
        }

        public PERCENTILE(double percentile) {
            this((float) percentile, true);
        }

        public PERCENTILE(float percentile) {
            this(percentile, true);
        }

        @Override
        protected Value fill(long[] timestamps, double[] values, long start, long end) {
            // valuesSet will be a set with NaN packet at the start
            SortedSet<PercentElem> valuesSet = new TreeSet<PercentElem>(new ComparPercentElemen());
            for (int i = 0 ; i < values.length ; i++) {
                valuesSet.add(new PercentElem(i, timestamps[i], values[i]));
            }

            //If not with nan, just drop all nan (inferior to min value)
            if (! withNaN) {
                valuesSet = valuesSet.tailSet(new PercentElem(0, 0, Double.NEGATIVE_INFINITY ));
            }

            PercentElem[] element = (PercentElem[]) valuesSet.toArray(new PercentElem[valuesSet.size()]);
            int pos = Math.round(percentile * (element.length - 1) / 100);
            // if we have anything left...
            if (pos >= 0) {
                double value = element[pos].value;
                long timestamp = element[pos].timestamp;
                return new Value(timestamp, value);
            }
            return new Value(0, Double.NaN);
        }
    }

    public static class PERCENTILENAN extends PERCENTILE {

        public PERCENTILENAN(float percentile) {
            super(percentile, false);
        }

        public PERCENTILENAN(double percentile) {
            super((float)percentile, false);
        }
    }

    /**
     * Calculate the slop of the least squares line.
     *
     */
    public static class LSLSLOPE extends Variable {

        @Override
        protected Value fill(long[] timestamps, double[] values, long start, long end) {
            int cnt = 0;
            int lslstep = 0;
            double SUMx = 0.0;
            double SUMy = 0.0;
            double SUMxy = 0.0;
            double SUMxx = 0.0;
            double lslslope;

            for (int i = 0; i < values.length; i++) {
                double value = values[i];

                if (!Double.isNaN(value)) {
                    cnt++;

                    SUMx += lslstep;
                    SUMxx += lslstep * lslstep;
                    SUMy  += value;
                    SUMxy += lslstep * value;

                }
                lslstep++;
            }
            double divisor = (SUMx * SUMx - cnt * SUMxx);
            if (divisor != 0) {
                /* Bestfit line by linear least squares method */
                lslslope = (SUMx * SUMy - cnt * SUMxy) / divisor;
                return new Value(0, lslslope);
            } else {
                return new Value(0, Double.NaN);
            }
        }

    }

    /**
     * Calculate the y-intercept of the least squares line.
     *
     */
    public static class LSLINT extends Variable {

        @Override
        protected Value fill(long[] timestamps, double[] values, long start, long end) {
            int cnt = 0;
            int lslstep = 0;
            double SUMx = 0.0;
            double SUMy = 0.0;
            double SUMxy = 0.0;
            double SUMxx = 0.0;
            double lslslope;
            double lslint;

            for (int i = 0; i < values.length; i++) {
                double value = values[i];

                if (!Double.isNaN(value)) {
                    cnt++;

                    SUMx += lslstep;
                    SUMxx += lslstep * lslstep;
                    SUMy  += value;
                    SUMxy += lslstep * value;
                }
                lslstep++;
            }
            double divisor = (SUMx * SUMx - cnt * SUMxx);
            if (cnt > 0 && divisor != 0) {
                /* Bestfit line by linear least squares method */
                lslslope = (SUMx * SUMy - cnt * SUMxy) / divisor;
                lslint = (SUMy - lslslope * SUMx) / cnt;
                return new Value(0, lslint);
            } else {
                return new Value(0, Double.NaN);
            }
        }

    }

    /**
     * Calculate the correlation coefficient of the least squares line.
     *
     */
    public static class LSLCORREL extends Variable {

        @Override
        protected Value fill(long[] timestamps, double[] values, long start, long end) {
            int cnt = 0;
            int lslstep = 0;
            double SUMx = 0.0;
            double SUMy = 0.0;
            double SUMxy = 0.0;
            double SUMxx = 0.0;
            double SUMyy = 0.0;
            double lslcorrel;

            for (int i = 0; i < values.length; i++) {
                double value = values[i];

                if (!Double.isNaN(value)) {
                    cnt++;

                    SUMx += lslstep;
                    SUMxx += lslstep * lslstep;
                    SUMy  += value;
                    SUMxy += lslstep * value;
                    SUMyy += value * value;
                }
                lslstep++;
            }
            if (cnt > 0) {
                /* Bestfit line by linear least squares method */
                lslcorrel =
                        (SUMxy - (SUMx * SUMy) / cnt) /
                        Math.sqrt((SUMxx - (SUMx * SUMx) / cnt) * (SUMyy - (SUMy * SUMy) / cnt));
                return new Value(0, lslcorrel);
            }
            return new Value(0, Double.NaN);
        }

    }
}
