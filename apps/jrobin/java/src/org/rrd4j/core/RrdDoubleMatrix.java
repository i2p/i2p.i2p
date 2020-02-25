package org.rrd4j.core;

import java.io.IOException;

class RrdDoubleMatrix<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
    private static final String LENGTH = ", length=";
    private final int rows;
    private final int columns;

    RrdDoubleMatrix(RrdUpdater<U> updater, int row, int column, boolean shouldInitialize) throws IOException {
        super(updater, RrdPrimitive.RRD_DOUBLE, row * column, false);
        this.rows = row;
        this.columns = column;
        if (shouldInitialize)
            writeDouble(0, Double.NaN, rows * columns);
    }

    void set(int column, int index, double value) throws IOException {
        writeDouble(columns * index + column, value);
    }

    void set(int column, int index, double value, int count) throws IOException {
        // rollovers not allowed!
        assert index + count <= rows : "Invalid robin index supplied: index=" + index +
                ", count=" + count + LENGTH + rows;
        for (int i = columns * index + column, c = 0; c < count; i += columns, c++)
            writeDouble(i, value);
    }

    /**
     * <p>set.</p>
     *
     * @param column a int.
     * @param index a int.
     * @param newValues an array of double.
     * @throws java.io.IOException if any.
     */
    public void set(int column, int index, double[] newValues) throws IOException {
        int count = newValues.length;
        // rollovers not allowed!
        assert index + count <= rows : "Invalid robin index supplied: index=" + index +
                ", count=" + count + LENGTH + rows;
        for (int i = columns * index + column, c = 0; c < count; i += columns, c++)
            writeDouble(i, newValues[c]);
    }

    double get(int column, int index) throws IOException {
        assert index < rows : "Invalid index supplied: " + index + LENGTH + rows;
        return readDouble(columns * index + column);
    }

    double[] get(int column, int index, int count) throws IOException {
        assert index + count <= rows : "Invalid index/count supplied: " + index +
                "/" + count + " (length=" + rows + ")";
        double[] values = new double[count];
        for (int i = columns * index + column, c = 0; c < count; i += columns, c++) {
            values[c] = readDouble(i);
        }
        return values;
    }

    /**
     * <p>Getter for the field <code>columns</code>.</p>
     *
     * @return a int.
     */
    public int getColumns() {
        return columns;
    }

    /**
     * <p>Getter for the field <code>rows</code>.</p>
     *
     * @return a int.
     */
    public int getRows() {
        return rows;
    }
}
