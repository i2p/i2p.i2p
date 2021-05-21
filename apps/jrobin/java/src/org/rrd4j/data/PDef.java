package org.rrd4j.data;

class PDef extends Source implements NonRrdSource  {
    private final IPlottable plottable;

    PDef(String name, IPlottable plottable2) {
        super(name);
        this.plottable = plottable2;
    }

    /** {@inheritDoc} */
    public void calculate(long tStart, long tEnd, DataProcessor dataProcessor) {
        long[] times = getTimestamps();
        double[] vals = new double[times.length];
        for (int i = 0; i < times.length; i++) {
            vals[i] = plottable.getValue(times[i]);
        }
        setValues(vals);
    }
}
