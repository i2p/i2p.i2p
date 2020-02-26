package org.rrd4j.core;

import java.io.IOException;

interface RrdUpdater<T extends RrdUpdater<T>> {
    /**
     * <p>getRrdBackend.</p>
     *
     * @return a {@link org.rrd4j.core.RrdBackend} object.
     */
    RrdBackend getRrdBackend();

    /**
     * <p>copyStateTo.</p>
     *
     * @param updater a {@link org.rrd4j.core.RrdUpdater} object.
     * @throws java.io.IOException if any.
     */
    void copyStateTo(T updater) throws IOException;

    /**
     * <p>getRrdAllocator.</p>
     *
     * @return a {@link org.rrd4j.core.RrdAllocator} object.
     */
    RrdAllocator getRrdAllocator();
}
