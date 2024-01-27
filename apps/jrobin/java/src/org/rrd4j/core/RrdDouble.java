package org.rrd4j.core;

import java.io.IOException;

class RrdDouble<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
    private double cache;
    private boolean cached = false;

    RrdDouble(RrdUpdater<U> updater, boolean isConstant) {
        super(updater, RrdDouble.RRD_DOUBLE, isConstant);
    }

    RrdDouble(RrdUpdater<U> updater) {
        super(updater, RrdDouble.RRD_DOUBLE, false);
    }

    void set(double value) throws IOException {
        if (!isCachingAllowed()) {
            writeDouble(value);
        }
        // caching allowed
        else if (!cached || !Util.equal(cache, value)) {
            // update cache
            writeDouble(cache = value);
            cached = true;
        }
    }

    double get() throws IOException {
        if (!isCachingAllowed()) {
            return readDouble();
        }
        else {
            if (!cached) {
                cache = readDouble();
                cached = true;
            }
            return cache;
        }
    }
}
