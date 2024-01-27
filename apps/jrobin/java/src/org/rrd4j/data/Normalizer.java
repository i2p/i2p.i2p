package org.rrd4j.data;

import org.rrd4j.core.Util;

import java.util.Arrays;

class Normalizer {
    private final long[] timestamps;
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
        double[] weights = new double[count];
        Arrays.fill(weights, Double.NaN);
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
                        weights[fillSeg] = Util.sum(weights[fillSeg], (t2 - t1));
                    }
                    else {
                        overlap = false;
                    }
                }
            }
        }
        for (int seg = 0; seg < count; seg++) {
            if (!Double.isNaN(weights[seg])) {
                values[seg] /= weights[seg];
            }
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
