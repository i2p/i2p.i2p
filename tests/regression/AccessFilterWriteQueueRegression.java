package net.i2p.i2ptunnel.access;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.util.SimpleTimer2;

/** Real timer callbacks, actual shared disk executor, and controllable disk work. */
public final class AccessFilterWriteQueueRegression {
    private static int checks, failures;
    private static final int CAPACITY = 128;

    public static void main(String[] args) throws Exception {
        I2PAppContext context = new I2PAppContext(new Properties());
        context.logManager().setDefaultLimit("CRIT");
        Field writer = field(AccessFilter.class, "DISK_WRITER");
        ExecutorService executor = (ExecutorService) writer.get(null);
        CountDownLatch entered = new CountDownLatch(1), unblockWorker = new CountDownLatch(1);
        CountDownLatch diskEntered = new CountDownLatch(1), unblockDisk = new CountDownLatch(1);
        AtomicInteger refreshed = new AtomicInteger();
        List<AccessFilter> filters = new ArrayList<>();
        List<Element> elements = new ArrayList<>();
        try {
            executor.submit(() -> { entered.countDown(); await(unblockWorker); });
            check(entered.await(3, TimeUnit.SECONDS), "disk worker stalled deterministically");
            for (int i = 0; i < CAPACITY * 2; i++) {
                Element element = new Element(refreshed, diskEntered, unblockDisk);
                elements.add(element);
                AccessFilter filter = make(context, element);
                filters.add(filter);
                filter.start();
                fire(syncer(filter));
            }
            // Place a barrier behind the admitted work once one queue slot opens.
            unblockWorker.countDown();
            check(diskEntered.await(3, TimeUnit.SECONDS), "admitted disk work starts");
            Future<?> barrier = executor.submit(() -> { });
            unblockDisk.countDown();
            barrier.get(5, TimeUnit.SECONDS);
            int admitted = refreshed.get();
            check(admitted > 0 && admitted <= CAPACITY, "stalled disk admits bounded work (observed " + admitted + ")");
            check(elements.get(elements.size() - 1).calls.get() == 1, "overflow work deferred instead of running on timer thread");
            SimpleTimer2.TimedEvent retry = syncer(filters.get(filters.size() - 1));
            check(!future(retry).isCancelled(), "overflow update retains a retry timer");
            int previous = elements.get(elements.size() - 1).calls.get();
            fire(retry);
            executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
            check(elements.get(elements.size() - 1).calls.get() == previous + 1, "deferred update runs after capacity returns");
            for (AccessFilter filter : filters) filter.stop();

        } finally {
            unblockWorker.countDown(); unblockDisk.countDown();
            for (AccessFilter filter : filters) filter.stop();
            executor.shutdownNow();
            check(executor.awaitTermination(3, TimeUnit.SECONDS), "disk worker terminates");
            context.simpleTimer2().stop();
            context.logManager().shutdown();
        }
        System.out.println("ACCESS_FILTER_CAPACITY_CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0) throw new AssertionError("access-filter capacity/lifecycle checks failed");
    }
    private static AccessFilter make(I2PAppContext context, Element element) throws IOException {
        return new AccessFilter(context, new FilterDefinition(Threshold.ALLOW,
                new FilterDefinitionElement[] { element }, new Recorder[0]));
    }
    private static SimpleTimer2.TimedEvent syncer(AccessFilter filter) throws Exception {
        return (SimpleTimer2.TimedEvent) field(AccessFilter.class, "syncer").get(filter);
    }
    private static ScheduledFuture<?> future(SimpleTimer2.TimedEvent event) throws Exception {
        return (ScheduledFuture<?>) field(SimpleTimer2.TimedEvent.class, "_future").get(event);
    }
    private static void fire(SimpleTimer2.TimedEvent event) { event.cancel(); event.timeReached(); }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("disk fixture timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static void check(boolean ok, String description) {
        checks++; if (!ok) { failures++; System.out.println("FAIL: " + description); }
    }
    private static final class Element extends FilterDefinitionElement {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failNext;
        private final AtomicInteger refreshed;
        private final CountDownLatch entered, release;
        Element(AtomicInteger refreshed, CountDownLatch entered, CountDownLatch release) {
            super(Threshold.ALLOW); this.refreshed = refreshed; this.entered = entered; this.release = release;
        }
        @Override void update(Map<Hash, DestTracker> map) throws IOException {
            if (calls.incrementAndGet() > 1) {
                if (failNext) { failNext = false; throw new IOException("synthetic disk read failure"); }
                refreshed.incrementAndGet(); entered.countDown(); await(release);
            }
        }
    }
}
