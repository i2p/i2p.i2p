package org.rrd4j.data;

interface NonRrdSource {
    /**
     * <p>calculate.</p>
     *
     * @param tStart a long.
     * @param tEnd a long.
     * @param dataProcessor a {@link org.rrd4j.data.DataProcessor} object.
     */
    void calculate(long tStart, long tEnd, DataProcessor dataProcessor);
}
