package org.rrd4j.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class Aggregator {
    private final long timestamps[], step;
    private final double[] values;

    Aggregator(long[] timestamps, double[] values) {
        assert timestamps.length == values.length : "Incompatible timestamps/values arrays (unequal lengths)";
        assert timestamps.length >= 2 : "At least two timestamps must be supplied";
        this.timestamps = timestamps;
        this.values = values;
        this.step = timestamps[1] - timestamps[0];
    }

    @Deprecated
    Aggregates getAggregates(long tStart, long tEnd) {
        Aggregates agg = new Aggregates();
        long totalSeconds = 0;
        int cnt = 0;

        for (int i = 0; i < timestamps.length; i++) {
            long left = Math.max(timestamps[i] - step, tStart);
            long right = Math.min(timestamps[i], tEnd);
            long delta = right - left;

            // delta is only > 0 when the time stamp for a given buck is within the range of tStart and tEnd
            if (delta > 0) {
                double value = values[i];

                if (!Double.isNaN(value)) {
                    totalSeconds += delta;
                    cnt++;

                    if (cnt == 1) {
                        agg.last = agg.first = agg.total = agg.min = agg.max = value;
                    }
                    else {
                        if (delta >= step) {  // an entire bucket is included in this range
                            agg.last = value;
                        }

                        agg.min = Math.min(agg.min, value);
                        agg.max = Math.max(agg.max, value);
                        agg.total += value;

                    }
                }
            }
        }

        if(cnt > 0) {
            agg.average = agg.total / totalSeconds;
        }

        return agg;
    }

    double getPercentile(long tStart, long tEnd, double percentile) {
        List<Double> valueList = new ArrayList<Double>();
        // create a list of included datasource values (different from NaN)
        for (int i = 0; i < timestamps.length; i++) {
            long left = Math.max(timestamps[i] - step, tStart);
            long right = Math.min(timestamps[i], tEnd);
            if (right > left && !Double.isNaN(values[i])) {
                valueList.add(Double.valueOf(values[i]));
            }
        }
        // create an array to work with
        int count = valueList.size();
        if (count > 1) {
            double[] valuesCopy = new double[count];
            for (int i = 0; i < count; i++) {
                valuesCopy[i] = valueList.get(i).doubleValue();
            }
            // sort array
            Arrays.sort(valuesCopy);
            // skip top (100% - percentile) values
            double topPercentile = (100.0 - percentile) / 100.0;
            count -= (int) Math.ceil(count * topPercentile);
            // if we have anything left...
            if (count > 0) {
                return valuesCopy[count - 1];
            }
        }
        // not enough data available
        return Double.NaN;
    }
}

