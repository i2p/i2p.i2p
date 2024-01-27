package org.rrd4j.data;

abstract class Source {
    private final String name;

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

    /**
     * @param tStart
     * @param tEnd
     * @return
     * @deprecated This method is deprecated. Uses instance of {@link org.rrd4j.data.Variable}, used with {@link org.rrd4j.data.DataProcessor#addDatasource(String, String, Variable)}
     */
    @Deprecated
    Aggregates getAggregates(long tStart, long tEnd) {
        Aggregator agg = new Aggregator(timestamps, values);
        return agg.getAggregates(tStart, tEnd);
    }

    /**
     * @param tStart
     * @param tEnd
     * @param percentile
     * @return
     * @deprecated This method is deprecated. Uses instance of {@link org.rrd4j.data.Variable.PERCENTILE}, used with {@link org.rrd4j.data.DataProcessor#addDatasource(String, String, Variable)}
     */
    @Deprecated
    double getPercentile(long tStart, long tEnd, double percentile) {
        Variable vpercent = new Variable.PERCENTILE((float) percentile);
        vpercent.calculate(this, tStart, tEnd);
        return vpercent.getValue().value;
    }

}
