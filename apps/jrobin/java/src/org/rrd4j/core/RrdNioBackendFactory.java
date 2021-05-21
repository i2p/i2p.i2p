package org.rrd4j.core;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Factory class which creates actual {@link org.rrd4j.core.RrdNioBackend} objects. This is the default factory since
 * 1.4.0 version.
 * <h2>Managing the thread pool</h2>
 * <p>Each RrdNioBackendFactory is optionally backed by a {@link org.rrd4j.core.RrdSyncThreadPool}, which it uses to sync the memory-mapped files to
 * disk. In order to avoid having these threads live longer than they should, it is recommended that clients create and
 * destroy thread pools at the appropriate time in their application's life time. Failure to manage thread pools
 * appropriately may lead to the thread pool hanging around longer than necessary, which in turn may cause memory leaks.</p>
 * <p>if sync period is negative, no sync thread will be launched</p>
 *
 */
@RrdBackendAnnotation(name="NIO", shouldValidateHeader=true)
public class RrdNioBackendFactory extends RrdFileBackendFactory {
    /**
     * Period in seconds between consecutive synchronizations when
     * sync-mode is set to SYNC_BACKGROUND. By default in-memory cache will be
     * transferred to the disc every 300 seconds (5 minutes). Default value can be
     * changed via {@link #setSyncPeriod(int)} method.
     */
    public static final int DEFAULT_SYNC_PERIOD = 300; // seconds

    private static int defaultSyncPeriod = DEFAULT_SYNC_PERIOD;

    /**
     * The core pool size for the sync executor. Defaults to 6.
     */
    public static final int DEFAULT_SYNC_CORE_POOL_SIZE = 6;

    private static int defaultSyncPoolSize = DEFAULT_SYNC_CORE_POOL_SIZE;

    /**
     * Returns time between two consecutive background synchronizations. If not changed via
     * {@link #setSyncPeriod(int)} method call, defaults to {@link #DEFAULT_SYNC_PERIOD}.
     * See {@link #setSyncPeriod(int)} for more information.
     *
     * @return Time in seconds between consecutive background synchronizations.
     */
    public static int getSyncPeriod() {
        return defaultSyncPeriod;
    }

    /**
     * Sets time between consecutive background synchronizations. If negative, it will disabling syncing for
     * all NIO backend factory.
     *
     * @param syncPeriod Time in seconds between consecutive background synchronizations.
     */
    public static void setSyncPeriod(int syncPeriod) {
        RrdNioBackendFactory.defaultSyncPeriod = syncPeriod;
    }

    /**
     * Returns the number of synchronizing threads. If not changed via
     * {@link #setSyncPoolSize(int)} method call, defaults to {@link #DEFAULT_SYNC_CORE_POOL_SIZE}.
     * See {@link #setSyncPoolSize(int)} for more information.
     *
     * @return Number of synchronizing threads.
     */
    public static int getSyncPoolSize() {
        return defaultSyncPoolSize;
    }

    /**
     * Sets the number of synchronizing threads. It must be set before the first use of this factory.
     * It will not have any effect afterward.
     *
     * @param syncPoolSize Number of synchronizing threads.
     */
    public static void setSyncPoolSize(int syncPoolSize) {
        RrdNioBackendFactory.defaultSyncPoolSize = syncPoolSize;
    }

    private final int syncPeriod;

    /**
     * The thread pool to pass to newly-created RrdNioBackend instances.
     */
    private RrdSyncThreadPool syncThreadPool;

    /**
     * Creates a new RrdNioBackendFactory with default settings.
     */
    public RrdNioBackendFactory() {
        this(RrdNioBackendFactory.defaultSyncPeriod, RrdNioBackendFactory.defaultSyncPeriod > 0 ? DefaultSyncThreadPool.INSTANCE : null);
    }

