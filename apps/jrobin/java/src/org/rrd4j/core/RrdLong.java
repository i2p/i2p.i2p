package org.rrd4j.core;

import java.io.IOException;

class RrdLong<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
    private long cache;
    private boolean cached = false;

    RrdLong(RrdUpdater<U> updater, boolean isConstant) {
        super(updater, RrdPrimitive.RRD_LONG, isConstant);
    }

    RrdLong(RrdUpdater<U> updater) {
        this(updater, false);
    }

    void set(long value) throws IOException {
        if (!isCachingAllowed()) {
            writeLong(value);
        }
        // caching allowed
        else if (!cached || cache != value) {
            // update cache
            writeLong(cache = value);
            cached = true;
        }
    }

    long get() throws IOException {
        if (!isCachingAllowed()) {
            return readLong();
        }
        else {
            if (!cached) {
                cache = readLong();
                cached = true;
            }
            return cache;
        }
    }
}
