package net.i2p.i2ptunnel.access;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.SimpleTimer2;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class AccessFilterLifecycleTest {
    @Test
    public void reloadRetriesAfterIOException() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        FilterDefinitionElement element = element(loads);
        doAnswer(invocation -> {
            if (loads.incrementAndGet() == 2)
                throw new IOException("temporary read failure");
            return null;
        }).when(element).update(anyMap());
        AccessFilter filter = filter(element, new Recorder[0]);
        try {
            filter.start();
            SimpleTimer2.TimedEvent event = syncer(filter);
            fire(event);
            assertEquals(2, loads.get());
            assertFalse("Refresh must be scheduled after a disk error", future(event).isCancelled());
            fire(event);
            assertEquals(3, loads.get());
        } finally {
            filter.stop();
        }
    }

    @Test
    public void recorderFailureDoesNotSkipBlocklistReload() throws Exception {
        File directory = Files.createTempDirectory("access-recorder-directory").toFile();
        AtomicInteger loads = new AtomicInteger();
        AccessFilter filter = filter(element(loads), new Recorder[] { new Recorder(directory, new Threshold(1, 60)) });
        try {
            Destination destination = mock(Destination.class);
            when(destination.getHash()).thenReturn(new Hash(new byte[32]));
            assertTrue(filter.allowDestination(destination));
            filter.start();
            SimpleTimer2.TimedEvent event = syncer(filter);
            fire(event);
            assertEquals("Blocklists must reload even if recording fails", 2, loads.get());
            assertFalse(future(event).isCancelled());
        } finally {
            filter.stop();
            directory.delete();
        }
    }

    @Test
    public void staleSyncerDoesNoWorkAfterRestart() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AccessFilter filter = filter(element(loads), new Recorder[0]);
        try {
            filter.start();
            SimpleTimer2.TimedEvent old = syncer(filter);
            filter.stop();
            filter.start();
            old.timeReached();
            drain();
            assertEquals(1, loads.get());
            assertNotSame(old, syncer(filter));
        } finally {
            filter.stop();
        }
    }

    @Test
    public void stopCancelsOutstandingRefresh() throws Exception {
        AccessFilter filter = filter(element(new AtomicInteger()), new Recorder[0]);
        try {
            filter.start();
            ScheduledFuture<?> future = future(syncer(filter));
            filter.stop();
            assertTrue("Closing a filter must cancel its scheduled refresh", future.isCancelled());
        } finally {
            filter.stop();
        }
    }

    @Test
    public void queuedSyncDoesNoWorkAfterStop() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        ExecutorService executor = executor();
        executor.submit(() -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        });
        AtomicInteger loads = new AtomicInteger();
        AccessFilter filter = filter(element(loads), new Recorder[0]);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            filter.start();
            syncer(filter).timeReached();
            filter.stop();
            release.countDown();
            drain();
            assertEquals("Queued disk work must respect stop()", 1, loads.get());
        } finally {
            release.countDown();
            filter.stop();
        }
    }

    private static FilterDefinitionElement element(final AtomicInteger loads) throws IOException {
        FilterDefinitionElement element = mock(FilterDefinitionElement.class);
        when(element.getThreshold()).thenReturn(Threshold.ALLOW);
        doAnswer(invocation -> { loads.incrementAndGet(); return null; }).when(element).update(anyMap());
        return element;
    }

    private static AccessFilter filter(FilterDefinitionElement element, Recorder[] recorders) throws IOException {
        return new AccessFilter(I2PAppContext.getGlobalContext(),
            new FilterDefinition(Threshold.ALLOW, new FilterDefinitionElement[] { element }, recorders));
    }

    private static SimpleTimer2.TimedEvent syncer(AccessFilter filter) throws Exception {
        Field field = AccessFilter.class.getDeclaredField("syncer");
        field.setAccessible(true);
        return (SimpleTimer2.TimedEvent) field.get(filter);
    }

    private static ScheduledFuture<?> future(SimpleTimer2.TimedEvent event) throws Exception {
        Field field = SimpleTimer2.TimedEvent.class.getDeclaredField("_future");
        field.setAccessible(true);
        return (ScheduledFuture<?>) field.get(event);
    }

    private static ExecutorService executor() throws Exception {
        Field field = AccessFilter.class.getDeclaredField("DISK_WRITER");
        field.setAccessible(true);
        return (ExecutorService) field.get(null);
    }

    private static void fire(SimpleTimer2.TimedEvent event) throws Exception {
        event.cancel();
        event.timeReached();
        drain();
    }

    private static void drain() throws Exception {
        executor().submit(() -> {}).get(3, TimeUnit.SECONDS);
    }
}
