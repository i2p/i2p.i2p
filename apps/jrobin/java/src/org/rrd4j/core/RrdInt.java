package org.rrd4j.core;

import java.io.IOException;

class RrdInt<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
    private int cache;
    private boolean cached = false;

    RrdInt(RrdUpdater<U> updater, boolean isConstant) {
        super(updater, RrdPrimitive.RRD_INT, isConstant);
    }

    RrdInt(RrdUpdater<U> updater) {
        this(updater, false);
    }

    void set(int value) throws IOException {
        if (!isCachingAllowed()) {
            writeInt(value);
        }
        // caching allowed
        else if (!cached || cache != value) {
            // update cache
            writeInt(cache = value);
            cached = true;
        }
    }

    int get() throws IOException {
        if (!isCachingAllowed()) {
            return readInt();
        }
        else {
            if (!cached) {
                cache = readInt();
                cached = true;
            }
            return cache;
        }
    }
}
