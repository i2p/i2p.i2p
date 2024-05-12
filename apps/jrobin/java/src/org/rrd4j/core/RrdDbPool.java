package org.rrd4j.core;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * <p>This class should be used to synchronize access to RRD files
 * in a multithreaded environment. This class should be also used to prevent opening of
 * too many RRD files at the same time (thus avoiding operating system limits).
 * </p>
 * <p>It can also be used a factory for RrdDb, using a default backend factory.</p>
 * <p>In case of interruptions, it throws IllegalStateException.
 */
public class RrdDbPool {
    private static class RrdDbPoolSingletonHolder {
        static final RrdDbPool instance = new RrdDbPool();

        private RrdDbPoolSingletonHolder() {}
    }

    private static class PoolFullException extends RuntimeException {
        PoolFullException() {
            super("", null, false, false);
        }
    }

    /**
     * Initial capacity of the pool i.e. maximum number of simultaneously open RRD. The pool will
     * never open too many RRD at the same time.
     */
    public static final int INITIAL_CAPACITY = 200;

    /*
     * The RrdEntry stored in the pool can be of tree kind:
     * - null, the URI is available, just take it and play
     * - placeholder is true, it's not the real RrdDb entry, just a place holder
     *   meaning that some other thread is using it. Wait until the real entry is put back.
     * - placeholder is false, this is the active entry pointing to a RrdDb. It's
     *   only used by the current thread.
     *
     */
    private static class RrdEntry {
        RrdDb rrdDb = null;
        int count = 0;
        final CountDownLatch waitempty;
        final ReentrantReadWriteLock inuse;
        final Lock lock;
        final boolean placeholder;
        final URI uri;
        RrdEntry(URI canonicalPath) {
            placeholder = false;
            uri = canonicalPath;
            inuse = new ReentrantReadWriteLock();
            lock = inuse.writeLock();
            waitempty = new CountDownLatch(1);
        }
        RrdEntry(RrdEntry parent) {
            assert ! parent.placeholder;
            placeholder = true;
            uri = parent.uri;
            inuse = null;
            lock = parent.inuse.readLock();
            waitempty = null;
        }
        @Override
        public String toString() {
            if (placeholder) {
                return String.format("RrdEntry [placeholder, uri=%s]", uri);
            } else {
                return String.format("RrdEntry [count=%d, rrdDb=%s, uri %s]", count, rrdDb, uri);
            }
        }
    }

    /**
     * Creates a single instance of the class on the first call,
     * or returns already existing one. Uses Initialization On Demand Holder idiom.
     *
     * @return Single instance of this class
     */
    public static RrdDbPool getInstance() {
        return RrdDbPoolSingletonHolder.instance;
    }

    private int maxCapacity = INITIAL_CAPACITY;
    private Semaphore usage = new Semaphore(maxCapacity);
    private final ReentrantReadWriteLock.WriteLock usageWLock;
    private final ReentrantReadWriteLock.ReadLock usageRLock;
    private final Condition fullCondition;
    // Needed because external threads can detect waiting condition
    private final AtomicBoolean waitFull = new AtomicBoolean(false);

    private final ConcurrentMap<URI, RrdEntry> pool = new ConcurrentHashMap<>(INITIAL_CAPACITY);

    private RrdBackendFactory defaultFactory;

    /**
     * Constructor for RrdDbPool. It will use the default backend factory.
     * @since 3.5
     */
    public RrdDbPool() {
        this(RrdBackendFactory.getDefaultFactory());
    }

    /**
     * Constructor for RrdDbPool.
     * @param defaultFactory the default factory used when given a simple path of a RRD.
     * @since 3.6
     */
    public RrdDbPool(RrdBackendFactory defaultFactory) {
        this.defaultFactory = defaultFactory;
        ReentrantReadWriteLock usageLock = new ReentrantReadWriteLock(true);
        usageWLock = usageLock.writeLock();
        usageRLock = usageLock.readLock();
        fullCondition = usageWLock.newCondition();
    }

    /**
     * Returns the number of open RRD.
     *
     * @return Number of currently open RRD held in the pool.
     */
    public int getOpenFileCount() {
        return pool.size();
    }

    /**
     * Returns an array of open RRD URI.
     *
     * @return Array with {@link URI} to open RRD held in the pool.
     */
    public URI[] getOpenUri() {
        return pool.keySet().stream().toArray(URI[]::new);
    }

    /**
     * Returns an stream open RRD.
     *
     * @return Stream with canonical URI to open RRD path held in the pool.
     * @since 3.7
     */
    public Stream<URI> getOpenUriStream() {
        return pool.keySet().stream();
    }

