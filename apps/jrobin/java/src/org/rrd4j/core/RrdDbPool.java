package org.rrd4j.core;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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

    /**
     * Initial capacity of the pool i.e. maximum number of simultaneously open RRD files. The pool will
     * never open too many RRD files at the same time.
     */
    public static final int INITIAL_CAPACITY = 200;

    /*
     * The RrdEntry stored in the pool can be of tree kind:
     * - null, the URI is available, just for it and play
     * - placeholder is true, it's not the real RrdDb entry, just a place holder
     *   meaning that some other thread is using it.
     * - placehold is false, this is the real entry pointing to a RrdDb. It's
     *   only used by the current thread.
     *
     */
    private static class RrdEntry {
        RrdDb rrdDb = null;
        int count = 0;
        final CountDownLatch waitempty;
        final CountDownLatch inuse;
        final boolean placeholder;
        final URI uri;
        RrdEntry(boolean placeholder, URI canonicalPath) {
            this.placeholder = placeholder;
            this.uri = canonicalPath;
            if (placeholder) {
                inuse = new CountDownLatch(1);
                waitempty = null;
            } else {
                inuse = null;
                waitempty = new CountDownLatch(1);
            }
        }
        @Override
        public String toString() {
            if (this.placeholder) {
                return "RrdEntry [inuse=" + inuse.getCount()+ ", uri=" + uri + "]";
            } else {
                return "RrdEntry [rrdDb=" + rrdDb + ", count=" + count + ", uri=" + uri + "]";
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

    private final AtomicInteger usage = new AtomicInteger(0);
    private final ReentrantLock countLock = new ReentrantLock();
    private final Condition full = countLock.newCondition();
    private int maxCapacity = INITIAL_CAPACITY;

    private final ConcurrentMap<URI, RrdEntry> pool = new ConcurrentHashMap<>(INITIAL_CAPACITY);

    private final RrdBackendFactory defaultFactory;

    /**
     * Constructor for RrdDbPool.
     * @since 3.5
     */
    public RrdDbPool() {
        defaultFactory = RrdBackendFactory.getDefaultFactory();
    }

    /**
     * Returns the number of open RRD files.
     *
     * @return Number of currently open RRD files held in the pool.
     */
    public int getOpenFileCount() {
        return usage.get();
    }

    /**
     * Returns an array of open file URI.
     *
     * @return Array with {@link URI} to open RRD files held in the pool.
     */
    public URI[] getOpenUri() {
        //Direct toarray from keySet can fail
        Set<URI> files = new HashSet<>();
        files.addAll(pool.keySet());
        return files.toArray(new URI[files.size()]);
    }

    /**
     * Returns an array of open file path.
     *
     * @return Array with canonical path to open RRD files held in the pool.
     */
    public String[] getOpenFiles() {
        //Direct toarray from keySet can fail
        Set<String> files = new HashSet<>();
        for (RrdEntry i: pool.values()) {
            files.add(i.rrdDb.getPath());
        }
        return files.toArray(new String[files.size()]);
    }

    private RrdEntry getEntry(URI uri, boolean cancreate) throws InterruptedException {
        RrdEntry ref = null;
        try {
            do {
                ref = pool.get(uri);
                if (ref == null) {
                    //Slot empty
                    //If still absent put a place holder, and create the entry to return
                    try {
                        countLock.lockInterruptibly();
                        while (ref == null && usage.get() >= maxCapacity && cancreate) {
                            full.await();
                            ref = pool.get(uri);
                        }
                        if (ref == null && cancreate) {
                            ref = pool.putIfAbsent(uri, new RrdEntry(true, uri));
                            if (ref == null) {
                                ref = new RrdEntry(false, uri);
                                usage.incrementAndGet();
                            }
                        }
                    } finally {
                        countLock.unlock();
                    }
                } else if (! ref.placeholder) {
                    // Real entry, try to put a place holder if some one didn't get it meanwhile
                    if ( ! pool.replace(uri, ref, new RrdEntry(true, uri))) {
                        //Dummy ref, a new iteration is needed
                        ref = new RrdEntry(true, uri);
                    }
                } else {
                    // a place holder, wait for the using task to finish
                    ref.inuse.await();
                }
            } while (ref != null && ref.placeholder);
            return ref;
        } catch (InterruptedException | RuntimeException e) {
            // Oups we were interrupted, put everything back and go away
            passNext(ACTION.SWAP, ref);
            Thread.currentThread().interrupt();
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
            if(usage.decrementAndGet() < maxCapacity) {
                try {
                    countLock.lockInterruptibly();
                    full.signalAll();
                    countLock.unlock();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            break;
        }
        //task finished, waiting on a place holder can go on
        if(o != null) {
            o.inuse.countDown();
        }  
    }

    /**
     * Releases RrdDb reference previously obtained from the pool. When a reference is released, its usage
     * count is decremented by one. If usage count drops to zero, the underlying RRD file will be closed.
     *
     * @param rrdDb RrdDb reference to be returned to the pool
     * @throws java.io.IOException Thrown in case of I/O error
     * @deprecated a pool remember if it was open directly or from the pool, no need to manage it manually any more
     */
    @Deprecated
    public void release(RrdDb rrdDb) throws IOException {
        // null pointer should not kill the thread, just ignore it
        if (rrdDb == null) {
            return;
        }

        URI dburi = rrdDb.getUri();
        RrdEntry ref = null;
        try {
            ref = getEntry(dburi, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("release interrupted for " + rrdDb, e);
        }
        if (ref == null) {
            return;
        }

        if (ref.count <= 0) {
            passNext(ACTION.DROP, ref);
            throw new IllegalStateException("Could not release [" + rrdDb.getPath() + "], the file was never requested");
        }
        if (--ref.count == 0) {
            if(ref.rrdDb == null) {
                passNext(ACTION.DROP, ref);
                throw new IllegalStateException("Could not release [" + rrdDb.getPath() + "], pool corruption");
            }
            try {
                ref.rrdDb.internalClose();
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
     * @deprecated Use the {@link org.rrd4j.core.RrdDb.Builder} instead.
     */
    @Deprecated
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
     * @deprecated Use the {@link org.rrd4j.core.RrdDb.Builder} instead.
     */
    @Deprecated
    public RrdDb requestRrdDb(URI uri) throws IOException {
        RrdBackendFactory factory = RrdBackendFactory.findFactory(uri);
        return requestRrdDb(uri, factory);
    }

    RrdDb requestRrdDb(URI uri, RrdBackendFactory factory) throws IOException {
        uri = factory.getCanonicalUri(uri);
        RrdEntry ref = null;
        try {
            ref = getEntry(uri, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("request interrupted for " + uri, e);
        }

        //Someone might have already open it, rechecks
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
        ref.count = 1;
        return ref;
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
     * @deprecated Use the {@link org.rrd4j.core.RrdDb.Builder} instead.
     */
    @Deprecated
    public RrdDb requestRrdDb(RrdDef rrdDef) throws IOException {
        return requestRrdDb(rrdDef, RrdBackendFactory.findFactory(rrdDef.getUri()));
    }

    RrdDb requestRrdDb(RrdDef rrdDef, RrdBackendFactory backend) throws IOException {
        RrdEntry ref = null;
        try {
            URI uri = backend.getCanonicalUri(rrdDef.getUri());
            ref = requestEmpty(uri);
            ref.rrdDb = RrdDb.getBuilder().setRrdDef(rrdDef).setBackendFactory(backend).setPool(this).build();
            return ref.rrdDb;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("request interrupted for new rrdDef " + rrdDef.getPath(), e);
        } catch (RuntimeException e) {
            passNext(ACTION.DROP, ref);
            ref = null;
            throw e;
        } finally {
            if (ref != null) {
                passNext(ACTION.SWAP, ref);
            }
        }
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
     * @param path       Path to RRD file which should be created
     * @param sourcePath Path to external data which is to be converted to Rrd4j's native RRD file format
     * @return Reference to the newly created RRD file
     * @throws java.io.IOException Thrown in case of I/O error
     * @deprecated Use the {@link org.rrd4j.core.RrdDb.Builder} instead.
     */
    @Deprecated
    public RrdDb requestRrdDb(String path, String sourcePath)
            throws IOException {
        URI uri = RrdBackendFactory.getDefaultFactory().getUri(path);
        RrdBackendFactory backend = RrdBackendFactory.getDefaultFactory();
        return requestRrdDb(RrdDb.getBuilder().setExternalPath(sourcePath), uri, backend);
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
     * @deprecated Use the {@link org.rrd4j.core.RrdDb.Builder} instead.
     */
    @Deprecated
    public RrdDb requestRrdDb(URI uri, String sourcePath)
            throws IOException {
        RrdBackendFactory backend = RrdBackendFactory.getDefaultFactory();
        return requestRrdDb(RrdDb.getBuilder().setExternalPath(sourcePath), uri, backend);
    }

    private RrdDb requestRrdDb(RrdDb.Builder builder, URI uri, RrdBackendFactory backend)
            throws IOException {
        RrdEntry ref = null;
        uri = backend.getCanonicalUri(uri);
        try {
            ref = requestEmpty(uri);
            ref.rrdDb = builder.setPath(uri).setBackendFactory(backend).setPool(this).build();
            return ref.rrdDb;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("request interrupted for new rrd " + uri, e);
        } catch (RuntimeException e) {
            passNext(ACTION.DROP, ref);
            ref = null;
            throw e;
        } finally {
            if (ref != null) {
                passNext(ACTION.SWAP, ref);
            }
        }
    }

    RrdDb requestRrdDb(URI uri, RrdBackendFactory backend, DataImporter importer) throws IOException {
        return requestRrdDb(RrdDb.getBuilder().setImporter(importer), uri, backend);
    }

    /**
     * Sets the maximum number of simultaneously open RRD files.
     *
     * @param newCapacity Maximum number of simultaneously open RRD files.
     */
    public void setCapacity(int newCapacity) {
        int oldUsage = usage.getAndSet(maxCapacity);
        try {
            if (oldUsage != 0) {
                throw new RuntimeException("Can only be done on a empty pool");
            }
        } finally {
            usage.set(oldUsage);
        }
        maxCapacity = newCapacity;
    }

    /**
     * Returns the maximum number of simultaneously open RRD files.
     *
     * @return maximum number of simultaneously open RRD files
     */
    public int getCapacity() {
        return maxCapacity;
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
            if (ref == null)
                return 0;
            else {
                return ref.count;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("getOpenCount interrupted", e);
        } finally {
            if (ref != null) {
                passNext(ACTION.SWAP, ref);
            }
        }
    }

}
