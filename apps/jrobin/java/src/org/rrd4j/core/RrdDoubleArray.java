package org.rrd4j.core;

import java.io.IOException;

class RrdDoubleArray<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
    private final int length;

    RrdDoubleArray(RrdUpdater<U> updater, int length) {
        super(updater, RrdPrimitive.RRD_DOUBLE, length, false);
        this.length = length;
    }

    void set(int index, double value) throws IOException {
        set(index, value, 1);
    }

    void set(int index, double value, int count) throws IOException {
        // rollovers not allowed!
        assert index + count <= length : "Invalid robin index supplied: index=" + index +
                ", count=" + count + ", length=" + length;
        writeDouble(index, value, count);
    }

    double get(int index) throws IOException {
        assert index < length : "Invalid index supplied: " + index + ", length=" + length;
        return readDouble(index);
    }

    double[] get(int index, int count) throws IOException {
        assert index + count <= length : "Invalid index/count supplied: " + index +
                "/" + count + " (length=" + length + ")";
        return readDouble(index, count);
    }

}