    /**
     * Creates a new RrdNioBackendFactory, using a default {@link RrdSyncThreadPool}.
     *
     * @param syncPeriod the sync period, in seconds. If negative or 0, sync threads are disabled.
     */
    public RrdNioBackendFactory(int syncPeriod) {
        this(syncPeriod, syncPeriod > 0 ? DefaultSyncThreadPool.INSTANCE : null);
    }

    /**
     * Creates a new RrdNioBackendFactory.
     *
     * @param syncPeriod the sync period, in seconds.
     * @param syncPoolSize The number of threads to use to sync the mapped file to disk, if negative or 0, sync threads are disabled.
     */
    public RrdNioBackendFactory(int syncPeriod, int syncPoolSize) {
        this(syncPeriod, syncPoolSize > 0 ? new RrdSyncThreadPool(syncPoolSize) : null);
    }

    /**
     * Creates a new RrdNioBackendFactory.
     *
     * @param syncPeriod the sync period, in seconds.
     * @param syncThreadPool If null, disable background sync threads
     */
    public RrdNioBackendFactory(int syncPeriod, ScheduledExecutorService syncThreadPool) {
        this(syncPeriod, syncThreadPool != null ? new RrdSyncThreadPool(syncThreadPool) :null);
    }

    /**
     * Creates a new RrdNioBackendFactory.
     *
     * @param syncPeriod the sync period, in seconds.
     * @param syncThreadPool If null, disable background sync threads
     */
    public RrdNioBackendFactory(int syncPeriod, RrdSyncThreadPool syncThreadPool) {
        if (syncThreadPool != null && syncPeriod < 0) {
            throw new IllegalArgumentException("Both thread pool defined and negative sync period");
        }
        this.syncPeriod = syncPeriod;
        this.syncThreadPool = syncThreadPool;
    }

    /**
     * <p>Setter for the field <code>syncThreadPool</code>.</p>
     *
     * @param syncThreadPool the RrdSyncThreadPool to use to sync the memory-mapped files.
     * @deprecated Create a custom instance instead
     */
    @Deprecated
    public void setSyncThreadPool(RrdSyncThreadPool syncThreadPool) {
        this.syncThreadPool = syncThreadPool;
    }

    /**
     * <p>Setter for the field <code>syncThreadPool</code>.</p>
     *
     * @param syncThreadPool the ScheduledExecutorService that will back the RrdSyncThreadPool used to sync the memory-mapped files.
     * @deprecated Create a custom instance instead
     */
    @Deprecated
    public void setSyncThreadPool(ScheduledExecutorService syncThreadPool) {
        this.syncThreadPool = new RrdSyncThreadPool(syncThreadPool);
    }

    /**
     * {@inheritDoc}
     *
     * Creates RrdNioBackend object for the given file path.
     */
    protected RrdBackend open(String path, boolean readOnly) throws IOException {
        return new RrdNioBackend(path, readOnly, syncThreadPool, syncPeriod);
    }

    /**
     * @return The {@link RrdSyncThreadPool} or null if syncing is disabled
     */
    public RrdSyncThreadPool getSyncThreadPool() {
        return syncThreadPool;
    }

    @Override
    public void close() throws IOException {
        if (syncThreadPool != null) {
            syncThreadPool.shutdown();
        }
    }

    /**
     * This is a holder class as per the "initialisation on demand" Java idiom. The only purpose of this holder class is
     * to ensure that the thread pool is created lazily the first time that it is needed, and not before.
     * <p/>
     * In practice this thread pool will be used if clients rely on the factory returned by {@link
     * org.rrd4j.core.RrdBackendFactory#getDefaultFactory()}, but not if clients provide their own backend instance when
     * creating {@code RrdDb} instances or syncing was not disabled.
     */
    private static class DefaultSyncThreadPool
    {
        /**
         * The default thread pool used to periodically sync the mapped file to disk with.
         */
        static final RrdSyncThreadPool INSTANCE = new RrdSyncThreadPool(defaultSyncPoolSize);

        private DefaultSyncThreadPool() {}
    }

}
