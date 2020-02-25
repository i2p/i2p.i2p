package org.rrd4j.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread pool used by {@link org.rrd4j.core.RrdNioBackend} instances to periodically sync the mapped file to disk. Note that instances
 * of RrdSyncThreadPool must be disposed of by calling {@link #shutdown()}.
 * <p>
 * For ease of use in standalone applications, clients may choose to register a shutdown hook by calling
 * {@link #registerShutdownHook()}. However, in web applications it is best to explicitly {@code shutdown()} the pool
 * when the application is un-deployed, usually within a {@code javax.servlet.ServletContextListener}.
 *
 * @since 2.2
 */
public class RrdSyncThreadPool
{
    /**
     * The reference to the shutdown hook, or null.
     */
    private final AtomicReference<Thread> shutdownHook = new AtomicReference<>();

    /**
     * The {@link java.util.concurrent.ScheduledExecutorService} used to periodically sync the mapped file to disk with.
     * Defaults to {@value org.rrd4j.core.RrdNioBackendFactory#DEFAULT_SYNC_CORE_POOL_SIZE} threads.
     */
    private final ScheduledExecutorService syncExecutor;

    /**
     * Creates a new RrdSyncThreadPool with a default pool size of {@value org.rrd4j.core.RrdNioBackendFactory#DEFAULT_SYNC_CORE_POOL_SIZE}.
     */
    public RrdSyncThreadPool() {
        this(RrdNioBackendFactory.DEFAULT_SYNC_CORE_POOL_SIZE);
    }

    /**
     * Creates a new RrdSyncThreadPool with a user-provided ScheduledExecutorService.
     *
     * @param syncExecutor the ScheduledExecutorService to use for sync'ing mapped files to disk
     */
    public RrdSyncThreadPool(ScheduledExecutorService syncExecutor)
    {
        if (syncExecutor == null) {
            throw new NullPointerException("syncExecutor");
        }

        this.syncExecutor = syncExecutor;
    }

    /**
     * Creates a new RrdSyncThreadPool with the given pool size.
     *
     * @param syncPoolSize the number of threads to use to sync the mapped file to disk
     */
    public RrdSyncThreadPool(int syncPoolSize) {
        this(syncPoolSize, null);
    }

    /**
     * Creates a new RrdSyncThreadPool with the given pool size. Threads will be created by {@code threadFactory}.
     *
     * @param syncPoolSize the number of threads to use to sync the mapped file to disk
     * @param threadFactory the ThreadFactory to use for creating threads
     */
    public RrdSyncThreadPool(int syncPoolSize, ThreadFactory threadFactory) {
        ThreadFactory poolThreadFactory = threadFactory;
        if (poolThreadFactory == null) {
            poolThreadFactory = new DaemonThreadFactory("RRD4J Sync-ThreadPool for " + this);
        }

        this.syncExecutor = Executors.newScheduledThreadPool(syncPoolSize, poolThreadFactory);
    }

    /**
     * Registers a shutdown hook that destroys the underlying thread pool when when the JVM is about to quit.
     *
     * @return this
     * @see #unregisterShutdownHook()
     */
    public RrdSyncThreadPool registerShutdownHook() {
        Thread shutdownThread = new ShutdownThread();

        // if this returns null, then this pool has not registered a hook yet
        boolean wasNull = shutdownHook.compareAndSet(null, shutdownThread);
        if (wasNull) {
            // Add a shutdown hook to stop the thread pool gracefully when the application exits
            Runtime.getRuntime().addShutdownHook(shutdownThread);
        }

        return this;
    }

    /**
     * Unregisters the shutdown hook installed by {@link #registerShutdownHook()}. Has no effect if the hook is not
     * currently installed.
     *
     * @see #unregisterShutdownHook()
     */
    public void unregisterShutdownHook() {
        // if this returns a non-null value, then the hook needs to be uninstalled
        Thread shutdownThread = shutdownHook.getAndSet(null);
        if (shutdownThread != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownThread);
        }
    }

    /**
     * Shuts down this thread pool in an orderly manner. Has no effect if it has already been called previously.
     */
    public void shutdown() {
        unregisterShutdownHook();
        syncExecutor.shutdown();
    }

    ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return syncExecutor.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    /**
     * Daemon thread factory used by the monitor executors.
     * <p>
     * This factory creates all new threads used by an Executor in the same ThreadGroup.
     * If there is a SecurityManager, it uses the group of System.getSecurityManager(), else the group
     * of the thread instantiating this DaemonThreadFactory. Each new thread is created as a daemon thread
     * with priority Thread.NORM_PRIORITY. New threads have names accessible via Thread.getName()
     * of "<pool-name> Pool [Thread-M]", where M is the sequence number of the thread created by this factory.
     */
    static class DaemonThreadFactory implements ThreadFactory
    {
        final ThreadGroup group;
        final AtomicInteger threadNumber = new AtomicInteger(1);
        final String poolName;

        DaemonThreadFactory(String poolName) {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            this.poolName = poolName;
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, poolName + " [Thread-" + threadNumber.getAndIncrement() + "]");
            t.setDaemon(true);
            t.setContextClassLoader(null);
            if (t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    private class ShutdownThread extends Thread
    {
        public ShutdownThread() {
            // include the RrdSyncThreadPool's toString in the thread name
            super("RRD4J Sync-ThreadPool-Shutdown for " + RrdSyncThreadPool.this);
        }

        @Override
        public void run()
        {
            // Progress and failure logging arising from the following code cannot be logged, since the
            // behavior of logging is undefined in shutdown hooks.
            syncExecutor.shutdown();
        }
    }
}
