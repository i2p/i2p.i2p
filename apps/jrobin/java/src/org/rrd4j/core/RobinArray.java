package org.rrd4j.core;

import java.io.IOException;

/**
 * Class to represent archive values for a single datasource. Robin class is the heart of
 * the so-called "round robin database" concept. Basically, each Robin object is a
 * fixed length array of double values. Each double value represents consolidated, archived
 * value for the specific timestamp. When the underlying array of double values gets completely
 * filled, new values will replace the oldest ones.<p>
 * <p/>
 * Robin object does not hold values in memory - such object could be quite large.
 * Instead of it, Robin reads them from the backend I/O only when necessary.
 *
 * @author Sasa Markovic
 */
class RobinArray implements Robin {
    private final Archive parentArc;
    private final RrdInt<Robin> pointer;
    private final RrdDoubleArray<Robin> values;
    private int rows;

    RobinArray(Archive parentArc, int rows, boolean shouldInitialize) throws IOException {
        this.parentArc = parentArc;
        this.pointer = new RrdInt<>(this);
        this.values = new RrdDoubleArray<>(this, rows);
        this.rows = rows;
        if (shouldInitialize) {
            pointer.set(0);
            values.set(0, Double.NaN, rows);
        }
    }

    /* (non-Javadoc)
     * @see org.rrd4j.core.Robin#getValues()
     */
    /**
     * <p>Getter for the field <code>values</code>.</p>
     *
     * @return an array of double.
     * @throws java.io.IOException if any.
     */
    public double[] getValues() throws IOException {
        return getValues(0, rows);
    }

    // stores single value
    /** {@inheritDoc} */
    public void store(double newValue) throws IOException {
        int position = pointer.get();
        values.set(position, newValue);
        pointer.set((position + 1) % rows);
    }

    // stores the same value several times
    /** {@inheritDoc} */
    public void bulkStore(double newValue, int bulkCount) throws IOException {
        assert bulkCount <= rows: "Invalid number of bulk updates: " + bulkCount + " rows=" + rows;

        int position = pointer.get();

        // update tail
        int tailUpdateCount = Math.min(rows - position, bulkCount);

        values.set(position, newValue, tailUpdateCount);
        pointer.set((position + tailUpdateCount) % rows);

        // do we need to update from the start?
        int headUpdateCount = bulkCount - tailUpdateCount;
        if (headUpdateCount > 0) {
            values.set(0, newValue, headUpdateCount);
            pointer.set(headUpdateCount);
        }
    }

    /**
     * <p>update.</p>
     *
     * @param newValues an array of double.
     * @throws java.io.IOException if any.
     */
    public void update(double[] newValues) throws IOException {
        assert rows == newValues.length: "Invalid number of robin values supplied (" + newValues.length +
        "), exactly " + rows + " needed";
        pointer.set(0);
        values.writeDouble(0, newValues);
    }

    /* (non-Javadoc)
     * @see org.rrd4j.core.Robin#setValues(double)
     */
    /** {@inheritDoc} */
    public void setValues(double... newValues) throws IOException {
        if (rows != newValues.length) {
            throw new IllegalArgumentException("Invalid number of robin values supplied (" + newValues.length +
                    "), exactly " + rows + " needed");
        }
        update(newValues);
    }

    /* (non-Javadoc)
     * @see org.rrd4j.core.Robin#setValues(double)
     */
    /** {@inheritDoc} */
    public void setValues(double newValue) throws IOException {
        double[] values = new double[rows];
        for (int i = 0; i < values.length; i++) {
            values[i] = newValue;
        }
        update(values);
    }

    /**
     * <p>dump.</p>
     *
     * @return a {@link java.lang.String} object.
     * @throws java.io.IOException if any.
     */
    public String dump() throws IOException {
        StringBuilder buffer = new StringBuilder("Robin " + pointer.get() + "/" + rows + ": ");
        double[] values = getValues();
        for (double value : values) {
            buffer.append(Util.formatDouble(value, true)).append(" ");
        }
        buffer.append("\n");
        return buffer.toString();
    }

    /* (non-Javadoc)
     * @see org.rrd4j.core.Robin#getValue(int)
     */
    /** {@inheritDoc} */
    public double getValue(int index) throws IOException {
        int arrayIndex = (pointer.get() + index) % rows;
        return values.get(arrayIndex);
    }

    /* (non-Javadoc)
     * @see org.rrd4j.core.Robin#setValue(int, double)
     */
    /** {@inheritDoc} */
    public void setValue(int index, double value) throws IOException {
        int arrayIndex = (pointer.get() + index) % rows;
        values.set(arrayIndex, value);
    }

    /** {@inheritDoc} */
    public double[] getValues(int index, int count) throws IOException {
        assert count <= rows: "Too many values requested: " + count + " rows=" + rows;

        int startIndex = (pointer.get() + index) % rows;
        int tailReadCount = Math.min(rows - startIndex, count);
        double[] tailValues = values.get(startIndex, tailReadCount);
        if (tailReadCount < count) {
            int headReadCount = count - tailReadCount;
            double[] headValues = values.get(0, headReadCount);
            double[] values = new double[count];
            int k = 0;
            for (double tailValue : tailValues) {
                values[k++] = tailValue;
            }
            for (double headValue : headValues) {
                values[k++] = headValue;
            }
            return values;
        }
        else {
            return tailValues;
        }
    }

    /* (non-Javadoc)
     * @see org.rrd4j.core.Robin#getParent()
     */
    /**
     * <p>getParent.</p>
     *
     * @return a {@link org.rrd4j.core.Archive} object.
     */
    public Archive getParent() {
        return parentArc;
    }

    /* (non-Javadoc)
     * @see org.rrd4j.core.Robin#getSize()
     */
    /**
     * <p>getSize.</p>
     *
     * @return a int.
     */
    public int getSize() {
        return rows;
    }

    /* (non-Javadoc)
     * @see org.rrd4j.core.Robin#copyStateTo(org.rrd4j.core.RrdUpdater)
     */
    /** {@inheritDoc} */
    public void copyStateTo(Robin robin) throws IOException {
        int rowsDiff = rows - robin.getSize();
        for (int i = 0; i < robin.getSize(); i++) {
            int j = i + rowsDiff;
            robin.store(j >= 0 ? getValue(j) : Double.NaN);
        }
    }

    /* (non-Javadoc)
     * @see org.rrd4j.core.Robin#filterValues(double, double)
     */
    /** {@inheritDoc} */
    public void filterValues(double minValue, double maxValue) throws IOException {
        for (int i = 0; i < rows; i++) {
            double value = values.get(i);
            if (!Double.isNaN(minValue) && !Double.isNaN(value) && minValue > value) {
                values.set(i, Double.NaN);
            }
            if (!Double.isNaN(maxValue) && !Double.isNaN(value) && maxValue < value) {
                values.set(i, Double.NaN);
            }
        }
    }

    /* (non-Javadoc)
     * @see org.rrd4j.core.Robin#getRrdBackend()
     */
    /**
     * <p>getRrdBackend.</p>
     *
     * @return a {@link org.rrd4j.core.RrdBackend} object.
     */
    public RrdBackend getRrdBackend() {
        return parentArc.getRrdBackend();
    }

    /* (non-Javadoc)
     * @see org.rrd4j.core.Robin#getRrdAllocator()
     */
    /**
     * <p>getRrdAllocator.</p>
     *
     * @return a {@link org.rrd4j.core.RrdAllocator} object.
     */
    public RrdAllocator getRrdAllocator() {
        return parentArc.getRrdAllocator();
    }
}