    /**
     * Returns an array of open RRD.
     *
     * @return Array with canonical path to open RRD path held in the pool.
     */
    public String[] getOpenFiles() {
        return pool.keySet().stream().map(URI::getPath).toArray(String[]::new);
    }

    private RrdEntry getEntry(URI uri, boolean cancreate) throws InterruptedException {
        RrdEntry ref = null;
        try {
            CompletableFuture<RrdEntry> holder = new CompletableFuture<>();
            do {
                try {
                    ref = pool.compute(uri, (u, e) -> {
                        try {
                            if (e == null) {
                                if (cancreate) {
                                    usageRLock.lockInterruptibly();
                                    try {
                                        if (! usage.tryAcquire()) {
                                            throw new PoolFullException();
                                        } else {
                                            RrdEntry r = new RrdEntry(u);
                                            holder.complete(r);
                                            r.lock.lock();
                                            return new RrdEntry(r);
                                        }
                                    } finally {
                                        usageRLock.unlock();
                                    }
                                } else {
                                    throw new IllegalStateException("Unknown URI in pool: " + u);
                                }
                            } else {
                                if (e.placeholder) {
                                    return e;
                                } else {
                                    e.lock.lock();
                                    holder.complete(e);
                                    return new RrdEntry(e);
                                }
                            }
                        } catch (InterruptedException ex) {
                            holder.completeExceptionally(ex);
                            return null;
                        }
                    });
                } catch (PoolFullException e) {
                    ref = null;
                    try {
                        usageWLock.lockInterruptibly();
                        waitFull.set(true);
                        fullCondition.await();
                    } catch (InterruptedException ex) {
                        holder.completeExceptionally(ex);
                        Thread.currentThread().interrupt();
                    } finally {
                        if (usageWLock.isHeldByCurrentThread()) {
                            waitFull.set(false);
                            usageWLock.unlock();
                        }
                    }
                }
                if (ref != null && !holder.isDone()) {
                    // Wait for a signal from the active entry, it's available
                    ref.lock.lockInterruptibly();
                    ref.lock.unlock();
                }
            } while (! holder.isDone());
            return holder.get();
        } catch (ExecutionException e) {
            InterruptedException ex = (InterruptedException) e.getCause();
            Thread.currentThread().interrupt();
            throw ex;
        } catch (InterruptedException | RuntimeException e) {
            // Oups we were interrupted, put everything back and go away
            passNext(ACTION.SWAP, ref);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }
    }

    private enum ACTION {
        SWAP, DROP
    }

    private void passNext(ACTION a, RrdEntry e) {
        if (e == null) {
            return;
        }
        RrdEntry o = null;
        switch (a) {
        case SWAP:
            o = pool.put(e.uri, e);
            break;
        case DROP:
            o = pool.remove(e.uri);
            usage.release();
            assert o == null || o.placeholder;
            if (waitFull.get()) {
                try {
                    usageWLock.lockInterruptibly();
                    fullCondition.signalAll();
                } catch (InterruptedException e1) {
                    // Lost slot available notification
                    Thread.currentThread().interrupt();
                } finally {
                    if (usageWLock.isHeldByCurrentThread()) {
                        usageWLock.unlock();
                    }
                }
            }
            break;
        }
        assert o != e : String.format("Same entry, action=%s, entry=%s\n", a, e);
        assert o == null || ((e.placeholder && ! o.placeholder) || (o.placeholder && ! e.placeholder)) : String.format("Inconsistent entry, action=%s, in=%s out=%s\n", a, e, o);
        //task finished, waiting on a place holder can go on
        e.lock.unlock();
    }

    /**
     * Releases RrdDb reference previously obtained from the pool. When a reference is released, its usage
     * count is decremented by one. If usage count drops to zero, the underlying RRD will be closed.
     *
     * @param rrdDb RrdDb reference to be returned to the pool
     * @throws java.io.IOException Thrown in case of I/O error
     * @throws java.lang.IllegalStateException if the thread was interrupted
     * @deprecated A RrdDb remember if it was open directly or from a pool, no need to manage it manually any more
     */
    @Deprecated
    public void release(RrdDb rrdDb) throws IOException {
        // null pointer should not kill the thread, just ignore it
        // They can happens in case of failures or interruptions at wrong place
        if (rrdDb == null) {
            return;
        }
        URI dburi = rrdDb.getCanonicalUri();
        RrdEntry ref;
        try {
            ref = getEntry(dburi, false);
        } catch (IllegalStateException e) {
            // This means that corresponding database has been already closed before, so returning immediately.
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Release interrupted for " + rrdDb.getPath(), e);
        }
        if (ref == null) {
            throw new IllegalStateException("Could not release [" + rrdDb.getPath() + "], not using pool for it");
        }
        if (ref.rrdDb == null) {
            passNext(ACTION.DROP, ref);
            throw new IllegalStateException("Could not release [" + rrdDb.getPath() + "], pool corruption");
        }
        if (ref.count <= 0) {
            passNext(ACTION.DROP, ref);
            throw new IllegalStateException("Could not release [" + rrdDb.getPath() + "], the file was never requested");
        }
        if (--ref.count == 0) {
            try {
                ref.rrdDb.internalClose();
                ref.rrdDb = null;
            } finally {
                passNext(ACTION.DROP, ref);
                //If someone is waiting for an empty entry, signal it
                ref.waitempty.countDown();
            }
        } else {
            passNext(ACTION.SWAP, ref);
        }
    }

