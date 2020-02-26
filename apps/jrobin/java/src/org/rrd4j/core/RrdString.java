package org.rrd4j.core;

import java.io.IOException;

class RrdString<U extends RrdUpdater<U>> extends RrdPrimitive<U> {
    private String cache;

    RrdString(RrdUpdater<U> updater, boolean isConstant) throws IOException {
        super(updater, RrdPrimitive.RRD_STRING, isConstant);
    }

    RrdString(RrdUpdater<U> updater) throws IOException {
        this(updater, false);
    }

    void set(String value) throws IOException {
        if (!isCachingAllowed()) {
            writeString(value);
        }
        // caching allowed
        else if (cache == null || !cache.equals(value)) {
            // update cache
            writeString(cache = value);
        }
    }

    String get() throws IOException {
        if (!isCachingAllowed()) {
            return readString();
        }
        else {
            if (cache == null) {
                cache = readString();
            }
            return cache;
        }
    }
}
