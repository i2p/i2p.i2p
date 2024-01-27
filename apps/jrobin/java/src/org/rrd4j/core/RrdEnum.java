package org.rrd4j.core;

import java.io.IOException;

class RrdEnum<U extends RrdUpdater<U>, E extends Enum<E>> extends RrdPrimitive<U> {

    private E cache;
    private final Class<E> clazz;

    RrdEnum(RrdUpdater<U> updater, boolean isConstant, Class<E> clazz) {
        super(updater, RrdPrimitive.RRD_STRING, isConstant);
        this.clazz = clazz;
    }

    RrdEnum(RrdUpdater<U> updater, Class<E> clazz) {
        this(updater, false, clazz);
    }

    void set(E value) throws IOException {
        if (!isCachingAllowed()) {
            writeEnum(value);
        }
        // caching allowed
        else if (cache == null || cache != value) {
            // update cache
            writeEnum((cache = value));
        }
    }

    E get() throws IOException {
        if (!isCachingAllowed()) {
            return readEnum(clazz);
        }
        else {
            if (cache == null) {
                cache = readEnum(clazz);
            }
            return cache;
        }
    }

    String name() throws IOException {
        return get().name();
    }

}