    /**
     * <p>Requests a RrdDb reference for the given RRD path.</p>
     * <ul>
     * <li>If the RRD is already open, previously returned RrdDb reference will be returned. Its usage count
     * will be incremented by one.
     * <li>If the RRD is not already open and the number of already open RRD is less than
     * {@link #getCapacity()}, it will be opened and a new RrdDb reference will be returned.
     * If the RRD is not already open and the number of already open RRD is equal to
     * {@link #getCapacity()}, the method blocks until some RRD are closed.
     * </ul>
     * <p>The path is transformed to an URI using the default factory defined at the creation of the pool.</p>
     *
     * @param path Path to existing RRD.
     * @return reference for the given RRD.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public RrdDb requestRrdDb(String path) throws IOException {
        return requestRrdDb(defaultFactory.getUri(path), defaultFactory);
    }

    /**
     * <p>Requests a RrdDb reference for the given RRD URI.</p>
     * <ul>
     * <li>If the RRD is already open, previously returned RrdDb reference will be returned. Its usage count
     * will be incremented by one.
     * <li>If the RRD is not already open and the number of already open RRD is less than
     * {@link #getCapacity()}, it will be opened and a new RrdDb reference will be returned.
     * If the RRD is not already open and the number of already open RRD is equal to
     * {@link #getCapacity()}, the method blocks until some RRD are closed.
     * </ul>
     * <p>
     * If the default backend factory for the pool can handle this URI, it will be used, 
     * or else {@link RrdBackendFactory#findFactory(URI)} will be used to find the backend factory used.
     *
     * @param uri {@link URI} to existing RRD file
     * @return reference for the give RRD file
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public RrdDb requestRrdDb(URI uri) throws IOException {
        return requestRrdDb(uri, checkFactory(uri));
    }

    /**
     * Wait for a empty reference with no usage
     * @param uri
     * @return an reference with no usage 
     * @throws InterruptedException
     */
    private RrdEntry waitEmpty(URI uri) throws InterruptedException {
        RrdEntry ref = getEntry(uri, true);
        try {
            while (ref.count != 0) {
                //Not empty, give it back, but wait for signal
                passNext(ACTION.SWAP, ref);
                ref.waitempty.await();
                ref = getEntry(uri, true);
            }
            return ref;
        } catch (InterruptedException e) {
            passNext(ACTION.SWAP, ref);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Got an empty reference, use it only if slots are available
     * But don't hold any lock waiting for it
     * @param uri
     * @return an reference with no usage 
     * @throws InterruptedException
     */
    private RrdEntry requestEmpty(URI uri) throws InterruptedException {
        return waitEmpty(uri);
    }

    RrdDb requestRrdDb(URI uri, RrdBackendFactory factory) throws IOException {
        uri = factory.getCanonicalUri(uri);
        RrdEntry ref;
        try {
            ref = getEntry(uri, true);

            // Someone might have already open it, rechecks
            if (ref.count == 0) {
                try {
                    ref.rrdDb = RrdDb.getBuilder().setPath(factory.getPath(uri)).setBackendFactory(factory).setPoolInternal(this).build();
                } catch (IOException | RuntimeException e) {
                    passNext(ACTION.DROP, ref);
                    throw e;
                }
            }
            ref.count++;
            passNext(ACTION.SWAP, ref);
            return ref.rrdDb;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("request interrupted for " + uri, e);
        }
    }

    RrdDb requestRrdDb(RrdDef rrdDef, RrdBackendFactory factory) throws IOException {
        RrdEntry ref = null;
        try {
            URI uri = factory.getCanonicalUri(rrdDef.getUri());
            ref = requestEmpty(uri);
            ref.rrdDb = RrdDb.getBuilder().setRrdDef(rrdDef).setBackendFactory(factory).setPoolInternal(this).build();
            ref.count = 1;
            return ref.rrdDb;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("request interrupted for new rrdDef " + rrdDef.getPath(), e);
        } catch (RuntimeException e) {
            passNext(ACTION.DROP, ref);
            ref = null;
            throw e;
        } finally {
            passNext(ACTION.SWAP, ref);
        }
    }

    private RrdDb requestRrdDb(RrdDb.Builder builder, URI uri, RrdBackendFactory factory)
            throws IOException {
        RrdEntry ref = null;
        uri = factory.getCanonicalUri(uri);
        try {
            ref = requestEmpty(uri);
            ref.rrdDb = builder.setPath(uri).setBackendFactory(factory).setPoolInternal(this).build();
            ref.count = 1;
            return ref.rrdDb;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("request interrupted for new rrd " + uri, e);
        } catch (RuntimeException e) {
            passNext(ACTION.DROP, ref);
            ref = null;
            throw e;
        } finally {
            passNext(ACTION.SWAP, ref);
        }
    }

    RrdDb requestRrdDb(URI uri, RrdBackendFactory factory, DataImporter importer) throws IOException {
        return requestRrdDb(RrdDb.getBuilder().setImporter(importer), uri, factory);
    }

    /**
     * <p>Requests a RrdDb reference for the given RRD definition object.</p>
     * <ul>
     * <li>If the RRD with the path specified in the RrdDef object is already open,
     * the method blocks until the file is closed.
     * <li>If the RRD is not already open and the number of already open RRD is less than
     * {@link #getCapacity()}, a new RRD will be created and it's RrdDb reference will be returned.
     * If the RRD is not already open and the number of already open RRD is equal to
     * {@link #getCapacity()}, the method blocks until some RrdDb references are closed.
     * </ul>
     * <p>
     * If the factory defined when creating the pool can handle the URI, it will be used, 
     * or else {@link RrdBackendFactory#findFactory(URI)} will be used.
     *
     * @param rrdDef Definition of the RRD file to be created.
     * @return Reference to the newly created RRD file.
     * @throws java.io.IOException Thrown in case of I/O error
     * @throws java.lang.IllegalStateException if the thread was interrupted
     */
    public RrdDb requestRrdDb(RrdDef rrdDef) throws IOException {
        return requestRrdDb(rrdDef, checkFactory(rrdDef.getUri()));
    }

    /**
     * <p>Requests a RrdDb reference for the given path. The RRD will be created from
     * external data (from XML dump or RRDTool's binary RRD file).</p>
     * <ul>
     * <li>If the RRD with the path specified is already open,
     * the method blocks until the file is closed.
     * <li>If the RRD is not already open and the number of already open RRD is less than
     * {@link #getCapacity()}, a new RRD will be created and it's RrdDb reference will be returned.
     * If the RRD is not already open and the number of already open RRD is equal to
     * {@link #getCapacity()}, the method blocks until some RrdDb references are closed.
     * </ul>
     * <p>The path is transformed to an URI using the default factory of the pool.</p>
     *
     * @param path       Path to the RRD that should be created.
     * @param sourcePath Path to external data which is to be converted to Rrd4j's native RRD file format.
     * @return Reference to the newly created RRD.
     * @throws java.io.IOException Thrown in case of I/O error
     * @throws java.lang.IllegalStateException if the thread was interrupted
     */
    public RrdDb requestRrdDb(String path, String sourcePath)
            throws IOException {
        URI uri = defaultFactory.getUri(path);
        return requestRrdDb(RrdDb.getBuilder().setExternalPath(sourcePath), uri, defaultFactory);
    }

    /**
     * <p>Requests a RrdDb reference for the given URI. The RRD will be created from
     * external data (from XML dump or RRDTool's binary RRD file).</p>
     * <ul>
     * <li>If the RRD with the URI specified is already open,
     * the method blocks until the file is closed.
     * <li>If the RRD is not already open and the number of already open RRD is less than
     * {@link #getCapacity()}, a new RRD will be created and it's RrdDb reference will be returned.
     * If the RRD is not already open and the number of already open RRD is equal to
     * {@link #getCapacity()}, the method blocks until some RrdDb references are closed.
     * </ul>
     * If the factory defined when creating the pool can handle the URI, it will be used, 
     * or else {@link RrdBackendFactory#findFactory(URI)} will be used to choose the factory.
     *
     * @param uri        URI to the RRD that should be created
     * @param sourcePath Path to external data which is to be converted to Rrd4j's native RRD file format
     * @return Reference to the newly created RRD
     * @throws java.io.IOException Thrown in case of I/O error
     * @throws java.lang.IllegalStateException if the thread was interrupted
     */
    public RrdDb requestRrdDb(URI uri, String sourcePath)
            throws IOException {
        return requestRrdDb(RrdDb.getBuilder().setExternalPath(sourcePath), uri, checkFactory(uri));
    }

    /**
     * Sets the default factory to use when obtaining RrdDb reference from simple path and not URI.
     *
     * @param defaultFactory The factory to use.
     * @throws IllegalStateException if called while the pool is not empty or the thread was interrupted
     * @throws java.lang.IllegalStateException if the thread was interrupted
     * @deprecated the pool is no longer a singleton, create a new pool instead of changing it.
     */
    @Deprecated
    public void setDefaultFactory(RrdBackendFactory defaultFactory) {
        try {
            usageWLock.lockInterruptibly();
            if (usage.availablePermits() != maxCapacity) {
                throw new IllegalStateException("Can only be done on a empty pool");
            }
            this.defaultFactory = defaultFactory;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Factory not changed");
        } finally {
            if (usageWLock.isHeldByCurrentThread()) {
                usageWLock.unlock();
            }
        }
    }

    /**
     * Sets the maximum number of simultaneously open RRD.
     *
     * @param newCapacity Maximum number of simultaneously open RRD.
     * @throws IllegalStateException if called while the pool is not empty or the thread was interrupted.
     */
    public void setCapacity(int newCapacity) {
        try {
            usageWLock.lockInterruptibly();
            if (usage.availablePermits() != maxCapacity) {
                throw new IllegalStateException("Can only be done on a empty pool");
            }
            maxCapacity = newCapacity;
            usage = new Semaphore(maxCapacity);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Resizing interrupted");
        } finally {
            if (usageWLock.isHeldByCurrentThread()) {
                usageWLock.unlock();
            }
        }
    }

    /**
     * Returns the maximum number of simultaneously open RRD.
     *
     * @return maximum number of simultaneously open RRD
     * @throws java.lang.IllegalStateException if the thread was interrupted
     */
    public int getCapacity() {
        try {
            usageRLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted, can't get pool size");
        }
        try {
            return maxCapacity;
        } finally {
            usageRLock.unlock();
        }
    }

    /**
     * Returns the number of usage for a RRD.
     *
     * @param rrdDb RrdDb reference for which informations is needed.
     * @return the number of request for this RRD.
     * @throws java.lang.IllegalStateException if the thread was interrupted
     */
    public int getOpenCount(RrdDb rrdDb) {
        return getCanonicalUriUsage(rrdDb.getCanonicalUri());
    }

    /**
     * Returns the number of usage for a RRD.
     * <p>The path is transformed to an URI using the default factory.</p>
     *
     * @param path RRD's path for which informations is needed.
     * @return the number of request for this RRD.
     * @throws java.lang.IllegalStateException if the thread was interrupted
     */
    public int getOpenCount(String path) {
        return getCanonicalUriUsage(defaultFactory.getCanonicalUri(defaultFactory.getUri(path)));
    }

    /**
     * Returns the number of usage for a RRD.
     *
     * @param uri RRD's URI for which informations is needed.
     * @return the number of request for this RRD.
     * @throws java.lang.IllegalStateException if the thread was interrupted
     */
    public int getOpenCount(URI uri) {
        return getCanonicalUriUsage(checkFactory(uri).getCanonicalUri(uri));
    }

    private int getCanonicalUriUsage(URI uri) {
        RrdEntry ref = null;
        try {
            ref = getEntry(uri, false);
            return Optional.ofNullable(ref).map(e -> e.count).orElse(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("getOpenCount interrupted", e);
        } finally {
            passNext(ACTION.SWAP, ref);
        }
    }

    /**
     * Wait until the pool is empty and return a lock that prevent any additions of new RrdDb references until it's released.
     * 
     * @since 3.7
     *
     * @param timeout the time to wait for the write lock
     * @param unit the time unit of the timeout argument
     * @return a lock to release when operations on this pool are finished.
     * @throws InterruptedException if interrupted whole waiting for the lock
     */
    public Lock lockEmpty(long timeout, TimeUnit unit) throws InterruptedException {
        usageWLock.tryLock(timeout, unit);
        try {
            usage.acquire(maxCapacity);
        } catch (InterruptedException e) {
            usageWLock.unlock();
            Thread.currentThread().interrupt();
            throw e;
        }
        return usageWLock;
    }

    private RrdBackendFactory checkFactory(URI uri) {
        return defaultFactory.canStore(uri) ? defaultFactory : RrdBackendFactory.findFactory(uri);
    }

}
