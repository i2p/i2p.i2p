package net.i2p.jetty;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

/** Bounded, non-blocking log submission with one writer and batched flushes. */
public class AsyncLogWriter extends AbstractLifeCycle {
    private final Writer writer;
    private final boolean closeWriter;
    private final BlockingQueue<String> queue;
    private final Object submissions = new Object();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean accepting;
    private volatile IOException failure;
    private Thread worker;

    public AsyncLogWriter(Writer writer, boolean closeWriter, int capacity) {
        this.writer = writer;
        this.closeWriter = closeWriter;
        queue = new ArrayBlockingQueue<String>(capacity);
    }

    /** The complete record must already include its line terminator. */
    public void write(String record) {
        synchronized (submissions) {
            if (!accepting || !queue.offer(record))
                dropped.incrementAndGet();
        }
    }

    public long getDroppedRecords() { return dropped.get(); }
    public int getQueuedRecords() { return queue.size(); }
    public IOException getFailure() { return failure; }

    @Override
    protected void doStart() {
        if (worker != null)
            throw new IllegalStateException("A log writer cannot be reused after stop");
        accepting = true;
        worker = new Thread(this::drain, "I2P HTTP request log");
        worker.setDaemon(true);
        worker.start();
    }

    private void drain() {
        List<String> batch = new ArrayList<String>(64);
        try {
            while (accepting || !queue.isEmpty()) {
                String first;
                try {
                    first = queue.poll(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException wakeup) {
                    continue;
                }
                if (first == null)
                    continue;
                batch.add(first);
                queue.drainTo(batch, 63);
                for (String record : batch)
                    writer.write(record);
                writer.flush();
                batch.clear();
            }
        } catch (IOException error) {
            failure = error;
            synchronized (submissions) {
                accepting = false;
                dropped.addAndGet(batch.size() + queue.size());
                queue.clear();
            }
        } finally {
            try {
                writer.flush();
            } catch (IOException error) {
                if (failure == null)
                    failure = error;
            }
            if (closeWriter) {
                try { writer.close(); }
                catch (IOException error) { if (failure == null) failure = error; }
            }
        }
    }

    @Override
    protected void doStop() throws Exception {
        synchronized (submissions) { accepting = false; }
        if (worker == null)
            return;
        worker.join(5000);
        if (worker.isAlive())
            throw new IOException("Timed out draining the HTTP request log");
        if (failure != null)
            throw failure;
    }
}
