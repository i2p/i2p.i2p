package org.rrd4j.core;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Backend which is used to store RRD data to ordinary disk files
 * using java.nio.* package. This is the default backend engine.
 *
 */
public class RrdNioBackend extends ByteBufferBackend implements RrdFileBackend {

    // The java 8- methods
    private static final Method cleanerMethod;
    private static final Method cleanMethod;
    // The java 9+ methods
    private static final Method invokeCleaner;
    private static final Object unsafe;
    static {
        // Temporary variable, because destinations variables are final
        // And it interfere with exceptions
        Method cleanerMethodTemp;
        Method cleanMethodTemp;
        Method invokeCleanerTemp;
        Object unsafeTemp;
        try {
            // The java 8- way, using sun.nio.ch.DirectBuffer.cleaner().clean()
            Class<?> directBufferClass = RrdRandomAccessFileBackend.class.getClassLoader().loadClass("sun.nio.ch.DirectBuffer");
            Class<?> cleanerClass = RrdNioBackend.class.getClassLoader().loadClass("sun.misc.Cleaner");
            cleanerMethodTemp = directBufferClass.getMethod("cleaner");
            cleanerMethodTemp.setAccessible(true);
            cleanMethodTemp = cleanerClass.getMethod("clean");
            cleanMethodTemp.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
            cleanerMethodTemp = null;
            cleanMethodTemp = null;
        }
        try {
            // The java 9+ way, using unsafe.invokeCleaner(buffer)
            Field singleoneInstanceField = RrdRandomAccessFileBackend.class.getClassLoader().loadClass("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            singleoneInstanceField.setAccessible(true);
            unsafeTemp = singleoneInstanceField.get(null);
            invokeCleanerTemp = unsafeTemp.getClass().getMethod("invokeCleaner", ByteBuffer.class);
        } catch (NoSuchFieldException | SecurityException
                | IllegalArgumentException | IllegalAccessException
                | NoSuchMethodException | ClassNotFoundException e) {
            invokeCleanerTemp = null;
            unsafeTemp = null;
        }
        cleanerMethod = cleanerMethodTemp;
        cleanMethod = cleanMethodTemp;
        invokeCleaner = invokeCleanerTemp;
        unsafe = unsafeTemp;
    }

    private MappedByteBuffer byteBuffer;
    private final FileChannel file;
    private final boolean readOnly;

    private ScheduledFuture<?> syncRunnableHandle = null;

    /**
     * Creates RrdFileBackend object for the given file path, backed by java.nio.* classes.
     *
     * @param path       Path to a file
     * @param readOnly   True, if file should be open in a read-only mode. False otherwise
     * @param syncPeriod See {@link org.rrd4j.core.RrdNioBackendFactory#setSyncPeriod(int)} for explanation
     * @throws java.io.IOException Thrown in case of I/O error
     * @param threadPool a {@link org.rrd4j.core.RrdSyncThreadPool} object, it can be null.
     */
    protected RrdNioBackend(String path, boolean readOnly, RrdSyncThreadPool threadPool, int syncPeriod) throws IOException {
        super(path);
        Set<StandardOpenOption> options = new HashSet<>(3);
        options.add(StandardOpenOption.READ);
        options.add(StandardOpenOption.CREATE);
        if (! readOnly) {
            options.add(StandardOpenOption.WRITE);
        }

        file = FileChannel.open(Paths.get(path), options);
        this.readOnly = readOnly;
        try {
            mapFile(file.size());
        } catch (IOException | RuntimeException ex) {
            file.close();
            super.close();
            throw ex;
        }
        try {
            if (!readOnly && threadPool != null) {
                Runnable syncRunnable = new Runnable() {
                    public void run() {
                        sync();
                    }
                };
                syncRunnableHandle = threadPool.scheduleWithFixedDelay(syncRunnable, syncPeriod, syncPeriod, TimeUnit.SECONDS);
            }
        } catch (RuntimeException rte) {
            unmapFile();
            file.close();
            super.close();
            throw rte;
        }
    }

    private void mapFile(long length) throws IOException {
        if (length > 0) {
            FileChannel.MapMode mapMode =
                    readOnly ? FileChannel.MapMode.READ_ONLY : FileChannel.MapMode.READ_WRITE;
            byteBuffer = file.map(mapMode, 0, length);
            setByteBuffer(byteBuffer);
        }
    }

    private void unmapFile() {
        if (byteBuffer != null && byteBuffer.isDirect()) {
            try {
                if (cleanMethod != null) {
                    Object cleaner = cleanerMethod.invoke(byteBuffer);
                    cleanMethod.invoke(cleaner);
                } else {
                    invokeCleaner.invoke(unsafe, byteBuffer);
                }
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
                throw new RuntimeException(ex);
            }
        }
        byteBuffer = null;
    }

    /**
     * {@inheritDoc}
     *
     * Sets length of the underlying RRD file. This method is called only once, immediately
     * after a new RRD file gets created.
     * @throws java.lang.IllegalArgumentException if the length is bigger that the possible mapping position (2GiB).
     */
    protected synchronized void setLength(long newLength) throws IOException {
        if (newLength < 0 || newLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Illegal offset: " + newLength);
        }

        unmapFile();
        file.truncate(newLength);
        mapFile(newLength);
    }

    /**
     * Closes the underlying RRD file.
     *
     * @throws java.io.IOException Thrown in case of I/O error.
     */
    @Override
    public synchronized void close() throws IOException {
        // cancel synchronization
        try {
            if (!readOnly && syncRunnableHandle != null) {
                syncRunnableHandle.cancel(false);
                syncRunnableHandle = null;
                sync();
            }
            unmapFile();
        } finally {
            file.close();
            super.close();
        }
    }

    /**
     * This method forces all data cached in memory but not yet stored in the file,
     * to be stored in it.
     */
    protected synchronized void sync() {
        if (byteBuffer != null) {
            byteBuffer.force();
        }
    }

    @Override
    public synchronized long getLength() throws IOException {
        return file.size();
    }

    @Override
    public String getCanonicalPath() throws IOException {
        return Paths.get(getPath()).toAbsolutePath().normalize().toString();
    }

}
