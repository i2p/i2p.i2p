package org.rrd4j.core;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * <p>This class should be used to synchronize access to RRD files
 * in a multithreaded environment. This class should be also used to prevent opening of
 * too many RRD files at the same time (thus avoiding operating system limits).
 * </p>
 * <p>It should not be called directly. Use {@link RrdDb.Builder#usePool()} instead.</p>
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
     * Initial capacity of the pool i.e. maximum number of simultaneously open RRD files. The pool will
     * never open too many RRD files at the same time.
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
        RrdEntry(URI canonicalPath) throws InterruptedException {
            placeholder = false;
            uri = canonicalPath;
            inuse = new ReentrantReadWriteLock();
            lock = inuse.writeLock();
            waitempty = new CountDownLatch(1);
        }
        RrdEntry(RrdEntry parent) throws InterruptedException {
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
                return String.format("RrdEntry [count=%d, rrdDb=%s, uri%s]", count, rrdDb, uri);
            }
        }
    }

    /**
     * Creates a single instance of the class on the first call,
     * or returns already existing one. Uses Initialization On Demand Holder idiom.
     *
     * @return Single instance of this class
     * @throws java.lang.RuntimeException Thrown if the default RRD backend is not derived from the {@link org.rrd4j.core.RrdFileBackendFactory}
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
     * Constructor for RrdDbPool.
     * @since 3.5
     */
    public RrdDbPool() {
        this(RrdBackendFactory.getDefaultFactory());
    }

    /**
     * Constructor for RrdDbPool.
     * @param defaultFactory the default factory used when given simple path of a rrdDb.
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
     * Returns the number of open RRD files.
     *
     * @return Number of currently open RRD files held in the pool.
     */
    public int getOpenFileCount() {
        return pool.size();
    }

    /**
     * Returns an array of open file URI.
     *
     * @return Array with {@link URI} to open RRD files held in the pool.
     */
    public URI[] getOpenUri() {
        //Direct toarray from keySet can fail
        Set<URI> uris = new HashSet<>(pool.size());
        pool.forEach((k,v) -> uris.add(k));
        return uris.toArray(new URI[uris.size()]);
    }

    /**
     * Returns an array of open file path.
     *
     * @return Array with canonical path to open RRD files held in the pool.
     */
    public String[] getOpenFiles() {
        //Direct toarray from keySet can fail
        Set<String> uris = new HashSet<>(pool.size());
        pool.forEach((k,v) -> uris.add(k.getPath()));
        return uris.toArray(new String[uris.size()]);
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
        SWAP, DROP;
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
                } catch (InterruptedException ex) {
                    throw new UndeclaredThrowableException(ex);
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
     * count is decremented by one. If usage count drops to zero, the underlying RRD file will be closed.
     *
     * @param rrdDb RrdDb reference to be returned to the pool
     * @throws java.io.IOException Thrown in case of I/O error
     * @deprecated a db remember if it was open directly or from the pool, no need to manage it manually any more
     */
    @Deprecated
    public void release(RrdDb rrdDb) throws IOException {
        // null pointer should not kill the thread, just ignore it
        // They can happens in case of failures or interruptions at wrong place
        if (rrdDb == null) {
            return;
        }

        URI dburi = rrdDb.getUri();
        RrdEntry ref = null;
        try {
            ref = getEntry(dburi, false);
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
     * <p>Requests a RrdDb reference for the given RRD file path.</p>
     * <ul>
     * <li>If the file is already open, previously returned RrdDb reference will be returned. Its usage count
     * will be incremented by one.
     * <li>If the file is not already open and the number of already open RRD files is less than
     * {@link #INITIAL_CAPACITY}, the file will be open and a new RrdDb reference will be returned.
     * If the file is not already open and the number of already open RRD files is equal to
     * {@link #INITIAL_CAPACITY}, the method blocks until some RRD file is closed.
     * </ul>
     * <p>The path is transformed internally to URI using the default factory, that is the reference that will
     * be used elsewhere.</p>
     *
     * @param path Path to existing RRD file
     * @return reference for the give RRD file
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public RrdDb requestRrdDb(String path) throws IOException {
        return requestRrdDb(defaultFactory.getUri(path), defaultFactory);
    }

    /**
     * <p>Requests a RrdDb reference for the given RRD file path.</p>
     * <ul>
     * <li>If the file is already open, previously returned RrdDb reference will be returned. Its usage count
     * will be incremented by one.
     * <li>If the file is not already open and the number of already open RRD files is less than
     * {@link #INITIAL_CAPACITY}, the file will be open and a new RrdDb reference will be returned.
     * If the file is not already open and the number of already open RRD files is equal to
     * {@link #INITIAL_CAPACITY}, the method blocks until some RRD file is closed.
     * </ul>
     *
     * @param uri {@link URI} to existing RRD file
     * @return reference for the give RRD file
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public RrdDb requestRrdDb(URI uri) throws IOException {
        RrdBackendFactory factory = RrdBackendFactory.findFactory(uri);
        return requestRrdDb(uri, factory);
    }

    /**
     * Wait for a empty reference with no usage
     * @param uri
     * @return an reference with no usage 
     * @throws IOException
     * @throws InterruptedException
     */
    private RrdEntry waitEmpty(URI uri) throws IOException, InterruptedException {
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
     * @throws IOException
     */
    private RrdEntry requestEmpty(URI uri) throws InterruptedException, IOException {
        RrdEntry ref = waitEmpty(uri);
        return ref;
    }

    RrdDb requestRrdDb(URI uri, RrdBackendFactory factory) throws IOException {
        uri = factory.getCanonicalUri(uri);
        RrdEntry ref = null;
        try {
            ref = getEntry(uri, true);

            // Someone might have already open it, rechecks
            if (ref.count == 0) {
                try {
                    ref.rrdDb = RrdDb.getBuilder().setPath(factory.getPath(uri)).setBackendFactory(factory).setPool(this).build();
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

    RrdDb requestRrdDb(RrdDef rrdDef, RrdBackendFactory backend) throws IOException {
        RrdEntry ref = null;
        try {
            URI uri = backend.getCanonicalUri(rrdDef.getUri());
            ref = requestEmpty(uri);
            ref.rrdDb = RrdDb.getBuilder().setRrdDef(rrdDef).setBackendFactory(backend).setPool(this).build();
            ref.count = 1;
            return ref.rrdDb;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("request interrupted for new rrdDef " + rrdDef.getPath(), e);
        } catch (RuntimeException e) {
            passNext(ACTION.DROP, ref);
            ref = null;
            throw e;
        } finally {
            passNext(ACTION.SWAP, ref);
        }
    }

    private RrdDb requestRrdDb(RrdDb.Builder builder, URI uri, RrdBackendFactory backend)
            throws IOException {
        RrdEntry ref = null;
        uri = backend.getCanonicalUri(uri);
        try {
            ref = requestEmpty(uri);
            ref.rrdDb = builder.setPath(uri).setBackendFactory(backend).setPool(this).build();
            ref.count = 1;
            return ref.rrdDb;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("request interrupted for new rrd " + uri, e);
        } catch (RuntimeException e) {
            passNext(ACTION.DROP, ref);
            ref = null;
            throw e;
        } finally {
            passNext(ACTION.SWAP, ref);
        }
    }

    RrdDb requestRrdDb(URI uri, RrdBackendFactory backend, DataImporter importer) throws IOException {
        return requestRrdDb(RrdDb.getBuilder().setImporter(importer), uri, backend);
    }

    /**
     * <p>Requests a RrdDb reference for the given RRD file definition object.</p>
     * <ul>
     * <li>If the file with the path specified in the RrdDef object is already open,
     * the method blocks until the file is closed.
     * <li>If the file is not already open and the number of already open RRD files is less than
     * {@link #INITIAL_CAPACITY}, a new RRD file will be created and a its RrdDb reference will be returned.
     * If the file is not already open and the number of already open RRD files is equal to
     * {@link #INITIAL_CAPACITY}, the method blocks until some RRD file is closed.
     * </ul>
     *
     * @param rrdDef Definition of the RRD file to be created
     * @return Reference to the newly created RRD file
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public RrdDb requestRrdDb(RrdDef rrdDef) throws IOException {
        return requestRrdDb(rrdDef, RrdBackendFactory.findFactory(rrdDef.getUri()));
    }

    /**
     * <p>Requests a RrdDb reference for the given path. The file will be created from
     * external data (from XML dump or RRDTool's binary RRD file).</p>
     * <ul>
     * <li>If the file with the path specified is already open,
     * the method blocks until the file is closed.
     * <li>If the file is not already open and the number of already open RRD files is less than
     * {@link #INITIAL_CAPACITY}, a new RRD file will be created and a its RrdDb reference will be returned.
     * If the file is not already open and the number of already open RRD files is equal to
     * {@link #INITIAL_CAPACITY}, the method blocks until some RRD file is closed.
     * </ul>
     * <p>The path is transformed internally to an URI using the default factory of the pool.</p>
     *
     * @param path       Path to RRD file which should be created
     * @param sourcePath Path to external data which is to be converted to Rrd4j's native RRD file format
     * @return Reference to the newly created RRD file
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public RrdDb requestRrdDb(String path, String sourcePath)
            throws IOException {
        URI uri = defaultFactory.getUri(path);
        return requestRrdDb(RrdDb.getBuilder().setExternalPath(sourcePath), uri, defaultFactory);
    }

    /**
     * <p>Requests a RrdDb reference for the given path. The file will be created from
     * external data (from XML dump or RRDTool's binary RRD file).</p>
     * <ul>
     * <li>If the file with the path specified is already open,
     * the method blocks until the file is closed.
     * <li>If the file is not already open and the number of already open RRD files is less than
     * {@link #INITIAL_CAPACITY}, a new RRD file will be created and a its RrdDb reference will be returned.
     * If the file is not already open and the number of already open RRD files is equal to
     * {@link #INITIAL_CAPACITY}, the method blocks until some RRD file is closed.
     * </ul>
     * <p>The path is transformed internally to URI using the default factory, that is the reference that will
     * be used elsewhere.</p>
     *
     * @param uri       Path to RRD file which should be created
     * @param sourcePath Path to external data which is to be converted to Rrd4j's native RRD file format
     * @return Reference to the newly created RRD file
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public RrdDb requestRrdDb(URI uri, String sourcePath)
            throws IOException {
        return requestRrdDb(RrdDb.getBuilder().setExternalPath(sourcePath), uri, RrdBackendFactory.findFactory(uri));
    }

    /**
     * Sets the default factory to use when obtaining rrdDb from simple path and not URI.
     *
     * @param defaultFactory The factory to used.
     * @throws IllegalStateException if done will the pool is not empty or the thread was interrupted.
     */
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
     * Sets the maximum number of simultaneously open RRD files.
     *
     * @param newCapacity Maximum number of simultaneously open RRD files.
     * @throws IllegalStateException if done will the pool is not empty or the thread was interrupted.
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
     * Returns the maximum number of simultaneously open RRD files.
     *
     * @return maximum number of simultaneously open RRD files
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
     * @return the number of request for this rrd
     * @throws java.io.IOException if any.
     */
    public int getOpenCount(RrdDb rrdDb) throws IOException {
        return getOpenCount(rrdDb.getUri());
    }

    /**
     * Returns the number of usage for a RRD.
     *
     * @param path RRD's path for which informations is needed.
     * @return the number of request for this file
     * @throws java.io.IOException if any.
     */
    public int getOpenCount(String path) throws IOException {
        return getOpenCount(defaultFactory.getUri(path));
    }

    /**
     * Returns the number of usage for a RRD.
     *
     * @param uri RRD's uri for which informations is needed.
     * @return the number of request for this file
     * @throws java.io.IOException if any.
     */
    public int getOpenCount(URI uri) throws IOException {
        RrdEntry ref = null;
        try {
            ref = getEntry(uri, false);
            return Optional.ofNullable(ref).map(e -> e.count).orElse(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("getOpenCount interrupted", e);
        } finally {
            passNext(ACTION.SWAP, ref);
        }
    }

}
